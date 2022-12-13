package sparkz.core.network.peer

import akka.actor.ActorSystem
import org.mockito.ArgumentMatchers.any
import org.mockito.MockitoSugar.{doReturn, spy}
import sparkz.core.network.NetworkTests
import sparkz.core.network.peer.BucketManager.Exception.PeerNotFoundException
import sparkz.core.network.peer.BucketManager.PeerBucketValue
import sparkz.core.network.peer.PeerBucketStorage.{BucketConfig, PeerBucketStorageImpl}
import sparkz.core.network.peer.PeerDatabase.{PeerConfidence, PeerDatabaseValue}

import java.net.InetSocketAddress

class BucketManagerTest extends NetworkTests {
  private val bucketConf = BucketConfig(256, 64, 8)
  private val nKey = 1234
  private val newBucket = PeerBucketStorageImpl(bucketConf, nKey, timeProvider)
  private val triedBucket = PeerBucketStorageImpl(bucketConf, nKey, timeProvider)

  "BucketManager" should "be empty when created" in {
    // Arrange
    val bucketManager = new BucketManager(newBucket, triedBucket)

    // Assert
    bucketManager.isEmpty shouldBe true
    bucketManager.getTriedPeers shouldBe Map.empty
  }

  it should "persist a new peer added and being able to retrieve it" in {
    // Arrange
    implicit val system: ActorSystem = ActorSystem()

    val peerAddress = new InetSocketAddress("55.66.77.88", 1234)
    val peerInfo = getPeerInfo(peerAddress)
    val peerDatabaseValue = PeerDatabaseValue(peerAddress, peerInfo, PeerConfidence.Unknown)
    val peerBucketValue = PeerBucketValue(peerDatabaseValue, isNew = true)
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

    system.terminate()
  }

  it should "move the peer when established a connection to it" in {
    // Arrange
    implicit val system: ActorSystem = ActorSystem()

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

    system.terminate()
  }

  it should "re-add a peer's address into new" in {
    // Arrange
    implicit val system: ActorSystem = ActorSystem()

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

    system.terminate()
  }

  it should "remove all occurrences of the same peer's address" in {
    // Arrange
    implicit val system: ActorSystem = ActorSystem()

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

    system.terminate()
  }

  it should "raise a PeerNotFoundException if makeTried method is called but the peer does not exist in the new table" in {
    // Arrange
    implicit val system: ActorSystem = ActorSystem()

    val peerAddress = new InetSocketAddress("55.66.77.88", 1234)
    val bucketManager = new BucketManager(newBucket, triedBucket)
    val peerInfo = getPeerInfo(peerAddress)
    val peerDatabaseValue = PeerDatabaseValue(peerAddress, peerInfo, PeerConfidence.Unknown)

    // Act
    val exception = intercept[PeerNotFoundException] {
      bucketManager.makeTried(peerDatabaseValue)
    }

    // Assert
    exception.getMessage shouldBe s"Cannot move peer $peerAddress to tried table because it doesn't exist in new"

    system.terminate()
  }

  it should "correctly move peers from tried back to new without deleting them" in {
    // Arrange
    implicit val system: ActorSystem = ActorSystem()

    val bucket = 1
    val bucketPosition = 1
    val bucketConfig = BucketConfig(10, 10, 10)
    val newBucketMock = spy(PeerBucketStorageImpl(bucketConfig, nKey, timeProvider))
    val triedBucketMock = spy(PeerBucketStorageImpl(bucketConfig, nKey, timeProvider))

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

    system.terminate()
  }
}
