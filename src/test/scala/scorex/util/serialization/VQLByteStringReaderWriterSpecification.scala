package scorex.util.serialization

import akka.util.ByteString
import org.scalatest.matchers.should.Matchers
import org.scalatest.propspec.AnyPropSpecLike
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class VQLByteStringReaderWriterSpecification extends AnyPropSpecLike with scorex.util.Generators with ScalaCheckPropertyChecks with Matchers {

  def byteBufReader(bytes: Array[Byte]): VLQReader = {
    new VLQByteStringReader(ByteString(bytes))
  }

  def byteArrayWriter(): VLQWriter = {
    new VLQByteStringWriter()
  }
}
