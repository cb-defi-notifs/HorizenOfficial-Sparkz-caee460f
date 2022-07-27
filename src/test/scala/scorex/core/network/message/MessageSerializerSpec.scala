package scorex.core.network.message

import akka.util.ByteString
import org.scalatest.Inside.inside
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scorex.core.network.MaliciousBehaviorException


class MessageSerializerSpec extends AnyFlatSpec with Matchers {
  private val specs = Seq(GetPeersSpec)

  private def withSerializer(test: MessageSerializer => Unit): Unit =
    test(new MessageSerializer(specs, Array(1, 2, 3, 4), 20))

  "Message Serializer" should "be consistent" in {
    withSerializer { messageSerializer =>
      val message = Message[Unit](GetPeersSpec, Right(Unit), None)
      val bytes = messageSerializer.serialize(message)
      println(bytes)
      inside(messageSerializer.deserialize(bytes, None).get) {
        case Some(Message(spec, Left(_), None)) =>
          spec shouldBe GetPeersSpec
        case _ => fail("deserialized message does no match serialized message")
      }
    }
  }

  it should "throw exception if byteString is bigger that limit" in {
    withSerializer { messageSerializer =>
      val maliciousBytesString = ByteString(1, 2, 3, 4, 1, 0, 0, 0, 21)

      an[MaliciousBehaviorException] should be thrownBy messageSerializer.deserialize(maliciousBytesString, None).get
    }
  }
}