package sparkz.core.network.peer

import sparkz.core.network.peer.BucketManager.{NewPeerBucketValue, PeerBucketValue, TriedPeerBucketValue}
import sparkz.core.network.peer.PeerBucketStorage._
import sparkz.core.network.peer.PeerDatabase.PeerDatabaseValue

import java.net.InetSocketAddress
import scala.util.Random

class BucketManager(newBucket: PeerBucketStorageImpl, triedBucket: PeerBucketStorageImpl) {
  def addOrUpdatePeerIntoBucket(peerDatabaseValue: PeerDatabaseValue): Unit = {
    if (newBucket.contains(peerDatabaseValue.address)) {
      updatePeerIfDifferent(newBucket, NewPeerBucketValue(peerDatabaseValue))
    } else if (triedBucket.contains(peerDatabaseValue.address)) {
      updatePeerIfDifferent(triedBucket, TriedPeerBucketValue(peerDatabaseValue))
    } else {
      newBucket.add(NewPeerBucketValue(peerDatabaseValue))
    }
  }

  private def updatePeerIfDifferent(peerBucketStorage: PeerBucketStorage[_], peerBucketValue: PeerBucketValue): Unit = {
    val oldPeer = peerBucketStorage.getStoredPeerByAddress(peerBucketValue.peerDatabaseValue.address)
    oldPeer.foreach(p => {
      if (p.peerDatabaseValue.hasBeenUpdated(peerBucketValue.peerDatabaseValue)) {
        peerBucketStorage.updateExistingPeer(peerBucketValue)
      }
    })
  }

  private[peer] def addNewPeer(peerDatabaseValue: PeerDatabaseValue): Unit = {
    newBucket.add(NewPeerBucketValue(peerDatabaseValue))
  }

  private[peer] def makeTried(peerDatabaseValue: PeerDatabaseValue): Unit = {
    val address = peerDatabaseValue.address
    val peerToBeRemovedFromNew = newBucket.getStoredPeerByAddress(address)
    peerToBeRemovedFromNew.foreach(p => newBucket.remove(p.peerDatabaseValue.address))

    val peerToBeMovedInTried = TriedPeerBucketValue(peerDatabaseValue)
    if (triedBucket.bucketPositionIsAlreadyTaken(peerToBeMovedInTried)) {
      val (bucket, bucketPosition) = triedBucket.getPeerIndexes(peerToBeMovedInTried)
      val peerToBeRemovedOption = triedBucket.getStoredPeerByIndexes(bucket, bucketPosition)
      val peerToBeRemoved = peerToBeRemovedOption.getOrElse(throw new IllegalArgumentException())
      val addressToBeRemoved = peerToBeRemoved.peerDatabaseValue.peerInfo.peerSpec.address.getOrElse(throw new IllegalArgumentException())
      triedBucket.remove(addressToBeRemoved)

      addNewPeer(peerToBeRemoved.peerDatabaseValue)
    }

    triedBucket.add(TriedPeerBucketValue(peerDatabaseValue))
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
  trait PeerBucketValue {
    val peerDatabaseValue: PeerDatabaseValue
    val isNew: Boolean
  }
  case class NewPeerBucketValue(peerDatabaseValue: PeerDatabaseValue) extends PeerBucketValue {
    override val isNew: Boolean = true
  }
  case class TriedPeerBucketValue(peerDatabaseValue: PeerDatabaseValue) extends PeerBucketValue {
    override val isNew: Boolean = false
  }

  case object Exception {
    case class PeerNotFoundException(msg: String) extends Exception(msg)
  }
}