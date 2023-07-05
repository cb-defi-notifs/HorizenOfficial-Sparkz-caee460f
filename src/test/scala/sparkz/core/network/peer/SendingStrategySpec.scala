package sparkz.core.network.peer

import org.scalatest.BeforeAndAfter
import sparkz.ObjectGenerators
import sparkz.core.network._

import java.net.InetSocketAddress

@SuppressWarnings(Array(
  "org.wartremover.warts.Null",
  "org.wartremover.warts.OptionPartial"
))
class SendingStrategySpec extends NetworkTests with ObjectGenerators with BeforeAndAfter {

  private val peerAddress1 = new InetSocketAddress("1.1.1.1", 27017)
  private val peerAddress2 = new InetSocketAddress("2.2.2.2", 27017)
  private val peerAddress3 = new InetSocketAddress("3.3.3.3", 27017)
  private val peerAddress4 = new InetSocketAddress("4.4.4.4", 27017)


  "BroadcastTransaction" should "return empty list if input list is empty" in {
    BroadcastTransaction.choose(Seq.empty) shouldBe Seq.empty
  }

  it should "filter out peers with TransactionsDisabledPeerFeature" in {

    val peer1: ConnectedPeer = ConnectedPeer(ConnectionId(peerAddress1, peerAddress2, Outgoing),null, 1L, None)
    val peer2: ConnectedPeer = ConnectedPeer(ConnectionId(peerAddress3, peerAddress1, Incoming),null, 1L, Some(getPeerInfo(peerAddress1,None,Seq.empty )))
    val peer3: ConnectedPeer = ConnectedPeer(ConnectionId(peerAddress2, peerAddress1, Incoming),null, 1L, Some(getPeerInfo(peerAddress1,None,
      List[PeerFeature](LocalAddressPeerFeature(peerAddress1),TransactionsDisabledPeerFeature()))))
    val peer4: ConnectedPeer = ConnectedPeer(ConnectionId(peerAddress1, peerAddress4, Outgoing), null, 1L, Some(getPeerInfo(peerAddress1, None,
      List(LocalAddressPeerFeature(peerAddress1)))))

    val listOfPeers = List(peer1,peer2,peer3,peer4)
    val result = BroadcastTransaction.choose(listOfPeers)
    result.length shouldBe 3
    result.contains(peer1) shouldBe true
    result.contains(peer2) shouldBe true
    result.contains(peer4) shouldBe true
    result.contains(peer3) shouldBe false
  }

  "Broadcast" should "not filter out peers with TransactionsDisabledPeerFeature" in {

    val peer1: ConnectedPeer = ConnectedPeer(ConnectionId(peerAddress1, peerAddress2, Outgoing), null, 1L, None)
    val peer2: ConnectedPeer = ConnectedPeer(ConnectionId(peerAddress3, peerAddress1, Incoming), null, 1L,
      Some(getPeerInfo(peerAddress1, None, Seq.empty)))
    val peer3: ConnectedPeer = ConnectedPeer(ConnectionId(peerAddress2, peerAddress1, Incoming), null, 1L,
      Some(getPeerInfo(peerAddress1, None,List[PeerFeature](LocalAddressPeerFeature(peerAddress1), TransactionsDisabledPeerFeature()))))
    val peer4: ConnectedPeer = ConnectedPeer(ConnectionId(peerAddress1, peerAddress4, Outgoing), null, 1L,
      Some(getPeerInfo(peerAddress1, None,List(LocalAddressPeerFeature(peerAddress1)))))

    val listOfPeers = List(peer1, peer2, peer3, peer4)
    val result = Broadcast.choose(listOfPeers)
    result.length shouldBe 4
    result.contains(peer1) shouldBe true
    result.contains(peer2) shouldBe true
    result.contains(peer3) shouldBe true
    result.contains(peer4) shouldBe true
  }

}