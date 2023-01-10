package sparkz.core.network.peer

import com.google.common.primitives.{Bytes, Ints}
import sparkz.core.network.peer.BucketManager.PeerBucketValue
import sparkz.core.network.peer.PeerBucketStorage.{BucketConfig, BucketHashContent, NOT_FOUND_PEER_INDEXES}
import sparkz.core.network.peer.PeerDatabase.PeerDatabaseValue
import sparkz.core.utils.TimeProvider.Time
import sparkz.core.utils.{NetworkAddressWrapper, TimeProvider}
import sparkz.crypto.hash.Blake2b256

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import scala.collection.mutable
import scala.util.{Success, Try}

abstract class PeerBucketStorage[T <: BucketHashContent](
                                  private val bucketConfig: BucketConfig,
                                  private val nKey: Int,
                                  private val timeProvider: TimeProvider,
                                  private val isNewPeer: Boolean
                                ) {
  private val (buckets, positionsInBucket, bucketSubgroup) = BucketConfig.unapply(bucketConfig).getOrElse(throw new IllegalArgumentException())
  private val table: mutable.Map[(Int, Int), (PeerBucketValue, Time)] = mutable.Map.empty[(Int, Int), (PeerBucketValue, Time)]
  private val reverseTable: mutable.Map[InetSocketAddress, (Int, Int)] = mutable.Map.empty[InetSocketAddress, (Int, Int)]

  private[peer] def getBucket(hashContent: T): Int = {
    val hash1Content = Bytes.concat(Ints.toByteArray(nKey), hashContent.getHashAsBytes)
    val h: Array[Byte] = Blake2b256.hash(hash1Content)
    val hInt = ByteBuffer.wrap(h).getInt

    val hash2Content = Bytes.concat(Ints.toByteArray(nKey), getAddressGroup(hashContent.getAddress), Ints.toByteArray(hInt % bucketSubgroup))
    val hash2: Array[Byte] = Blake2b256.hash(hash2Content)

    ByteBuffer.wrap(hash2).getInt % buckets
  }

  private def getAddressGroup(peerAddress: InetSocketAddress): Array[Byte] = {
    val na = new NetworkAddressWrapper(peerAddress)
    var nClass = NetworkAddressWrapper.NET_IPV6
    var addressSubgroupIndex = 0
    var addressSubGroupToInclude = 1

    if (na.isLocal) {
      nClass = 255
      addressSubGroupToInclude = 0
    } else if (!na.isRoutable) {
      nClass = NetworkAddressWrapper.NET_UNROUTABLE
      addressSubGroupToInclude = 0
    } else if (na.hasLinkedIPv4) {
      nClass = NetworkAddressWrapper.NET_IPV4
    } else
      addressSubGroupToInclude = 2

    var result: Array[Byte] = Array(nClass.toByte)
    while (addressSubGroupToInclude > 0) {
      result = result ++ na.getSubNum(addressSubgroupIndex)
      addressSubgroupIndex += 1
      addressSubGroupToInclude -= 1
    }
    result
  }

  private[peer] def getBucketPosition(nBucket: Int, peerAddress: InetSocketAddress): Int = {
    val hashContent = Bytes.concat(
      Ints.toByteArray(nKey),
      Ints.toByteArray(if (isNewPeer) 1 else 0),
      Ints.toByteArray(nBucket),
      peerAddress.getHostString.getBytes,
      Ints.toByteArray(peerAddress.getPort)
    )
    val hash1 = Blake2b256.hash(hashContent)
    ByteBuffer.wrap(hash1).getInt % positionsInBucket
  }

  def add(peerBucketValue: PeerBucketValue): Unit = {
    val address = peerBucketValue.peerDatabaseValue.address
    val hashContent = getHashContent(peerBucketValue)
    val (bucket, bucketPosition) = getPeerIndexes(hashContent)
    table += (bucket, bucketPosition) -> (peerBucketValue, timeProvider.time())
    reverseTable += address -> (bucket, bucketPosition)
  }

  def updateExistingPeer(peerBucketValue: PeerBucketValue): Unit = {
    val address = peerBucketValue.peerDatabaseValue.address
    val indexes = reverseTable(address)
    table(indexes) = (peerBucketValue, timeProvider.time())
  }

  protected def getHashContent(peerBucketValue: PeerBucketValue): T

  def remove(address: InetSocketAddress): Unit = {
    val peerIndexes = tryGetIndexesFromPeerAddress(address)

    if (peerIndexes != NOT_FOUND_PEER_INDEXES) {
      table -= ((peerIndexes._1, peerIndexes._2))
      reverseTable -= address
    }
  }

  def bucketPositionIsAlreadyTaken(peerBucketValue: PeerBucketValue): Boolean = {
    val hashContent = getHashContent(peerBucketValue)
    val (bucket, bucketPosition) = getPeerIndexes(hashContent)

    table.contains((bucket, bucketPosition))
  }

  def contains(address: InetSocketAddress): Boolean = {
    tryGetIndexesFromPeerAddress(address) != NOT_FOUND_PEER_INDEXES
  }

  def getStoredPeerByAddress(address: InetSocketAddress): Option[PeerBucketValue] = {
    tryGetIndexesFromPeerAddress(address) match {
      case indexes if indexes != NOT_FOUND_PEER_INDEXES => Some(table(indexes._1, indexes._2)._1)
      case _ => None
    }
  }

  def getStoredPeerByIndexes(bucket: Int, bucketPosition: Int): Option[PeerBucketValue] = {
    Try(table(bucket, bucketPosition)) match {
      case Success((peerBucketValue, _)) => Some(peerBucketValue)
      case _ => None
    }
  }

  def getPeerIndexes(peerBucketValue: PeerBucketValue): (Int, Int) = {
    val peerHashContent = getHashContent(peerBucketValue)
    getPeerIndexes(peerHashContent)
  }

  def getPeerIndexes(peerHashContent: T): (Int, Int) = {
    val bucket = getBucket(peerHashContent)
    val bucketPosition = getBucketPosition(bucket, peerHashContent.getAddress)
    (bucket, bucketPosition)
  }

  private def tryGetIndexesFromPeerAddress(address: InetSocketAddress): (Int, Int) = {
    Try(reverseTable(address)) match {
      case Success(indexes) => indexes
      case _ => NOT_FOUND_PEER_INDEXES
    }
  }

  def isEmpty: Boolean = table.isEmpty

  def nonEmpty: Boolean = !isEmpty

  def getPeers: Map[InetSocketAddress, PeerDatabaseValue] =
    table
      .values
      .map { p => (p._1.peerDatabaseValue.address, p._1.peerDatabaseValue) }
      .toMap

  def clear(): Unit = {
    table.clear()
    reverseTable.clear()
  }

  def getPeersAsBucketValue: Seq[PeerBucketValue] = table.values.map(value => value._1).toSeq
}

object PeerBucketStorage {
  val NOT_FOUND_PEER_INDEXES: (Int, Int) = (-1, -1)
  case class BucketConfig(buckets: Int, bucketPositions: Int, bucketSubgroups: Int)

  trait BucketHashContent {
    def getAddress: InetSocketAddress

    def getHashAsBytes: Array[Byte]
  }

  case class PeerBucketHashContentImpl(address: InetSocketAddress) extends BucketHashContent {
    override def getHashAsBytes: Array[Byte] = Bytes.concat(
      address.getHostString.getBytes,
      Ints.toByteArray(address.getPort)
    )

    override def getAddress: InetSocketAddress = address
  }

  case class PeerBucketStorageImpl(bucketConfig: BucketConfig, nKey: Int, timeProvider: TimeProvider)
    extends PeerBucketStorage[PeerBucketHashContentImpl](bucketConfig, nKey, timeProvider, true) {

    override protected def getHashContent(peerBucketValue: PeerBucketValue): PeerBucketHashContentImpl = {
      val address = peerBucketValue.peerDatabaseValue.address
      PeerBucketHashContentImpl(address)
    }
  }
}