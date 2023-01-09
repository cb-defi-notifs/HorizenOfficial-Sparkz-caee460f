package sparkz.core.network.peer

import org.mockito.ArgumentMatchers.any
import org.mockito.MockitoSugar.{doReturn, spy}
import sparkz.core.network.NetworkTests
import sparkz.core.network.peer.BucketManager.Exception.PeerNotFoundException
import sparkz.core.network.peer.BucketManager.{NewPeerBucketValue, PeerBucketValue}
import sparkz.core.network.peer.PeerBucketStorage.{BucketConfig, PeerBucketStorageImpl}
import sparkz.core.network.peer.PeerDatabase.{PeerConfidence, PeerDatabaseValue}

import java.net.InetSocketAddress

class BucketManagerTest extends NetworkTests {
  private val bucketConf = BucketConfig(256, 64, 8)
  private val nKey = 1234
  private val newBucket = PeerBucketStorageImpl(bucketConf, nKey, mockTimeProvider)
  private val triedBucket = PeerBucketStorageImpl(bucketConf, nKey, mockTimeProvider)

  "BucketManager" should "be empty when created" in {
    // Arrange
    val bucketManager = new BucketManager(newBucket, triedBucket)

    // Assert
    bucketManager.isEmpty shouldBe true
    bucketManager.getTriedPeers shouldBe Map.empty
  }

  it should "persist a new peer added and being able to retrieve it" in {
    // Arrange
    val peerAddress = new InetSocketAddress("55.66.77.88", 1234)
    val peerInfo = getPeerInfo(peerAddress)
    val peerDatabaseValue = PeerDatabaseValue(peerAddress, peerInfo, PeerConfidence.Unknown)
    val peerBucketValue = NewPeerBucketValue(peerDatabaseValue)
    val bucketManager = new BucketManager(newBucket, triedBucket)

    // Act
    bucketManager.addNewPeer(peerDatabaseValue)

    // Assert
    bucketManager.isEmpty shouldBe false

    val retrievedPeer = bucketManager.getPeer(peerAddress)
    retrievedPeer match {
      case Some(storedPeer) => peerBucketValue shouldBe storedPeer
      case _ => fail("")
    }
  }

  it should "move the peer when established a connection to it" in {
    // Arrange
    val peerAddress = new InetSocketAddress("55.66.77.88", 1234)
    val peerInfo = getPeerInfo(peerAddress)
    val peerDatabaseValue = PeerDatabaseValue(peerAddress, peerInfo, PeerConfidence.Unknown)
    val bucketManager = new BucketManager(newBucket, triedBucket)
    bucketManager.addNewPeer(peerDatabaseValue)

    // Act
    bucketManager.makeTried(peerDatabaseValue)

    // Assert
    bucketManager.isEmpty shouldBe false

    val retrievedTriedPeer = bucketManager.getTriedPeers(peerAddress)
    retrievedTriedPeer shouldBe peerDatabaseValue
    val retrievedNewPeer = bucketManager.getNewPeers
    retrievedNewPeer.isEmpty shouldBe true
  }

  it should "re-add a peer's address into new" in {
    // Arrange
    val peerAddress = new InetSocketAddress("55.66.77.88", 1234)
    val peerInfo = getPeerInfo(peerAddress)
    val peerDatabaseValue = PeerDatabaseValue(peerAddress, peerInfo, PeerConfidence.Unknown)
    val bucketManager = new BucketManager(newBucket, triedBucket)
    bucketManager.addNewPeer(peerDatabaseValue)
    bucketManager.makeTried(peerDatabaseValue)

    // Act
    bucketManager.addNewPeer(peerDatabaseValue)

    // Assert
    bucketManager.isEmpty shouldBe false

    val triedPeers = bucketManager.getTriedPeers
    val newPeers = bucketManager.getNewPeers
    triedPeers.size shouldBe 1
    newPeers.size shouldBe 1
    triedPeers.contains(peerAddress) shouldBe true
    newPeers.contains(peerAddress) shouldBe true
  }

  it should "remove all occurrences of the same peer's address" in {
    // Arrange
    val peerAddress = new InetSocketAddress("55.66.77.88", 1234)
    val peerInfo = getPeerInfo(peerAddress)
    val peerDatabaseValue = PeerDatabaseValue(peerAddress, peerInfo, PeerConfidence.Unknown)
    val bucketManager = new BucketManager(newBucket, triedBucket)
    bucketManager.addNewPeer(peerDatabaseValue)
    bucketManager.makeTried(peerDatabaseValue)
    bucketManager.addNewPeer(peerDatabaseValue)

    // Act
    bucketManager.removePeer(peerAddress)

    // Assert
    bucketManager.isEmpty shouldBe true
  }

  it should "correctly move peers from tried back to new without deleting them" in {
    // Arrange
    val bucket = 1
    val bucketPosition = 1
    val bucketConfig = BucketConfig(10, 10, 10)
    val newBucketMock = spy(PeerBucketStorageImpl(bucketConfig, nKey, mockTimeProvider))
    val triedBucketMock = spy(PeerBucketStorageImpl(bucketConfig, nKey, mockTimeProvider))

    doReturn(bucket).when(triedBucketMock).getBucket(any())
    doReturn(bucketPosition).when(triedBucketMock).getBucketPosition(any(), any())

    val peerAddress1 = new InetSocketAddress("55.66.77.88", 1234)
    val peerInfo1 = getPeerInfo(peerAddress1)
    val peerDatabaseValue1 = PeerDatabaseValue(peerAddress1, peerInfo1, PeerConfidence.Unknown)

    val peerAddress2 = new InetSocketAddress("88.77.66.55", 1234)
    val peerInfo2 = getPeerInfo(peerAddress2)
    val peerDatabaseValue2 = PeerDatabaseValue(peerAddress2, peerInfo2, PeerConfidence.Unknown)

    val bucketManager = new BucketManager(newBucketMock, triedBucketMock)

    // Act
    bucketManager.addNewPeer(peerDatabaseValue1)
    bucketManager.addNewPeer(peerDatabaseValue2)
    bucketManager.makeTried(peerDatabaseValue1)
    bucketManager.makeTried(peerDatabaseValue2)

    // Assert
    bucketManager.isEmpty shouldBe false

    val newPeers = bucketManager.getNewPeers
    val triedPeers = bucketManager.getTriedPeers

    newPeers shouldBe Map(peerAddress1 -> peerDatabaseValue1)
    triedPeers shouldBe Map(peerAddress2 -> peerDatabaseValue2)
  }

  it should "return an empty map getRandomPeers with empty buckets" in {
    // Arrange
    val bucketManager = new BucketManager(newBucket, triedBucket)

    // Act
    val peersMap = bucketManager.getRandomPeers

    // Assert
    peersMap.isEmpty shouldBe true
  }

  it should "retrieve a random peer from tried bucket having new empty" in {
    // Arrange
    val peerAddress = new InetSocketAddress("55.66.77.88", 1234)
    val peerInfo = getPeerInfo(peerAddress)
    val peerDatabaseValue = PeerDatabaseValue(peerAddress, peerInfo, PeerConfidence.Unknown)
    val bucketManager = new BucketManager(newBucket, triedBucket)
    bucketManager.addNewPeer(peerDatabaseValue)

    // Act
    bucketManager.makeTried(peerDatabaseValue)
    val retrievedPeer = bucketManager.getRandomPeers

    // Assert
    retrievedPeer shouldBe Map(peerAddress -> peerDatabaseValue)

    bucketManager.getNewPeers shouldBe empty
    bucketManager.getTriedPeers shouldNot be(empty)
  }
}
