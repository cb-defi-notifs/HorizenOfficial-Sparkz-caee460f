package sparkz.core.network.peer

import akka.actor.ActorSystem
import akka.testkit.TestProbe
import sparkz.core.network.peer.BucketManager.Exception.PeerNotFoundException
import sparkz.core.network.{ConnectedPeer, ConnectionId, Incoming, NetworkTests}
import sparkz.core.network.peer.BucketManager.{BucketManagerConfig, PeerBucketValue}
import sparkz.core.network.peer.PeerBucketStorage.BucketConfig

import java.net.InetSocketAddress

class BucketManagerTest extends NetworkTests {
  private val bucketConf = BucketConfig(256, 64, 8)
  private val nKey = 1234
  private val bucketManagerConfig = BucketManagerConfig(bucketConf, bucketConf, nKey)

  "BucketManager" should "be empty when created" in {
    // Arrange
    val bucketManager = new BucketManager(bucketManagerConfig, timeProvider)

    // Assert
    bucketManager.isEmpty shouldBe true
    bucketManager.getTriedPeers shouldBe Map.empty
  }

  it should "persist a new peer added and being able to retrieve it" in {
    // Arrange
    implicit val system: ActorSystem = ActorSystem()

    val peerAddress = new InetSocketAddress("55.66.77.88", 1234)
    val peerInfo = getPeerInfo(peerAddress)
    val source = ConnectedPeer(ConnectionId(new InetSocketAddress(10), new InetSocketAddress(11), Incoming), TestProbe().ref, 0L, None)
    val peerBucketValue = PeerBucketValue(peerInfo, source, isNew = true)
    val bucketManager = new BucketManager(bucketManagerConfig, timeProvider)

    // Act
    bucketManager.addNewPeer(peerBucketValue)

    // Assert
    bucketManager.isEmpty shouldBe false

    val retrievedPeer = bucketManager.getPeer(peerAddress)
    retrievedPeer match {
      case Some(storedPeer) => peerBucketValue shouldBe storedPeer
      case _ => fail("")
    }

    system.terminate()
  }

  it should "move the peer when established a connection to it" in {
    // Arrange
    implicit val system: ActorSystem = ActorSystem()

    val peerAddress = new InetSocketAddress("55.66.77.88", 1234)
    val peerInfo = getPeerInfo(peerAddress)
    val source = ConnectedPeer(ConnectionId(new InetSocketAddress(10), new InetSocketAddress(11), Incoming), TestProbe().ref, 0L, None)
    val peerBucketValue = PeerBucketValue(peerInfo, source, isNew = true)
    val bucketManager = new BucketManager(bucketManagerConfig, timeProvider)
    bucketManager.addNewPeer(peerBucketValue)

    // Act
    bucketManager.makeTried(peerAddress)

    // Assert
    bucketManager.isEmpty shouldBe false

    val retrievedPeer = bucketManager.getTriedPeers(peerAddress)
    retrievedPeer shouldBe peerInfo

    system.terminate()
  }

  it should "move the peer when established a connection to it 2" in {
    // Arrange
    implicit val system: ActorSystem = ActorSystem()

    val peerAddress = new InetSocketAddress("55.66.77.88", 1234)
    val peerInfo = getPeerInfo(peerAddress)
    val source = ConnectedPeer(ConnectionId(new InetSocketAddress(10), new InetSocketAddress(11), Incoming), TestProbe().ref, 0L, None)
    val peerBucketValue = PeerBucketValue(peerInfo, source, isNew = true)
    val bucketManager = new BucketManager(bucketManagerConfig, timeProvider)
    bucketManager.addNewPeer(peerBucketValue)
    bucketManager.makeTried(peerAddress)

    // Act
    bucketManager.addNewPeer(peerBucketValue)

    // Assert
    bucketManager.isEmpty shouldBe false

    val triedPeers = bucketManager.getTriedPeers
    val newPeers = bucketManager.getNewPeers
    triedPeers.size shouldBe 1
    newPeers.size shouldBe 1
    triedPeers.contains(peerAddress) shouldBe true
    newPeers.contains(peerAddress) shouldBe true

    system.terminate()
  }

  it should "move the peer when established a connection to it 3" in {
    // Arrange
    implicit val system: ActorSystem = ActorSystem()

    val peerAddress = new InetSocketAddress("55.66.77.88", 1234)
    val peerInfo = getPeerInfo(peerAddress)
    val source = ConnectedPeer(ConnectionId(new InetSocketAddress(10), new InetSocketAddress(11), Incoming), TestProbe().ref, 0L, None)
    val peerBucketValue = PeerBucketValue(peerInfo, source, isNew = true)
    val bucketManager = new BucketManager(bucketManagerConfig, timeProvider)
    bucketManager.addNewPeer(peerBucketValue)
    bucketManager.makeTried(peerAddress)
    bucketManager.addNewPeer(peerBucketValue)

    // Act
    bucketManager.removePeer(peerAddress)

    // Assert
    bucketManager.isEmpty shouldBe true

    system.terminate()
  }

  it should "raise a PeerNotFoundException if makeTried method is called but the peer does not exist in the new table" in {
    // Arrange
    implicit val system: ActorSystem = ActorSystem()

    val peerAddress = new InetSocketAddress("55.66.77.88", 1234)
    val bucketManager = new BucketManager(bucketManagerConfig, timeProvider)

    // Act
    val exception = intercept[PeerNotFoundException] {
      bucketManager.makeTried(peerAddress)
    }

    // Assert
    exception.getMessage shouldBe s"Cannot move peer $peerAddress to tried table because it doesnt exist in new"

    system.terminate()
  }
}
