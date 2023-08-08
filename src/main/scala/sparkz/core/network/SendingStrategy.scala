package sparkz.core.network

import sparkz.core.network.peer.TransactionsDisabledPeerFeature
import sparkz.core.settings.NetworkSettings

import java.security.SecureRandom
import scala.util.Random

trait SendingStrategy {
  val secureRandom = new SecureRandom()
  def choose(peers: Seq[ConnectedPeer]): Seq[ConnectedPeer]
}

object SendToRandom extends SendingStrategy {
  override def choose(peers: Seq[ConnectedPeer]): Seq[ConnectedPeer] = {
    if (peers.nonEmpty) {
      Seq(peers(secureRandom.nextInt(peers.length)))
    } else {
      Seq.empty
    }
  }
}

case object Broadcast extends SendingStrategy {
  override def choose(peers: Seq[ConnectedPeer]): Seq[ConnectedPeer] = peers
}


case object BroadcastTransaction extends SendingStrategy {
  override def choose(peers: Seq[ConnectedPeer]): Seq[ConnectedPeer] = {
    peers.filter(p => p.peerInfo.flatMap{
      info =>  info.peerSpec.features.collectFirst {case f:TransactionsDisabledPeerFeature => true}}.isEmpty)
  }
}

case class BroadcastBlock(settings: NetworkSettings) extends SendingStrategy {
  val maxBlockBroadcastPeers: Int = settings.maxForgerConnections +
    (settings.maxOutgoingConnections + settings.maxIncomingConnections) / 2

  override def choose(peers: Seq[ConnectedPeer]): Seq[ConnectedPeer] = {
    val forgerPeers = peers.filter(_.peerInfo.exists(_.peerSpec.forgerPeer))
    val remainingPeers = peers.filter(_.peerInfo.exists(!_.peerSpec.forgerPeer))

    forgerPeers ++ Random.shuffle(remainingPeers).take(maxBlockBroadcastPeers - forgerPeers.length)
  }
}

case class BroadcastExceptOf(exceptOf: Seq[ConnectedPeer]) extends SendingStrategy {
  override def choose(peers: Seq[ConnectedPeer]): Seq[ConnectedPeer] =
    peers.filterNot(exceptOf.contains)
}

case class SendToPeer(chosenPeer: ConnectedPeer) extends SendingStrategy {
  override def choose(peers: Seq[ConnectedPeer]): Seq[ConnectedPeer] = Seq(chosenPeer)
}

case class SendToPeers(chosenPeers: Seq[ConnectedPeer]) extends SendingStrategy {
  override def choose(peers: Seq[ConnectedPeer]): Seq[ConnectedPeer] = chosenPeers
}

case class SendToRandomFromChosen(chosenPeers: Seq[ConnectedPeer]) extends SendingStrategy {
  override def choose(peers: Seq[ConnectedPeer]): Seq[ConnectedPeer] =
    Seq(chosenPeers(secureRandom.nextInt(chosenPeers.length)))
}
