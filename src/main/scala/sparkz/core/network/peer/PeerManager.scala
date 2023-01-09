package sparkz.core.network.peer

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import sparkz.core.app.SparkzContext
import sparkz.core.network._
import sparkz.core.settings.SparkzSettings
import sparkz.core.utils.NetworkUtils
import sparkz.util.SparkzLogging
import sparkz.core.network.peer.PeerDatabase.{PeerConfidence, PeerDatabaseValue}

import java.net.{InetAddress, InetSocketAddress}
import scala.util.Random
import java.security.SecureRandom

/**
  * Peer manager takes care of peers connected and in process, and also chooses a random peer to connect
  * Must be singleton
  */
class PeerManager(settings: SparkzSettings, sparkzContext: SparkzContext, peerDatabase: PeerDatabase) extends Actor with SparkzLogging {

  import PeerManager.ReceivableMessages._

  override def receive: Receive = peersManagement orElse {
    case a: Any =>
      log.error(s"Wrong input for peer manager: $a")
  }

  private def peersManagement: Receive = {
    case ConfirmConnection(connectionId, handlerRef) =>
      log.info(s"Connection confirmation request: $connectionId")
      if (peerDatabase.isBlacklisted(connectionId.remoteAddress.getAddress)) sender() ! ConnectionDenied(connectionId, handlerRef)
      else sender() ! ConnectionConfirmed(connectionId, handlerRef)

    case AddOrUpdatePeer(peerInfo) =>
      // We have connected to a peer and got his peerInfo from him
      if (!isSelf(peerInfo.peerSpec)) {
        peerDatabase.addOrUpdateKnownPeer(
          PeerDatabaseValue(
            extractAddressFromPeerInfoOrFeature(peerInfo),
            peerInfo,
            PeerConfidence.Unknown
          )
        )
      }

    case UpdatePeer(peerInfo) =>
      if (!isSelf(peerInfo.peerSpec)) {
        peerDatabase.updatePeer(
          PeerDatabaseValue(
            extractAddressFromPeerInfoOrFeature(peerInfo),
            peerInfo,
            PeerConfidence.Unknown
          )
        )
      }

    case Penalize(peer, penaltyType) =>
      log.info(s"$peer penalized, penalty: $penaltyType")
      if (peerDatabase.peerPenaltyScoreOverThreshold(peer, penaltyType)) {
        log.info(s"$peer blacklisted")
        peerDatabase.addToBlacklist(peer, penaltyType)
        sender() ! DisconnectFromAddress(peer)
      }

    case AddPeersIfEmpty(peersSpec) =>
      // We have received peers data from other peers. It might be modified and should not affect existing data if any
      val filteredPeers = peersSpec
        .collect {
          case peerSpec if peerSpec.address.forall(a => peerDatabase.get(a).isEmpty) && !isSelf(peerSpec) =>
            val address: InetSocketAddress = peerSpec.address.getOrElse(
              peerSpec.features.find(f => f.featureId == LocalAddressPeerFeature.featureId)
                .getOrElse(throw new IllegalArgumentException()).asInstanceOf[LocalAddressPeerFeature].address
            )
            val peerInfo: PeerInfo = PeerInfo(peerSpec, 0L, None)
            log.info(s"New discovered peer: $peerInfo")
            PeerDatabaseValue(address, peerInfo, PeerConfidence.Unknown)
        }
      peerDatabase.addOrUpdateKnownPeers(filteredPeers)

    case AddToBlacklist(address, penaltyType) =>
      penaltyType match {
        case Some(penalty) => peerDatabase.addToBlacklist(address, penalty)
        case _ => peerDatabase.addToBlacklist(address, PenaltyType.MisbehaviorPenalty)
      }

    case RemoveFromBlacklist(address) =>
      peerDatabase.removeFromBlacklist(address.getAddress)

    case RemovePeer(address) =>
      peerDatabase.remove(address)
      log.info(s"$address removed from peers database")

    case get: RandomPeerForConnectionExcluding =>
      sender() ! get.choose(peerDatabase.randomPeersSubset, peerDatabase.blacklistedPeers, sparkzContext)

    case get: GetPeers[_] =>
      sender() ! get.choose(peerDatabase.allPeers, peerDatabase.blacklistedPeers, sparkzContext)
  }

