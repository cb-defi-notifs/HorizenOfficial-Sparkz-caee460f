package sparkz.core.network

import java.net.InetSocketAddress
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sparkz.core.app.Version
import sparkz.core.network.peer.PeerInfo
import sparkz.core.settings.SparkzSettings
import sparkz.core.utils.{NetworkTimeProvider, TimeProvider}

import scala.concurrent.ExecutionContext.Implicits.global

class NetworkTests extends AnyFlatSpec with Matchers {

  protected val settings: SparkzSettings = SparkzSettings.read(None)
  protected val timeProvider: NetworkTimeProvider = new NetworkTimeProvider(settings.ntp)

  protected def currentTime(): TimeProvider.Time = timeProvider.time()

  protected def getPeerInfo(address: InetSocketAddress, nameOpt: Option[String] = None, featureSeq: Seq[PeerFeature] = Seq()): PeerInfo = {
    val data = PeerSpec("full node", Version.last, nameOpt.getOrElse(address.toString), Some(address), featureSeq)
    PeerInfo(data, currentTime(), None)
  }

}