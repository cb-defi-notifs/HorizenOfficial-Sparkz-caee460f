package sparkz.core.transaction

import sparkz.core.transaction.box.proposition.Proposition
import sparkz.core.transaction.box.{Box, BoxUnlocker}


abstract class BoxTransaction[P <: Proposition, BX <: Box[P]] extends Transaction {

  val unlockers: Iterable[BoxUnlocker[P]]
  val newBoxes: Iterable[BX]

  val fee: Long

  val timestamp: Long

}
