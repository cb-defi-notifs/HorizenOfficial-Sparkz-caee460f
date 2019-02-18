package scorex.core.network.message


import com.google.common.primitives.{Bytes, Ints, Longs}
import scorex.core.consensus.SyncInfo
import scorex.core.network.message.Message.MessageCode
import scorex.core.network._
import scorex.core.{ModifierTypeId, NodeViewModifier}
import scorex.util.{ModifierId, ScorexLogging, bytesToId, idToBytes}

import scala.annotation.tailrec
import scala.util.Try

case class ModifiersData(typeId: ModifierTypeId, modifiers: Map[ModifierId, Array[Byte]])
case class InvData(typeId: ModifierTypeId, ids: Seq[ModifierId])

class SyncInfoMessageSpec[SI <: SyncInfo](deserializer: Array[Byte] => Try[SI]) extends MessageSpecV1[SI] {

  override val messageCode: MessageCode = 65: Byte
  override val messageName: String = "Sync"

  override def parseBytes(bytes: Array[Byte]): Try[SI] = deserializer(bytes)

  override def toBytes(data: SI): Array[Byte] = data.bytes
}

object InvSpec {
  val MessageCode: Byte = 55
  val MessageName: String = "Inv"
}

class InvSpec(maxInvObjects: Int) extends MessageSpecV1[InvData] {

  import InvSpec._

  override val messageCode: MessageCode = MessageCode
  override val messageName: String = MessageName

  override def parseBytes(bytes: Array[Byte]): Try[InvData] = Try {
    val typeId = ModifierTypeId @@ bytes.head
    val count = Ints.fromByteArray(bytes.slice(1, 5))

    require(count > 0, "empty inv list")
    require(count <= maxInvObjects, s"$count elements in a message while limit is $maxInvObjects")

    val elems = (0 until count).map { c =>
      bytesToId(bytes.slice(5 + c * NodeViewModifier.ModifierIdSize, 5 + (c + 1) * NodeViewModifier.ModifierIdSize))
    }

    InvData(typeId, elems)
  }

  override def toBytes(data: InvData): Array[Byte] = {
    require(data.ids.nonEmpty, "empty inv list")
    require(data.ids.lengthCompare(maxInvObjects) <= 0, s"more invs than $maxInvObjects in a message")
    val idsBytes = data.ids.map(idToBytes).ensuring(_.forall(_.lengthCompare(NodeViewModifier.ModifierIdSize) == 0))

    Bytes.concat(Array(data.typeId), Ints.toByteArray(data.ids.size), scorex.core.utils.concatBytes(idsBytes))
  }
}

object RequestModifierSpec {
  val MessageCode: MessageCode = 22: Byte
  val MessageName: String = "RequestModifier"
}

class RequestModifierSpec(maxInvObjects: Int) extends MessageSpecV1[InvData] {

  import RequestModifierSpec._

  override val messageCode: MessageCode = MessageCode
  override val messageName: String = MessageName

  private val invSpec = new InvSpec(maxInvObjects)

  override def toBytes(typeAndId: InvData): Array[Byte] =
    invSpec.toBytes(typeAndId)

  override def parseBytes(bytes: Array[Byte]): Try[InvData] =
    invSpec.parseBytes(bytes)
}


object ModifiersSpec {
  val MessageCode: MessageCode = 33: Byte
  val MessageName: String = "Modifier"
}

class ModifiersSpec(maxMessageSize: Int) extends MessageSpecV1[ModifiersData] with ScorexLogging {

  import ModifiersSpec._

  override val messageCode: MessageCode = MessageCode
  override val messageName: String = MessageName

  override def parseBytes(bytes: Array[Byte]): Try[ModifiersData] = Try {
    val typeId = ModifierTypeId @@ bytes.head
    val count = Ints.fromByteArray(bytes.slice(1, 5))
    val objBytes = bytes.slice(5, bytes.length)
    val (_, seq) = (0 until count).foldLeft(0 -> Seq[(ModifierId, Array[Byte])]()) {
      case ((pos, collected), _) =>

        val id = bytesToId(objBytes.slice(pos, pos + NodeViewModifier.ModifierIdSize))
        val objBytesCnt = Ints.fromByteArray(objBytes.slice(pos + NodeViewModifier.ModifierIdSize, pos + NodeViewModifier.ModifierIdSize + 4))
        val obj = objBytes.slice(pos + NodeViewModifier.ModifierIdSize + 4, pos + NodeViewModifier.ModifierIdSize + 4 + objBytesCnt)

        (pos + NodeViewModifier.ModifierIdSize + 4 + objBytesCnt) -> (collected :+ (id -> obj))
    }
    ModifiersData(typeId, seq.toMap)
  }

