package sparkz.core.api.http

import java.net.{InetAddress, InetSocketAddress}
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import sparkz.core.app.Version
import sparkz.core.network.NetworkController.ReceivableMessages.GetConnectedPeers
import sparkz.core.network.peer.PeerInfo
import sparkz.core.network._
import sparkz.core.network.peer.PeerManager.ReceivableMessages.GetPeer

trait Stubs {

  implicit val system: ActorSystem

  private val inetAddr1 = new InetSocketAddress("92.92.92.92", 27017)
  private val inetAddr2 = new InetSocketAddress("93.93.93.93", 27017)
  private val ts1 = System.currentTimeMillis() - 100
  private val ts2 = System.currentTimeMillis() + 100
  private val version: Version = Version.initial

  private val peerFeatures = Seq()

  val peers: Map[InetSocketAddress, PeerInfo] = Map(
    inetAddr1 -> PeerInfo(PeerSpec("app", version, "first", Some(inetAddr1), peerFeatures), ts1, Some(Incoming)),
    inetAddr2 -> PeerInfo(PeerSpec("app", version, "second", Some(inetAddr2), peerFeatures), ts1, Some(Outgoing))
  )

  def createPeerOption(address: InetSocketAddress): Option[PeerInfo] =
    Some(
      PeerInfo(PeerSpec("app", version, "first", Some(address), peerFeatures), ts1, Some(Incoming))
    )

  val protocolVersion: Version = Version("1.1.1")

  val connectedPeers: Seq[Handshake] = Seq(
    Handshake(PeerSpec("node_pop", protocolVersion, "first", Some(inetAddr1), peerFeatures), ts1),
    Handshake(PeerSpec("node_pop", protocolVersion, "second", Some(inetAddr2), peerFeatures), ts2)
  )

  val emptyPeers: Seq[ConnectedPeer] = Seq()

  val blacklistedPeers: Seq[InetAddress] = Seq(InetAddress.getByName("4.4.4.4"), InetAddress.getByName("8.8.8.8"))

  class PeersManagerStub extends Actor {

    import sparkz.core.network.peer.PeerManager.ReceivableMessages.{GetAllPeers, GetBlacklistedPeers}

    def receive: PartialFunction[Any, Unit] = {
      // case GetConnectedPeers => sender() ! connectedPeers
      case GetAllPeers => sender() ! peers
      case GetPeer(address) => sender() ! createPeerOption(address)
      case GetBlacklistedPeers => sender() ! blacklistedPeers
    }
  }

  object PeersManagerStubRef {
    def props(): Props = Props(new PeersManagerStub)

    def apply()(implicit system: ActorSystem): ActorRef = system.actorOf(props())

    def apply(name: String)(implicit system: ActorSystem): ActorRef = system.actorOf(props(), name)
  }

  class NetworkControllerStub extends Actor {
    def receive: PartialFunction[Any, Unit] = {
      case GetConnectedPeers => sender() ! emptyPeers
      case _ => ()
    }
  }

  object NetworkControllerStubRef {
    def props(): Props = Props(new NetworkControllerStub)

    def apply()(implicit system: ActorSystem): ActorRef = system.actorOf(props())

    def apply(name: String)(implicit system: ActorSystem): ActorRef = system.actorOf(props(), name)
  }

  lazy val pmRef: ActorRef = PeersManagerStubRef()
  lazy val networkControllerRef: ActorRef = NetworkControllerStubRef()

}
