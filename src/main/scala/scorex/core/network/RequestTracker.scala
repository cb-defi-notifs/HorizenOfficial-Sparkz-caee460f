package scorex.core.network

import akka.actor._
import akka.util.Timeout
import scorex.core.network.NetworkController.ReceivableMessages.{GetConnectedPeers, PenalizePeer, RegisterMessageSpecs, SendToNetwork}
import scorex.core.network.message.Message
import scorex.core.network.message.Message.MessageCode
import scorex.core.network.peer.PenaltyType
import scorex.core.utils.ActorHelper
import scorex.util.ScorexLogging

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{FiniteDuration, SECONDS}
import scala.language.postfixOps

/**
  * Wraps NetworkController actor, providing verification of request-response communication with other peers
  */
class RequestTracker(
                      networkControllerRef: ActorRef,
                      trackedRequestCode: MessageCode,
                      trackedResponseCode: MessageCode,
                      deliveryTimeout: FiniteDuration,
                      penalizeNonDelivery: Boolean)(implicit ec: ExecutionContext)
  extends Actor with ScorexLogging with ActorHelper {

  private var handler: Option[ActorRef] = None

  private val requestTracker = mutable.Set.empty[(MessageCode, ConnectedPeer)]

  implicit val timeout: Timeout = Timeout(60, SECONDS)

  override def receive: Receive = {
    registerMessageSpec orElse
      sendTrackedRequest orElse
      receiveResponse orElse
      verifyDelivery orElse
      forward
  }

  private def registerMessageSpec: Receive = {
    case RegisterMessageSpecs(specs, handler) =>
      this.handler = Some(handler)
      networkControllerRef ! RegisterMessageSpecs(specs, self)
  }

  private def sendTrackedRequest: Receive = {
    case message@SendToNetwork(m@Message(spec, _, _), strategy) if spec.messageCode == trackedRequestCode =>
      askActor[Seq[ConnectedPeer]](networkControllerRef, GetConnectedPeers)
        .map { peers =>
          strategy.choose(peers.filter(_.peerInfo.exists(_.peerSpec.protocolVersion >= m.spec.protocolVersion)))
            .foreach { peer =>
              val requestKey = (trackedRequestCode, peer)

              requestTracker += requestKey

              networkControllerRef ! message.copy(sendingStrategy = SendToPeer(peer))

              context.system.scheduler.scheduleOnce(timeout.duration, self, VerifyDelivery(requestKey, peer))
            }
        }
  }

  private def receiveResponse: Receive = {
    case m@Message(spec, _, Some(remote)) if spec.messageCode == trackedResponseCode =>
      //verify we asked with requestTracker
      if (requestTracker.remove((trackedResponseCode, remote))) {
        handler.foreach(actor => actor ! m)
      } else {
        networkControllerRef ! PenalizePeer(remote.connectionId.remoteAddress, PenaltyType.SpamPenalty)
      }
  }

  private def verifyDelivery: Receive = {
    case VerifyDelivery(key, remote) =>
      if (requestTracker.remove(key) && penalizeNonDelivery) {
        networkControllerRef ! PenalizePeer(remote.connectionId.remoteAddress, PenaltyType.NonDeliveryPenalty)
      }
  }

  private def forward: Receive = {
    case any if sender() == networkControllerRef =>
      handler.foreach(actor => actor ! any)
    case any =>
      networkControllerRef ! any
  }

  case class VerifyDelivery(requestKey: (MessageCode, ConnectedPeer), remote: ConnectedPeer)
}

object RequestTrackerRef {
  def props(ncr: ActorRef, tRequestC: MessageCode, tResponseC: MessageCode, dt: FiniteDuration, pnd: Boolean)(implicit ec: ExecutionContext): Props = {
    Props(new RequestTracker(ncr, tRequestC, tResponseC, dt, pnd))
  }

  def apply(ncr: ActorRef, tRequestC: MessageCode, tResponseC: MessageCode, dt: FiniteDuration, pnd: Boolean)
           (implicit system: ActorSystem, ec: ExecutionContext): ActorRef = {
    system.actorOf(props(ncr, tRequestC, tResponseC, dt, pnd))
  }
}

