package sparkz.core.network.peer

import sparkz.core.network.peer.BucketManager.Exception.PeerNotFoundException
import sparkz.core.network.peer.BucketManager.PeerBucketValue
import sparkz.core.network.peer.PeerBucketStorage._

import java.net.InetSocketAddress
import scala.util.Random

class BucketManager(newBucket: PeerBucketStorageImpl, triedBucket: PeerBucketStorageImpl) {
  def addPeerIntoBucket(peerInfo: PeerInfo): Unit = {
    peerInfo.peerSpec.address.foreach(address =>
      if (newBucket.contains(address)) {
        makeTried(peerInfo)
      } else {
        // We don't want to add the peer if we already have it in the tried database
        if (!triedBucket.contains(address))
          addNewPeer(peerInfo)
      }
    )
  }

  private[peer] def addNewPeer(peerInfo: PeerInfo): Unit = {
    newBucket.add(PeerBucketValue(peerInfo, isNew = true))
  }

  private[peer] def makeTried(peerInfo: PeerInfo): Unit = {
    val address = peerInfo.peerSpec.address.getOrElse(throw new IllegalArgumentException())
    val peerToBeMovedInTried = newBucket
      .getStoredPeerByAddress(address)
      .getOrElse(
        throw PeerNotFoundException(s"Cannot move peer $peerInfo to tried table because it doesn't exist in new")
      )
    newBucket.remove(address)

    if (triedBucket.bucketPositionIsAlreadyTaken(peerToBeMovedInTried)) {
      val (bucket, bucketPosition) = triedBucket.getPeerIndexes(peerToBeMovedInTried)
      val peerToBeRemovedOption = triedBucket.getStoredPeerByIndexes(bucket, bucketPosition)
      val peerToBeRemoved = peerToBeRemovedOption.getOrElse(throw new IllegalArgumentException())
      val addressToBeRemoved = peerToBeRemoved.peerInfo.peerSpec.address.getOrElse(throw new IllegalArgumentException())
      triedBucket.remove(addressToBeRemoved)

      addNewPeer(peerToBeRemoved.peerInfo)
    }

    triedBucket.add(PeerBucketValue(peerInfo, isNew = false))
  }

  def removePeer(address: InetSocketAddress): Unit = {
    newBucket.remove(address)
    triedBucket.remove(address)
  }

  def isEmpty: Boolean = triedBucket.isEmpty && newBucket.isEmpty

  def getNewPeers: Map[InetSocketAddress, PeerInfo] = newBucket.getPeers

  def getTriedPeers: Map[InetSocketAddress, PeerInfo] = triedBucket.getPeers

  def getRandomPeers: Map[InetSocketAddress, PeerInfo] = {
    val pickFromNewBucket = new Random().nextBoolean()

    if ((pickFromNewBucket && newBucket.nonEmpty) || triedBucket.isEmpty)
      newBucket.getPeers
    else
      triedBucket.getPeers
  }

  def getPeer(peerAddress: InetSocketAddress): Option[PeerBucketValue] = {
    triedBucket.getStoredPeerByAddress(peerAddress)
      .orElse(newBucket.getStoredPeerByAddress(peerAddress))
  }
}

object BucketManager {
  case class PeerBucketValue(peerInfo: PeerInfo, isNew: Boolean)

  case object Exception {
    case class PeerNotFoundException(msg: String) extends Exception(msg)
  }
}