  private def extractAddressFromPeerInfoOrFeature(peerInfo: PeerInfo) = {
    val address: InetSocketAddress = peerInfo.peerSpec.address.getOrElse(
      peerInfo.peerSpec.features.find(f => f.featureId == LocalAddressPeerFeature.featureId)
        .getOrElse(throw new IllegalArgumentException()).asInstanceOf[LocalAddressPeerFeature].address
    )
    address
  }

  /**
    * Given a peer's address, returns `true` if the peer is the same is this node.
    */
  private def isSelf(peerAddress: InetSocketAddress): Boolean = {
    NetworkUtils.isSelf(peerAddress, settings.network.bindAddress, sparkzContext.externalNodeAddress)
  }

  private def isSelf(peerSpec: PeerSpec): Boolean = {
    peerSpec.declaredAddress.exists(isSelf) || peerSpec.localAddressOpt.exists(isSelf)
  }

}

object PeerManager {

  object ReceivableMessages {

    case class ConfirmConnection(connectionId: ConnectionId, handlerRef: ActorRef)

    case class ConnectionConfirmed(connectionId: ConnectionId, handlerRef: ActorRef)

    case class ConnectionDenied(connectionId: ConnectionId, handlerRef: ActorRef)

    case class Penalize(remote: InetSocketAddress, penaltyType: PenaltyType)

    case class Blacklisted(remote: InetSocketAddress)
    case class DisconnectFromAddress(remote: InetSocketAddress)

    case class AddToBlacklist(remote: InetSocketAddress, penalty: Option[PenaltyType] = None)

    case class RemoveFromBlacklist(remote: InetSocketAddress)

    // peerListOperations messages
    /**
      * @param data : information about peer to be stored in PeerDatabase
      * */
    case class AddOrUpdatePeer(data: PeerInfo)

    case class UpdatePeer(data: PeerInfo)

    case class AddPeersIfEmpty(data: Seq[PeerSpec])

    case class RemovePeer(address: InetSocketAddress)

    /**
      * Message to get peers from known peers map filtered by `choose` function
      */
    trait GetPeers[T] {
      def choose(peers: Map[InetSocketAddress, PeerDatabaseValue],
                 blacklistedPeers: Seq[InetAddress],
                 sparkzContext: SparkzContext): T
    }

    /**
      * Choose at most `howMany` random peers, which were connected to our peer and weren't blacklisted.
      *
      * Used in peer propagation: peers chosen are recommended to a peer asking our node about more peers.
      */
    case class SeenPeers(howMany: Int) extends GetPeers[Seq[PeerInfo]] {

      override def choose(peers: Map[InetSocketAddress, PeerDatabaseValue],
                          blacklistedPeers: Seq[InetAddress],
                          sparkzContext: SparkzContext): Seq[PeerInfo] = {
        val recentlySeenNonBlacklisted = peers.values.toSeq
          .filter { p =>
            (p.peerInfo.connectionType.isDefined || p.peerInfo.lastHandshake > 0) &&
              !blacklistedPeers.contains(p.address.getAddress)
          }
        Random.shuffle(recentlySeenNonBlacklisted).take(howMany).map(_.peerInfo)
      }
    }

    case object GetAllPeers extends GetPeers[Map[InetSocketAddress, PeerInfo]] {

      override def choose(peers: Map[InetSocketAddress, PeerDatabaseValue],
                          blacklistedPeers: Seq[InetAddress],
                          sparkzContext: SparkzContext): Map[InetSocketAddress, PeerInfo] = peers.map(p => p._1 -> p._2.peerInfo)
    }

