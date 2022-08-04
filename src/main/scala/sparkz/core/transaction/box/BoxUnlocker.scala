package sparkz.core.transaction.box

import sparkz.core.transaction.box.proposition.Proposition
import sparkz.core.transaction.proof.Proof
import sparkz.core.utils.SparkzEncoding
import scorex.crypto.authds.ADKey
import sparkz.core.transaction.box.proposition.Proposition
import sparkz.core.transaction.proof.Proof

trait BoxUnlocker[P <: Proposition] extends SparkzEncoding {
  val closedBoxId: ADKey
  val boxKey: Proof[P]

  override def toString: String = s"BoxUnlocker(id: ${encoder.encode(closedBoxId)}, boxKey: $boxKey)"
}
