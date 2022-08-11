package sparkz.core.utils

import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import org.scalatest.propspec.AnyPropSpec
import scorex.util.serialization.{Reader, VLQByteBufferWriter, Writer}
import scorex.util.{ByteArrayBuilder, ModifierId}
import sparkz.core.ModifierTypeId
import sparkz.core.network.message.Message.MessageCode
import sparkz.core.network.message._
import sparkz.core.settings.MessageDelays
import sparkz.core.transaction.Transaction

class TpsUtilsTest extends AnyPropSpec {
  private val defaultModifierDelay = 0
  private val getPeersSpecDelay = 1
  private val peerSpecDelay = 2
  private val transactionDelay = 3
  private val blockDelay = 4
  private val requestModifierSpecDelay = 5
  private val modifiersSpecDelay = 6

  private val delaySettings = MessageDelays(
    getPeersSpecDelay,
    peerSpecDelay,
    transactionDelay,
    blockDelay,
    requestModifierSpecDelay,
    modifiersSpecDelay
  )

  property("Returns correct delay for InvData Transaction with content") {
    val invSpec = new InvSpec(5)
    val content = Right(InvData(Transaction.ModifierTypeId, Seq(ModifierId @@ "2e5b8fee0256805f0c630314a4ad19d0277ee7947ced935f774efeed811f27e1")))
    val message = Message(invSpec, content, None)
    val result = TpsUtils.addDelay(message, delaySettings)
    result shouldBe transactionDelay
  }

  property("Returns correct delay for InvData Transaction with byteArray") {
    val invSpec = new InvSpec(5)
    val invData = InvData(Transaction.ModifierTypeId, Seq(ModifierId @@ "2e5b8fee0256805f0c630314a4ad19d0277ee7947ced935f774efeed811f27e1"))
    val writer = new VLQByteBufferWriter(new ByteArrayBuilder())
    invSpec.serialize(invData, writer)
    val content = Left(writer.result().toBytes)
    val message = Message(invSpec, content, None)
    val result = TpsUtils.addDelay(message, delaySettings)
    result shouldBe transactionDelay
  }

  property("Returns correct delay for ModifiersSpec") {
    val modSpec = new ModifiersSpec(5)
    val content = Right(ModifiersData(ModifierTypeId @@ 100.toByte, Map()))
    val message = Message(modSpec, content, None)
    val result = TpsUtils.addDelay(message, delaySettings)
    result shouldBe modifiersSpecDelay
  }


  property("Returns correct delay for GetPeersSpec") {
    val message = Message(GetPeersSpec, Left(Array(100.toByte)), None)
    val result = TpsUtils.addDelay(message, delaySettings)
    result shouldBe getPeersSpecDelay
  }

  property("Returns correct delay for RequestModifierSpec") {
    val requestModifierSpec = new RequestModifierSpec(5)
    val message = Message(requestModifierSpec, Left(Array(100.toByte)), None)
    val result = TpsUtils.addDelay(message, delaySettings)
    result shouldBe requestModifierSpecDelay
  }

  property("Returns default delay for Message spec not in the list") {
    val requestModifierSpec = new FakeSpec
    val message = Message(requestModifierSpec, Left(Array(100.toByte)), None)
    val result = TpsUtils.addDelay(message, delaySettings)
    result shouldBe defaultModifierDelay
  }
}
class FakeData
class FakeSpec extends MessageSpecV1[FakeData] {
  /**
    * Code which identifies what message type is contained in the payload
    */
  override val messageCode: MessageCode = 123.toByte
  /**
    * Name of this message type. For debug purposes only.
    */
  override val messageName: String = "fakeMessage"

  override def serialize(obj: FakeData, w: Writer): Unit = ???

  override def parse(r: Reader): FakeData = ???
}