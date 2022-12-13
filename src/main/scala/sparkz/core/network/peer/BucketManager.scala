package sparkz.core.network.peer

import sparkz.core.network.peer.BucketManager.Exception.PeerNotFoundException
import sparkz.core.network.peer.BucketManager.PeerBucketValue
import sparkz.core.network.peer.PeerBucketStorage._
import sparkz.core.network.peer.PeerDatabase.PeerDatabaseValue

import java.net.InetSocketAddress
import scala.util.Random

class BucketManager(newBucket: PeerBucketStorageImpl, triedBucket: PeerBucketStorageImpl) {
  def addPeerIntoBucket(peerDatabaseValue: PeerDatabaseValue): Unit = {
    if (newBucket.contains(peerDatabaseValue.address)) {
      makeTried(peerDatabaseValue)
    } else {
      // We don't want to add the peer if we already have it in the tried database unless it's an update
      if (!triedBucket.contains(peerDatabaseValue.address)) {
        addNewPeer(peerDatabaseValue)
      } else {
        updatePeerInTriedIfDifferent(peerDatabaseValue)
      }
    }
  }

  private def updatePeerInTriedIfDifferent(peerDatabaseValue: PeerDatabaseValue): Unit = {
    val oldPeer = triedBucket.getStoredPeerByAddress(peerDatabaseValue.address)
    oldPeer.foreach(p => {
      if (p.peerDatabaseValue.hasBeenUpdated(peerDatabaseValue)) {
        triedBucket.updateExistingPeer(PeerBucketValue(peerDatabaseValue, isNew = false))
      }
    })
  }

  private[peer] def addNewPeer(peerDatabaseValue: PeerDatabaseValue): Unit = {
    newBucket.add(PeerBucketValue(peerDatabaseValue, isNew = true))
  }

  private[peer] def makeTried(peerDatabaseValue: PeerDatabaseValue): Unit = {
    val address = peerDatabaseValue.address
    val peerToBeMovedInTried = newBucket
      .getStoredPeerByAddress(address)
      .getOrElse(
        throw PeerNotFoundException(s"Cannot move peer $address to tried table because it doesn't exist in new")
      )
    newBucket.remove(address)

    if (triedBucket.bucketPositionIsAlreadyTaken(peerToBeMovedInTried)) {
      val (bucket, bucketPosition) = triedBucket.getPeerIndexes(peerToBeMovedInTried)
      val peerToBeRemovedOption = triedBucket.getStoredPeerByIndexes(bucket, bucketPosition)
      val peerToBeRemoved = peerToBeRemovedOption.getOrElse(throw new IllegalArgumentException())
      val addressToBeRemoved = peerToBeRemoved.peerDatabaseValue.peerInfo.peerSpec.address.getOrElse(throw new IllegalArgumentException())
      triedBucket.remove(addressToBeRemoved)

      addNewPeer(peerToBeRemoved.peerDatabaseValue)
    }

    triedBucket.add(PeerBucketValue(peerDatabaseValue, isNew = false))
  }

  def removePeer(address: InetSocketAddress): Unit = {
    newBucket.remove(address)
    triedBucket.remove(address)
  }

  def isEmpty: Boolean = triedBucket.isEmpty && newBucket.isEmpty

  def getNewPeers: Map[InetSocketAddress, PeerDatabaseValue] = newBucket.getPeers

  def getTriedPeers: Map[InetSocketAddress, PeerDatabaseValue] = triedBucket.getPeers

  def getRandomPeers: Map[InetSocketAddress, PeerDatabaseValue] = {
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
  case class PeerBucketValue(peerDatabaseValue: PeerDatabaseValue, isNew: Boolean)

  case object Exception {
    case class PeerNotFoundException(msg: String) extends Exception(msg)
  }
}