  override def toBytes(data: ModifiersData): Array[Byte] = {
    require(data.modifiers.nonEmpty, "empty modifiers list")
    val typeId = data.typeId
    val modifiers = data.modifiers

    var msgSize = 5
    val payload: Seq[Array[Byte]] = modifiers.flatMap { case (id, modifier) =>
      msgSize += NodeViewModifier.ModifierIdSize + 4 + modifier.length
      if (msgSize <= maxMessageSize) Seq(idToBytes(id), Ints.toByteArray(modifier.length), modifier) else Seq()
    }.toSeq


    val bytes = scorex.core.utils.concatBytes(Seq(Array(typeId), Ints.toByteArray(payload.size / 3)) ++ payload)
    if (msgSize > maxMessageSize) {
      log.warn(s"Message with modifiers ${data.modifiers.keySet} have size $msgSize exceeding limit $maxMessageSize." +
        s" Sending ${bytes.length} bytes instead")
    }
    bytes
  }
}

object GetPeersSpec extends MessageSpecV1[Unit] {
  override val messageCode: Message.MessageCode = 1: Byte

  override val messageName: String = "GetPeers message"

  override def parseBytes(bytes: Array[Byte]): Try[Unit] =
    Try(require(bytes.isEmpty, "Non-empty data for GetPeers"))

  override def toBytes(data: Unit): Array[Byte] = Array()
}

object PeersSpec {

  /**
    * Limit number of peers to send to always fit in 0.5 Mb.
    */
  val MaxPeersInMessage: Int = 524288 / (HandshakeSpec.MaxHandshakeSize + 4)

  val messageCode: Message.MessageCode = 2: Byte

  val messageName: String = "Peers message"

}

class PeersSpec(featureSerializers: PeerFeature.Serializers) extends MessageSpecV1[Seq[PeerData]] {
  private val handshakeSerializer = new PeerDataSerializer(featureSerializers)

  override val messageCode: Message.MessageCode = PeersSpec.messageCode

  override val messageName: String = PeersSpec.messageName

  override def toBytes(peers: Seq[PeerData]): Array[Byte] = {
    peers.flatMap { p =>
      val b = handshakeSerializer.toBytes(p)
      Ints.toByteArray(b.length) ++ b
    }.toArray
  }

  override def parseBytes(bytes: Array[Byte]): Try[Seq[PeerData]] = Try {
    @tailrec
    def loop(i: Int, acc: Seq[PeerData]): Seq[PeerData] = if (i < bytes.length) {
      require(acc.size <= PeersSpec.MaxPeersInMessage)
      val l = Ints.fromByteArray(bytes.slice(i, i + 4))
      val peer = handshakeSerializer.parseBytes(bytes.slice(i + 4, i + 4 + l)).get
      loop(i + 4 + l, peer +: acc)
    } else {
      acc
    }

    loop(0, Seq())
  }

}


object HandshakeSpec {
  // todo what is the real limit?
  val MaxHandshakeSize: Int = 16384

  val messageCode: MessageCode = 75: Byte
  val messageName: String = "Handshake"
}

class HandshakeSpec(featureSerializers: PeerFeature.Serializers) extends MessageSpecV1[Handshake] {

  private val peersDataSerializer = new PeerDataSerializer(featureSerializers)

  override val messageCode: MessageCode = HandshakeSpec.messageCode
  override val messageName: String = HandshakeSpec.messageName

  override def toBytes(obj: Handshake): Array[Byte] = {
    Bytes.concat(Longs.toByteArray(obj.time), peersDataSerializer.toBytes(obj.peerData))
  }

  override def parseBytes(bytes: Array[Byte]): Try[Handshake] = Try {
    require(bytes.length <= HandshakeSpec.MaxHandshakeSize)
    Handshake(peersDataSerializer.parseBytes(bytes.drop(8)).get, Longs.fromByteArray(bytes.take(8)))
  }

}