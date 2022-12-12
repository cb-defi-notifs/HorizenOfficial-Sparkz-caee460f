package sparkz.core.network.peer

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.TestProbe
import org.scalatest.BeforeAndAfter
import sparkz.core.app.SparkzContext
import sparkz.core.network.NetworkTests
import sparkz.core.network.peer.PeerManager.ReceivableMessages.{RandomPeerForConnectionExcluding, RemovePeer}

import java.net.InetSocketAddress

class PeerManagerSpec extends NetworkTests with BeforeAndAfter {

  import sparkz.core.network.peer.PeerManager.ReceivableMessages.{AddOrUpdatePeer, GetAllPeers}

  type Data = Map[InetSocketAddress, PeerInfo]
  private val DefaultPort = 27017

  it should "ignore adding self as a peer" in {
    implicit val system: ActorSystem = ActorSystem()
    val p = TestProbe("p")(system)
    implicit val defaultSender: ActorRef = p.testActor


    val selfAddress = settings.network.bindAddress
    val sparkzContext = SparkzContext(Seq.empty, Seq.empty, timeProvider, Some(selfAddress))
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

    val sparkzContext = SparkzContext(Seq.empty, Seq.empty, timeProvider, None)
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

    val sparkzContext = SparkzContext(Seq.empty, Seq.empty, timeProvider, None)
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
    val sparkzContext = SparkzContext(Seq.empty, Seq.empty, timeProvider, None)

    val peerAddressOne = new InetSocketAddress("127.0.0.1", DefaultPort)
    val peerAddressTwo = new InetSocketAddress("127.0.0.2", DefaultPort)
    val peerInfoOne = getPeerInfo(peerAddressOne)
    val peerInfoTwo = getPeerInfo(peerAddressTwo)

    val allPeers = Map(
      peerAddressOne -> peerInfoOne,
      peerAddressTwo -> peerInfoTwo
    )
    val peersToExclude = Seq(Some(peerAddressOne))
    val peersForConnections = RandomPeerForConnectionExcluding(peersToExclude)

    // Act
    val resultOption = peersForConnections.choose(allPeers, Seq(), sparkzContext)

    // Assert
    val result = resultOption.getOrElse(fail("Test result should not be None"))
    result shouldBe peerInfoTwo
  }

  it should "exclude blacklisted peers" in {
    // Arrange
    val sparkzContext = SparkzContext(Seq.empty, Seq.empty, timeProvider, None)

    val peerAddressOne = new InetSocketAddress("127.0.0.1", DefaultPort)
    val peerAddressTwo = new InetSocketAddress("127.0.0.2", DefaultPort)
    val peerInfoOne = getPeerInfo(peerAddressOne)
    val peerInfoTwo = getPeerInfo(peerAddressTwo)

    val allPeers = Map(
      peerAddressOne -> peerInfoOne,
      peerAddressTwo -> peerInfoTwo
    )
    val peersForConnections = RandomPeerForConnectionExcluding(Seq())
    val blacklistedPeers = Seq(peerAddressOne.getAddress)

    // Act
    val resultOption = peersForConnections.choose(allPeers, blacklistedPeers, sparkzContext)

    // Assert
    val result = resultOption.getOrElse(fail("Test result should not be None"))
    result shouldBe peerInfoTwo
  }

  it should "exclude peers from known peers" in {
    // Arrange
    val sparkzContext = SparkzContext(Seq.empty, Seq.empty, timeProvider, None)

    val peerAddressOne = new InetSocketAddress("127.0.0.1", DefaultPort)
    val peerAddressTwo = new InetSocketAddress("127.0.0.2", DefaultPort)
    val peerInfoOne = getPeerInfo(peerAddressOne)
    val peerInfoTwo = getPeerInfo(peerAddressTwo)

    val allPeers = Map(
      peerAddressOne -> peerInfoOne,
      peerAddressTwo -> peerInfoTwo
    )
    val peersToExclude = Seq(Some(peerAddressOne))
    val peersForConnections = RandomPeerForConnectionExcluding(peersToExclude)

    // Act
    val resultOption = peersForConnections.choose(allPeers, Seq(), sparkzContext)

    // Assert
    val result = resultOption.getOrElse(fail("Test result should not be None"))
    result shouldBe peerInfoTwo
  }
}
