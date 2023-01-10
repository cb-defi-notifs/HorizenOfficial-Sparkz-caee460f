package sparkz.core.app

import java.net.InetSocketAddress
import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.{ExceptionHandler, RejectionHandler, Route}
import sparkz.core.api.http.{ApiErrorHandler, ApiRejectionHandler, ApiRoute, CompositeHttpService}
import sparkz.core.network._
import sparkz.core.network.message._
import sparkz.core.network.peer.{InMemoryPeerDatabase, PeerManagerRef}
import sparkz.core.settings.{NetworkSettings, SparkzSettings}
import sparkz.core.transaction.Transaction
import sparkz.core.utils.NetworkTimeProvider
import sparkz.core.{NodeViewHolder, PersistentNodeViewModifier}
import sparkz.util.SparkzLogging

import scala.concurrent.ExecutionContext

trait Application extends SparkzLogging {

  import sparkz.core.network.NetworkController.ReceivableMessages.ShutdownNetwork

  type TX <: Transaction
  type PMOD <: PersistentNodeViewModifier
  type NVHT <: NodeViewHolder[TX, PMOD]

  //settings
  implicit val settings: SparkzSettings

  //api
  val apiRoutes: Seq[ApiRoute]

  implicit def exceptionHandler: ExceptionHandler = ApiErrorHandler.exceptionHandler
  implicit def rejectionHandler: RejectionHandler = ApiRejectionHandler.rejectionHandler

  private val networkSettings: NetworkSettings = settings.network
  protected implicit lazy val actorSystem: ActorSystem = ActorSystem(networkSettings.agentName)
  implicit val executionContext: ExecutionContext = actorSystem.dispatchers.lookup("sparkz.executionContext")

  protected val features: Seq[PeerFeature]
  protected val additionalMessageSpecs: Seq[MessageSpec[_]]
  private val featureSerializers: PeerFeature.Serializers = features.map(f => f.featureId -> f.serializer).toMap

  private lazy val basicSpecs = {
    val invSpec = new InvSpec(networkSettings.maxInvObjects)
    val requestModifierSpec = new RequestModifierSpec(networkSettings.maxInvObjects)
    val modifiersSpec = new ModifiersSpec(networkSettings.maxModifiersSpecMessageSize)
    Seq(
      GetPeersSpec,
      new PeersSpec(featureSerializers, networkSettings.maxPeerSpecObjects),
      invSpec,
      requestModifierSpec,
      modifiersSpec
    )
  }

  val nodeViewHolderRef: ActorRef
  val nodeViewSynchronizer: ActorRef

  /** API description in openapi format in YAML or JSON */
  val swaggerConfig: String

  val timeProvider = new NetworkTimeProvider(settings.ntp)

  //an address to send to peers
  lazy val externalSocketAddress: Option[InetSocketAddress] = {
    networkSettings.declaredAddress
  }

  val sparkzContext: SparkzContext = SparkzContext(
    messageSpecs = basicSpecs ++ additionalMessageSpecs,
    features = features,
    timeProvider = timeProvider,
    externalNodeAddress = externalSocketAddress
  )

  protected val peerDatabase = new InMemoryPeerDatabase(settings, sparkzContext)
  val peerManagerRef: ActorRef = PeerManagerRef(settings, sparkzContext, peerDatabase)

  val networkControllerRef: ActorRef = NetworkControllerRef(
    "networkController", networkSettings, peerManagerRef, sparkzContext)

  val peerSynchronizer: ActorRef = PeerSynchronizerRef("PeerSynchronizer",
    networkControllerRef, peerManagerRef, networkSettings, featureSerializers)

  lazy val combinedRoute: Route = CompositeHttpService(actorSystem, apiRoutes, settings.restApi, swaggerConfig).compositeRoute

  def run(): Unit = {
    require(networkSettings.agentName.length <= Application.ApplicationNameLimit)

    log.debug(s"Available processors: ${Runtime.getRuntime.availableProcessors}")
    log.debug(s"Max memory available: ${Runtime.getRuntime.maxMemory}")
    log.debug(s"RPC is allowed at ${settings.restApi.bindAddress.toString}")

    val bindAddress = settings.restApi.bindAddress

    Http().newServerAt(bindAddress.getAddress.getHostAddress, bindAddress.getPort).bindFlow(combinedRoute)

    //on unexpected shutdown
    Runtime.getRuntime.addShutdownHook(new Thread() {
      override def run(): Unit = {
        log.error("Unexpected shutdown")
        stopAll()
      }
    })
  }

  def stopAll(): Unit = synchronized {
    log.info("Stopping network services")
    networkControllerRef ! ShutdownNetwork

    log.info("Stopping actors (incl. block generator)")
    actorSystem.terminate().onComplete { _ =>
      log.info("Exiting from the app...")
      System.exit(0)
    }
  }
}

object Application {

  val ApplicationNameLimit: Int = 50
}
