package sparkz.core.network.peer

import java.net.{InetAddress, InetSocketAddress}
import sparkz.core.settings.NetworkSettings
import sparkz.core.utils.TimeProvider
import sparkz.util.SparkzLogging
import sparkz.core.network.peer.PenaltyType.DisconnectPenalty

import scala.concurrent.duration._

/**
  * In-memory peer database implementation supporting temporal blacklisting.
  */
final class InMemoryPeerDatabase(settings: NetworkSettings, timeProvider: TimeProvider)
  extends PeerDatabase with SparkzLogging {

  private val safeInterval = settings.penaltySafeInterval.toMillis

  private var peers = Map.empty[InetSocketAddress, (PeerInfo, TimeProvider.Time)]

  /**
    * banned peer ip -> ban expiration timestamp
    */
  private var blacklist = Map.empty[InetAddress, TimeProvider.Time]

  /**
    * penalized peer ip -> (accumulated penalty score, last penalty timestamp)
    */
  private var penaltyBook = Map.empty[InetAddress, (Int, Long)]

  override def get(peer: InetSocketAddress): Option[PeerInfo] = peers.get(peer).map(_._1)

  private def addPeer(peerInfo: PeerInfo): Unit = {
    peerInfo.peerSpec.address.foreach { address =>
      log.debug(s"Updating peer info for $address")
      peers += address -> (peerInfo, timeProvider.time())
    }
  }

  /**
   * Adds a peer to the in-memory database ignoring the configurable limit.
   * Used for high-priority peers, like peers from config file or connected peers
   */
  override def addOrUpdateKnownPeer(peerInfo: PeerInfo): Unit = {
    if (!peerInfo.peerSpec.declaredAddress.exists(x => isBlacklisted(x.getAddress))) {
      addPeer(peerInfo)
    }
  }

  override def addOrUpdateKnownPeers(peersInfo: Seq[PeerInfo]): Unit = {
    val validPeers = peersInfo.filterNot {_.peerSpec.declaredAddress.exists(x => isBlacklisted(x.getAddress))}
    if (peers.size + validPeers.size > settings.storedPeersLimit) {
      addOrReplaceOldPeers(validPeers)
    }
    else
      validPeers.foreach(addPeer)
  }

  private def addOrReplaceOldPeers(validPeers: Seq[PeerInfo]): Unit = {
    val needToRemove = peers.size + validPeers.size - settings.storedPeersLimit
    //it can be that all peers are connected, so we won't make enough place
    val canRemove = peers.toSeq
      .filter { p => p._2._1.connectionType.isEmpty || p._2._1.lastHandshake == 0 }
      .sortBy { p => p._2._2 }(Ordering.Long)
      .take(needToRemove)
    canRemove
      .foreach(p => remove(p._1))

    validPeers
      .take(validPeers.size - (needToRemove - canRemove.size))
      .foreach(addPeer)
  }

  override def addToBlacklist(socketAddress: InetSocketAddress,
                              penaltyType: PenaltyType): Unit = {
    peers -= socketAddress
    Option(socketAddress.getAddress).foreach { address =>
      penaltyBook -= address
      if (!blacklist.keySet.contains(address))
        blacklist += address -> (timeProvider.time() + penaltyDuration(penaltyType))
      else log.warn(s"${address.toString} is already blacklisted")
    }
  }

  override def removeFromBlacklist(address: InetAddress): Unit = {
    log.info(s"$address removed from blacklist")
    blacklist -= address
  }

  override def remove(address: InetSocketAddress): Unit = {
    peers -= address
  }

  override def knownPeers: Map[InetSocketAddress, PeerInfo] = peers.transform { (_, info_time) => info_time._1 }

  override def blacklistedPeers: Seq[InetAddress] = blacklist
    .map { case (address, bannedTill) =>
      checkBanned(address, bannedTill)
      address
    }
    .toSeq

  override def isEmpty: Boolean = peers.isEmpty

  override def isBlacklisted(address: InetAddress): Boolean =
    blacklist.get(address).exists(checkBanned(address, _))

  def isBlacklisted(address: InetSocketAddress): Boolean =
    Option(address.getAddress).exists(isBlacklisted)

  /**
    * Registers a new penalty in the penalty book.
    *
    * @return - `true` if penalty threshold is reached, `false` otherwise.
    */
  def peerPenaltyScoreOverThreshold(socketAddress: InetSocketAddress, penaltyType: PenaltyType): Boolean =
    Option(socketAddress.getAddress).exists { address =>
      val (newPenaltyScore, penaltyTs) = penaltyBook.get(address) match {
        case Some((penaltyScoreAcc, lastPenaltyTs)) =>
          val currentTime = timeProvider.time()
          if (currentTime - lastPenaltyTs - safeInterval > 0 || penaltyType.isPermanent)
            (penaltyScoreAcc + penaltyType.penaltyScore, timeProvider.time())
          else
            (penaltyScoreAcc, lastPenaltyTs)
        case None =>
          (penaltyType.penaltyScore, timeProvider.time())
      }
      if (newPenaltyScore > settings.penaltyScoreThreshold)
        true
      else {
        penaltyBook += address -> (newPenaltyScore -> penaltyTs)
        false
      }
    }

  /**
    * Currently accumulated penalty score for a given address.
    */
  def penaltyScore(address: InetAddress): Int =
    penaltyBook.getOrElse(address, (0, 0L))._1

  def penaltyScore(socketAddress: InetSocketAddress): Int =
    Option(socketAddress.getAddress).map(penaltyScore).getOrElse(0)

  private def checkBanned(address: InetAddress, bannedTill: Long): Boolean = {
    val stillBanned = timeProvider.time() < bannedTill
    if (!stillBanned) removeFromBlacklist(address)
    stillBanned
  }

  private def penaltyDuration(penalty: PenaltyType): Long =
    penalty match {
      case PenaltyType.NonDeliveryPenalty | PenaltyType.MisbehaviorPenalty | PenaltyType.SpamPenalty | _: DisconnectPenalty =>
        settings.temporalBanDuration.toMillis
      case PenaltyType.PermanentPenalty =>
        (360 * 10).days.toMillis
    }

}
