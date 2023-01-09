package sparkz.core.network.peer

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.TestProbe
import org.scalatest.BeforeAndAfter
import sparkz.core.app.{SparkzContext, Version}
import sparkz.core.network.peer.PeerDatabase.{PeerConfidence, PeerDatabaseValue}
import sparkz.core.network.peer.PeerManager.ReceivableMessages._
import sparkz.core.network.{NetworkTests, PeerSpec}

import java.net.{InetAddress, InetSocketAddress}

class PeerManagerSpec extends NetworkTests with BeforeAndAfter {

  import sparkz.core.network.peer.PeerManager.ReceivableMessages.{AddOrUpdatePeer, GetAllPeers}

  type Data = Map[InetSocketAddress, PeerInfo]
  private val DefaultPort = 27017

  it should "ignore adding self as a peer" in {
    implicit val system: ActorSystem = ActorSystem()
    val p = TestProbe("p")(system)
    implicit val defaultSender: ActorRef = p.testActor


    val selfAddress = settings.network.bindAddress
    val sparkzContext = SparkzContext(Seq.empty, Seq.empty, mockTimeProvider, Some(selfAddress))
    val peerDatabase = new InMemoryPeerDatabase(settings.network, sparkzContext)
    val peerManager = PeerManagerRef(settings, sparkzContext, peerDatabase)(system)
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

    val sparkzContext = SparkzContext(Seq.empty, Seq.empty, mockTimeProvider, None)
    val peerDatabase = new InMemoryPeerDatabase(settings.network, sparkzContext)
    val peerManager = PeerManagerRef(settings, sparkzContext, peerDatabase)(system)
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

    val sparkzContext = SparkzContext(Seq.empty, Seq.empty, mockTimeProvider, None)
    val peerDatabase = new InMemoryPeerDatabase(settingsWithKnownPeer.network, sparkzContext)
    val peerManager = PeerManagerRef(settingsWithKnownPeer, sparkzContext, peerDatabase)(system)

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

  it should "exclude peers from stored peers" in {
    // Arrange
    val sparkzContext = SparkzContext(Seq.empty, Seq.empty, mockTimeProvider, None)

    val peerAddressOne = new InetSocketAddress("127.0.0.1", DefaultPort)
    val peerAddressTwo = new InetSocketAddress("127.0.0.2", DefaultPort)
    val peerDatabaseValueOne = PeerDatabaseValue(peerAddressOne, getPeerInfo(peerAddressOne), PeerConfidence.Unknown)
    val peerInfo2 = getPeerInfo(peerAddressTwo)
    val peerDatabaseValueTwo = PeerDatabaseValue(peerAddressTwo, peerInfo2, PeerConfidence.Unknown)

    val allPeers = Map(
      peerAddressOne -> peerDatabaseValueOne,
      peerAddressTwo -> peerDatabaseValueTwo
    )
    val peersToExclude = Seq(Some(peerAddressOne))
    val peersForConnections = RandomPeerForConnectionExcluding(peersToExclude)

    // Act
    val resultOption = peersForConnections.choose(allPeers, Seq(), sparkzContext)

    // Assert
    val result = resultOption.getOrElse(fail("Test result should not be None"))
    result shouldBe peerInfo2
  }

  it should "exclude blacklisted peers" in {
    // Arrange
    val sparkzContext = SparkzContext(Seq.empty, Seq.empty, mockTimeProvider, None)

    val peerAddressOne = new InetSocketAddress("127.0.0.1", DefaultPort)
    val peerAddressTwo = new InetSocketAddress("127.0.0.2", DefaultPort)
    val peerDatabaseValue1 = PeerDatabaseValue(peerAddressOne, getPeerInfo(peerAddressOne), PeerConfidence.Unknown)
    val peerInfo2 = getPeerInfo(peerAddressTwo)
    val peerDatabaseValue2 = PeerDatabaseValue(peerAddressTwo, peerInfo2, PeerConfidence.Unknown)

    val allPeers = Map(
      peerAddressOne -> peerDatabaseValue1,
      peerAddressTwo -> peerDatabaseValue2
    )
    val peersForConnections = RandomPeerForConnectionExcluding(Seq())
    val blacklistedPeers = Seq(peerAddressOne.getAddress)

    // Act
    val resultOption = peersForConnections.choose(allPeers, blacklistedPeers, sparkzContext)

    // Assert
    val result = resultOption.getOrElse(fail("Test result should not be None"))
    result shouldBe peerInfo2
  }

  it should "exclude peers from known peers" in {
    // Arrange
    val sparkzContext = SparkzContext(Seq.empty, Seq.empty, mockTimeProvider, None)

    val peerAddressOne = new InetSocketAddress("127.0.0.1", DefaultPort)
    val peerAddressTwo = new InetSocketAddress("127.0.0.2", DefaultPort)
    val peerDatabaseValue1 = PeerDatabaseValue(peerAddressOne, getPeerInfo(peerAddressOne), PeerConfidence.Unknown)
    val peerInfo2 = getPeerInfo(peerAddressTwo)
    val peerDatabaseValue2 = PeerDatabaseValue(peerAddressTwo, peerInfo2, PeerConfidence.Unknown)

    val allPeers = Map(
      peerAddressOne -> peerDatabaseValue1,
      peerAddressTwo -> peerDatabaseValue2
    )
    val peersToExclude = Seq(Some(peerAddressOne))
    val peersForConnections = RandomPeerForConnectionExcluding(peersToExclude)

    // Act
    val resultOption = peersForConnections.choose(allPeers, Seq(), sparkzContext)

    // Assert
    val result = resultOption.getOrElse(fail("Test result should not be None"))
    result shouldBe peerInfo2
  }

  it should "prioritize knownPeers over shared peers" in {
    // Arrange
    val knownPeerAddress1 = new InetSocketAddress("127.0.0.1", DefaultPort)
    val knownPeerAddress2 = new InetSocketAddress("127.0.0.2", DefaultPort)
    val settingsWithKnownPeer = settings.copy(network = settings.network.copy(knownPeers = Seq(knownPeerAddress1, knownPeerAddress2)))

    implicit val system: ActorSystem = ActorSystem()
    val p = TestProbe("p")(system)
    implicit val defaultSender: ActorRef = p.testActor

    val sparkzContext = SparkzContext(Seq.empty, Seq.empty, mockTimeProvider, None)
    val peerDatabase = new InMemoryPeerDatabase(settingsWithKnownPeer.network, sparkzContext)
    val peerManager = PeerManagerRef(settingsWithKnownPeer, sparkzContext, peerDatabase)(system)

    val peerAddress = new InetSocketAddress("1.1.1.1", DefaultPort)
    val peerInfo = getPeerInfo(peerAddress)

    // Act
    peerManager ! AddOrUpdatePeer(peerInfo)

    peerManager ! RandomPeerForConnectionExcluding(Seq(Some(knownPeerAddress2)))

    // Assert
    val data = p.expectMsgClass(classOf[Option[PeerInfo]])
    data shouldNot be(empty)
    data.foreach(p => p.peerSpec.address.contains(knownPeerAddress1) shouldBe true)

    system.terminate()
  }

  it should "sending a AddPeersIfEmpty to PeerManager with an existing peer discard that peer" in {
    implicit val system: ActorSystem = ActorSystem()
    val p = TestProbe("p")(system)
    implicit val defaultSender: ActorRef = p.testActor

    val sparkzContext = SparkzContext(Seq.empty, Seq.empty, mockTimeProvider, None)
    val peerDatabase = new InMemoryPeerDatabase(settings.network, sparkzContext)
    val peerManager = PeerManagerRef(settings, sparkzContext, peerDatabase)(system)
    val peerAddress = new InetSocketAddress("1.1.1.1", DefaultPort)
    val peerInfo = getPeerInfo(peerAddress)

    peerManager ! AddOrUpdatePeer(peerInfo)

    val differentPeerSpec = PeerSpec("different peer spec", Version.last, "nodeName", Some(peerAddress), Seq())
    peerManager ! AddPeersIfEmpty(Seq(differentPeerSpec))

    peerManager ! GetAllPeers

    val data = p.expectMsgClass(classOf[Data])
    data.keySet should contain(peerAddress)

    system.terminate()
  }

  it should "sending an AddPeersIfEmpty to PeerManager with new peers should add them in the database" in {
    implicit val system: ActorSystem = ActorSystem()
    val p = TestProbe("p")(system)
    implicit val defaultSender: ActorRef = p.testActor

    val sparkzContext = SparkzContext(Seq.empty, Seq.empty, mockTimeProvider, None)
    val peerDatabase = new InMemoryPeerDatabase(settings.network, sparkzContext)
    val peerManager = PeerManagerRef(settings, sparkzContext, peerDatabase)(system)
    val peerAddress = new InetSocketAddress("1.1.1.1", DefaultPort)
    val peerInfo = getPeerInfo(peerAddress)

    peerManager ! AddPeersIfEmpty(Seq(peerInfo.peerSpec))

    peerManager ! GetAllPeers

    val data = p.expectMsgClass(classOf[Data])
    data.keySet should contain(peerAddress)

    system.terminate()
  }

  it should "disconnect from peers when penalty goes over the threshold" in {
    implicit val system: ActorSystem = ActorSystem()
    val p = TestProbe("p")(system)
    implicit val defaultSender: ActorRef = p.testActor

    val sparkzContext = SparkzContext(Seq.empty, Seq.empty, mockTimeProvider, None)
    val peerDatabase = new InMemoryPeerDatabase(settings.network, sparkzContext)
    val peerManager = PeerManagerRef(settings, sparkzContext, peerDatabase)(system)
    val peerAddress = new InetSocketAddress("1.1.1.1", DefaultPort)
    val peerInfo = getPeerInfo(peerAddress)

    peerManager ! AddPeersIfEmpty(Seq(peerInfo.peerSpec))
    peerManager ! Penalize(peerAddress, PenaltyType.MisbehaviorPenalty)

    // Make sure the peer is not blacklisted yet
    peerManager ! GetAllPeers

    val data = p.expectMsgClass(classOf[Data])
    data.keySet should contain(peerAddress)

    peerManager ! GetBlacklistedPeers
    val blacklistedResult = p.expectMsgClass(classOf[Seq[InetAddress]])
    blacklistedResult shouldBe empty

    // Overflow the disconnect threshold
    peerManager ! Penalize(peerAddress, PenaltyType.PermanentPenalty)

    val disconnectMessage = p.expectMsgClass(classOf[DisconnectFromAddress])
    disconnectMessage.remote shouldBe peerAddress

    // Make sure the peer doesn't show up in the database
    peerManager ! GetAllPeers

    val dataAfterBan = p.expectMsgClass(classOf[Data])
    dataAfterBan shouldBe empty

    peerManager ! GetBlacklistedPeers
    val blacklistedResultAfterBan = p.expectMsgClass(classOf[Seq[InetAddress]])
    blacklistedResultAfterBan shouldBe Seq(peerAddress.getAddress)

    system.terminate()
  }

  it should "add the peer into the blacklist" in {
    implicit val system: ActorSystem = ActorSystem()
    val p = TestProbe("p")(system)
    implicit val defaultSender: ActorRef = p.testActor

    val sparkzContext = SparkzContext(Seq.empty, Seq.empty, mockTimeProvider, None)
    val peerDatabase = new InMemoryPeerDatabase(settings.network, sparkzContext)
    val peerManagerRef = PeerManagerRef(settings, sparkzContext, peerDatabase)(system)

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

    val sparkzContext = SparkzContext(Seq.empty, Seq.empty, mockTimeProvider, None)
    val peerDatabase = new InMemoryPeerDatabase(settings.network, sparkzContext)
    val peerManagerRef = PeerManagerRef(settings, sparkzContext, peerDatabase)(system)

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
