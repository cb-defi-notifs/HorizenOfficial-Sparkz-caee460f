package sparkz.core.network.peer

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.TestProbe
import sparkz.core.app.SparkzContext
import sparkz.core.network.peer.PeerManager.ReceivableMessages.{AddToBlacklist, GetBlacklistedPeers, RemoveFromBlacklist, RemovePeer}
import sparkz.core.network.NetworkTests

import java.net.{InetAddress, InetSocketAddress}

class PeerManagerSpec extends NetworkTests {

  import sparkz.core.network.peer.PeerManager.ReceivableMessages.{AddOrUpdatePeer, GetAllPeers}

  type Data = Map[InetSocketAddress, PeerInfo]
  private val DefaultPort = 27017

  it should "ignore adding self as a peer" in {
    implicit val system: ActorSystem = ActorSystem()
    val p = TestProbe("p")(system)
    implicit val defaultSender: ActorRef = p.testActor


    val selfAddress = settings.network.bindAddress
    val sparkzContext = SparkzContext(Seq.empty, Seq.empty, timeProvider, Some(selfAddress))
    val peerManager = PeerManagerRef(settings, sparkzContext)(system)
    val peerInfo = getPeerInfo(selfAddress)

    peerManager ! AddOrUpdatePeer(peerInfo)
    peerManager ! GetAllPeers
    val data = p.expectMsgClass(classOf[Data])

    data.keySet should not contain selfAddress
    system.terminate()
  }

  it should "added peer be returned in GetAllPeers" in {
    implicit val system: ActorSystem = ActorSystem()
    val p = TestProbe("p")(system)
    implicit val defaultSender: ActorRef = p.testActor

    val sparkzContext = SparkzContext(Seq.empty, Seq.empty, timeProvider, None)
    val peerManager = PeerManagerRef(settings, sparkzContext)(system)
    val peerAddress = new InetSocketAddress("1.1.1.1", DefaultPort)
    val peerInfo = getPeerInfo(peerAddress)

    peerManager ! AddOrUpdatePeer(peerInfo)
    peerManager ! GetAllPeers

    val data = p.expectMsgClass(classOf[Data])
    data.keySet should contain(peerAddress)
    system.terminate()
  }

  it should "not remove a known peer, but remove a normal peer" in {
    val knownPeerAddress = new InetSocketAddress("127.0.0.1", DefaultPort)
    val settingsWithKnownPeer = settings.copy(network = settings.network.copy(knownPeers = Seq(knownPeerAddress)))

    implicit val system: ActorSystem = ActorSystem()
    val p = TestProbe("p")(system)
    implicit val defaultSender: ActorRef = p.testActor

    val sparkzContext = SparkzContext(Seq.empty, Seq.empty, timeProvider, None)
    val peerManager = PeerManagerRef(settingsWithKnownPeer, sparkzContext)(system)

    val peerAddress = new InetSocketAddress("1.1.1.1", DefaultPort)
    val peerInfo = getPeerInfo(peerAddress)

    peerManager ! AddOrUpdatePeer(peerInfo)

    peerManager ! RemovePeer(peerAddress)
    peerManager ! RemovePeer(knownPeerAddress)
    peerManager ! GetAllPeers

    val data = p.expectMsgClass(classOf[Data])
    data.keySet should contain(knownPeerAddress)
    data.keySet should not contain peerAddress
    system.terminate()
  }

  it should "add the peer into the blacklist" in {
    implicit val system: ActorSystem = ActorSystem()
    val p = TestProbe("p")(system)
    implicit val defaultSender: ActorRef = p.testActor

    val sparkzContext = SparkzContext(Seq.empty, Seq.empty, timeProvider, None)
    val peerManagerRef = PeerManagerRef(settings, sparkzContext)(system)

    val peerAddress = new InetSocketAddress("1.1.1.1", DefaultPort)
    val peerInfo = getPeerInfo(peerAddress)

    peerManagerRef ! AddOrUpdatePeer(peerInfo)

    // Check added peers
    peerManagerRef ! GetAllPeers

    val allPeersMsg = p.expectMsgClass(classOf[Map[InetSocketAddress, PeerInfo]])
    allPeersMsg.size shouldBe 1
    allPeersMsg.contains(peerAddress) shouldBe true

    // Check blacklisted peers
    peerManagerRef ! GetBlacklistedPeers

    val blacklistMsg = p.expectMsgClass(classOf[Seq[InetAddress]])
    blacklistMsg.size shouldBe 0

    // Add peer to blacklist
    peerManagerRef ! AddToBlacklist(peerAddress)

    // Check again all peers
    peerManagerRef ! GetAllPeers

    val allPeersAfterBlacklistMsg = p.expectMsgClass(classOf[Map[InetSocketAddress, PeerInfo]])
    allPeersAfterBlacklistMsg.size shouldBe 0
    allPeersAfterBlacklistMsg.contains(peerAddress) shouldBe false

    // Check again blacklisted peers
    peerManagerRef ! GetBlacklistedPeers

    val blacklistedPeersAfterBlacklistMsg = p.expectMsgClass(classOf[Seq[InetAddress]])
    blacklistedPeersAfterBlacklistMsg.size shouldBe 1
    blacklistedPeersAfterBlacklistMsg.headOption.foreach(address => address shouldBe peerAddress.getAddress)

    system.terminate()
  }

  it should "remove the peer into the blacklist" in {
    implicit val system: ActorSystem = ActorSystem()
    val p = TestProbe("p")(system)
    implicit val defaultSender: ActorRef = p.testActor

    val sparkzContext = SparkzContext(Seq.empty, Seq.empty, timeProvider, None)
    val peerManagerRef = PeerManagerRef(settings, sparkzContext)(system)

    val peerAddress = new InetSocketAddress("1.1.1.1", DefaultPort)
    val peerInfo = getPeerInfo(peerAddress)

    peerManagerRef ! AddOrUpdatePeer(peerInfo)
    peerManagerRef ! AddToBlacklist(peerAddress)
    peerManagerRef ! RemoveFromBlacklist(peerAddress)

    // Check all peers
    peerManagerRef ! GetAllPeers
    val allPeersAfterBlacklistMsg = p.expectMsgClass(classOf[Map[InetSocketAddress, PeerInfo]])
    allPeersAfterBlacklistMsg.size shouldBe 0

    // Check blacklisted peers
    peerManagerRef ! GetBlacklistedPeers
    val blacklistedPeersAfterBlacklistMsg = p.expectMsgClass(classOf[Seq[InetAddress]])
    blacklistedPeersAfterBlacklistMsg.size shouldBe 0

    system.terminate()
  }
}
