package sparkz.core.network.message

import akka.util.ByteString
import org.scalatest.Inside.inside
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sparkz.core.network.MaliciousBehaviorException
import sparkz.core.utils.FakeSpec

import scala.util.Failure


class MessageSerializerSpec extends AnyFlatSpec with Matchers {
  private val specs = Seq(GetPeersSpec)

  private def withSerializer(test: MessageSerializer => Unit): Unit =
    test(new MessageSerializer(specs, Array(1, 2, 3, 4), 20))

  "Message Serializer" should "be consistent" in {
    withSerializer { messageSerializer =>
      val message = Message[Unit](GetPeersSpec, Right(()), None)
      val bytes = messageSerializer.serialize(message)
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

  it should "throw exception if magic bytes doesn't match" in {
    withSerializer { messageSerializer =>
      val otherSerializer = new MessageSerializer(specs, Array(4, 3, 2, 1), 20)
      val message = Message[Unit](GetPeersSpec, Right(Unit), None)
      val bytes = messageSerializer.serialize(message)
      val parsedMessage = otherSerializer.deserialize(bytes, None)
      an[MaliciousBehaviorException] should be thrownBy parsedMessage.get
      parsedMessage match {
        case Failure(exception) => exception.getMessage should include("Wrong magic bytes")
        case _ => fail("Unexpected test behaviour")
      }
    }
  }

  it should "throw exception if message handler doesn't exist" in {
    withSerializer { messageSerializer =>
      val otherSerializer = new MessageSerializer(specs, Array(1, 2, 3, 4), 20)
      val message = Message(new FakeSpec, Left(Array(1, 2, 3)), None)
      val bytes = messageSerializer.serialize(message)
      val parsedMessage = otherSerializer.deserialize(bytes, None)
      an[MaliciousBehaviorException] should be thrownBy parsedMessage.get
      parsedMessage match {
        case Failure(exception) => exception.getMessage should include("No message handler found for")
        case _ => fail("Unexpected test behaviour")
      }
    }
  }
}