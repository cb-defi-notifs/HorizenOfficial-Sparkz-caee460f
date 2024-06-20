package sparkz.core.api.http

import akka.actor.{ActorRef, ActorRefFactory}
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import io.circe.generic.auto.exportDecoder
import io.circe.generic.semiauto._
import io.circe.syntax._
import io.circe.{Decoder, Encoder, Json}
import sparkz.core.api.http.PeersApiRoute.PeerApiRequest.AddToBlacklistBodyRequest
import sparkz.core.api.http.PeersApiRoute.Request.AddressBodyRequest
import sparkz.core.api.http.PeersApiRoute.{BlacklistedPeers, PeerInfoResponse, PeersStatusResponse}
import sparkz.core.network.ConnectedPeer
import sparkz.core.network.NetworkController.ReceivableMessages.{ConnectTo, DisconnectFromNode, GetConnectedPeers, GetPeersStatus}
import sparkz.core.network.peer.PeerManager.ReceivableMessages._
import sparkz.core.network.peer.PenaltyType.CustomPenaltyDuration
import sparkz.core.network.peer.{PeerInfo, PeersStatus}
import sparkz.core.settings.RESTApiSettings
import sparkz.core.utils.NetworkTimeProvider

import java.net.{InetAddress, InetSocketAddress}
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}

case class PeersApiRoute(peerManager: ActorRef,
                         networkController: ActorRef,
                         timeProvider: NetworkTimeProvider,
                         override val settings: RESTApiSettings)
                        (implicit val context: ActorRefFactory, val ec: ExecutionContext) extends ApiRoute {

  override lazy val route: Route = pathPrefix("peers") {
    allPeers ~ peerByAddress ~
      connectedPeers ~ peersStatus ~
      blacklistedPeers ~ addToBlacklist ~ removeFromBlacklist ~
      removePeer ~ connect
  }

  private val addressAndPortRegexp = "([\\w\\.]+):(\\d{1,5})".r

  /////////////////////
  // READ OPERATIONS //
  /////////////////////

  def allPeers: Route = (path("all") & get & withBasicAuth) {
    _ => {
      val result = askActor[Map[InetSocketAddress, PeerInfo]](peerManager, GetAllPeers).map {
        _.map { case (address, peerInfo) =>
          PeerInfoResponse.fromAddressAndInfo(address, peerInfo)
        }
      }
      ApiResponse(result)
    }
  }

  def peerByAddress: Route = (path("peer" / Remaining) & get) { addressParam =>
    val maybeAddress = addressAndPortRegexp.findFirstMatchIn(addressParam)
    maybeAddress match {
      case None => ApiError(StatusCodes.BadRequest, s"address $maybeAddress is not well formatted")

      case Some(addressAndPort) =>
        val host = InetAddress.getByName(addressAndPort.group(1))
        val port = addressAndPort.group(2).toInt

        val address = new InetSocketAddress(host, port)
        val result = askActor[Option[PeerInfo]](peerManager, GetPeer(address)).map {
          _.map(peerInfo =>
            PeerInfoResponse.fromAddressAndInfo(address, peerInfo)
          )
        }
        ApiResponse(result)
    }
  }

  def connectedPeers: Route = (path("connected") & get & withBasicAuth) {
    _ => {
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


  def blacklistedPeers: Route = (path("blacklist") & get & withBasicAuth) {
    _ => {
      val result = askActor[Seq[InetAddress]](peerManager, GetBlacklistedPeers)
        .map(x => BlacklistedPeers(x.map(_.toString)).asJson)
      ApiResponse(result)
    }
  }

  //////////////////////
  // WRITE OPERATIONS //
  //////////////////////

  def connect: Route = (path("connect") & post & withBasicAuth) {
    _ => {
      entity(as[AddressBodyRequest]) { bodyRequest =>
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
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.PublicInference"))
  def addToBlacklist: Route = (post & path("blacklist") & withBasicAuth) {
    _ => {
      entity(as[AddToBlacklistBodyRequest]) { bodyRequest =>
        val peerAddress = bodyRequest.address
        val banDuration = bodyRequest.durationInMinutes

        if (banDuration <= 0) {
          ApiError(StatusCodes.BadRequest, s"duration must be greater than 0; $banDuration not allowed")
        } else {
          addressAndPortRegexp.findFirstMatchIn(peerAddress) match {
            case None => ApiError(StatusCodes.BadRequest, s"address $peerAddress is not well formatted")

            case Some(addressAndPort) =>
              val host = InetAddress.getByName(addressAndPort.group(1))
              val port = addressAndPort.group(2).toInt
              val peerAddress = new InetSocketAddress(host, port)
              peerManager ! AddToBlacklist(peerAddress, Some(CustomPenaltyDuration(banDuration)))
              networkController ! DisconnectFromAddress(peerAddress)
              ApiResponse.OK
          }
        }
      }
    }
  }

  def removePeer: Route = (path("peer") & delete & withBasicAuth) {
    _ => {
      entity(as[Json]) { json =>
        val maybeAddress = json.asString.flatMap(addressAndPortRegexp.findFirstMatchIn)

        maybeAddress match {
          case None => ApiError(StatusCodes.BadRequest, s"address $maybeAddress is not well formatted")

          case Some(addressAndPort) =>
            val host = InetAddress.getByName(addressAndPort.group(1))
            val port = addressAndPort.group(2).toInt
            val peerAddress = new InetSocketAddress(host, port)
            peerManager ! RemovePeer(peerAddress)
            networkController ! DisconnectFromNode(peerAddress)
            ApiResponse.OK
        }
      }
    }
  }

  def removeFromBlacklist: Route = (path("blacklist") & delete & withBasicAuth) {
    _ => {
      entity(as[AddressBodyRequest]) { bodyRequest =>
        val peerAddress = bodyRequest.address
        Try(InetAddress.getByName(peerAddress)) match {
          case Failure(exception) =>
            ApiError(StatusCodes.BadRequest, s"address $peerAddress is not well formatted: ${exception.getMessage}")

          case Success(address) =>
            peerManager ! RemoveFromBlacklist(address)
            ApiResponse.OK
        }
      }
    }
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
    case class AddressBodyRequest(address: String)
  }

  object PeerApiRequest {
    private val DEFAULT_BAN_DURATION: Long = 60

    case class AddToBlacklistBodyRequest(address: String, durationInMinutes: Long = DEFAULT_BAN_DURATION)
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
  implicit val decodeConnectBodyRequest: Decoder[AddressBodyRequest] = deriveDecoder
}

