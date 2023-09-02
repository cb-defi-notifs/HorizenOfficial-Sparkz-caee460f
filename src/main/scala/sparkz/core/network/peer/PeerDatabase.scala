package sparkz.core.network.peer

import sparkz.core.network.peer.PeerDatabase.PeerConfidence.PeerConfidence
import sparkz.core.network.peer.PeerDatabase.PeerDatabaseValue

import java.net.{InetAddress, InetSocketAddress}

trait PeerDatabase {

  def get(peer: InetSocketAddress): Option[PeerDatabaseValue]

  def isEmpty: Boolean

  /**
    * Add peer to the database, or update it
    *
    * @param peerInfo - peer record
    */
  def addOrUpdateKnownPeer(peerInfo: PeerDatabaseValue): Unit

  def addOrUpdateKnownPeers(peersInfo: Seq[PeerDatabaseValue]): Unit

  def updatePeer(peerInfo: PeerDatabaseValue): Unit

  def allPeers: Map[InetSocketAddress, PeerDatabaseValue]

  def randomPeersSubset: Map[InetSocketAddress, PeerDatabaseValue]

  def addToBlacklist(address: InetSocketAddress, penaltyType: PenaltyType): Unit

  def removeFromBlacklist(address: InetAddress): Unit

  def blacklistedPeers: Seq[InetAddress]

  def isBlacklisted(address: InetAddress): Boolean

  def remove(address: InetSocketAddress): Unit

  def peerPenaltyScoreOverThreshold(peer: InetSocketAddress, penaltyType: PenaltyType): Boolean
}

object PeerDatabase {
  /**
    * This is an Enum that represents the confidence level the node has about this peer
    */
  object PeerConfidence extends Enumeration {
    type PeerConfidence = Value
    val Unknown, Low, Medium, High, Forger: Value = Value
  }

  case class PeerDatabaseValue(address: InetSocketAddress, peerInfo: PeerInfo, confidence: PeerConfidence) {
    def hasBeenUpdated(otherPeerDbValue: PeerDatabaseValue): Boolean = {
      val isSameAddress = address == otherPeerDbValue.address
      val confidenceChanged = confidence != otherPeerDbValue.confidence
      val peerInfoChanged = peerInfo != otherPeerDbValue.peerInfo

      isSameAddress && (confidenceChanged || peerInfoChanged)
    }
  }
}