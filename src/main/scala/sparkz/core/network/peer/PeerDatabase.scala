package sparkz.core.network.peer

import sparkz.core.network.ConnectedPeer

import java.net.{InetAddress, InetSocketAddress}

trait PeerDatabase {

  def get(peer: InetSocketAddress): Option[PeerInfo]

  def isEmpty: Boolean

  /**
    * Add peer to the database, or update it
    *
    * @param peerInfo - peer record
    * @param source - the peer that sent the peerInfo
    */
  def addOrUpdateKnownPeer(peerInfo: PeerInfo, source: Option[ConnectedPeer]): Unit

  def addOrUpdateKnownPeers(peersInfo: Seq[PeerInfo], source: Option[ConnectedPeer]): Unit

  def allPeers: Map[InetSocketAddress, PeerInfo]

  def randomPeersSubset: Map[InetSocketAddress, PeerInfo]

  def addToBlacklist(address: InetSocketAddress, penaltyType: PenaltyType): Unit

  def removeFromBlacklist(address: InetAddress): Unit

  def blacklistedPeers: Seq[InetAddress]

  def isBlacklisted(address: InetAddress): Boolean

  def remove(address: InetSocketAddress): Unit

  def peerPenaltyScoreOverThreshold(peer: InetSocketAddress, penaltyType: PenaltyType): Boolean
}
