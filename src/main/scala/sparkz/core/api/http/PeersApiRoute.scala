package sparkz.core.api.http

import akka.actor.{ActorRef, ActorRefFactory}
import akka.http.scaladsl.server.Route
import io.circe.generic.semiauto._
import io.circe.syntax._
import io.circe.{Decoder, Encoder}
import sparkz.core.api.http.PeersApiRoute.Request.ConnectBodyRequest
import sparkz.core.api.http.PeersApiRoute.{BlacklistedPeers, PeerInfoResponse, PeersStatusResponse}
import sparkz.core.network.ConnectedPeer
import sparkz.core.network.NetworkController.ReceivableMessages.{ConnectTo, GetConnectedPeers, GetPeersStatus}
import sparkz.core.network.peer.PeerManager.ReceivableMessages.{AddPeersIfEmpty, GetAllPeers, GetBlacklistedPeers}
import sparkz.core.network.peer.{PeerInfo, PeersStatus}
import sparkz.core.settings.RESTApiSettings
import sparkz.core.utils.NetworkTimeProvider

import java.net.{InetAddress, InetSocketAddress}
import scala.concurrent.ExecutionContext

case class PeersApiRoute(peerManager: ActorRef,
                         networkController: ActorRef,
                         timeProvider: NetworkTimeProvider,
                         override val settings: RESTApiSettings)
                        (implicit val context: ActorRefFactory, val ec: ExecutionContext) extends ApiRoute {

  override lazy val route: Route = pathPrefix("peers") {
    allPeers ~ connectedPeers ~ blacklistedPeers ~ connect ~ peersStatus
  }

  def allPeers: Route = (path("all") & get) {
    val result = askActor[Map[InetSocketAddress, PeerInfo]](peerManager, GetAllPeers).map {
      _.map { case (address, peerInfo) =>
        PeerInfoResponse.fromAddressAndInfo(address, peerInfo)
      }
    }
    ApiResponse(result)
  }

  def connectedPeers: Route = (path("connected") & get) {
    val result = askActor[Seq[ConnectedPeer]](networkController, GetConnectedPeers).map {
      _.flatMap { con =>
        con.peerInfo.map { peerInfo =>
          PeerInfoResponse(
            address = peerInfo.peerSpec.declaredAddress.map(_.toString).getOrElse(""),
            lastMessage = con.lastMessage,
            lastHandshake = peerInfo.lastHandshake,
            name = peerInfo.peerSpec.nodeName,
            connectionType = peerInfo.connectionType.map(_.toString)
          )
        }
      }
    }
    ApiResponse(result)
  }

  /**
    * Get status of P2P layer
    *
    * @return time of last incoming message and network time (got from NTP server)
    */
  def peersStatus: Route = (path("status") & get) {
    val result = askActor[PeersStatus](networkController, GetPeersStatus).map {
      case PeersStatus(lastIncomingMessage, currentNetworkTime) =>
        PeersStatusResponse(lastIncomingMessage, currentNetworkTime)
    }
    ApiResponse(result)
  }

  private val addressAndPortRegexp = "([\\w\\.]+):(\\d{1,5})".r

  def connect: Route = (path("connect") & post & withAuth & entity(as[ConnectBodyRequest])) { bodyRequest =>
    val peerAddress = bodyRequest.address

    val maybeAddress = addressAndPortRegexp.findFirstMatchIn(peerAddress)
    maybeAddress match {
      case None => ApiError.BadRequest

      case Some(addressAndPort) =>
        val host = InetAddress.getByName(addressAndPort.group(1))
        val port = addressAndPort.group(2).toInt
        val address = new InetSocketAddress(host, port)
        val peerInfo = PeerInfo.fromAddress(address)

        peerManager ! AddPeersIfEmpty(Seq(peerInfo.peerSpec))
        networkController ! ConnectTo(peerInfo)

        ApiResponse.OK
    }
  }

  def blacklistedPeers: Route = (path("blacklisted") & get) {
    val result = askActor[Seq[InetAddress]](peerManager, GetBlacklistedPeers)
      .map(x => BlacklistedPeers(x.map(_.toString)).asJson)
    ApiResponse(result)
  }

}

object PeersApiRoute {

  case class PeerInfoResponse(address: String,
                              lastMessage: Long,
                              lastHandshake: Long,
                              name: String,
                              connectionType: Option[String])

  object PeerInfoResponse {

    def fromAddressAndInfo(address: InetSocketAddress, peerInfo: PeerInfo): PeerInfoResponse = PeerInfoResponse(
      address.toString,
      0,
      peerInfo.lastHandshake,
      peerInfo.peerSpec.nodeName,
      peerInfo.connectionType.map(_.toString)
    )
  }

  object Request {
    case class ConnectBodyRequest(address: String)
  }

  case class PeersStatusResponse(lastIncomingMessage: Long, currentSystemTime: Long)

  case class BlacklistedPeers(addresses: Seq[String])

  @SuppressWarnings(Array("org.wartremover.warts.PublicInference"))
  implicit val encodePeerInfoResponse: Encoder[PeerInfoResponse] = deriveEncoder

  @SuppressWarnings(Array("org.wartremover.warts.PublicInference"))
  implicit val encodeBlackListedPeers: Encoder[BlacklistedPeers] = deriveEncoder

  @SuppressWarnings(Array("org.wartremover.warts.PublicInference"))
  implicit val encodePeersStatusResponse: Encoder[PeersStatusResponse] = deriveEncoder

  @SuppressWarnings(Array("org.wartremover.warts.PublicInference"))
  implicit val decodeConnectBodyRequest: Decoder[ConnectBodyRequest] = deriveDecoder
}

