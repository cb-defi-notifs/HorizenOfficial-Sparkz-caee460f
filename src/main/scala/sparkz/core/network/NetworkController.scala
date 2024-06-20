package sparkz.core.network

import akka.actor._
import akka.io.Tcp._
import akka.io.{IO, Tcp}
import akka.pattern.ask
import akka.util.Timeout
import sparkz.core.app.{SparkzContext, Version}
import sparkz.core.network.NetworkController.ReceivableMessages.Internal.ConnectionToPeer
import sparkz.core.network.NodeViewSynchronizer.ReceivableMessages.{DisconnectedPeer, HandshakedPeer}
import sparkz.core.network.message.Message.MessageCode
import sparkz.core.network.message.{Message, MessageSpec}
import sparkz.core.network.peer.PeerManager.ReceivableMessages._
import sparkz.core.network.peer._
import sparkz.core.settings.NetworkSettings
import sparkz.core.utils.TimeProvider.Time
import sparkz.core.utils.{LRUSimpleCache, NetworkUtils, TimeProvider}
import sparkz.util.SparkzLogging

import java.net._
import scala.jdk.CollectionConverters._
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.language.{existentials, postfixOps}
import scala.util.Try

/**
  * Control all network interaction
  * must be singleton
  */
class NetworkController(settings: NetworkSettings,
                        peerManagerRef: ActorRef,
                        sparkzContext: SparkzContext,
                        tcpManager: ActorRef
                       )(implicit ec: ExecutionContext) extends Actor with SparkzLogging {

  import NetworkController.ReceivableMessages._
  import PeerConnectionHandler.ReceivableMessages.CloseConnection
  import akka.actor.SupervisorStrategy._

  override val supervisorStrategy: OneForOneStrategy = OneForOneStrategy(
    maxNrOfRetries = NetworkController.ChildActorHandlingRetriesNr,
    withinTimeRange = 1.minute) {
    case _: ActorKilledException => Stop
    case _: DeathPactException => Stop
    case e: ActorInitializationException =>
      log.warn(s"Stopping child actor failed with: $e")
      Stop
    case e: Exception =>
      log.warn(s"Restarting child actor failed with: $e")
      Restart
  }

  private implicit val system: ActorSystem = context.system

  private implicit val timeout: Timeout = Timeout(settings.controllerTimeout.getOrElse(5 seconds))

  private var messageHandlers = Map.empty[MessageCode, ActorRef]

  private lazy val bindAddress = settings.bindAddress

  private var connections = Map.empty[InetSocketAddress, ConnectedPeer]
  private var unconfirmedConnections = Set.empty[InetSocketAddress]
  private val maxConnections = settings.maxIncomingConnections + settings.maxOutgoingConnections + settings.maxForgerConnections
  private val peersLastConnectionAttempts = new LRUSimpleCache[InetSocketAddress, TimeProvider.Time](threshold = 10000)

  private val tryNewConnectionAttemptDelay = 5.seconds

  private val mySessionIdFeature = SessionIdPeerFeature(settings.magicBytes)
  /**
    * Storing timestamp of a last message got via p2p network.
    * Used to check whether connectivity is lost.
    */
  private var lastIncomingMessageTime: TimeProvider.Time = 0L

  //check own declared address for validity
  validateDeclaredAddress()

  log.info(s"Declared address: ${sparkzContext.externalNodeAddress}")

  //bind to listen incoming connections
  tcpManager ! Bind(self, bindAddress, options = Nil, pullMode = false)

  override def receive: Receive =
    bindingLogic orElse
      startConnectingCommand orElse
      businessLogic orElse
      peerCommands orElse
      connectionEvents orElse
      interfaceCalls orElse
      nonsense

  private def bindingLogic: Receive = {
    case Bound(_) =>
      log.info("Successfully bound to the port " + settings.bindAddress.getPort)

    case CommandFailed(_: Bind) =>
      log.error("Network port " + settings.bindAddress.getPort + " already in use!")
      java.lang.System.exit(1) // Terminate node if port is in use
      context stop self
  }

  private def startConnectingCommand: Receive = {
    case StartConnectingPeers =>
      log.info("Start connecting to peers")
      scheduleConnectionToPeer()
      scheduleDroppingDeadConnections()
  }

  private def networkTime(): Time = sparkzContext.timeProvider.time()

  private def businessLogic: Receive = {
    //a message coming in from another peer
    case msg@Message(spec, _, Some(remote)) =>
      messageHandlers.get(spec.messageCode) match {
        case Some(handler) => handler ! msg // forward the message to the appropriate handler for processing
        case None => log.error(s"No handlers found for message $remote: " + spec.messageCode)
      }

      // Update last seen message timestamps, global and peer's, with the message timestamp
      val remoteAddress = remote.connectionId.remoteAddress
      connections.get(remoteAddress) match {
        case Some(cp) =>
          val now = networkTime()
          lastIncomingMessageTime = now
          cp.lastMessage = now
        case None => log.warn("Connection not found for a message got from: " + remoteAddress)
      }

    case SendToNetwork(message, sendingStrategy) =>
      filterConnections(sendingStrategy, message.spec.protocolVersion).foreach { connectedPeer =>
        connectedPeer.handlerRef ! message
      }

    case RegisterMessageSpecs(specs, handler) =>
      log.info(s"Registering handlers for ${specs.map(s => s.messageCode -> s.messageName)}")
      messageHandlers ++= specs.map(_.messageCode -> handler)
  }

  private def peerCommands: Receive = {
    case ConnectTo(peer) =>
      connectTo(peer)

    case DisconnectFrom(peer) =>
      log.info(s"Disconnected from ${peer.connectionId}")
      peer.handlerRef ! CloseConnection

    case PenalizePeer(peerAddress, penaltyType) =>
      penalize(peerAddress, penaltyType)

    case DisconnectFromAddress(peerAddress) =>
      closeConnection(peerAddress)

    case DisconnectFromNode(peerAddress) =>
      closeConnectionFromNode(peerAddress)

  }

  private def connectionEvents: Receive = {
    case Connected(remoteAddress, localAddress) if isNewConnectionAndStillHaveRoom(remoteAddress) =>
      handleNewConnectionCreation(remoteAddress, localAddress)

    case Connected(remoteAddress, _) =>
      val logMessage = if (connections.size >= maxConnections) "Max connections limit reached"
                       else s"Connection to peer $remoteAddress is already established"
      log.warn(logMessage)
      sender() ! Close

    case ConnectionConfirmed(connectionId, handlerRef) =>
      log.info(s"Connection confirmed to $connectionId")
      createPeerConnectionHandler(connectionId, handlerRef)

    case ConnectionDenied(connectionId, handlerRef) =>
      log.info(s"Incoming connection from ${connectionId.remoteAddress} denied")
      handlerRef ! Close

    case Handshaked(connectedPeer) =>
      handleHandshake(connectedPeer, sender())

    case f@CommandFailed(c: Connect) =>
      unconfirmedConnections -= c.remoteAddress
      f.cause match {
        case Some(t) => log.info("Failed to connect to : " + c.remoteAddress, t)
        case None => log.info("Failed to connect to : " + c.remoteAddress)
      }

      // If a message received from p2p within connection timeout,
      // connectivity is not lost thus we're removing the peer
      if (networkTime() - lastIncomingMessageTime < settings.connectionTimeout.toMillis) {
        peerManagerRef ! RemovePeer(c.remoteAddress)
      }

    case Terminated(ref) =>
      connectionForHandler(ref).foreach { connectedPeer =>
        val remoteAddress = connectedPeer.connectionId.remoteAddress
        connections -= remoteAddress
        unconfirmedConnections -= remoteAddress
        context.system.eventStream.publish(DisconnectedPeer(remoteAddress))
      }

    case _: ConnectionClosed =>
      log.info("Denied connection has been closed")

    case ConnectionToPeer(activeConnections, unconfirmedConnections) =>
      connectionToPeer(activeConnections, unconfirmedConnections)
  }

  private def handleNewConnectionCreation(remoteAddress: InetSocketAddress, localAddress: InetSocketAddress): Unit = {
    val connectionDirection: ConnectionDirection = if (unconfirmedConnections.contains(remoteAddress)) Outgoing else Incoming
    val connectionId = ConnectionId(remoteAddress, localAddress, connectionDirection)

    log.info(s"Unconfirmed connection: ($remoteAddress, $localAddress) => $connectionId")

    val handlerRef = sender()
    if (connectionDirection.isOutgoing && (canEstablishNewOutgoingConnection || canEstablishNewForgerConnection)) {
      createPeerConnectionHandler(connectionId, handlerRef)
    }
    else if (connectionDirection.isIncoming && canEstablishNewIncomingConnection)
      peerManagerRef ! ConfirmConnection(connectionId, handlerRef)
    else {
      log.info(s"Connection to address ${connectionId.remoteAddress} refused; there is no room left for a new peer")
      handlerRef ! Close
    }
  }

  private def updateLastConnectionAttemptTimestamp(remoteAddress: InetSocketAddress): Unit = {
    peersLastConnectionAttempts.put(remoteAddress, sparkzContext.timeProvider.time())
  }

  private def isNewConnectionAndStillHaveRoom(remoteAddress: InetSocketAddress) = {
    connectionForPeerAddress(remoteAddress).isEmpty && connections.size < maxConnections
  }

  //calls from API / application
  private def interfaceCalls: Receive = {
    case GetPeersStatus =>
      sender() ! PeersStatus(lastIncomingMessageTime, networkTime())

    case GetConnectedPeers =>
      sender() ! connections.values.filter(_.peerInfo.nonEmpty)

    case GetFilteredConnectedPeers(strategy, version) =>
      sender() ! filterConnections(strategy, version)

    case ShutdownNetwork =>
      log.info("Going to shutdown all connections & unbind port")
      filterConnections(Broadcast, Version.initial).foreach { connectedPeer =>
        connectedPeer.handlerRef ! CloseConnection
      }
      tcpManager ! Unbind
      context stop self
  }

  private def nonsense: Receive = {
    case CommandFailed(cmd: Command) =>
      log.info("Failed to execute command : " + cmd)

    case nonsense: Any =>
      log.warn(s"NetworkController: got unexpected input $nonsense")
  }

  /**
    * Schedule a periodic connection to a random known peer
    */
  private def scheduleConnectionToPeer(): Unit = {
    val connectionTask: Runnable = () => {
      if (canEstablishNewOutgoingConnection  || canEstablishNewForgerConnection) {
        log.trace(s"Looking for a new random connection")
        connectionToPeer(connections, unconfirmedConnections)
      }
    }
    context.system.scheduler.scheduleWithFixedDelay(5.seconds, tryNewConnectionAttemptDelay)(connectionTask)
    connectionTask.run()
  }

  private def canEstablishNewOutgoingConnection: Boolean = {
    getOutgoingConnectionsSize < settings.maxOutgoingConnections
  }

  private def canEstablishNewIncomingConnection: Boolean = {
    getIncomingConnectionsSize < settings.maxIncomingConnections
  }

  private def canEstablishNewForgerConnection: Boolean = {
    getForgerConnectionsSize < settings.maxForgerConnections
  }

  private def shouldDropForgerConnection: Boolean = {
    getForgerConnectionsSize > settings.maxForgerConnections
  }

  private def shouldDropOutgoingConnection: Boolean = {
    getOutgoingConnectionsSize > settings.maxOutgoingConnections
  }

  private def shouldDropIncomingConnection: Boolean = {
    getIncomingConnectionsSize > settings.maxIncomingConnections
  }

  private def getOutgoingConnectionsSize: Int = {
    connections.count { p => p._2.connectionId.direction == Outgoing }
  }

  private def getIncomingConnectionsSize: Int = {
    connections.count { p => p._2.connectionId.direction == Incoming }
  }

  private def getForgerConnectionsSize: Int = {
    connections.count { p => p._2.peerInfo.exists(_.peerSpec.features.contains(ForgerNodePeerFeature())) }
  }

  private def connectionToPeer(activeConnections: Map[InetSocketAddress, ConnectedPeer], unconfirmedConnections: Set[InetSocketAddress]): Unit = {
    val connectionsAddressSeq = activeConnections.values.flatMap(_.peerInfo).toSeq
    val unconfirmedConnectionsAddressSeq = unconfirmedConnections.map(connection => PeerInfo.fromAddress(connection)).toSeq
    val mergedSeq = connectionsAddressSeq ++ unconfirmedConnectionsAddressSeq
    val peersAddresses = mergedSeq.map(getPeerAddress)

    val peersAlreadyTriedFewTimeBefore = getPeersWeAlreadyTriedToConnectFewTimeAgo

    val randomPeerF = peerManagerRef ? RandomPeerForConnectionExcluding(peersAddresses ++ peersAlreadyTriedFewTimeBefore, settings.onlyConnectToKnownPeers)
    randomPeerF.mapTo[Option[PeerInfo]].foreach {
      case Some(peerInfo) =>
        peerInfo.peerSpec.address.foreach(address => {
          updateLastConnectionAttemptTimestamp(address)
          self ! ConnectTo(peerInfo)
        })
      case None => log.warn("Could not find a peer to connect to, skipping this connectionToPeer round")
    }
  }

  private def getPeersWeAlreadyTriedToConnectFewTimeAgo: Seq[Option[InetSocketAddress]] = {
    val now = sparkzContext.timeProvider.time()
    // This delta is to make sure we wait for enough time before trying another connection attempt to the same peer
    // In this case we take into account the size of the known peers since they are always prioritized over other peers
    val delta = (settings.knownPeers.size + 1) * 5
    val thresholdInMillis = (tryNewConnectionAttemptDelay * delta).toMillis

    peersLastConnectionAttempts.asScala.map {
      case entry if now - entry._2 < thresholdInMillis => Some(entry._1)
      case _ => None
    }.toSeq
  }

  /**
    * Schedule a periodic dropping of connections which seem to be inactive
    */
  private def scheduleDroppingDeadConnections(): Unit = {
    context.system.scheduler.scheduleWithFixedDelay(60.seconds, 60.seconds) {
      () => {
        // Drop connections with peers if they seem to be inactive
        val now = networkTime()
        connections.values.foreach { cp =>
          val lastSeen = cp.lastMessage
          val timeout = settings.inactiveConnectionDeadline.toMillis
          val delta = now - lastSeen
          if (delta > timeout) {
            log.info(s"Dropping connection with ${cp.peerInfo}, last seen ${delta / 1000.0} seconds ago")
            cp.handlerRef ! CloseConnection
          }
        }
      }
    }
  }

  /**
    * Connect to peer
    *
    * @param peer - PeerInfo
    */
  private def connectTo(peer: PeerInfo): Unit = {
    log.info(s"Connecting to peer: $peer")
    getPeerAddress(peer) match {
      case Some(remote) =>
        if (connectionForPeerAddress(remote).isEmpty && !unconfirmedConnections.contains(remote)) {
          unconfirmedConnections += remote
          tcpManager ! Connect(
            remoteAddress = remote,
            options = Nil,
            timeout = Some(settings.connectionTimeout),
            pullMode = true
          )
        } else {
          log.warn(s"Connection to peer $remote is already established")
        }
      case None =>
        log.warn(s"Can't obtain remote address for peer $peer")
    }
  }

  /**
    * Creates a PeerConnectionHandler for the established connection
    *
    * @param connectionId - connection detailed info
    * @param connection   - connection ActorRef
    */
  private def createPeerConnectionHandler(connectionId: ConnectionId,
                                          connection: ActorRef): Unit = {
    log.info {
      connectionId.direction match {
        case Incoming =>
          s"New incoming connection from ${connectionId.remoteAddress} established (bound to local ${connectionId.localAddress})"
        case Outgoing =>
          s"New outgoing connection to ${connectionId.remoteAddress} established (bound to local ${connectionId.localAddress})"
      }
    }
    val isLocal = NetworkUtils.isLocalAddress(connectionId.remoteAddress.getAddress)
    val mandatoryFeatures = sparkzContext.features :+ mySessionIdFeature

    val maybeTransactionDisabledFeature =
      if (settings.handlingTransactionsEnabled) None else Some(TransactionsDisabledPeerFeature())
    val maybeLocalAddressFeature = if (isLocal) {
      val la = new InetSocketAddress(connectionId.localAddress.getAddress, settings.bindAddress.getPort)
      Some(LocalAddressPeerFeature(la))
    } else None
    val maybeForgerNodeFeature = if (settings.isForgerNode) Some(ForgerNodePeerFeature()) else None

    val peerFeatures = mandatoryFeatures ++ maybeTransactionDisabledFeature ++ maybeLocalAddressFeature ++ maybeForgerNodeFeature
    val selfAddressOpt = getNodeAddressForPeer(connectionId.localAddress)

    if (selfAddressOpt.isEmpty)
      log.warn("Unable to define external address. Specify it manually in `sparkz.network.declaredAddress`.")

    val connectionDescription = ConnectionDescription(connection, connectionId, selfAddressOpt, peerFeatures)

    val handlerProps: Props = PeerConnectionHandlerRef.props(settings, self, peerManagerRef,
      sparkzContext, connectionDescription)

    val handler = context.actorOf(handlerProps) // launch connection handler
    context.watch(handler)
    val connectedPeer = ConnectedPeer(connectionId, handler, networkTime(), None)
    connections += connectionId.remoteAddress -> connectedPeer
    unconfirmedConnections -= connectionId.remoteAddress
  }

  private def handleHandshake(peerInfo: PeerInfo, peerHandlerRef: ActorRef): Unit = {
    connectionForHandler(peerHandlerRef).foreach { connectedPeer =>
      val remoteAddress = connectedPeer.connectionId.remoteAddress
      val peerAddress = peerInfo.peerSpec.address.getOrElse(remoteAddress)
      // Drop connection to self if occurred or peer already connected.
      // Decision whether connection is local or is from some other network is made
      // based on SessionIdPeerFeature if exists or in old way using isSelf() function
      var shouldDrop =
        connectionForPeerAddress(peerAddress).exists(_.handlerRef != peerHandlerRef) ||
        peerInfo.peerSpec.features.collectFirst {
          case SessionIdPeerFeature(networkMagic, sessionId) =>
            !networkMagic.sameElements(mySessionIdFeature.networkMagic) || sessionId == mySessionIdFeature.sessionId
        }.getOrElse(isSelf(remoteAddress))

      // We allow temporary overflowing outgoing connection limits to get the peerInfo and see if peer if a forger.
      // Drop connection if the peer does not fit in the limits.
      val isForgerConnection = peerInfo.peerSpec.features.contains(ForgerNodePeerFeature()) && settings.isForgerNode

      val connectionLimitExhausted = isConnectionLimitExhausted(peerInfo, isForgerConnection)
      shouldDrop = shouldDrop || connectionLimitExhausted

      if (shouldDrop) {
        connectedPeer.handlerRef ! CloseConnection
        peerManagerRef ! RemovePeer(peerAddress)
        connections -= connectedPeer.connectionId.remoteAddress
      } else {
        peerManagerRef ! UpdatePeer(peerInfo)

        val updatedConnectedPeer = connectedPeer.copy(peerInfo = Some(peerInfo))
        connections += remoteAddress -> updatedConnectedPeer
        context.system.eventStream.publish(HandshakedPeer(updatedConnectedPeer))
      }
    }
  }

  private[network] def isConnectionLimitExhausted(peerInfo: PeerInfo, isForgerConnection: Boolean) = {
    (isForgerConnection && shouldDropForgerConnection) ||
      (!isForgerConnection && peerInfo.connectionType.contains(Incoming) && shouldDropIncomingConnection) ||
      (!isForgerConnection && peerInfo.connectionType.contains(Outgoing) && shouldDropOutgoingConnection)
  }

  /**
    * Returns connections filtered by given SendingStrategy and Version.
    * Exclude all connections with lower version and apply sendingStrategy to remaining connected peers
    *
    * @param sendingStrategy - SendingStrategy
    * @param version         - minimal version required
    * @return sequence of ConnectedPeer instances according SendingStrategy
    */
  private def filterConnections(sendingStrategy: SendingStrategy, version: Version): Seq[ConnectedPeer] = {
    sendingStrategy.choose(connections.values.toSeq.filter(_.peerInfo.exists(_.peerSpec.protocolVersion >= version)))
  }

  /**
    * Returns connection for given PeerConnectionHandler ActorRef
    *
    * @param handler ActorRef on PeerConnectionHandler actor
    * @return Some(ConnectedPeer) when the connection exists for this handler, and None otherwise
    */
  private def connectionForHandler(handler: ActorRef) = {
    connections.values.find { connectedPeer =>
      connectedPeer.handlerRef == handler
    }
  }

  /**
    * Returns connection for given address of the peer
    *
    * @param peerAddress - socket address of peer
    * @return Some(ConnectedPeer) when the connection exists for this peer, and None otherwise
    */
  private def connectionForPeerAddress(peerAddress: InetSocketAddress): Option[ConnectedPeer] = {
    connections.values.find { connectedPeer =>
      connectedPeer.connectionId.remoteAddress == peerAddress ||
        connectedPeer.peerInfo.exists(peerInfo => getPeerAddress(peerInfo).contains(peerAddress))
    }
  }

  /**
    * Checks the node owns the address
    */
  private def isSelf(peerAddress: InetSocketAddress): Boolean = {
    NetworkUtils.isSelf(peerAddress, bindAddress, sparkzContext.externalNodeAddress)
  }

  /**
    * Returns local address of peer for local connections and WAN address of peer for
    * external connections.
    *
    * @param peer - known information about peer
    * @return socket address of the peer
    */
  private def getPeerAddress(peer: PeerInfo): Option[InetSocketAddress] = {
    (peer.peerSpec.localAddressOpt, peer.peerSpec.declaredAddress) match {
      case (Some(localAddr), _) =>
        Some(localAddr)

      case _ => peer.peerSpec.declaredAddress
    }
  }

  /**
    * Returns the node address reachable from Internet
    *
    * @param localSocketAddress - local socket address of the connection to the peer
    * @return - socket address of the node
    */
  private def getNodeAddressForPeer(localSocketAddress: InetSocketAddress) = {
    val localAddr = localSocketAddress.getAddress
    sparkzContext.externalNodeAddress match {
      case Some(extAddr) =>
        Some(extAddr)

      case None =>
        if (!NetworkUtils.isLocalAddress(localAddr) && localSocketAddress.getPort == settings.bindAddress.getPort) {
          Some(localSocketAddress)
        } else {
          val listenAddrs = NetworkUtils.getListenAddresses(settings.bindAddress)
            .filterNot(addr => NetworkUtils.isLocalAddress(addr.getAddress))

          listenAddrs.find(addr => localAddr == addr.getAddress).orElse(listenAddrs.headOption)
        }
    }
  }

  private def validateDeclaredAddress(): Unit = {
    if (!settings.localOnly) {
      settings.declaredAddress.foreach { mySocketAddress =>
        Try {
          val uri = new URI("http://" + mySocketAddress)
          val myHost = uri.getHost
          val myAddress = InetAddress.getAllByName(myHost)

          val listenAddresses = NetworkUtils.getListenAddresses(bindAddress)
          val valid = listenAddresses.exists(addr => myAddress.contains(addr.getAddress))

          if (!valid) {
            log.error(
              s"""Declared address validation failed:
                 | $mySocketAddress not match any of the listening address: $listenAddresses""".stripMargin)
          }
        } recover { case t: Throwable =>
          log.error("Declared address validation failed: ", t)
        }
      }
    }
  }

  private def closeConnection(peerAddress: InetSocketAddress): Unit = {
    connections = connections.filter { case (_, connectedPeer) =>
      Option(connectedPeer)
        .filter(_.connectionId.remoteAddress.equals(peerAddress))
        .map { peer =>
          peer.handlerRef ! CloseConnection
          context.system.eventStream.publish(DisconnectedPeer(peerAddress))
        }
        .isEmpty
    }
  }

  private def closeConnectionFromNode(peerAddress: InetSocketAddress): Unit = {
    connections = connections.filterNot {
      case (address, connectedPeer) if address == peerAddress =>
          connectedPeer.handlerRef ! CloseConnection
          context.system.eventStream.publish(DisconnectedPeer(peerAddress))
          true // exclude the entry from the filtered map
      case _ => false // don't modify the connections map
    }
  }

  /**
    * Register a new penalty for given peer address.
    */
  private def penalize(peerAddress: InetSocketAddress, penaltyType: PenaltyType): Unit =
    peerManagerRef ! PeerManager.ReceivableMessages.Penalize(peerAddress, penaltyType)

}

