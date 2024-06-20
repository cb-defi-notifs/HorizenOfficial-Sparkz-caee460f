package sparkz.core.settings

import java.io.File
import java.net.InetSocketAddress
import com.typesafe.config.{Config, ConfigFactory}
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ArbitraryTypeReader._
import sparkz.core.network.message.Message
import sparkz.core.utils.NetworkTimeProviderSettings
import sparkz.util.SparkzLogging

import scala.concurrent.duration._

trait ApiSettings {
    def bindAddress: InetSocketAddress;
    def apiKeyHash: Option[String];
    def corsAllowedOrigin: Option[String];
    def timeout: FiniteDuration;
}

case class RESTApiSettings(bindAddress: InetSocketAddress,
                           apiKeyHash: Option[String],
                           corsAllowedOrigin: Option[String],
                           timeout: FiniteDuration) extends ApiSettings

case class NetworkSettings(nodeName: String,
                           addedMaxDelay: Option[FiniteDuration],
                           localOnly: Boolean,
                           knownPeers: Seq[InetSocketAddress],
                           onlyConnectToKnownPeers: Boolean,
                           bindAddress: InetSocketAddress,
                           maxIncomingConnections: Int,
                           maxOutgoingConnections: Int,
                           maxForgerConnections: Int,
                           connectionTimeout: FiniteDuration,
                           declaredAddress: Option[InetSocketAddress],
                           handshakeTimeout: FiniteDuration,
                           deliveryTimeout: FiniteDuration,
                           maxDeliveryChecks: Int,
                           penalizeNonDelivery: Boolean,
                           maxRequestedPerPeer: Int,
                           slowModeFeatureFlag: Boolean,
                           slowModeThresholdMs: Long,
                           slowModeMaxRequested: Int,
                           slowModeMeasurementImpact: Double,
                           rebroadcastEnabled: Boolean,
                           rebroadcastDelay: FiniteDuration,
                           rebroadcastQueueSize: Int,
                           rebroadcastBatchSize: Int,
                           appVersion: String,
                           agentName: String,
                           maxModifiersSpecMessageSize: Int,
                           maxHandshakeSize: Int,
                           maxInvObjects: Int,
                           desiredInvObjects: Int,
                           syncInterval: FiniteDuration,
                           syncStatusRefresh: FiniteDuration,
                           syncIntervalStable: FiniteDuration,
                           syncStatusRefreshStable: FiniteDuration,
                           inactiveConnectionDeadline: FiniteDuration,
                           syncTimeout: Option[FiniteDuration],
                           controllerTimeout: Option[FiniteDuration],
                           maxModifiersCacheSize: Int,
                           magicBytes: Array[Byte],
                           messageLengthBytesLimit: Int,
                           getPeersInterval: FiniteDuration,
                           maxPeerSpecObjects: Int,
                           storageBackupInterval: FiniteDuration,
                           storageBackupDelay: FiniteDuration,
                           temporalBanDuration: FiniteDuration,
                           penaltySafeInterval: FiniteDuration,
                           penaltyScoreThreshold: Int,
                           handlingTransactionsEnabled: Boolean,
                           isForgerNode: Boolean)

case class SparkzSettings(dataDir: File,
                          logDir: File,
                          network: NetworkSettings,
                          restApi: RESTApiSettings,
                          ntp: NetworkTimeProviderSettings)


object SparkzSettings extends SparkzLogging with SettingsReaders {

  protected val configPath: String = "sparkz"

  def readConfigFromPath(userConfigPath: Option[String], configPath: String): Config = {

    val maybeConfigFile: Option[File] = userConfigPath.map(filename => new File(filename)).filter(_.exists())
      .orElse(userConfigPath.flatMap(filename => Option(getClass.getClassLoader.getResource(filename))).
        map(r => new File(r.toURI)).filter(_.exists()))

    val config = maybeConfigFile match {
      // if no user config is supplied, the library will handle overrides/application/reference automatically
      case None =>
        log.warn("NO CONFIGURATION FILE WAS PROVIDED. STARTING WITH DEFAULT SETTINGS FOR TESTNET!")
        ConfigFactory.load()
      // application config needs to be resolved wrt both system properties *and* user-supplied config.
      case Some(file) =>
        val cfg = ConfigFactory.parseFile(file)
        if (!cfg.hasPath(configPath)) {
          throw new Error("Malformed configuration file was provided! Aborting!")
        }
        ConfigFactory
          .defaultOverrides()
          .withFallback(cfg) // user-supplied config
          .withFallback(ConfigFactory.defaultApplication())
          .withFallback(ConfigFactory.defaultReference()) // "src/main/resources/reference.conf"
          .resolve()
    }

    config
  }

  def read(userConfigPath: Option[String]): SparkzSettings = {
    fromConfig(readConfigFromPath(userConfigPath, configPath))
  }

  def fromConfig(config: Config): SparkzSettings = {
    config.as[SparkzSettings](configPath)
      .ensuring(_.network.magicBytes.length == Message.MagicLength)
  }
}
