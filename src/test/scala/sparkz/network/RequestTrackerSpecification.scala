package sparkz.network

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{TestActor, TestProbe}
import sparkz.ObjectGenerators
import sparkz.core.app.Version
import sparkz.core.network.NetworkController.ReceivableMessages.{GetFilteredConnectedPeers, PenalizePeer, RegisterMessageSpecs, SendToNetwork}
import sparkz.core.network.message.Message.MessageCode
import sparkz.core.network.message.{GetPeersSpec, Message, ModifiersSpec, PeersSpec}
import sparkz.core.network.peer.PeerInfo
import sparkz.core.network.peer.PenaltyType.{NonDeliveryPenalty, SpamPenalty}
import sparkz.core.network._
import sparkz.core.settings.{NetworkSettings, SparkzSettings}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

@SuppressWarnings(Array(
  "org.wartremover.warts.Null",
  "org.wartremover.warts.TraversableOps",
  "org.wartremover.warts.OptionPartial"))
class RequestTrackerSpecification extends NetworkTests with ObjectGenerators {

  implicit val actorSystem: ActorSystem = ActorSystem()
  implicit val executionContext: ExecutionContext = actorSystem.dispatchers.lookup("sparkz.executionContext")

  private val deliveryTimeout: FiniteDuration = 2.seconds
  private val requestMessageCode: MessageCode = GetPeersSpec.messageCode
  private val responseMessageCode: MessageCode = PeersSpec.messageCode
  private val connectedPeer = connectedPeerGen(null).sample.get
    .copy(peerInfo = Some(PeerInfo(PeerSpec("unknown", Version.initial, "unknown", None, Seq()), 0L, Some(Outgoing))))

  private def withTestedActors(test: (ActorRef, TestProbe, TestProbe, NetworkSettings) => Unit): Unit = {
    val (requestTracker, networkControllerProbe, peerSynchronizerProbe, networkSettings) = prepareTestData()
    test(requestTracker, networkControllerProbe, peerSynchronizerProbe, networkSettings)
  }

  "Request Tracker" should "forward registerMessageSpec to network controller, replacing ref to itself" in {
    withTestedActors { (requestTracker, networkControllerProbe, peerSynchronizerProbe, _) =>
      val registerSpecMessage = RegisterMessageSpecs(Seq(), peerSynchronizerProbe.ref)
      requestTracker ! registerSpecMessage
      networkControllerProbe.expectMsg(RegisterMessageSpecs(Seq(), requestTracker))
    }
  }

  it should "use actor specified in registerMessageSpec for forwarding" in {
    withTestedActors { (requestTracker, networkControllerProbe, peerSynchronizerProbe, _) =>
      val registerSpecMessage = RegisterMessageSpecs(Seq(), peerSynchronizerProbe.ref)
      requestTracker ! registerSpecMessage
      val messageToBeForwarded = Message(new ModifiersSpec(1), Left(null), None)
      networkControllerProbe.expectMsg(RegisterMessageSpecs(Seq(), requestTracker))

      requestTracker tell (messageToBeForwarded, networkControllerProbe.ref)
      peerSynchronizerProbe.expectMsg(messageToBeForwarded)

      requestTracker ! messageToBeForwarded
      networkControllerProbe.expectMsg(messageToBeForwarded)
    }
  }

  it should "penalize peer for not delivering response" in {
    withTestedActors { (requestTracker, networkControllerProbe, peerSynchronizerProbe, _) =>
      val msg = Message(GetPeersSpec, Left(null), None)
      val request = SendToNetwork(msg, SendToRandom)

      //register
      val registerSpecMessage = RegisterMessageSpecs(Seq(), peerSynchronizerProbe.ref)
      requestTracker ! registerSpecMessage
      networkControllerProbe.expectMsg(RegisterMessageSpecs(Seq(), requestTracker))

      //request
      networkControllerProbe.setAutoPilot(
        (sender: ActorRef, msg: Any) => {
          msg match {
            case GetFilteredConnectedPeers(_,_) => sender ! Seq(connectedPeer)
            case _ =>
          }
          TestActor.KeepRunning
        }
      )
      requestTracker ! request
      networkControllerProbe.expectMsg(GetFilteredConnectedPeers(SendToRandom, GetPeersSpec.protocolVersion))
      networkControllerProbe.expectMsg(request.copy(sendingStrategy = SendToPeer(connectedPeer)))

      //penalty
      networkControllerProbe.expectMsg(deliveryTimeout + 1.seconds, PenalizePeer(connectedPeer.connectionId.remoteAddress, NonDeliveryPenalty))
    }
  }


  it should "not penalize when response is received" in {
    withTestedActors { (requestTracker, networkControllerProbe, peerSynchronizerProbe, _) =>
      val msg = Message(GetPeersSpec, Left(null), None)
      val request = SendToNetwork(msg, SendToRandom)
      val response = Message(new PeersSpec(Map(), 1), Left(null), Some(connectedPeer))

      //register
      val registerSpecMessage = RegisterMessageSpecs(Seq(), peerSynchronizerProbe.ref)
      requestTracker ! registerSpecMessage
      networkControllerProbe.expectMsg(RegisterMessageSpecs(Seq(), requestTracker))

      //request
      networkControllerProbe.setAutoPilot(
        (sender: ActorRef, msg: Any) => {
          msg match {
            case GetFilteredConnectedPeers(_,_) => sender ! Seq(connectedPeer)
            case _ =>
          }
          TestActor.KeepRunning
        }
      )
      requestTracker ! request
      networkControllerProbe.expectMsg(GetFilteredConnectedPeers(SendToRandom, GetPeersSpec.protocolVersion))
      networkControllerProbe.expectMsg(request.copy(sendingStrategy = SendToPeer(connectedPeer)))

      //response
      requestTracker ! response
      peerSynchronizerProbe.expectMsg(response)

      //no penalty
      networkControllerProbe.expectNoMessage(deliveryTimeout + 1.seconds)
    }
  }

  it should "penalize peer for spamming" in {
    withTestedActors { (requestTracker, networkControllerProbe, peerSynchronizerProbe, _) =>
      val response = Message(new PeersSpec(Map(), 1), Left(null), Some(connectedPeer))

      //register
      val registerSpecMessage = RegisterMessageSpecs(Seq(), peerSynchronizerProbe.ref)
      requestTracker ! registerSpecMessage
      networkControllerProbe.expectMsg(RegisterMessageSpecs(Seq(), requestTracker))

      //response without request
      requestTracker ! response

      //spam penalty
      networkControllerProbe.expectMsg(PenalizePeer(connectedPeer.connectionId.remoteAddress, SpamPenalty))
      peerSynchronizerProbe.expectNoMessage()
    }
  }

  private def prepareTestData(): (ActorRef, TestProbe, TestProbe, NetworkSettings) = {
    val networkControllerProbe = TestProbe()
    val peerSynchronizerProbe = TestProbe()
    val networkSettings = SparkzSettings.read(None).network.copy(
      penalizeNonDelivery = true
    )
    val requestTracker = RequestTrackerRef(networkControllerProbe.ref, requestMessageCode, responseMessageCode, deliveryTimeout, networkSettings.penalizeNonDelivery)

    (requestTracker, networkControllerProbe, peerSynchronizerProbe, networkSettings)
  }

}
