package sparkz.core.network

import akka.actor.{ActorSystem, Props}
import akka.io.Tcp.Received
import akka.testkit.{TestActorRef, TestProbe}
import akka.util.ByteString
import org.mockito.Mockito
import org.mockito.MockitoSugar.{mock, when}
import sparkz.ObjectGenerators
import sparkz.core.app.{SparkzContext, Version}
import sparkz.core.network.NetworkController.ReceivableMessages.{Handshaked, PenalizePeer}
import sparkz.core.network.message._
import sparkz.core.network.peer.PenaltyType.DisconnectPenalty
import sparkz.core.settings.NetworkSettings
import sparkz.core.utils.{FakeSpec, LocalTimeProvider}

import java.net.InetSocketAddress
import scala.concurrent.ExecutionContext

class PeerConnectionHandlerSpecification extends NetworkTests with ObjectGenerators {
  private implicit val executionContext: ExecutionContext = mock[ExecutionContext]
  private implicit val system: ActorSystem = ActorSystem()
  private val networkSettings = settings.network
  private val networkController = TestProbe("networkController")
  private val peerManager = TestProbe("peerManager")
  private val connection = TestProbe("connection")
  private val sparkzContext = Mockito.mock(classOf[SparkzContext])
  private val socketAddress = new InetSocketAddress("localhost", 1234)
  private val connectionId = ConnectionId(socketAddress, socketAddress, Outgoing)
  private val connectionDescription = ConnectionDescription(connection.ref, connectionId, None, Seq())
  private val featSerializers = Map(FullNodePeerFeature.featureId -> FullNodePeerFeature.serializer)
  private val handshakeSerializer = new HandshakeSpec(featSerializers, networkSettings.maxHandshakeSize)

  "PeerConnectionHandler" should "penalize and disconnect to peer if message handler does not exist" in {
    // Arrange
    val specs = Seq(new FakeSpec)
    val messageSerializer = new MessageSerializer(specs, settings.network.magicBytes, settings.network.messageLengthBytesLimit)
    val timeProvider = LocalTimeProvider

    when(sparkzContext.messageSpecs) thenReturn Seq(GetPeersSpec)
    when(sparkzContext.timeProvider) thenReturn timeProvider

    val peerConnectionHandlerRef: TestActorRef[PeerConnectionHandler] = createPeerConnectionHandlerAndHandshakeAndExpectHandshakedMessage(
      networkSettings, networkController, peerManager, sparkzContext, socketAddress, connectionDescription, handshakeSerializer
    )
    val dataBytes = messageSerializer.serialize(Message(new FakeSpec, Left(Array(1, 2, 3)), None))

    // Act
    peerConnectionHandlerRef ! Received(dataBytes)

    // Assert
    networkController.expectMsg(PenalizePeer(socketAddress, DisconnectPenalty(networkSettings)))
  }

  it should "penalize and disconnect to peer if magic bytes does not match" in {
    // Arrange
    val badMagicBytes: Array[Byte] = Array(1, 2, 3, 4)
    val specs = Seq(GetPeersSpec)
    val messageSerializer = new MessageSerializer(specs, badMagicBytes, settings.network.messageLengthBytesLimit)
    val timeProvider = LocalTimeProvider

    when(sparkzContext.messageSpecs) thenReturn Seq(GetPeersSpec)
    when(sparkzContext.timeProvider) thenReturn timeProvider

    val peerConnectionHandlerRef: TestActorRef[PeerConnectionHandler] = createPeerConnectionHandlerAndHandshakeAndExpectHandshakedMessage(
      networkSettings, networkController, peerManager, sparkzContext, socketAddress, connectionDescription, handshakeSerializer
    )
    val dataBytes = messageSerializer.serialize(Message(GetPeersSpec, Left(Array(1, 2, 3)), None))

    // Act
    peerConnectionHandlerRef ! Received(dataBytes)

    // Assert
    networkController.expectMsg(PenalizePeer(socketAddress, DisconnectPenalty(networkSettings)))
  }

  private def createPeerConnectionHandlerAndHandshakeAndExpectHandshakedMessage(
                                                                                 networkSettings: NetworkSettings,
                                                                                 networkController: TestProbe,
                                                                                 peerManager: TestProbe,
                                                                                 sparkzContext: SparkzContext,
                                                                                 socketAddress: InetSocketAddress,
                                                                                 connectionDescription: ConnectionDescription,
                                                                                 handshakeSerializer: HandshakeSpec
                                                                               ): TestActorRef[PeerConnectionHandler] = {
    val peerConnectionHandlerRef: TestActorRef[PeerConnectionHandler] = TestActorRef(
      Props(new PeerConnectionHandler(networkSettings, networkController.ref, peerManager.ref, sparkzContext, connectionDescription))
    )
    val fakeHandshakeBytes = handshakeSerializer.toBytes(Handshake(
      PeerSpec(
        "fakeName",
        Version("0.0.1"),
        "fakeName",
        Some(socketAddress),
        Seq()
      ),
      sparkzContext.timeProvider.time()
    ))
    peerConnectionHandlerRef ! Received(ByteString(fakeHandshakeBytes))

    networkController.expectMsgClass(classOf[Handshaked])

    peerConnectionHandlerRef
  }
}
