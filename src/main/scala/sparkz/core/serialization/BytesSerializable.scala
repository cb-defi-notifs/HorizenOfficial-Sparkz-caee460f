package sparkz.core.serialization

trait BytesSerializable extends Serializable {

  type M >: this.type <: BytesSerializable

  def bytes: Array[Byte] = serializer.toBytes(this)

  def serializer: SparkzSerializer[M]
}
