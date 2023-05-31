package sparkz.core.network

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.TestProbe
import org.mockito.Mockito.when
import org.mockito.MockitoSugar.mock
import org.scalatest.matchers.should.Matchers
import org.scalatest.propspec.AnyPropSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import sparkz.ObjectGenerators
import sparkz.core.consensus.ContainsModifiers
import sparkz.core.network.ModifiersStatus._
import sparkz.core.network.NodeViewSynchronizer.ReceivableMessages.TransactionRebroadcast
import sparkz.core.serialization.SparkzSerializer
import sparkz.core.settings.NetworkSettings
import sparkz.core.{ModifierTypeId, PersistentNodeViewModifier}
import sparkz.crypto.hash.Blake2b256
import sparkz.util.{ModifierId, bytesToId}

import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

@SuppressWarnings(Array(
  "org.wartremover.warts.Null",
  "org.wartremover.warts.TraversableOps",
  "org.wartremover.warts.OptionPartial"))
class DeliveryTrackerSpecification extends AnyPropSpec
  with ScalaCheckPropertyChecks
  with Matchers
  with ObjectGenerators {

  val cp: ConnectedPeer = connectedPeerGen(null).sample.get
  val mtid: ModifierTypeId = ModifierTypeId @@ (0: Byte)

  class FakeModifier(val id: ModifierId) extends PersistentNodeViewModifier {
    override def parentId: ModifierId = ???

    override val modifierTypeId: ModifierTypeId = mtid
    override type M = this.type

    override def serializer: SparkzSerializer[FakeModifier.this.type] = ???
  }

  class FakeHistory extends ContainsModifiers[FakeModifier] {
    private val mods: TrieMap[ModifierId, FakeModifier] = TrieMap[ModifierId, FakeModifier]()

    override def modifierById(modifierId: ModifierId): Option[FakeModifier] = mods.get(modifierId)

    def put(mod: FakeModifier): Unit = mods.put(mod.id, mod)
  }

  property("expect from peer") {
    val tracker = genDeliveryTracker

    val modids: Seq[ModifierId] = Seq(Blake2b256("1"), Blake2b256("2"), Blake2b256("3")).map(bytesToId)

    tracker.setRequested(modids, mtid, cp)
    modids.foreach(id => tracker.status(id) shouldBe Requested)

    // reexpect
    tracker.setRequested(modids, mtid, cp)
    modids.foreach(id => tracker.status(id) shouldBe Requested)
  }

  property("locally generated modifier") {
    val tracker = genDeliveryTracker
    val history = new FakeHistory

    val modids: Seq[ModifierId] = Seq(Blake2b256("1"), Blake2b256("2"), Blake2b256("3")).map(bytesToId)

    modids.foreach(id => history.put(new FakeModifier(id)))
    modids.foreach(id => tracker.setHeld(id))
    modids.foreach(id => tracker.status(id, history) shouldBe Held)
  }

  property("persistent modifier workflow") {
    val tracker = genDeliveryTracker
    val history = new FakeHistory

    val modids: Seq[ModifierId] = Seq(Blake2b256("1"), Blake2b256("2"), Blake2b256("3")).map(bytesToId)

    modids.foreach(id => tracker.status(id) shouldBe Unknown)
    modids.foreach(id => tracker.status(id) should not be Requested)

    tracker.setRequested(modids, mtid, cp)
    modids.foreach(id => tracker.status(id) shouldBe Requested)
    modids.foreach(id => tracker.status(id) shouldBe Requested)

    // received correct modifier
    val received = modids.head
    tracker.status(received) shouldBe Requested
    tracker.setReceived(received, cp)
    tracker.status(received) shouldBe Received
    tracker.status(received) should not be Requested

    history.put(new FakeModifier(received))
    tracker.setHeld(received)
    tracker.status(received, history) shouldBe Held

    // received incorrect modifier
    val invalid = modids.last
    tracker.status(invalid) shouldBe Requested
    tracker.setReceived(invalid, cp)
    tracker.status(invalid) shouldBe Received
    tracker.setInvalid(invalid)
    tracker.status(invalid, history) shouldBe Invalid
    tracker.status(invalid) should not be Requested

    // modifier was not delivered on time
    val nonDelivered = modids(1)
    tracker.status(nonDelivered) shouldBe Requested
    tracker.setUnknown(nonDelivered)
    tracker.status(nonDelivered, history) shouldBe Unknown
    tracker.status(nonDelivered) should not be Requested
  }

  property("stop expecting after maximum number of retries") {
    val tracker = genDeliveryTracker

    val modids: Seq[ModifierId] = Seq(Blake2b256("1"), Blake2b256("2"), Blake2b256("3")).map(bytesToId)

    tracker.setRequested(modids, mtid, cp)
    tracker.status(modids.head) shouldBe Requested

    tracker.setReceived(modids.head, cp)

    tracker.status(modids.head) should not be Requested


    tracker.onStillWaiting(cp, mtid, modids(1))
    tracker.status(modids(1)) shouldBe Requested

    tracker.onStillWaiting(cp, mtid, modids(1))
    tracker.status(modids(1)) should not be Requested
  }

  property("return count requests per peer and calculate remaining limit") {
    val tracker = genDeliveryTracker
    val otherPeer = connectedPeerGen(null).sample.get

    val modids: Seq[ModifierId] = Seq(Blake2b256("1"), Blake2b256("2"), Blake2b256("3")).map(bytesToId)

    tracker.setRequested(modids, mtid, cp)
    tracker.getPeerLimit(cp) shouldBe 0
    tracker.getPeerLimit(otherPeer) shouldBe 3

    tracker.setReceived(modids.head, cp)
    tracker.getPeerLimit(cp) shouldBe 1
    tracker.getPeerLimit(otherPeer) shouldBe 3

    tracker.onStillWaiting(cp, mtid, modids(1))
    tracker.getPeerLimit(cp) shouldBe 1
    tracker.getPeerLimit(otherPeer) shouldBe 3

    tracker.onStillWaiting(cp, mtid, modids(1))
    tracker.getPeerLimit(cp) shouldBe 2
    tracker.getPeerLimit(otherPeer) shouldBe 3
  }

  property("slow mode should be enabled when average processing time exceeds threshold") {
    val system = ActorSystem()
    val probe = TestProbe("p")(system)
    implicit val nvsStub: ActorRef = probe.testActor
    val dt = FiniteDuration(3, MINUTES)
    val networkSettings = mock[NetworkSettings]
    when(networkSettings.deliveryTimeout).thenReturn(dt)
    when(networkSettings.maxDeliveryChecks).thenReturn(2)
    when(networkSettings.maxRequestedPerPeer).thenReturn(3)
    when(networkSettings.slowModeFeatureFlag).thenReturn(true)
    when(networkSettings.slowModeThresholdMs).thenReturn(100)
    when(networkSettings.slowModeMeasurementImpact).thenReturn(0.1)
    val deliveryTracker = new DeliveryTracker(
      system,
      networkSettings,
      nvsRef = nvsStub)
    deliveryTracker.slowMode shouldBe false
    val modifiers = (1 to 10).map(int => bytesToId(Blake2b256(int+ "")))

    deliveryTracker.setRequested(modifiers, mtid, cp)
    modifiers.foreach(deliveryTracker.setReceived(_, cp))
    deliveryTracker.slowMode shouldBe false
    Thread.sleep(200)
    deliveryTracker.slowMode shouldBe false
    modifiers.foreach(deliveryTracker.setHeld)
    deliveryTracker.slowMode shouldBe true
  }

  property("slow mode should depend on the feature flag") {
    val system = ActorSystem()
    val probe = TestProbe("p")(system)
    implicit val nvsStub: ActorRef = probe.testActor
    val dt = FiniteDuration(3, MINUTES)
    val networkSettings = mock[NetworkSettings]
    when(networkSettings.deliveryTimeout).thenReturn(dt)
    when(networkSettings.maxDeliveryChecks).thenReturn(2)
    when(networkSettings.maxRequestedPerPeer).thenReturn(3)
    when(networkSettings.slowModeFeatureFlag).thenReturn(false)
    when(networkSettings.slowModeThresholdMs).thenReturn(100)
    val deliveryTracker = new DeliveryTracker(
      system,
      networkSettings,
      nvsRef = nvsStub)
    deliveryTracker.slowMode shouldBe false
    val modifiers = (1 to 10).map(int => bytesToId(Blake2b256(int+ "")))

    deliveryTracker.setRequested(modifiers, mtid, cp)
    modifiers.foreach(deliveryTracker.setReceived(_, cp))
    deliveryTracker.slowMode shouldBe false
    Thread.sleep(200)
    deliveryTracker.slowMode shouldBe false
    modifiers.foreach(deliveryTracker.setHeld)
    deliveryTracker.slowMode shouldBe false
  }

  property(" should schedule an event to rebroadcast modifiers") {
    val system = ActorSystem()
    val probe = TestProbe("p")(system)
    implicit val nvsStub: ActorRef = probe.testActor
    val dt = FiniteDuration(3, MINUTES)
    val networkSettings = mock[NetworkSettings]
    when(networkSettings.deliveryTimeout).thenReturn(dt)
    when(networkSettings.maxDeliveryChecks).thenReturn(2)
    when(networkSettings.maxRequestedPerPeer).thenReturn(3)
    when(networkSettings.slowModeFeatureFlag).thenReturn(true)
    when(networkSettings.slowModeThresholdMs).thenReturn(100)
    when(networkSettings.slowModeMeasurementImpact).thenReturn(0.1)
    when(networkSettings.rebroadcastEnabled).thenReturn(true)
    when(networkSettings.rebroadcastDelay).thenReturn(Duration.fromNanos(1000))
    when(networkSettings.rebroadcastBatchSize).thenReturn(5)
    when(networkSettings.rebroadcastQueueSize).thenReturn(1000)

    val deliveryTracker = new DeliveryTracker(
      system,
      networkSettings,
      nvsRef = nvsStub)
    deliveryTracker.slowMode shouldBe false
    val modifiersBatch1 = (1 to 10).map(int => bytesToId(Blake2b256(int+ "")))
    val throttledModifiers = (1 to 10).map(int => bytesToId(Blake2b256(int+ "")))

    // engage slow mode
    deliveryTracker.setRequested(modifiersBatch1, mtid, cp)
    modifiersBatch1.foreach(deliveryTracker.setReceived(_, cp))
    Thread.sleep(200)
    modifiersBatch1.foreach(deliveryTracker.setHeld)
    deliveryTracker.slowMode shouldBe true

    // send throttled transactions
    throttledModifiers.foreach(deliveryTracker.putInRebroadcastQueue)

    // end slow mode
    deliveryTracker.setRequested(modifiersBatch1, mtid, cp)
    modifiersBatch1.foreach(deliveryTracker.setReceived(_, cp))
    modifiersBatch1.foreach(deliveryTracker.setHeld)
    deliveryTracker.slowMode shouldBe false

    // assert rebroadcast logic
    probe.expectMsg(TransactionRebroadcast)
    deliveryTracker.getRebroadcastModifiers should (
      have size 5 and
      contain theSameElementsAs throttledModifiers.take(5)
    )
    deliveryTracker.scheduleRebroadcastIfNeeded()
    probe.expectMsg(TransactionRebroadcast)
    deliveryTracker.getRebroadcastModifiers should (
      have size 5 and
      contain theSameElementsAs throttledModifiers.drop(5)
    )
    deliveryTracker.scheduleRebroadcastIfNeeded()
    probe.expectNoMessage(200.millis)
    deliveryTracker.getRebroadcastModifiers should have size 0
  }

  property("should not rebroadcast modifiers if disabled") {
    val system = ActorSystem()
    val probe = TestProbe("p")(system)
    implicit val nvsStub: ActorRef = probe.testActor
    val dt = FiniteDuration(3, MINUTES)
    val networkSettings = mock[NetworkSettings]
    when(networkSettings.deliveryTimeout).thenReturn(dt)
    when(networkSettings.maxDeliveryChecks).thenReturn(2)
    when(networkSettings.maxRequestedPerPeer).thenReturn(3)
    when(networkSettings.slowModeFeatureFlag).thenReturn(true)
    when(networkSettings.slowModeThresholdMs).thenReturn(100)
    when(networkSettings.slowModeMeasurementImpact).thenReturn(0.1)
    when(networkSettings.rebroadcastEnabled).thenReturn(false)
    when(networkSettings.rebroadcastDelay).thenReturn(Duration.fromNanos(1000))
    when(networkSettings.rebroadcastBatchSize).thenReturn(5)
    when(networkSettings.rebroadcastQueueSize).thenReturn(1000)

    val deliveryTracker = new DeliveryTracker(
      system,
      networkSettings,
      nvsRef = nvsStub)
    deliveryTracker.slowMode shouldBe false
    val modifiersBatch1 = (1 to 10).map(int => bytesToId(Blake2b256(int+ "")))
    val throttledModifiers = (1 to 10).map(int => bytesToId(Blake2b256(int+ "")))

    // engage slow mode
    deliveryTracker.setRequested(modifiersBatch1, mtid, cp)
    modifiersBatch1.foreach(deliveryTracker.setReceived(_, cp))
    Thread.sleep(200)
    modifiersBatch1.foreach(deliveryTracker.setHeld)
    deliveryTracker.slowMode shouldBe true

    // send throttled transactions
    throttledModifiers.foreach(deliveryTracker.putInRebroadcastQueue)

    // end slow mode
    deliveryTracker.setRequested(modifiersBatch1, mtid, cp)
    modifiersBatch1.foreach(deliveryTracker.setReceived(_, cp))
    modifiersBatch1.foreach(deliveryTracker.setHeld)
    deliveryTracker.slowMode shouldBe false

    // assert rebroadcast logic
    probe.expectNoMessage(200.millis)
    deliveryTracker.getRebroadcastModifiers should have size 0
    deliveryTracker.scheduleRebroadcastIfNeeded()
    probe.expectNoMessage(200.millis)
  }


  private def genDeliveryTracker = {
    val system = ActorSystem()
    val probe = TestProbe("p")(system)
    implicit val nvsStub: ActorRef = probe.testActor
    val dt = FiniteDuration(3, MINUTES)
    val networkSettings = mock[NetworkSettings]
    when(networkSettings.deliveryTimeout).thenReturn(dt)
    when(networkSettings.maxDeliveryChecks).thenReturn(2)
    when(networkSettings.maxRequestedPerPeer).thenReturn(3)
    when(networkSettings.slowModeFeatureFlag).thenReturn(true)
    when(networkSettings.slowModeThresholdMs).thenReturn(100)
    new DeliveryTracker(
      system,
      networkSettings,
      nvsRef = nvsStub)
  }

}
