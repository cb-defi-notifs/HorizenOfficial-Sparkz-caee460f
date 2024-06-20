package sparkz.core.network

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import sparkz.core.NodeViewHolder.ReceivableMessages.{GetNodeViewChanges, ModifiersFromRemote, TransactionsFromRemote}
import sparkz.core.consensus.History._
import sparkz.core.consensus.{History, HistoryReader, SyncInfo}
import sparkz.core.network.ModifiersStatus.{Invalid, Requested}
import sparkz.core.network.NetworkController.ReceivableMessages.{PenalizePeer, RegisterMessageSpecs, SendToNetwork, StartConnectingPeers}
import sparkz.core.network.NodeViewSynchronizer.ReceivableMessages._
import sparkz.core.network.message._
import sparkz.core.network.peer.PenaltyType
import sparkz.core.serialization.SparkzSerializer
import sparkz.core.settings.NetworkSettings
import sparkz.core.transaction.state.StateReader
import sparkz.core.transaction.wallet.VaultReader
import sparkz.core.transaction.{MempoolReader, Transaction}
import sparkz.core.utils.NetworkTimeProvider
import sparkz.core.validation.MalformedModifierError
import sparkz.core.{ModifierTypeId, NodeViewModifier, PersistentNodeViewModifier, idsToString}
import sparkz.util.serialization.{VLQByteBufferReader, VLQReader}
import sparkz.util.{ModifierId, SparkzEncoding, SparkzLogging}

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import scala.annotation.{nowarn, tailrec}
import scala.concurrent.ExecutionContext
import scala.language.postfixOps
import scala.reflect.ClassTag
import scala.util.{Failure, Success}

/**
  * A component which is synchronizing local node view (processed by NodeViewHolder) with the p2p network.
  *
  * @tparam TX   transaction
  * @tparam SIS  SyncInfoMessage specification
  * @tparam PMOD Basic trait of persistent modifiers type family
  * @tparam HR   History reader type
  * @tparam MR   Mempool reader type
  * @param networkControllerRef reference to network controller actor
  * @param viewHolderRef        reference to node view holder actor
  * @param syncInfoSpec         SyncInfo specification
  * @param networkSettings      network settings instance
  * @param timeProvider         network time provider
  * @param modifierSerializers  dictionary of modifiers serializers
  */
class NodeViewSynchronizer[TX <: Transaction, SI <: SyncInfo, SIS <: SyncInfoMessageSpec[SI],
  PMOD <: PersistentNodeViewModifier, HR <: HistoryReader[PMOD, SI] : ClassTag, MR <: MempoolReader[TX] : ClassTag]
