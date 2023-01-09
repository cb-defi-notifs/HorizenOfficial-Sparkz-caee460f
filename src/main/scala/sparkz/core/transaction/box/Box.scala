package sparkz.core.transaction.box

import sparkz.core.serialization.BytesSerializable
import sparkz.core.transaction.box.proposition.Proposition
import sparkz.crypto.authds._

/**
  * Box is a state element locked by some proposition.
  */
trait Box[P <: Proposition] extends BytesSerializable {
  val value: Box.Amount
  val proposition: P

  val id: ADKey
}

object Box {
  type Amount = Long
}

