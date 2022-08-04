package sparkz.core.network.peer

import java.net.{InetAddress, InetSocketAddress}

import sparkz.core.network.PeerFeature
import sparkz.core.network.PeerFeature.Id
import scorex.util.serialization._
import sparkz.core.serialization.SparkzSerializer
import scorex.util.Extensions._

case class LocalAddressPeerFeature(address: InetSocketAddress) extends PeerFeature {
  override type M = LocalAddressPeerFeature
  override val featureId: Id = LocalAddressPeerFeature.featureId

  override def serializer: LocalAddressPeerFeatureSerializer.type = LocalAddressPeerFeatureSerializer
}

object LocalAddressPeerFeature {
  val featureId: Id = 2: Byte
}

object LocalAddressPeerFeatureSerializer extends SparkzSerializer[LocalAddressPeerFeature] {

  private val AddressLength = 4

  override def serialize(obj: LocalAddressPeerFeature, w: Writer): Unit = {
    w.putBytes(obj.address.getAddress.getAddress)
    w.putUInt(obj.address.getPort)
  }

  override def parse(r: Reader): LocalAddressPeerFeature = {
    val fa = r.getBytes(AddressLength)
    val port = r.getUInt().toIntExact
    LocalAddressPeerFeature(new InetSocketAddress(InetAddress.getByAddress(fa), port))
  }
}