package sparkz.core.network.peer

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import sparkz.core.app.SparkzContext
import sparkz.core.network._
import sparkz.core.settings.SparkzSettings
import sparkz.core.utils.NetworkUtils
import scorex.util.ScorexLogging

import java.net.{InetAddress, InetSocketAddress}
import scala.util.Random
import java.security.SecureRandom

/**
  * Peer manager takes care of peers connected and in process, and also chooses a random peer to connect
  * Must be singleton
  */
class PeerManager(settings: SparkzSettings, sparkzContext: SparkzContext, peerDatabase: PeerDatabase) extends Actor with ScorexLogging {

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
      if (!isSelf(peerInfo.peerSpec)) peerDatabase.addOrUpdateKnownPeer(peerInfo)

    case Penalize(peerAddress, penaltyType) =>
      log.info(s"$peerAddress penalized, penalty: $penaltyType")
      if (peerDatabase.peerPenaltyScoreOverThreshold(peerAddress, penaltyType)) {
        log.info(s"$peerAddress blacklisted")
        peerDatabase.addToBlacklist(peerAddress, penaltyType)
        sender() ! Blacklisted(peerAddress)
      }

    case AddPeersIfEmpty(peersSpec) =>
      // We have received peers data from other peers. It might be modified and should not affect existing data if any
      val filteredPeers = peersSpec
        .collect {
          case peerSpec if peerSpec.address.forall(a => peerDatabase.get(a).isEmpty) && !isSelf(peerSpec) =>
            val peerInfo: PeerInfo = PeerInfo(peerSpec, 0L, None)
            log.info(s"New discovered peer: $peerInfo")
            peerInfo
        }
      peerDatabase.addOrUpdateKnownPeers(filteredPeers)

    case RemovePeer(address) =>
      peerDatabase.remove(address)
      log.info(s"$address removed from peers database")

    case get: RandomPeerForConnectionExcluding =>
      sender() ! get.choose(peerDatabase.randomPeersSubset, peerDatabase.blacklistedPeers, sparkzContext)

    case get: GetPeers[_] =>
      sender() ! get.choose(peerDatabase.allPeers, peerDatabase.blacklistedPeers, sparkzContext)
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

    // peerListOperations messages
    /**
      * @param data : information about peer to be stored in PeerDatabase
      * */
    case class AddOrUpdatePeer(data: PeerInfo)

    case class AddPeersIfEmpty(data: Seq[PeerSpec])

    case class RemovePeer(address: InetSocketAddress)

    /**
      * Message to get peers from known peers map filtered by `choose` function
      */
    trait GetPeers[T] {
      def choose(peers: Map[InetSocketAddress, PeerInfo],
                 blacklistedPeers: Seq[InetAddress],
                 sparkzContext: SparkzContext): T
    }

    /**
      * Choose at most `howMany` random peers, which were connected to our peer and weren't blacklisted.
      *
      * Used in peer propagation: peers chosen are recommended to a peer asking our node about more peers.
      */
    case class SeenPeers(howMany: Int) extends GetPeers[Seq[PeerInfo]] {

      override def choose(peers: Map[InetSocketAddress, PeerInfo],
                          blacklistedPeers: Seq[InetAddress],
                          sparkzContext: SparkzContext): Seq[PeerInfo] = {
        val recentlySeenNonBlacklisted = peers.values.toSeq
          .filter { p =>
            (p.connectionType.isDefined || p.lastHandshake > 0) &&
              !blacklistedPeers.exists(ip => p.peerSpec.declaredAddress.exists(_.getAddress == ip))
          }
        Random.shuffle(recentlySeenNonBlacklisted).take(howMany)
      }
    }

    case object GetAllPeers extends GetPeers[Map[InetSocketAddress, PeerInfo]] {

      override def choose(peers: Map[InetSocketAddress, PeerInfo],
                          blacklistedPeers: Seq[InetAddress],
                          sparkzContext: SparkzContext): Map[InetSocketAddress, PeerInfo] = peers
    }

    case class RandomPeerForConnectionExcluding(excludedPeers: Seq[Option[InetSocketAddress]]) extends GetPeers[Option[PeerInfo]] {
      private val secureRandom = new SecureRandom()

      override def choose(peers: Map[InetSocketAddress, PeerInfo],
                          blacklistedPeers: Seq[InetAddress],
                          sparkzContext: SparkzContext): Option[PeerInfo] = {
        var response: Option[PeerInfo] = None


        val candidates = peers.values.filterNot { p =>
          excludedPeers.contains(p.peerSpec.address) ||
            blacklistedPeers.exists(addr => p.peerSpec.address.map(_.getAddress).contains(addr))
        }.toSeq

        if (candidates.nonEmpty)
          response = Some(candidates(secureRandom.nextInt(candidates.size)))

        response
      }
    }

    case object GetBlacklistedPeers extends GetPeers[Seq[InetAddress]] {

      override def choose(peers: Map[InetSocketAddress, PeerInfo],
                          blacklistedPeers: Seq[InetAddress],
                          sparkzContext: SparkzContext): Seq[InetAddress] = blacklistedPeers
    }

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
