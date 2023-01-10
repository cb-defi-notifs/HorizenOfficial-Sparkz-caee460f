package sparkz.core.persistence

import akka.actor.ActorSystem
import org.mockito.MockitoSugar.mock
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import sparkz.core.network.NetworkTests
import sparkz.core.network.peer.BucketManager.NewPeerBucketValue
import sparkz.core.network.peer.PeerBucketStorage.{BucketConfig, PeerBucketStorageImpl}
import sparkz.core.network.peer.PeerDatabase.{PeerConfidence, PeerDatabaseValue}
import sparkz.core.persistence.StorageFilePersister.StorageFilePersisterConfig
import sparkz.core.utils.TimeProvider

import java.net.{InetAddress, InetSocketAddress}
import java.nio.file.{Files, Path}
import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.util.Random

class StorageFileWriterTest extends NetworkTests with BeforeAndAfterAll with BeforeAndAfterEach {
  private val tempDir = Files.createTempDirectory("temp-directory")
  private var tempFile: Option[Path] = None
  private val random = new Random()
  private val MAX_ELEMENTS_TO_INSERT = 10000

  override protected def afterAll(): Unit = {
    tempDir.toFile.deleteOnExit()
  }

  override protected def beforeEach(): Unit = {
    tempFile = Some(Files.createTempFile(tempDir, "tempFile", ""))
  }

  override protected def afterEach(): Unit = {
    tempFile.foreach(path => Files.deleteIfExists(path))
  }

  private implicit val executionContext: ExecutionContext = mock[ExecutionContext]

  "The PeerBucketWriter" should "persist and restore the peers in a bucket" in {
    // Arrange
    implicit val system: ActorSystem = ActorSystem()

    val nKey = 1234

    val bucketConfig = BucketConfig(1024, 512, 64)
    val newB = PeerBucketStorageImpl(bucketConfig, nKey, mockTimeProvider)

    val writerConfig = tempFile match {
      case Some(file) =>
        StorageFilePersisterConfig(tempDir.toFile, file.getFileName.toString)
      case None => fail("")
    }

    val storageWriter = new PeerBucketPersister(newB, writerConfig)
    for (_ <- 1 to MAX_ELEMENTS_TO_INSERT) {
      val peerAddress = new InetSocketAddress(s"${random.nextInt(255)}.${random.nextInt(255)}.${random.nextInt(255)}.${random.nextInt(255)}", random.nextInt(35000))
      val peerInfo = getPeerInfo(peerAddress)
      val peer = NewPeerBucketValue(PeerDatabaseValue(peerAddress, peerInfo, PeerConfidence.Unknown))
      newB.add(peer)
    }
    val expectedPeersSize = newB.getPeers.size
    expectedPeersSize should be > 0

    // Act
    storageWriter.persist()
    newB.clear()

    val peersSizeAfterCleaning = newB.getPeers.size
    peersSizeAfterCleaning shouldBe 0

    storageWriter.restore()

    // Assert
    newB.getPeers.size shouldBe expectedPeersSize

    system.terminate()
  }

  "The MapWriter" should "persist and restore the peers in a map" in {
    // Arrange
    implicit val system: ActorSystem = ActorSystem()

    val blacklist = mutable.Map.empty[InetAddress, TimeProvider.Time]
    val penaltyBook = mutable.Map.empty[InetAddress, (Int, Long)]
    val config = tempFile match {
      case Some(file) =>
        StorageFilePersisterConfig(tempDir.toFile, file.getFileName.toString)
      case None => fail("")
    }

    val blacklistWriter = new MapPersister(blacklist, config)
    val penaltyWriter = new MapPersister(penaltyBook, config)

    for (_ <- 1 to MAX_ELEMENTS_TO_INSERT) {
      val peerAddress = new InetSocketAddress(
        s"${random.nextInt(255)}.${random.nextInt(255)}.${random.nextInt(255)}.${random.nextInt(255)}", random.nextInt(35000)
      ).getAddress
      blacklist += peerAddress -> mockTimeProvider.time()
      penaltyBook += peerAddress -> (random.nextInt(), random.nextLong())
    }
    val expectedBlacklistedSize = blacklist.size
    expectedBlacklistedSize shouldBe MAX_ELEMENTS_TO_INSERT

    val expectedPenaltyBookSize = blacklist.size
    expectedPenaltyBookSize shouldBe MAX_ELEMENTS_TO_INSERT

    // Act
    blacklistWriter.persist()
    penaltyWriter.persist()

    blacklist.clear()
    penaltyBook.clear()

    blacklist.size shouldBe 0
    penaltyBook.size shouldBe 0

    blacklistWriter.restore()
    penaltyWriter.restore()

    // Assert
    blacklist.size shouldBe MAX_ELEMENTS_TO_INSERT
    penaltyBook.size shouldBe MAX_ELEMENTS_TO_INSERT

    system.terminate()
  }
}