(networkControllerRef: ActorRef,
 viewHolderRef: ActorRef,
 syncInfoSpec: SIS,
 networkSettings: NetworkSettings,
 timeProvider: NetworkTimeProvider,
 modifierSerializers: Map[ModifierTypeId, SparkzSerializer[_ <: NodeViewModifier]])(implicit ec: ExecutionContext)
  extends Actor with Synchronizer with SparkzLogging with SparkzEncoding {

  protected val invSpec = new InvSpec(networkSettings.maxInvObjects)
  protected val requestModifierSpec = new RequestModifierSpec(networkSettings.maxInvObjects)
  protected val modifiersSpec = new ModifiersSpec(networkSettings.maxModifiersSpecMessageSize)

  protected val msgHandlers: PartialFunction[(MessageSpec[_], _, ConnectedPeer), Unit] = {
    case (_: SIS@unchecked, data: SI@unchecked, remote) => processSync(data, remote)
    case (_: InvSpec, data: InvData, remote) => processInv(data, remote)
    case (_: RequestModifierSpec, data: InvData, remote) => modifiersReq(data, remote)
    case (_: ModifiersSpec, data: ModifiersData, remote) => modifiersFromRemote(data, remote)
  }

  protected val deliveryTracker = new DeliveryTracker(context.system, networkSettings, self)
  protected val statusTracker = new SyncTracker(self, context, networkSettings, timeProvider)

  protected var historyReaderOpt: Option[HR] = None
  protected var mempoolReaderOpt: Option[MR] = None

  override def preStart(): Unit = {
    // register as a handler for synchronization-specific types of messages
    val messageSpecs: Seq[MessageSpec[_]] = Seq(invSpec, requestModifierSpec, modifiersSpec, syncInfoSpec)
    networkControllerRef ! RegisterMessageSpecs(messageSpecs, self)
    // trigger connecting to peers
    networkControllerRef ! StartConnectingPeers

    // register as a listener for peers got connected (handshaked) or disconnected
    context.system.eventStream.subscribe(self, classOf[HandshakedPeer])
    context.system.eventStream.subscribe(self, classOf[DisconnectedPeer])

    // subscribe for all the node view holder events involving modifiers and transactions
    context.system.eventStream.subscribe(self, classOf[ChangedHistory[HR]])
    context.system.eventStream.subscribe(self, classOf[ChangedMempool[MR]])
    context.system.eventStream.subscribe(self, classOf[ModificationOutcome])
    context.system.eventStream.subscribe(self, classOf[ModifiersProcessingResult[PMOD]])

    // subscribe for history and mempool changes
    viewHolderRef ! GetNodeViewChanges(history = true, state = false, vault = false, mempool = true)

    statusTracker.scheduleSendSyncInfo()
  }

  private def readersOpt: Option[(HR, MR)] = historyReaderOpt.flatMap(h => mempoolReaderOpt.map(mp => (h, mp)))

  protected def broadcastModifierInv[M <: NodeViewModifier](m: M): Unit = {
    m.modifierTypeId match {
      case Transaction.ModifierTypeId if deliveryTracker.slowMode =>
        deliveryTracker.putInRebroadcastQueue(m.id)
      case _ =>
        val msg = Message(invSpec, Right(InvData(m.modifierTypeId, Seq(m.id))), None)
        if (m.modifierTypeId == Transaction.ModifierTypeId)
          networkControllerRef ! SendToNetwork(msg, BroadcastTransaction)
        else
          networkControllerRef ! SendToNetwork(msg, BroadcastBlock)
    }
  }

  protected def viewHolderEvents: Receive = {
    case SuccessfulTransaction(tx) =>
      if (networkSettings.handlingTransactionsEnabled) {
        deliveryTracker.setHeld(tx.id)
        broadcastModifierInv(tx)
      }

    case FailedTransaction(id, _, immediateFailure) =>
      if (networkSettings.handlingTransactionsEnabled) {
        val senderOpt = deliveryTracker.setInvalid(id)
        // penalize sender only in case transaction was invalidated at first validation.
        if (immediateFailure) senderOpt.foreach(penalizeMisbehavingPeer)
      }

    case SyntacticallySuccessfulModifier(mod) =>
      deliveryTracker.setHeld(mod.id)

    case SyntacticallyFailedModification(mod, _) =>
      deliveryTracker.setInvalid(mod.id).foreach(penalizeAndDisconnectPeer)

    case SemanticallySuccessfulModifier(mod) =>
      broadcastModifierInv(mod)

    case SemanticallyFailedModification(mod, _) =>
      deliveryTracker.setInvalid(mod.id).foreach(penalizeAndDisconnectPeer)

    case ChangedHistory(reader: HR) =>
      historyReaderOpt = Some(reader)

    case ChangedMempool(reader: MR) =>
      mempoolReaderOpt = Some(reader)

    case ModifiersProcessingResult(applied: Seq[_], cleared: Seq[_]) =>
      // stop processing for cleared modifiers
      // applied modifiers state was already changed at `SyntacticallySuccessfulModifier`
      cleared.foreach(m => deliveryTracker.setUnknown(m.id))
      requestMoreModifiers(applied)
  }

  /**
    * Application-specific logic to request more modifiers after application if needed to
    * speed-up synchronization process, e.g. send Sync message for unknown or older peers
    * when our modifier is not synced yet, but no modifiers are expected from other peers
    * or request modifiers we need with known ids, that are not applied yet.
    */
  protected def requestMoreModifiers(applied: Seq[_]): Unit = {}

  protected def peerManagerEvents: Receive = {
    case HandshakedPeer(remote) =>
      statusTracker.updateStatus(remote, Unknown)

    case DisconnectedPeer(remote) =>
      statusTracker.clearStatus(remote)
  }

  protected def getLocalSyncInfo: Receive = {
    case SendLocalSyncInfo =>
      historyReaderOpt.foreach(sendSync(statusTracker, _))
  }

  protected def sendSync(syncTracker: SyncTracker, history: HR): Unit = {
    logger.debug("SYNC INFO: Trying to send sync info")
    val peers = statusTracker.peersToSyncWith()
    if (peers.nonEmpty) {
      logger.debug(s"SYNC INFO: Sending sync info to peers $peers")
      networkControllerRef ! SendToNetwork(Message(syncInfoSpec, Right(history.syncInfo), None), SendToPeers(peers))
    } else {
      logger.debug("SYNC INFO: No peers to send sync info to")
    }
  }

  protected def processDataFromPeer: Receive = {
    case Message(spec, Left(msgBytes), Some(source)) => parseAndHandle(spec, msgBytes, source)
  }

  //sync info is coming from another node
  protected def processSync(syncInfo: SI, remote: ConnectedPeer): Unit = {
    log.debug(s"SYNC INFO: Got sync info from $remote")
    historyReaderOpt match {
      case Some(historyReader) =>
        val ext = historyReader.continuationIds(syncInfo, networkSettings.desiredInvObjects)
        val comparison = historyReader.compare(syncInfo)
        log.debug(s"Comparison with $remote having starting points ${idsToString(syncInfo.startingPoints)}. " +
          s"Comparison result is $comparison. Sending extension of length ${ext.length}")
        log.debug(s"Extension ids: ${idsToString(ext)}")

        if (!(ext.nonEmpty || comparison != Younger))
          log.warn("Extension is empty while comparison is younger")

        self ! OtherNodeSyncingStatus(remote, comparison, ext)

      case _ =>
    }
  }

  // Send history extension to the (less developed) peer 'remote' which does not have it.
  @nowarn def sendExtension(remote: ConnectedPeer,
                            status: HistoryComparisonResult,
                            ext: Seq[(ModifierTypeId, ModifierId)]): Unit =
    ext.groupBy(_._1).mapValues(_.map(_._2)).foreach {
      case (mid, mods) =>
        networkControllerRef ! SendToNetwork(Message(invSpec, Right(InvData(mid, mods)), None), SendToPeer(remote))
    }

  //view holder is telling other node status
  protected def processSyncStatus: Receive = {
    case OtherNodeSyncingStatus(remote, status, ext) =>
      statusTracker.updateStatus(remote, status)

      status match {
        case Unknown =>
          //todo: should we ban peer if its status is unknown after getting info from it?
          log.warn("Peer status is still unknown")
        case Nonsense =>
          log.warn("Got nonsense")
        case Younger | Fork =>
          sendExtension(remote, status, ext)
        case _ => // does nothing for `Equal` and `Older`
      }
  }

  /**
    * Object ids coming from other node.
    * Filter out modifier ids that are already in process (requested, received or applied),
    * request unknown ids from peer and set this ids to requested state.
    */
  protected def processInv(invData: InvData, peer: ConnectedPeer): Unit = {
    (mempoolReaderOpt, historyReaderOpt) match {
      case (Some(mempool), Some(history)) =>
        val modifierTypeId = invData.typeId
        val newModifierIds = (modifierTypeId match {
          case Transaction.ModifierTypeId =>
            if (deliveryTracker.canRequestMoreTransactions && networkSettings.handlingTransactionsEnabled)
              invData.ids.filter(mid => deliveryTracker.status(mid, mempool) == ModifiersStatus.Unknown)
            else
              Seq.empty
          case _ =>
            invData.ids.filter(mid => deliveryTracker.status(mid, history) == ModifiersStatus.Unknown)
        })
          .take(deliveryTracker.getPeerLimit(peer))

        if (newModifierIds.nonEmpty) {
          val msg = Message(requestModifierSpec, Right(InvData(modifierTypeId, newModifierIds)), None)
          peer.handlerRef ! msg
          deliveryTracker.setRequested(newModifierIds, modifierTypeId, peer)
        }

      case _ =>
        log.warn(s"Got data from peer while readers are not ready ${(mempoolReaderOpt, historyReaderOpt)}")
    }
  }

  //other node asking for objects by their ids
  protected def modifiersReq(invData: InvData, remote: ConnectedPeer): Unit = {
    readersOpt.foreach { readers =>
      val objs: Seq[NodeViewModifier] = invData.typeId match {
        case typeId: ModifierTypeId if typeId == Transaction.ModifierTypeId =>
          if (networkSettings.handlingTransactionsEnabled)
            readers._2.getAll(invData.ids)
          else
            Seq.empty
        case _: ModifierTypeId =>
          invData.ids.flatMap(id => readers._1.modifierById(id))
      }

      log.debug(s"Requested ${invData.ids.length} modifiers ${idsToString(invData)}, " +
        s"sending ${objs.length} modifiers ${idsToString(invData.typeId, objs.map(_.id))} ")
      self ! ResponseFromLocal(remote, invData.typeId, objs)
    }
  }

  /**
    * Logic to process modifiers got from another peer.
    * Filter out non-requested modifiers (with a penalty to spamming peer),
    * parse modifiers and send valid modifiers to NodeViewHolder
    */
  protected def modifiersFromRemote(data: ModifiersData, remote: ConnectedPeer): Unit = {
    val typeId = data.typeId
    val modifiers = data.modifiers
    log.info(s"Got ${modifiers.size} modifiers of type $typeId from remote connected peer: $remote")
    log.trace(s"Received modifier ids ${modifiers.map(_._1).map(encoder.encodeId).mkString(",")}")

    // filter out non-requested modifiers
    val requestedModifiers = processSpam(remote, typeId, modifiers)

    modifierSerializers.get(typeId) match {
      case Some(serializer: SparkzSerializer[TX]@unchecked) if typeId == Transaction.ModifierTypeId =>
        // parse all transactions and send them to node view holder
        val parsed: Iterable[TX] = parseModifiers(requestedModifiers, serializer, remote)
        parsed.foreach(tx => deliveryTracker.setReceived(tx.id, remote))
        viewHolderRef ! TransactionsFromRemote(parsed)

      case Some(serializer: SparkzSerializer[PMOD]@unchecked) =>
        // parse all modifiers and put them to modifiers cache
        log.info(s"Received block ids ${modifiers.map(_._1).map(encoder.encodeId).mkString(",")}")
        val parsed: Iterable[PMOD] = parseModifiers(requestedModifiers, serializer, remote)
        val valid: Iterable[PMOD] = parsed.filter(validateAndSetStatus(remote, _))
        if (valid.nonEmpty) viewHolderRef ! ModifiersFromRemote[PMOD](valid)

      case _ =>
        log.error(s"Undefined serializer for modifier of type $typeId")
    }
  }

  /**
    * Move `pmod` to `Invalid` if it is permanently invalid, to `Received` otherwise
    */
  @SuppressWarnings(Array("org.wartremover.warts.IsInstanceOf"))
  private[network] def validateAndSetStatus(remote: ConnectedPeer, pmod: PMOD): Boolean = {
    if (deliveryTracker.status(pmod.parentId) == Invalid) {
      log.warn(s"Modifier ${pmod.encodedId} is invalid because of its parent ${pmod.parentId}")
      deliveryTracker.setInvalid(pmod.id)
      penalizeAndDisconnectPeer(remote)
      false
    } else {
      historyReaderOpt match {
        case Some(hr) =>
          hr.applicableTry(pmod) match {
            case Failure(e) if e.isInstanceOf[MalformedModifierError] =>
              log.warn(s"Modifier ${pmod.encodedId} is permanently invalid", e)
              deliveryTracker.setInvalid(pmod.id)
              penalizeMisbehavingPeer(remote)
              false
            case _ =>
              deliveryTracker.setReceived(pmod.id, remote)
              true
          }
        case None =>
          log.error("Got modifier while history reader is not ready")
          deliveryTracker.setReceived(pmod.id, remote)
          true
      }
    }
  }

  /**
    * Parse modifiers using specified serializer, check that its id is equal to the declared one,
    * penalize misbehaving peer for every incorrect modifier or additional bytes after the modifier,
    * call deliveryTracker.onReceive() for every correct modifier to update its status
    *
    * @return collection of parsed modifiers
    */
  private def parseModifiers[M <: NodeViewModifier](modifiers: Seq[(ModifierId, Array[Byte])],
                                                    serializer: SparkzSerializer[M],
                                                    remote: ConnectedPeer): Iterable[M] = {
    modifiers.flatMap { case (id, bytes) =>
      val reader: VLQReader = new VLQByteBufferReader(ByteBuffer.wrap(bytes))

      serializer.parseTry(reader) match {
        case Success(mod) if id == mod.id =>
          if (reader.remaining != 0) {
            penalizeMisbehavingPeer(remote)
            log.warn(s"Received additional bytes after block. Declared id ${encoder.encodeId(id)} from ${remote.toString}")
          }
          Some(mod)
        case _ =>
          // Penalize peer and do nothing - it will be switched to correct state on CheckDelivery
          penalizeMisbehavingPeer(remote)
          log.warn(s"Failed to parse modifier with declared id ${encoder.encodeId(id)} from ${remote.toString}")
          None
      }
    }
  }

  /**
    * Get modifiers from remote peer,
    * filter out spam modifiers and penalize peer for spam
    *
    * @return ids and bytes of modifiers that were requested by our node
    */
  private def processSpam(remote: ConnectedPeer,
                          typeId: ModifierTypeId,
                          modifiers: Seq[(ModifierId, Array[Byte])]): Seq[(ModifierId, Array[Byte])] = {

    val (requested, spam) = modifiers.partition { case (id, _) =>
      deliveryTracker.status(id) == Requested
    }

    if (spam.nonEmpty) {
      log.info(s"Spam attempt: peer $remote has sent a non-requested modifiers of type $typeId with ids" +
        s": ${spam.map(_._1).map(encoder.encodeId)}")
      penalizeSpammingPeer(remote)
    }
    requested
  }

  /**
    * Scheduler asking node view synchronizer to check whether requested modifiers have been delivered.
    * Do nothing, if modifier is already in a different state (it might be already received, applied, etc.),
    * wait for delivery until the number of checks exceeds the maximum if the peer sent `Inv` for this modifier
    */
  protected def checkDelivery: Receive = {
    case CheckDelivery(peer, modifierTypeId, modifierId) =>
      if (deliveryTracker.status(modifierId) == ModifiersStatus.Requested) {
        // If transaction not delivered on time, we just forget about it.
        // It could be removed from other peer's mempool, so no reason to penalize the peer.
        if (modifierTypeId == Transaction.ModifierTypeId) {
          deliveryTracker.clearStatusForModifier(modifierId, ModifiersStatus.Requested)
        } else {
          // A persistent modifier is not delivered on time.
          log.info(s"Peer ${peer.toString} has not delivered asked modifier ${encoder.encodeId(modifierId)} on time")
          penalizeNonDeliveringPeer(peer)
          deliveryTracker.onStillWaiting(peer, modifierTypeId, modifierId)
        }
      }
  }

  protected def penalizeNonDeliveringPeer(peer: ConnectedPeer): Unit = {
    networkControllerRef ! PenalizePeer(peer.connectionId.remoteAddress, PenaltyType.NonDeliveryPenalty)
  }

  protected def penalizeSpammingPeer(peer: ConnectedPeer): Unit = {
    networkControllerRef ! PenalizePeer(peer.connectionId.remoteAddress, PenaltyType.SpamPenalty)
  }

  protected def penalizeMisbehavingPeer(peer: ConnectedPeer): Unit = {
    networkControllerRef ! PenalizePeer(peer.connectionId.remoteAddress, PenaltyType.MisbehaviorPenalty)
  }

  protected def penalizeMaliciousPeer(peer: ConnectedPeer): Unit = {
    networkControllerRef ! PenalizePeer(peer.connectionId.remoteAddress, PenaltyType.PermanentPenalty)
  }

  protected def penalizeAndDisconnectPeer(peer: ConnectedPeer): Unit = {
    networkControllerRef ! PenalizePeer(peer.connectionId.remoteAddress, PenaltyType.DisconnectPenalty(networkSettings))
  }

  /**
    * Local node sending out objects requested to remote
    */
  protected def responseFromLocal: Receive = {
    case ResponseFromLocal(peer, _, modifiers: Seq[NodeViewModifier]) =>
      modifiers.headOption.foreach { head =>
        val modType = head.modifierTypeId

        @tailrec
        def sendByParts(mods: Seq[(ModifierId, Array[Byte])]): Unit = {
          var size = Message.HeaderLength + Message.ChecksumLength // message header (magic length + 5) + message checksum
          val batch = mods.takeWhile { case (_, modBytes) =>
            size += NodeViewModifier.ModifierIdSize + 4 + modBytes.length
            size < networkSettings.maxModifiersSpecMessageSize
          }
          peer.handlerRef ! Message(modifiersSpec, Right(ModifiersData(modType, batch)), None)
          val remaining = mods.drop(batch.length)
          if (remaining.nonEmpty) {
            sendByParts(remaining)
          }
        }

        modifierSerializers.get(modType) match {
          case Some(serializer: SparkzSerializer[NodeViewModifier]) =>
            sendByParts(modifiers.map(m => m.id -> serializer.toBytes(m)))
          case _ =>
            log.error(s"Undefined serializer for modifier of type $modType")
        }
      }
  }

  protected def transactionRebroadcast: Receive = {
    case TransactionRebroadcast =>
      val mods = deliveryTracker.getRebroadcastModifiers
      mempoolReaderOpt match {
        case Some(mempool) =>
          mempool.getAll(ids = mods).foreach { tx => broadcastModifierInv(tx) }
        case None =>
          log.warn(s"Trying to rebroadcast while readers are not ready $mempoolReaderOpt")
      }
      deliveryTracker.scheduleRebroadcastIfNeeded()
  }

  override def receive: Receive =
    processDataFromPeer orElse
      getLocalSyncInfo orElse
      processSyncStatus orElse
      responseFromLocal orElse
      viewHolderEvents orElse
      peerManagerEvents orElse
      checkDelivery orElse
      transactionRebroadcast orElse {
      case a: Any => log.error("Strange input: " + a)
    }

}

