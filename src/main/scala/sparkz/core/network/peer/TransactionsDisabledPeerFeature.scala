package sparkz.core.network.peer

import sparkz.core.network.PeerFeature
import sparkz.core.network.PeerFeature.Id
import sparkz.core.network.message.Message
import sparkz.util.serialization._
import sparkz.core.serialization.SparkzSerializer

/**
  * This peer feature allows to detect peers who don't support transactions
  */
case class TransactionsDisabledPeerFeature() extends PeerFeature {

  override type M = TransactionsDisabledPeerFeature
  override val featureId: Id = TransactionsDisabledPeerFeature.featureId

  override def serializer: TransactionsDisabledPeerFeatureSerializer.type = TransactionsDisabledPeerFeatureSerializer

}

object TransactionsDisabledPeerFeature {

  val featureId: Id = 4: Byte

}

object TransactionsDisabledPeerFeatureSerializer extends SparkzSerializer[TransactionsDisabledPeerFeature] {


  override def parse(r: Reader): TransactionsDisabledPeerFeature = {
    TransactionsDisabledPeerFeature()
  }

  override def serialize(obj: TransactionsDisabledPeerFeature, w: Writer): Unit = {}
}
