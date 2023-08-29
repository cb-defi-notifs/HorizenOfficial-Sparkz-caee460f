package sparkz.core.network.peer

import sparkz.core.network.PeerFeature
import sparkz.core.network.PeerFeature.Id
import sparkz.util.serialization._
import sparkz.core.serialization.SparkzSerializer

/**
  * This peer feature allows to detect peers who don't support transactions
  */
case class ForgerNodePeerFeature() extends PeerFeature {

  override type M = ForgerNodePeerFeature
  override val featureId: Id = ForgerNodePeerFeature.featureId

  override def serializer: ForgerNodePeerFeatureSerializer.type = ForgerNodePeerFeatureSerializer

}

object ForgerNodePeerFeature {

  val featureId: Id = 5: Byte

}

object ForgerNodePeerFeatureSerializer extends SparkzSerializer[ForgerNodePeerFeature] {


  override def parse(r: Reader): ForgerNodePeerFeature = {
    ForgerNodePeerFeature()
  }

  override def serialize(obj: ForgerNodePeerFeature, w: Writer): Unit = {}
}
