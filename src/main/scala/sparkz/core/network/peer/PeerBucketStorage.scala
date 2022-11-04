package sparkz.core.network.peer

import com.google.common.primitives.{Bytes, Ints}
import scorex.crypto.hash.Blake2b256
import sparkz.core.network.peer.BucketManager.PeerBucketValue
import sparkz.core.network.peer.PeerBucketStorage.{BucketConfig, BucketHashContent, NOT_FOUND_PEER_INDEXES, TriedPeerBucketHashContent}
import sparkz.core.utils.TimeProvider.Time
import sparkz.core.utils.{NetworkAddressWrapper, TimeProvider}

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import scala.collection.mutable
import scala.util.{Failure, Success, Try}

abstract class PeerBucketStorage[T <: BucketHashContent](
                                  private val bucketConfig: BucketConfig,
                                  private val nKey: Int,
                                  private val timeProvider: TimeProvider,
                                  private val isNewPeer: Boolean
                                ) {
  private val (buckets, positionsInBucket, bucketSubgroup) = BucketConfig.unapply(bucketConfig).getOrElse(throw new IllegalArgumentException())
  private val table: mutable.Map[(Int, Int), (PeerBucketValue, Time)] = mutable.Map.empty[(Int, Int), (PeerBucketValue, Time)]
  private val reverseTable: mutable.Map[InetSocketAddress, (Int, Int)] = mutable.Map.empty[InetSocketAddress, (Int, Int)]

  private def getBucket(hashContent: T): Int = {
    val hash1Content = Bytes.concat(Ints.toByteArray(nKey), hashContent.getHashAsBytes)
    val h: Array[Byte] = Blake2b256.hash(hash1Content)
    val hInt = ByteBuffer.wrap(h).getInt

    val hash2Content = Bytes.concat(Ints.toByteArray(nKey), getAddressGroup(hashContent.getAddress), Ints.toByteArray(hInt % bucketSubgroup))
    val hash2: Array[Byte] = Blake2b256.hash(hash2Content)
    ByteBuffer.wrap(hash2).getInt % buckets
  }

  private def getAddressGroup(peerAddress: InetSocketAddress): Array[Byte] = {
    val networkAddress = new NetworkAddressWrapper(peerAddress)
    var nClass = 0
    var nStartByte = 0
    var nBits = 16

    if (networkAddress.isLocal) {
      nClass = 255
      nBits = 0
    }
    Array[Byte]()
  }

  private def getBucketPosition(nBucket: Int, peerAddress: InetSocketAddress): Int = {
    val hashContent = Bytes.concat(
      Ints.toByteArray(nKey),
      Ints.toByteArray(if (isNewPeer) 1 else 0),
      Ints.toByteArray(nBucket),
      peerAddress.getHostName.getBytes,
      Ints.toByteArray(peerAddress.getPort)
    )
    val hash1 = Blake2b256.hash(hashContent)
    ByteBuffer.wrap(hash1).getInt % positionsInBucket
  }

  private def getPeerPositionIndexes(bucketHashContent: T): (Int, Int) = {
    if (reverseTable.contains(bucketHashContent.getAddress)) reverseTable(bucketHashContent.getAddress)
    else {
      val bucket = getBucket(bucketHashContent)
      val bucketPosition = getBucketPosition(bucket, bucketHashContent.getAddress)
      (bucket, bucketPosition)
    }
  }

  def add(peerBucketValue: PeerBucketValue): Unit = {
    peerBucketValue.peerInfo.peerSpec.address.foreach(address => {
      val hashContent = getHashContent(peerBucketValue)
      val (bucket, bucketPosition) = getPeerPositionIndexes(hashContent)
      table += (bucket, bucketPosition) -> (peerBucketValue, timeProvider.time())
      reverseTable += address -> (bucket, bucketPosition)
    })
  }

  protected def getHashContent(peerBucketValue: PeerBucketValue): T

  def remove(address: InetSocketAddress): Unit = {
    val peerIndexes = tryGetIndexesFromPeerAddress(address)

    if (peerIndexes != NOT_FOUND_PEER_INDEXES) {
      table -= ((peerIndexes._1, peerIndexes._2))
      reverseTable -= address
    }
  }

  def contains(address: InetSocketAddress): Boolean = {
    val peerIndexes = tryGetIndexesFromPeerAddress(address)

    if (peerIndexes == NOT_FOUND_PEER_INDEXES)
      false
    else
      table.contains((peerIndexes._1, peerIndexes._2))
  }

  def getStoredPeer(address: InetSocketAddress): Option[PeerBucketValue] = {
    tryGetIndexesFromPeerAddress(address) match {
      case indexes if indexes != NOT_FOUND_PEER_INDEXES => Some(table(indexes._1, indexes._2)._1)
      case _ => None
    }
  }

  private def tryGetIndexesFromPeerAddress(address: InetSocketAddress): (Int, Int) = {
    Try(reverseTable(address)) match {
      case Success(indexes) => indexes
      case _ => NOT_FOUND_PEER_INDEXES
    }
  }

  def isEmpty: Boolean = table.isEmpty

  def getPeers: Map[InetSocketAddress, PeerInfo] =
    table
      .values
      .map { p => (p._1.peerInfo.peerSpec.address.getOrElse(throw new IllegalArgumentException()), p._1.peerInfo) }
      .toMap
}

object PeerBucketStorage {
  val NOT_FOUND_PEER_INDEXES: (Int, Int) = (-1, -1)
  case class BucketConfig(buckets: Int, bucketPositions: Int, bucketSubgroups: Int)

  trait BucketHashContent {
    def getAddress: InetSocketAddress

    def getHashAsBytes: Array[Byte]
  }

  case class NewPeerBucketHashContent(address: InetSocketAddress, sourceAddress: InetSocketAddress) extends BucketHashContent {
    override def getHashAsBytes: Array[Byte] = Bytes.concat(
      address.getHostName.getBytes,
      Ints.toByteArray(address.getPort),
      sourceAddress.getHostName.getBytes,
      Ints.toByteArray(sourceAddress.getPort)
    )

    override def getAddress: InetSocketAddress = address
  }

  case class TriedPeerBucketHashContent(address: InetSocketAddress) extends BucketHashContent {
    override def getHashAsBytes: Array[Byte] = Bytes.concat(address.getHostName.getBytes, Ints.toByteArray(address.getPort))

    override def getAddress: InetSocketAddress = address
  }

  case class NewPeerBucketStorage(bucketConfig: BucketConfig, nKey: Int, timeProvider: TimeProvider)
    extends PeerBucketStorage[NewPeerBucketHashContent](bucketConfig, nKey, timeProvider, true) {

    override protected def getHashContent(peerBucketValue: PeerBucketValue): NewPeerBucketHashContent = {
      val address = peerBucketValue.peerInfo.peerSpec.address.getOrElse(throw new IllegalArgumentException())
      NewPeerBucketHashContent(address, peerBucketValue.source.connectionId.remoteAddress)
    }
  }

  case class TriedPeerBucketStorage(bucketConfig: BucketConfig, nKey: Int, timeProvider: TimeProvider)
    extends PeerBucketStorage[TriedPeerBucketHashContent](bucketConfig, nKey, timeProvider, false) {

    override protected def getHashContent(peerBucketValue: PeerBucketValue): TriedPeerBucketHashContent = {
      val address = peerBucketValue.peerInfo.peerSpec.address.getOrElse(throw new IllegalArgumentException())
      TriedPeerBucketHashContent(address)
    }
  }
}