    case class RandomPeerForConnectionExcluding(excludedPeers: Seq[Option[InetSocketAddress]]) extends GetPeers[Option[PeerInfo]] {
      private val secureRandom = new SecureRandom()

      override def choose(peers: Map[InetSocketAddress, PeerDatabaseValue],
                          blacklistedPeers: Seq[InetAddress],
                          sparkzContext: SparkzContext): Option[PeerInfo] = {
        var response: Option[PeerInfo] = None

        val highConfidencePeers = peers.filter(_._2.confidence == PeerConfidence.High)
        val highConfidenceCandidates = highConfidencePeers.values.filterNot(goodCandidateFilter(excludedPeers, blacklistedPeers, _)).toSeq

        if (highConfidenceCandidates.nonEmpty) {
          response = Some(highConfidenceCandidates(secureRandom.nextInt(highConfidenceCandidates.size)).peerInfo)
        } else {
          val candidates = peers.values.filterNot(goodCandidateFilter(excludedPeers, blacklistedPeers, _)).toSeq

          if (candidates.nonEmpty)
            response = Some(candidates(secureRandom.nextInt(candidates.size)).peerInfo)
        }

        response
      }
    }

    case class GetPeer(peerAddress: InetSocketAddress) extends GetPeers[Option[PeerDatabaseValue]] {
      override def choose(peers: Map[InetSocketAddress, PeerDatabaseValue],
                          blacklistedPeers: Seq[InetAddress],
                          sparkzContext: SparkzContext): Option[PeerDatabaseValue] = {
        peers.get(peerAddress)
      }
    }

    case class RandomPeerExcluding(excludedPeers: Seq[Option[InetSocketAddress]]) extends GetPeers[Option[PeerInfo]] {
      private val secureRandom = new SecureRandom()

      override def choose(peers: Map[InetSocketAddress, PeerDatabaseValue],
                          blacklistedPeers: Seq[InetAddress],
                          sparkzContext: SparkzContext): Option[PeerInfo] = {
        var response: Option[PeerInfo] = None

        val highConfidencePeers = peers.filter(_._2.confidence == PeerConfidence.High)
        val highConfidenceCandidates = highConfidencePeers.values.filterNot(goodCandidateFilter(excludedPeers, blacklistedPeers, _)).toSeq

        if (highConfidenceCandidates.nonEmpty) {
          response = Some(highConfidenceCandidates(secureRandom.nextInt(highConfidenceCandidates.size)).peerInfo)
        } else {
          val candidates = peers.values.filterNot(goodCandidateFilter(excludedPeers, blacklistedPeers, _)).toSeq

          if (candidates.nonEmpty)
            response = Some(candidates(secureRandom.nextInt(candidates.size)).peerInfo)
        }

        response
      }
    }

    case object GetBlacklistedPeers extends GetPeers[Seq[InetAddress]] {

      override def choose(peers: Map[InetSocketAddress, PeerDatabaseValue],
                          blacklistedPeers: Seq[InetAddress],
                          sparkzContext: SparkzContext): Seq[InetAddress] = blacklistedPeers
    }

  }

  private def goodCandidateFilter(excludedPeers: Seq[Option[InetSocketAddress]], blacklistedPeers: Seq[InetAddress], p: PeerDatabaseValue) = {
    excludedPeers.contains(p.peerInfo.peerSpec.address) ||
      blacklistedPeers.contains(p.address.getAddress)
  }
}

object PeerManagerRef {

  def props(settings: SparkzSettings, sparkzContext: SparkzContext, peerDatabase: PeerDatabase): Props = {
    Props(new PeerManager(settings, sparkzContext, peerDatabase))
  }

  def apply(settings: SparkzSettings, sparkzContext: SparkzContext, peerDatabase: PeerDatabase)
           (implicit system: ActorSystem): ActorRef = {
    system.actorOf(props(settings, sparkzContext, peerDatabase))
  }

  def apply(name: String, settings: SparkzSettings, sparkzContext: SparkzContext, peerDatabase: PeerDatabase)
           (implicit system: ActorSystem): ActorRef = {
    system.actorOf(props(settings, sparkzContext, peerDatabase), name)
  }

}
