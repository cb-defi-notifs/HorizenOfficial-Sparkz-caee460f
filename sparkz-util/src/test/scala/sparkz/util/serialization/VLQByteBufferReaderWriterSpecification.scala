package sparkz.util.serialization

import java.nio.ByteBuffer

import sparkz.util.ByteArrayBuilder

class VLQByteBufferReaderWriterSpecification extends VLQReaderWriterSpecification {

  override def byteBufReader(bytes: Array[Byte]): VLQReader = {
    val buf = ByteBuffer.wrap(bytes)
    buf.position(0)
    new VLQByteBufferReader(buf)
  }

  override def byteArrayWriter(): VLQWriter = {
    new VLQByteBufferWriter(new ByteArrayBuilder())
  }
}