object NetworkController {

  val ChildActorHandlingRetriesNr: Int = 10

  object ReceivableMessages {

    case class Handshaked(peer: PeerInfo)

    case class RegisterMessageSpecs(specs: Seq[MessageSpec[_]], handler: ActorRef)

    case class SendToNetwork(message: Message[_], sendingStrategy: SendingStrategy)

    case object ShutdownNetwork

    case class ConnectTo(peer: PeerInfo)

    case class DisconnectFrom(peer: ConnectedPeer)

    case class DisconnectFromNode(address: InetSocketAddress)

    case class PenalizePeer(address: InetSocketAddress, penaltyType: PenaltyType)

    case class GetFilteredConnectedPeers(sendingStrategy: SendingStrategy, version: Version)

    case object GetConnectedPeers

    case object StartConnectingPeers

    /**
      * Get p2p network status
      */
    case object GetPeersStatus

    private[network] object Internal {
      case class ConnectionToPeer(activeConnections: Map[InetSocketAddress, ConnectedPeer], unconfirmedConnections: Set[InetSocketAddress])
    }

  }

}

object NetworkControllerRef {
  def props(settings: NetworkSettings,
            peerManagerRef: ActorRef,
            sparkzContext: SparkzContext,
            tcpManager: ActorRef)(implicit ec: ExecutionContext): Props = {
    Props(new NetworkController(settings, peerManagerRef, sparkzContext, tcpManager))
  }

  def apply(settings: NetworkSettings,
            peerManagerRef: ActorRef,
            sparkzContext: SparkzContext)
           (implicit system: ActorSystem, ec: ExecutionContext): ActorRef = {
    system.actorOf(
      props(settings, peerManagerRef, sparkzContext, IO(Tcp))
    )
  }

  def apply(name: String,
            settings: NetworkSettings,
            peerManagerRef: ActorRef,
            sparkzContext: SparkzContext,
            tcpManager: ActorRef)
           (implicit system: ActorSystem, ec: ExecutionContext): ActorRef = {
    system.actorOf(
      props(settings, peerManagerRef, sparkzContext, tcpManager),
      name)
  }

  def apply(name: String,
            settings: NetworkSettings,
            peerManagerRef: ActorRef,
            sparkzContext: SparkzContext)
           (implicit system: ActorSystem, ec: ExecutionContext): ActorRef = {

    system.actorOf(
      props(settings, peerManagerRef, sparkzContext, IO(Tcp)),
      name)
  }

}
