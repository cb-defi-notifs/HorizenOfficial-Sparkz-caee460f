package sparkz.core.api.http

import akka.http.javadsl.model.headers.HttpCredentials
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.{RouteTestTimeout, ScalatestRouteTest}
import akka.testkit.{TestDuration, TestProbe}
import io.circe.Json
import io.circe.syntax._
import org.mindrot.jbcrypt.BCrypt
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sparkz.core.api.http.PeersApiRoute.PeerInfoResponse
import sparkz.core.network.peer.PeerInfo
import sparkz.core.network.peer.PeerManager.ReceivableMessages.{AddToBlacklist, DisconnectFromAddress, RemoveFromBlacklist, RemovePeer}
import sparkz.core.settings.{RESTApiSettings, SparkzSettings}
import sparkz.core.utils.NetworkTimeProvider

import java.net.InetSocketAddress
import scala.concurrent.duration._
import scala.language.postfixOps

class PeersApiRouteSpec extends AnyFlatSpec
  with Matchers
  with ScalatestRouteTest
  with Stubs {

  implicit val timeout: RouteTestTimeout = RouteTestTimeout(15.seconds dilated)

  private val addr = new InetSocketAddress("127.0.0.1", 8080)
  private val restApiSettings = RESTApiSettings(addr, None, None, 10 seconds)
  private val prefix = "/peers"
  private val settings = SparkzSettings.read(None)
  private val timeProvider = new NetworkTimeProvider(settings.ntp)

  private val credentials = HttpCredentials.createBasicHttpCredentials("username","password")
  private val badCredentials = HttpCredentials.createBasicHttpCredentials("username","wrong_password")
  private val body = HttpEntity("localhost:8080".asJson.toString).withContentType(ContentTypes.`application/json`)
  private val badBody = HttpEntity("badBodyContent".asJson.toString).withContentType(ContentTypes.`application/json`)

  private val restApiSettingsWithApiKey = RESTApiSettings(addr, Some(BCrypt.hashpw(credentials.password(), BCrypt.gensalt())), None, 10 seconds)
  private val routes = PeersApiRoute(pmRef, networkControllerRef, timeProvider, restApiSettings).route
  private val routesWithApiKey = PeersApiRoute(pmRef, networkControllerRef, timeProvider, restApiSettingsWithApiKey).route

  val peersResp: String = peers.map { case (address, peerInfo) =>
    PeerInfoResponse.fromAddressAndInfo(address, peerInfo).asJson
  }.asJson.toString

  val peer: Option[PeerInfo] = createPeerOption(addr)
  val peerResp: String = PeerInfoResponse.fromAddressAndInfo(
    addr,
    peer.getOrElse(throw new IllegalArgumentException())
  ).asJson.toString

  val connectedPeersResp: Json = connectedPeers.map { handshake =>
    Map(
      "address" -> handshake.peerSpec.declaredAddress.toString.asJson,
      "name" -> handshake.peerSpec.nodeName.asJson,
      "lastSeen" -> handshake.time.asJson
    ).asJson
  }.asJson

  it should "get all peers" in {
    Get(prefix + "/all") ~> routes ~> check {
      status shouldBe StatusCodes.OK
      peersResp shouldBe responseAs[String]
    }
  }

  it should "get all peers with Basich Auth" in {
    Get(prefix + "/all").addCredentials(credentials) ~> routesWithApiKey ~> check {
      status shouldBe StatusCodes.OK
      peersResp shouldBe responseAs[String]
    }
  }

  it should "get the specified peer" in {
    Get(prefix + "/peer/" + addr.toString).addCredentials(credentials) ~> routesWithApiKey ~> check {
      status shouldBe StatusCodes.OK
      peerResp shouldBe responseAs[String]
    }
  }

  it should "get all peer with wrong Basich Auth" in {
    Get(prefix + "/all").addCredentials(badCredentials) ~> Route.seal(routesWithApiKey) ~> check {
      status.intValue() shouldBe StatusCodes.Unauthorized.intValue
    }
  }

  it should "get connected peers" in {
    Get(prefix + "/connected") ~> Route.seal(routes) ~> check {
      status shouldBe StatusCodes.OK
    }
  }

  //can't check it cause original node using now() timestamp for last seen field.
  //We can check only that the authorization passed
  it should "get connected peers with Basich Auth" in {
    Get(prefix + "/connected").addCredentials(credentials) ~> routesWithApiKey ~> check {
      status shouldBe StatusCodes.OK
    }
  }

  it should "get connected peers with wrong Basich Auth" in {
    Get(prefix + "/connected").addCredentials(badCredentials) ~> Route.seal(routesWithApiKey) ~> check {
      status.intValue() shouldBe StatusCodes.Unauthorized.intValue
    }
  }

  it should "connect to peer" in {
    val body = HttpEntity("""{"address": "localhost:8080"}""").withContentType(ContentTypes.`application/json`)
    Post(prefix + "/connect", body) ~> routes ~> check {
      status shouldBe StatusCodes.OK
    }
  }

  it should "connect to peer with Basich Auth" in {
    val body = HttpEntity("""{"address": "localhost:8080"}""").withContentType(ContentTypes.`application/json`)
    Post(prefix + "/connect", body).addCredentials(credentials) ~> routesWithApiKey ~> check {
      status shouldBe StatusCodes.OK
    }
  }

  it should "not connect to peer with wrong Basich Auth" in {
    val body = HttpEntity("""{"address": "localhost:8080"}""").withContentType(ContentTypes.`application/json`)
    Post(prefix + "/connect", body).addCredentials(badCredentials) ~> Route.seal(routesWithApiKey) ~> check {
      status.intValue() shouldBe StatusCodes.Unauthorized.intValue
    }
  }

  it should "get blacklisted peers" in {
    Get(prefix + "/blacklist").addCredentials(credentials) ~> routesWithApiKey ~> check {
      status shouldBe StatusCodes.OK
      Map("addresses" -> blacklistedPeers.map(_.toString)).asJson.toString shouldBe responseAs[String]
    }
  }

  it should "respond ok when add or delete blacklisted peer request is correct" in {
    val networkControllerProbe = TestProbe("networkController")
    val peerManagerProbe = TestProbe("peerManagerProbe")
    val routesWithProbes = PeersApiRoute(peerManagerProbe.ref, networkControllerProbe.ref, timeProvider, restApiSettingsWithApiKey).route

    val bodyRequest = HttpEntity(
      """{"address": "127.0.0.1:8080", "durationInMinutes": 40}"""
    ).withContentType(ContentTypes.`application/json`)

    Post(prefix + "/blacklist", bodyRequest).addCredentials(credentials) ~> routesWithProbes ~> check {
      peerManagerProbe.expectMsgClass(classOf[AddToBlacklist])
      networkControllerProbe.expectMsgClass(classOf[DisconnectFromAddress])

      status shouldBe StatusCodes.OK
    }

    Delete(prefix + "/blacklist", body).addCredentials(credentials) ~> routesWithProbes ~> check {
      peerManagerProbe.expectMsgClass(classOf[RemoveFromBlacklist])

      status shouldBe StatusCodes.OK
    }
  }

  it should "response bad request if blacklist body content is not well formed" in {
    val bodyRequestInvalidAddress = HttpEntity(
      """{"address": "badAddress", "durationInMinutes": 40}"""
    ).withContentType(ContentTypes.`application/json`)

    Post(prefix + "/blacklist", bodyRequestInvalidAddress).addCredentials(credentials) ~> routesWithApiKey ~> check {
      status shouldBe StatusCodes.BadRequest
    }

    val invalidDurations = Seq(0, -1)
    invalidDurations.foreach(duration => {
      val bodyRequestInvalidBanDuration = HttpEntity(
        s"""{"address": "127.0.0.1:8080", "durationInMinutes": $duration}"""
      ).withContentType(ContentTypes.`application/json`)

      Post(prefix + "/blacklist", bodyRequestInvalidBanDuration).addCredentials(credentials) ~> routesWithApiKey ~> check {
        responseAs[String].contains("duration must be greater than 0") shouldBe true
        status shouldBe StatusCodes.BadRequest
      }
    })


    Delete(prefix + "/blacklist", badBody).addCredentials(credentials) ~> routesWithApiKey ~> check {
      status shouldBe StatusCodes.BadRequest
    }
  }

  it should "respond ok when delete peer request is correct" in {
    val networkControllerProbe = TestProbe("networkController")
    val peerManagerProbe = TestProbe("peerManagerProbe")
    val routesWithProbes = PeersApiRoute(peerManagerProbe.ref, networkControllerProbe.ref, timeProvider, restApiSettings).route

    Delete(prefix + "/peer", body) ~> routesWithProbes ~> check {
      peerManagerProbe.expectMsgClass(classOf[RemovePeer])
      networkControllerProbe.expectMsgClass(classOf[DisconnectFromAddress])

      status shouldBe StatusCodes.OK
    }
  }

  it should "response bad request if delete peer body content is not well formed" in {
    Delete(prefix + "/peer", badBody).addCredentials(credentials) ~> routesWithApiKey ~> check {
      status shouldBe StatusCodes.BadRequest
    }
  }
}
