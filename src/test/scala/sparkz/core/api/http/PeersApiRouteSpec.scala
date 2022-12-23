package sparkz.core.api.http

import akka.http.javadsl.model.headers.HttpCredentials

import java.net.InetSocketAddress
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.{RouteTestTimeout, ScalatestRouteTest}
import akka.testkit.TestDuration
import io.circe.Json
import io.circe.syntax._
import org.mindrot.jbcrypt.BCrypt
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sparkz.core.api.http.PeersApiRoute.PeerInfoResponse
import sparkz.core.settings.{RESTApiSettings, SparkzSettings}
import sparkz.core.utils.NetworkTimeProvider

import scala.concurrent.duration._
import scala.language.postfixOps

class PeersApiRouteSpec extends AnyFlatSpec
  with Matchers
  with ScalatestRouteTest
  with Stubs {

  implicit val timeout: RouteTestTimeout = RouteTestTimeout(15.seconds dilated)

  private val addr = new InetSocketAddress("localhost", 8080)
  private val restApiSettings = RESTApiSettings(addr, None, None, 10 seconds)
  private val prefix = "/peers"
  private val settings = SparkzSettings.read(None)
  private val timeProvider = new NetworkTimeProvider(settings.ntp)

  private val credentials = HttpCredentials.createBasicHttpCredentials("username","password")
  private val badCredentials = HttpCredentials.createBasicHttpCredentials("username","wrong_password")

  private val restApiSettingsWithApiKey = RESTApiSettings(addr, Some(BCrypt.hashpw(credentials.password(), BCrypt.gensalt())), None, 10 seconds)
  private val routes = PeersApiRoute(pmRef, networkControllerRef, timeProvider, restApiSettings).route
  private val routesWithApiKey = PeersApiRoute(pmRef, networkControllerRef, timeProvider, restApiSettingsWithApiKey).route

  val peersResp: String = peers.map { case (address, peerInfo) =>
    PeerInfoResponse.fromAddressAndInfo(address, peerInfo).asJson
  }.asJson.toString

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
    }
  }

  it should "get all peer with wrong Basich Auth" in {
    Get(prefix + "/all").addCredentials(badCredentials) ~> Route.seal(routesWithApiKey) ~> check {
      status.intValue() shouldBe StatusCodes.Unauthorized.intValue
    }
  }

  //can't check it cause original node using now() timestamp for last seen field
  ignore should "get connected peers" in {
    Get(prefix + "/connected") ~> routes ~> check {
      status shouldBe StatusCodes.OK
      connectedPeersResp shouldBe responseAs[String]
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
    val body = HttpEntity("localhost:8080".asJson.toString).withContentType(ContentTypes.`application/json`)
    Post(prefix + "/connect", body) ~> routes ~> check {
      status shouldBe StatusCodes.OK
    }
  }

  it should "connect to peer with Basich Auth" in {
    val body = HttpEntity("localhost:8080".asJson.toString).withContentType(ContentTypes.`application/json`)
    Post(prefix + "/connect", body).addCredentials(credentials) ~> routesWithApiKey ~> check {
      status shouldBe StatusCodes.OK
    }
  }

  it should "not connect to peer with wrong Basich Auth" in {
    val body = HttpEntity("localhost:8080".asJson.toString).withContentType(ContentTypes.`application/json`)
    Post(prefix + "/connect", body).addCredentials(badCredentials) ~> Route.seal(routesWithApiKey) ~> check {
      status.intValue() shouldBe StatusCodes.Unauthorized.intValue
    }
  }

  it should "get blacklisted peers" in {
    Get(prefix + "/blacklisted") ~> routes ~> check {
      status shouldBe StatusCodes.OK
      Map("addresses" -> blacklistedPeers.map(_.toString)).asJson.toString shouldBe responseAs[String]
    }
  }

}
