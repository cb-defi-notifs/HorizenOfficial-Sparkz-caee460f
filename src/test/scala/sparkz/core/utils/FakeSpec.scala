package sparkz.core.utils

import scorex.util.serialization.{Reader, Writer}
import sparkz.core.network.message.Message.MessageCode
import sparkz.core.network.message.MessageSpecV1

class FakeSpec extends MessageSpecV1[Unit] {
  /**
    * Code which identifies what message type is contained in the payload
    */
  override val messageCode: MessageCode = 1234567.toByte
  /**
    * Name of this message type. For debug purposes only.
    */
  override val messageName: String = "fakeMessage"

  override def serialize(obj: Unit, w: Writer): Unit = ???

  override def parse(r: Reader): Unit = ???
}