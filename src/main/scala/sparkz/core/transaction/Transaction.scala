package sparkz.core.transaction

import sparkz.core.{EphemerealNodeViewModifier, ModifierTypeId}
import sparkz.crypto.hash.Blake2b256
import sparkz.util.{ModifierId, bytesToId}


/**
  * A transaction is an atomic state modifier
  */
trait Transaction extends EphemerealNodeViewModifier {
  override val modifierTypeId: ModifierTypeId = Transaction.ModifierTypeId

  val messageToSign: Array[Byte]

  override lazy val id: ModifierId = bytesToId(Blake2b256(messageToSign))
}


object Transaction {
  val ModifierTypeId: sparkz.core.ModifierTypeId = sparkz.core.ModifierTypeId @@ 2.toByte
}
