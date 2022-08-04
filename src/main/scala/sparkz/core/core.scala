package sparkz

import sparkz.core.network.message.InvData
import sparkz.core.utils.SparkzEncoder
import scorex.util.encode.Base16
import supertagged.TaggedType

package object core {

  //TODO implement ModifierTypeId as a trait
  object ModifierTypeId extends TaggedType[Byte]

  @deprecated("use `scorex.util.ModifierId`", "")
  type ModifierId = scorex.util.ModifierId.Type

  @deprecated("use `scorex.util.ModifierId`", "")
  val ModifierId: scorex.util.ModifierId.type = scorex.util.ModifierId

  object VersionTag extends TaggedType[String]

  type ModifierTypeId = ModifierTypeId.Type

  type VersionTag = VersionTag.Type

  def idsToString(ids: Seq[(ModifierTypeId, scorex.util.ModifierId)])(implicit enc: SparkzEncoder): String = {
    List(ids.headOption, ids.lastOption)
      .flatten
      .map { case (typeId, id) => s"($typeId,${enc.encodeId(id)})" }
      .mkString("[", "..", "]")
  }

  def idsToString(modifierType: ModifierTypeId, ids: Seq[scorex.util.ModifierId])(implicit encoder: SparkzEncoder): String = {
    idsToString(ids.map(id => (modifierType, id)))
  }

  def idsToString(invData: InvData)(implicit encoder: SparkzEncoder): String = idsToString(invData.typeId, invData.ids)

  def bytesToId: Array[Byte] => scorex.util.ModifierId = scorex.util.bytesToId

  def idToBytes: scorex.util.ModifierId => Array[Byte] = scorex.util.idToBytes

  def bytesToVersion(bytes: Array[Byte]): VersionTag = VersionTag @@ Base16.encode(bytes)

  def versionToBytes(id: VersionTag): Array[Byte] = Base16.decode(id).get

  def versionToId(version: VersionTag): scorex.util.ModifierId = scorex.util.ModifierId @@ version

  def idToVersion(id: scorex.util.ModifierId): VersionTag = VersionTag @@ id

}
