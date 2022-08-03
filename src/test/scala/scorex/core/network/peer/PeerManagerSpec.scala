package scorex.core.network.peer

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.TestProbe
import scorex.core.app.ScorexContext
import scorex.core.network.peer.PeerManager.ReceivableMessages.RemovePeer
import scorex.network.NetworkTests

import java.net.InetSocketAddress

class PeerManagerSpec extends NetworkTests {

  import scorex.core.network.peer.PeerManager.ReceivableMessages.{AddOrUpdatePeer, GetAllPeers}

  type Data = Map[InetSocketAddress, PeerInfo]
  private val DefaultPort = 27017

  it should "ignore adding self as a peer" in {
    implicit val system: ActorSystem = ActorSystem()
    val p = TestProbe("p")(system)
    implicit val defaultSender: ActorRef = p.testActor


    val selfAddress = settings.network.bindAddress
    val scorexContext = ScorexContext(Seq.empty, Seq.empty, None, timeProvider, Some(selfAddress))
    val peerManager = PeerManagerRef(settings, scorexContext)(system)
    val peerInfo = getPeerInfo(selfAddress)

    peerManager ! AddOrUpdatePeer(peerInfo)
    peerManager ! GetAllPeers
    val data = p.expectMsgClass(classOf[Data])

    data.keySet should not contain selfAddress
    system.terminate()
  }

  it should "added peer be returned in GetAllPeers" in {
    implicit val system: ActorSystem = ActorSystem()
    val p = TestProbe("p")(system)
    implicit val defaultSender: ActorRef = p.testActor

    val scorexContext = ScorexContext(Seq.empty, Seq.empty, None, timeProvider, None)
    val peerManager = PeerManagerRef(settings, scorexContext)(system)
    val peerAddress = new InetSocketAddress("1.1.1.1", DefaultPort)
    val peerInfo = getPeerInfo(peerAddress)

    peerManager ! AddOrUpdatePeer(peerInfo)
    peerManager ! GetAllPeers

    val data = p.expectMsgClass(classOf[Data])
    data.keySet should contain(peerAddress)
    system.terminate()
  }

  it should "not remove a known peer, but remove a normal peer" in {
    val knownPeerAddress = new InetSocketAddress("127.0.0.1", DefaultPort)
    val settingsWithKnownPeer = settings.copy(network = settings.network.copy(knownPeers = Seq(knownPeerAddress)))

    implicit val system: ActorSystem = ActorSystem()
    val p = TestProbe("p")(system)
    implicit val defaultSender: ActorRef = p.testActor

    val scorexContext = ScorexContext(Seq.empty, Seq.empty, None, timeProvider, None)
    val peerManager = PeerManagerRef(settingsWithKnownPeer, scorexContext)(system)

    val peerAddress = new InetSocketAddress("1.1.1.1", DefaultPort)
    val peerInfo = getPeerInfo(peerAddress)

    peerManager ! AddOrUpdatePeer(peerInfo)

    peerManager ! RemovePeer(peerAddress)
    peerManager ! RemovePeer(knownPeerAddress)
    peerManager ! GetAllPeers

    val data = p.expectMsgClass(classOf[Data])
    data.keySet should contain(knownPeerAddress)
    data.keySet should not contain peerAddress
    system.terminate()
  }

}