object NodeViewSynchronizer {

  object Events {

    trait NodeViewSynchronizerEvent

    case object NoBetterNeighbour extends NodeViewSynchronizerEvent

    case object BetterNeighbourAppeared extends NodeViewSynchronizerEvent

  }

  object ReceivableMessages {

    // getLocalSyncInfo messages
    case object SendLocalSyncInfo

    case object TransactionRebroadcast

    case class ResponseFromLocal[M <: NodeViewModifier](source: ConnectedPeer, modifierTypeId: ModifierTypeId, localObjects: Seq[M])

    /**
      * Check delivery of modifier with type `modifierTypeId` and id `modifierId`.
      * `source` may be defined if we expect modifier from concrete peer or None if
      * we just need some modifier, but don't know who have it
      *
      */
    case class CheckDelivery(source: ConnectedPeer,
                             modifierTypeId: ModifierTypeId,
                             modifierId: ModifierId)

    case class OtherNodeSyncingStatus[SI <: SyncInfo](remote: ConnectedPeer,
                                                      status: History.HistoryComparisonResult,
                                                      extension: Seq[(ModifierTypeId, ModifierId)])

    trait PeerManagerEvent

    case class HandshakedPeer(remote: ConnectedPeer) extends PeerManagerEvent

    case class DisconnectedPeer(remote: InetSocketAddress) extends PeerManagerEvent

