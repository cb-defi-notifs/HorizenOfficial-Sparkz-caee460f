package sparkz.core.network

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.TestProbe
import scorex.util.ModifierId
import sparkz.core.NodeViewHolder.ReceivableMessages.{GetNodeViewChanges, TransactionsFromRemote}
import sparkz.core.network.NetworkController.ReceivableMessages.{PenalizePeer, RegisterMessageSpecs}
import sparkz.core.network.message._
import sparkz.core.network.peer.PenaltyType.MisbehaviorPenalty
import sparkz.core.serialization.SparkzSerializer
import sparkz.core.settings.SparkzSettings
import sparkz.core.transaction.Transaction
import sparkz.core.utils.NetworkTimeProvider
import sparkz.core.{ModifierTypeId, NodeViewModifier}

import java.net.InetSocketAddress
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success}

class NodeViewSynchronizerSpecification extends NetworkTests with TestImplementations {

  implicit val actorSystem: ActorSystem = ActorSystem()
  implicit val executionContext: ExecutionContext = actorSystem.dispatchers.lookup("sparkz.executionContext")
  private val modifiersSpec = new ModifiersSpec(1024 * 1024)
  private val (synchronizer, networkController, viewHolder) = createNodeViewSynchronizer(settings)
  private val peer = ConnectedPeer(ConnectionId(new InetSocketAddress(10), new InetSocketAddress(11), Incoming), TestProbe().ref, 0L, None)

  private val messageSerializer = new MessageSerializer(Seq(modifiersSpec), settings.network.magicBytes, settings.network.messageLengthBytesLimit)


  "NodeViewSynchronizer" should "not penalize peer for sending valid modifiers" in {
    val transaction = TestTransaction(1, 1)
    val txBytes = TestTransactionSerializer.toBytes(transaction)

    synchronizer ! roundTrip(Message(modifiersSpec, Right(ModifiersData(Transaction.ModifierTypeId, Map(transaction.id -> txBytes))), Some(peer)))
    viewHolder.expectMsg(TransactionsFromRemote(Seq(transaction)))
    networkController.expectNoMessage()
  }

  it should "penalize peer for sending malformed modifiers" in {
    val transaction = TestTransaction(1, 1)
    val txBytes = TestTransactionSerializer.toBytes(transaction) ++ Array[Byte](0x01, 0x02)

    synchronizer ! roundTrip(Message(modifiersSpec, Right(ModifiersData(Transaction.ModifierTypeId, Map(transaction.id -> txBytes))), Some(peer)))
    viewHolder.expectMsg(TransactionsFromRemote(Seq(transaction)))
    networkController.expectMsg(PenalizePeer(peer.connectionId.remoteAddress, MisbehaviorPenalty))
  }

  def roundTrip(msg: Message[_]): Message[_] = {
    messageSerializer.deserialize(messageSerializer.serialize(msg), msg.source) match {
      case Failure(e) => throw e
      case Success(value) => value match {
        case Some(value) => value
        case None => fail("")
      }
    }
  }

  private def createNodeViewSynchronizer(settings: SparkzSettings): (ActorRef, TestProbe, TestProbe) = {
    val networkControllerProbe = TestProbe()
    val viewHolderProbe = TestProbe()
    val timeProvider = new NetworkTimeProvider(settings.ntp)

    val modifierSerializers: Map[ModifierTypeId, SparkzSerializer[_ <: NodeViewModifier]] =
      Map(Transaction.ModifierTypeId -> TestTransactionSerializer)

    val nodeViewSynchronizerRef = actorSystem.actorOf(Props(
      new NodeViewSynchronizer
        [
          TestTransaction,
          TestSyncInfo,
          TestSyncInfoMessageSpec.type,
          TestModifier,
          TestHistory,
          TestMempool
        ]
      (
        networkControllerProbe.ref,
        viewHolderProbe.ref,
        TestSyncInfoMessageSpec,
        settings.network,
        timeProvider,
        modifierSerializers
      ) {
        override val deliveryTracker: DeliveryTracker = new DeliveryTracker(context.system, deliveryTimeout, maxDeliveryChecks, maxRequestedPerPeer, self) {
          override def status(modifierId: ModifierId): ModifiersStatus = ModifiersStatus.Requested
        }
      }
    ))
    networkControllerProbe.expectMsgType[RegisterMessageSpecs](5000.millis)
    viewHolderProbe.expectMsgType[GetNodeViewChanges](5000.millis)

    (nodeViewSynchronizerRef, networkControllerProbe, viewHolderProbe)
  }
}
