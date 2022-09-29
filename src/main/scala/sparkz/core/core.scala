package sparkz

import sparkz.core.network.message.InvData
import sparkz.core.utils.SparkzEncoder
import sparkz.util.encode.Base16
import supertagged.TaggedType

package object core {

  //TODO implement ModifierTypeId as a trait
  object ModifierTypeId extends TaggedType[Byte]

  @deprecated("use `sparkz.util.ModifierId`", "")
  type ModifierId = sparkz.util.ModifierId.Type

  @deprecated("use `sparkz.util.ModifierId`", "")
  val ModifierId: sparkz.util.ModifierId.type = sparkz.util.ModifierId

  object VersionTag extends TaggedType[String]

  type ModifierTypeId = ModifierTypeId.Type

  type VersionTag = VersionTag.Type

  def idsToString(ids: Seq[(ModifierTypeId, sparkz.util.ModifierId)])(implicit enc: SparkzEncoder): String = {
    List(ids.headOption, ids.lastOption)
      .flatten
      .map { case (typeId, id) => s"($typeId,${enc.encodeId(id)})" }
      .mkString("[", "..", "]")
  }

  def idsToString(modifierType: ModifierTypeId, ids: Seq[sparkz.util.ModifierId])(implicit encoder: SparkzEncoder): String = {
    idsToString(ids.map(id => (modifierType, id)))
  }

  def idsToString(invData: InvData)(implicit encoder: SparkzEncoder): String = idsToString(invData.typeId, invData.ids)

  def bytesToId: Array[Byte] => sparkz.util.ModifierId = sparkz.util.bytesToId

  def idToBytes: sparkz.util.ModifierId => Array[Byte] = sparkz.util.idToBytes

  def bytesToVersion(bytes: Array[Byte]): VersionTag = VersionTag @@ Base16.encode(bytes)

  def versionToBytes(id: VersionTag): Array[Byte] = Base16.decode(id).get

  def versionToId(version: VersionTag): sparkz.util.ModifierId = sparkz.util.ModifierId @@ version

  def idToVersion(id: sparkz.util.ModifierId): VersionTag = VersionTag @@ id

}