    trait NodeViewHolderEvent

    trait NodeViewChange extends NodeViewHolderEvent

    case class ChangedHistory[HR <: HistoryReader[_ <: PersistentNodeViewModifier, _ <: SyncInfo]](reader: HR) extends NodeViewChange

    case class ChangedMempool[MR <: MempoolReader[_ <: Transaction]](mempool: MR) extends NodeViewChange

    case class ChangedVault[VR <: VaultReader](reader: VR) extends NodeViewChange

    case class ChangedState[SR <: StateReader](reader: SR) extends NodeViewChange

    //todo: consider sending info on the rollback

    case object RollbackFailed extends NodeViewHolderEvent

    case class NewOpenSurface(newSurface: Seq[ModifierId]) extends NodeViewHolderEvent

    case class StartingPersistentModifierApplication[PMOD <: PersistentNodeViewModifier](modifier: PMOD) extends NodeViewHolderEvent

    /**
      * After application of batch of modifiers from cache to History, NodeViewHolder sends this message,
      * containing all just applied modifiers and cleared from cache
      */
    case class ModifiersProcessingResult[PMOD <: PersistentNodeViewModifier](applied: Seq[PMOD], cleared: Seq[PMOD])

    // hierarchy of events regarding modifiers application outcome
    trait ModificationOutcome extends NodeViewHolderEvent

