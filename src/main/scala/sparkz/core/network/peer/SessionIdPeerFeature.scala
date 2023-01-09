package sparkz.core.network.peer

import sparkz.core.network.PeerFeature
import sparkz.core.network.PeerFeature.Id
import sparkz.core.network.message.Message
import sparkz.util.serialization._
import sparkz.core.serialization.SparkzSerializer

/**
  * This peer feature allows to more reliably detect connections to self node and connections from other networks
  *
  * @param networkMagic network magic bytes (taken from settings)
  * @param sessionId    randomly generated 64-bit session identifier
  */
case class SessionIdPeerFeature(networkMagic: Array[Byte],
                                sessionId: Long = scala.util.Random.nextLong()) extends PeerFeature {

  override type M = SessionIdPeerFeature
  override val featureId: Id = SessionIdPeerFeature.featureId

  override def serializer: SessionIdPeerFeatureSerializer.type = SessionIdPeerFeatureSerializer

}

object SessionIdPeerFeature {

  val featureId: Id = 3: Byte

}

object SessionIdPeerFeatureSerializer extends SparkzSerializer[SessionIdPeerFeature] {

  override def serialize(obj: SessionIdPeerFeature, w: Writer): Unit = {
    w.putBytes(obj.networkMagic)
    w.putLong(obj.sessionId)
  }

  override def parse(r: Reader): SessionIdPeerFeature = {
    val networkMagic = r.getBytes(Message.MagicLength)
    val sessionId = r.getLong()
    SessionIdPeerFeature(networkMagic, sessionId)
  }

}
