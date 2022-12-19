package sparkz.core.network.peer

import akka.actor.ActorSystem
import sparkz.core.network.NetworkTests
import sparkz.core.network.peer.BucketManager.{NewPeerBucketValue, TriedPeerBucketValue}
import sparkz.core.network.peer.PeerBucketStorage.{BucketConfig, PeerBucketStorageImpl}
import sparkz.core.network.peer.PeerDatabase.{PeerConfidence, PeerDatabaseValue}

import java.net.InetSocketAddress

class PeerBucketStorageTest extends NetworkTests {
  private val buckets = 256
  private val bucketPositions = 64
  private val bucketSubgroups = 8
  private val nKey = 1234

  "Both new and tried buckets" should "should be empty when created" in {
    // Arrange
    val bucketConfig = BucketConfig(buckets, bucketPositions, bucketSubgroups)
    val tried = PeerBucketStorageImpl(bucketConfig, nKey, timeProvider)
    val newB = PeerBucketStorageImpl(bucketConfig, nKey, timeProvider)

    // Assert
    tried.isEmpty shouldBe true
    newB.isEmpty shouldBe true

    tried.getPeers shouldBe Map.empty
    newB.getPeers shouldBe Map.empty
  }

  they should "persist the peer when one is added" in {
    // Arrange
    implicit val system: ActorSystem = ActorSystem()

    val bucketConfig = BucketConfig(buckets, bucketPositions, bucketSubgroups)
    val tried = PeerBucketStorageImpl(bucketConfig, nKey, timeProvider)
    val newB = PeerBucketStorageImpl(bucketConfig, nKey, timeProvider)
    val peerAddress = new InetSocketAddress("55.66.77.88", 1234)
    val peerInfo = getPeerInfo(peerAddress)
    val newPeer = NewPeerBucketValue(PeerDatabaseValue(peerAddress, peerInfo, PeerConfidence.Unknown))
    val triedPeer = TriedPeerBucketValue(PeerDatabaseValue(peerAddress, peerInfo, PeerConfidence.Unknown))

    // Act
    newB.add(newPeer)
    tried.add(triedPeer)

    // Assert
    newB.isEmpty shouldBe false
    tried.isEmpty shouldBe false

    val newPeers = newB.getPeers
    val triedPeers = tried.getPeers

    newPeers.contains(newPeer.peerDatabaseValue.address) shouldBe true
    triedPeers.contains(triedPeer.peerDatabaseValue.address) shouldBe true

    system.terminate()
  }

  they should "delete existing peers" in {
    // Arrange
    implicit val system: ActorSystem = ActorSystem()

    val bucketConfig = BucketConfig(buckets, bucketPositions, bucketSubgroups)
    val tried = PeerBucketStorageImpl(bucketConfig, nKey, timeProvider)
    val newB = PeerBucketStorageImpl(bucketConfig, nKey, timeProvider)
    val peerAddress = new InetSocketAddress("55.66.77.88", 1234)
    val peerInfo = getPeerInfo(peerAddress)
    val newPeer = NewPeerBucketValue(PeerDatabaseValue(peerAddress, peerInfo, PeerConfidence.Unknown))
    val triedPeer = TriedPeerBucketValue(PeerDatabaseValue(peerAddress, peerInfo, PeerConfidence.Unknown))
    newB.add(newPeer)
    tried.add(triedPeer)

    // Act
    newB.remove(newPeer.peerDatabaseValue.address)
    tried.remove(triedPeer.peerDatabaseValue.address)

    // Assert
    newB.isEmpty shouldBe true
    tried.isEmpty shouldBe true

    newB.getPeers shouldBe Map.empty
    tried.getPeers shouldBe Map.empty

    system.terminate()
  }

  they should "do nothing when removing a non existing peer" in {
    // Arrange
    implicit val system: ActorSystem = ActorSystem()

    val bucketConfig = BucketConfig(buckets, bucketPositions, bucketSubgroups)
    val tried = PeerBucketStorageImpl(bucketConfig, nKey, timeProvider)
    val newB = PeerBucketStorageImpl(bucketConfig, nKey, timeProvider)
    val peerAddress = new InetSocketAddress("55.66.77.88", 1234)
    val peerInfo = getPeerInfo(peerAddress)
    val newPeer = NewPeerBucketValue(PeerDatabaseValue(peerAddress, peerInfo, PeerConfidence.Unknown))
    val triedPeer = TriedPeerBucketValue(PeerDatabaseValue(peerAddress, peerInfo, PeerConfidence.Unknown))

    // Act
    newB.remove(newPeer.peerDatabaseValue.address)
    tried.remove(triedPeer.peerDatabaseValue.address)

    // Assert
    newB.isEmpty shouldBe true
    tried.isEmpty shouldBe true

    newB.getPeers shouldBe Map.empty
    tried.getPeers shouldBe Map.empty

    system.terminate()
  }

  they should "return properly when contains method is called" in {
    // Arrange
    implicit val system: ActorSystem = ActorSystem()

    val bucketConfig = BucketConfig(buckets, bucketPositions, bucketSubgroups)
    val newB = PeerBucketStorageImpl(bucketConfig, nKey, timeProvider)
    val peerAddress1 = new InetSocketAddress("55.66.77.88", 1234)
    val peerInfo1 = getPeerInfo(peerAddress1)
    val peerAddress2 = new InetSocketAddress("88.77.66.55", 1234)
    val peerInfo2 = getPeerInfo(peerAddress2)
    val newPeer1 = NewPeerBucketValue(PeerDatabaseValue(peerAddress1, peerInfo1, PeerConfidence.Unknown))
    val newPeer2 = NewPeerBucketValue(PeerDatabaseValue(peerAddress2, peerInfo2, PeerConfidence.Unknown))
    val fakeAddress = new InetSocketAddress("55.88.77.66", 1234)

    // Act
    newB.add(newPeer1)
    newB.add(newPeer2)

    // Assert
    newB.isEmpty shouldBe false
    newB.contains(peerAddress1) shouldBe true
    newB.contains(peerAddress2) shouldBe true
    newB.contains(fakeAddress) shouldBe false

    system.terminate()
  }

  it should "update a peer" in {
    // Arrange
    implicit val system: ActorSystem = ActorSystem()

    val bucketConfig = BucketConfig(buckets, bucketPositions, bucketSubgroups)
    val newB = PeerBucketStorageImpl(bucketConfig, nKey, timeProvider)
    val peerAddress = new InetSocketAddress("55.66.77.88", 1234)
    val peerInfo = getPeerInfo(peerAddress)
    val newPeer = NewPeerBucketValue(PeerDatabaseValue(peerAddress, peerInfo, PeerConfidence.Unknown))

    // Act
    newB.add(newPeer)
    newB.updateExistingPeer(NewPeerBucketValue(PeerDatabaseValue(peerAddress, peerInfo, PeerConfidence.Medium)))

    // Assert
    val peers = newB.getPeers
    peers.contains(peerAddress) shouldBe true
    peers.size shouldBe 1

    val peer = peers(peerAddress)
    peer.confidence shouldBe PeerConfidence.Medium

    system.terminate()
  }
}