    /**
      * @param immediateFailure - a flag indicating whether a transaction was invalid by the moment it was received.
      */
    case class FailedTransaction(transactionId: ModifierId, error: Throwable, immediateFailure: Boolean) extends ModificationOutcome

    case class SuccessfulTransaction[TX <: Transaction](transaction: TX) extends ModificationOutcome

    case class SyntacticallyFailedModification[PMOD <: PersistentNodeViewModifier](modifier: PMOD, error: Throwable) extends ModificationOutcome

    case class SemanticallyFailedModification[PMOD <: PersistentNodeViewModifier](modifier: PMOD, error: Throwable) extends ModificationOutcome

    case class SyntacticallySuccessfulModifier[PMOD <: PersistentNodeViewModifier](modifier: PMOD) extends ModificationOutcome

    case class SemanticallySuccessfulModifier[PMOD <: PersistentNodeViewModifier](modifier: PMOD) extends ModificationOutcome

  }

}

object NodeViewSynchronizerRef {
  def props[TX <: Transaction,
    SI <: SyncInfo,
    SIS <: SyncInfoMessageSpec[SI],
    PMOD <: PersistentNodeViewModifier,
    HR <: HistoryReader[PMOD, SI] : ClassTag,
    MR <: MempoolReader[TX] : ClassTag]
  (networkControllerRef: ActorRef,
   viewHolderRef: ActorRef,
   syncInfoSpec: SIS,
   networkSettings: NetworkSettings,
   timeProvider: NetworkTimeProvider,
   modifierSerializers: Map[ModifierTypeId, SparkzSerializer[_ <: NodeViewModifier]])(implicit ec: ExecutionContext): Props =
    Props(new NodeViewSynchronizer[TX, SI, SIS, PMOD, HR, MR](networkControllerRef, viewHolderRef, syncInfoSpec,
      networkSettings, timeProvider, modifierSerializers))

