package scorex.network

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.TestProbe
import org.scalatest.Assertion
import org.scalatest.matchers.should.Matchers
import org.scalatest.propspec.AnyPropSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import scorex.ObjectGenerators
import scorex.core.consensus.ContainsModifiers
import scorex.core.network.ModifiersStatus._
import scorex.core.network.NetworkController.ReceivableMessages.RegisterMessageSpecs
import scorex.core.network.message.Message.MessageCode
import scorex.core.network.message.{GetPeersSpec, PeersSpec}
import scorex.core.network.{RequestTracker, RequestTrackerRef}
import scorex.core.serialization.ScorexSerializer
import scorex.core.settings.NetworkSettings
import scorex.core.{ModifierTypeId, PersistentNodeViewModifier}
import scorex.crypto.hash.Blake2b256
import scorex.util.{ModifierId, bytesToId}

import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

@SuppressWarnings(Array(
  "org.wartremover.warts.Null",
  "org.wartremover.warts.TraversableOps",
  "org.wartremover.warts.OptionPartial"))
class RequestTrackerSpecification extends NetworkTests {

  implicit val actorSystem: ActorSystem = ActorSystem()
  implicit val executionContext: ExecutionContext = actorSystem.dispatchers.lookup("scorex.executionContext")

  private val requestMessageCode: MessageCode = GetPeersSpec.messageCode
  private val responseMessageCode: MessageCode = PeersSpec.messageCode
  private val (networkControllerProbe, peerSynchronizerProbe, networkSettings) = prepareTestData()

  private def withRequestTracker(test: ActorRef => Unit): Unit =
    test(RequestTrackerRef(networkControllerProbe.ref, requestMessageCode, responseMessageCode, networkSettings.requestTrackerDeliveryTimeout, networkSettings.penalizeNonDelivery))

  "Request Tracker" should "forward registerMessageSpec to network controller, replacing ref to itself" in {
    withRequestTracker { requestTracker =>
      val registerSpecMessage = RegisterMessageSpecs(Seq(), peerSynchronizerProbe.ref)
      requestTracker ! registerSpecMessage
      networkControllerProbe.expectMsg(RegisterMessageSpecs(Seq(), requestTracker))
    }
  }

  "Request Tracker" should "use actor specified in registerMessageSpec for forwarding" in {
    withRequestTracker { requestTracker =>
      val registerSpecMessage = RegisterMessageSpecs(Seq(), peerSynchronizerProbe.ref)
      requestTracker ! registerSpecMessage

    }
  }

  private def prepareTestData(): (TestProbe, TestProbe, NetworkSettings) = {
    val networkControllerProbe = TestProbe()
    val peerSynchronizerProbe = TestProbe()
    val networkSettings = settings.network.copy(
      requestTrackerDeliveryTimeout = 5.seconds,
      penalizeNonDelivery = true
    )

    (networkControllerProbe, peerSynchronizerProbe, networkSettings)
  }

}
