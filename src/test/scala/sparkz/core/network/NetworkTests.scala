package sparkz.core.network

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sparkz.core.app.Version
import sparkz.core.network.NetworkTests.MockTimeProvider
import sparkz.core.network.peer.PeerInfo
import sparkz.core.settings.SparkzSettings
import sparkz.core.utils.TimeProvider
import sparkz.core.utils.TimeProvider.Time

import java.net.InetSocketAddress

class NetworkTests extends AnyFlatSpec with Matchers {

  protected val settings: SparkzSettings = SparkzSettings.read(None)
  protected val mockTimeProvider: TimeProvider = MockTimeProvider

  protected def currentTime(): TimeProvider.Time = mockTimeProvider.time()

  protected def getPeerInfo(address: InetSocketAddress, nameOpt: Option[String] = None, featureSeq: Seq[PeerFeature] = Seq(), forgerNode: Boolean = false): PeerInfo = {
    val data = PeerSpec("full node", Version.last, nameOpt.getOrElse(address.toString), Some(address), featureSeq, forgerNode)
    PeerInfo(data, currentTime(), None)
  }

}

object NetworkTests {
  case object MockTimeProvider extends TimeProvider {
    override def time(): Time = System.currentTimeMillis()
  }
}