  def apply[TX <: Transaction,
    SI <: SyncInfo,
    SIS <: SyncInfoMessageSpec[SI],
    PMOD <: PersistentNodeViewModifier,
    HR <: HistoryReader[PMOD, SI] : ClassTag,
    MR <: MempoolReader[TX] : ClassTag]
  (networkControllerRef: ActorRef,
   viewHolderRef: ActorRef,
   syncInfoSpec: SIS,
   networkSettings: NetworkSettings,
   timeProvider: NetworkTimeProvider,
   modifierSerializers: Map[ModifierTypeId, SparkzSerializer[_ <: NodeViewModifier]])
  (implicit system: ActorSystem, ec: ExecutionContext): ActorRef =
    system.actorOf(props[TX, SI, SIS, PMOD, HR, MR](networkControllerRef, viewHolderRef,
      syncInfoSpec, networkSettings, timeProvider, modifierSerializers))

  def apply[TX <: Transaction,
    SI <: SyncInfo,
    SIS <: SyncInfoMessageSpec[SI],
    PMOD <: PersistentNodeViewModifier,
    HR <: HistoryReader[PMOD, SI] : ClassTag,
    MR <: MempoolReader[TX] : ClassTag]
  (name: String,
   networkControllerRef: ActorRef,
   viewHolderRef: ActorRef,
   syncInfoSpec: SIS,
   networkSettings: NetworkSettings,
   timeProvider: NetworkTimeProvider,
   modifierSerializers: Map[ModifierTypeId, SparkzSerializer[_ <: NodeViewModifier]])
  (implicit system: ActorSystem, ec: ExecutionContext): ActorRef =
    system.actorOf(props[TX, SI, SIS, PMOD, HR, MR](networkControllerRef, viewHolderRef,
      syncInfoSpec, networkSettings, timeProvider, modifierSerializers), name)
}