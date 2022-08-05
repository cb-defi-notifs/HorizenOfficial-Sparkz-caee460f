package sparkz.core.utils

import sparkz.core.block.Block
import sparkz.core.transaction.Transaction
import shapeless.Typeable

class BlockTypeable[TX <: Transaction] extends Typeable[Block[TX]] {

  def cast(t: Any): Option[Block[TX]] = t match {
    case b: Block[TX] => Some(b)
    case _ => None
  }

  def describe: String = "Block[TX <: Transaction]"

  override def toString: String = s"Typeable[$describe]"
}