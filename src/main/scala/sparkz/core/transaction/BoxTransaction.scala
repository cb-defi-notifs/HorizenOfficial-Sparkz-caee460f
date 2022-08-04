package sparkz.core.transaction

import sparkz.core.transaction.box.proposition.Proposition
import sparkz.core.transaction.box.{Box, BoxUnlocker}
import sparkz.core.transaction.box.{Box, BoxUnlocker}
import sparkz.core.transaction.box.proposition.Proposition


abstract class BoxTransaction[P <: Proposition, BX <: Box[P]] extends Transaction {

  val unlockers: Traversable[BoxUnlocker[P]]
  val newBoxes: Traversable[BX]

  val fee: Long

  val timestamp: Long

}
