package sparkz.testkit.generators

import sparkz.core.{PersistentNodeViewModifier, TransactionsCarryingPersistentNodeViewModifier}
import sparkz.core.transaction.{MemoryPool, Transaction}
import sparkz.core.transaction.box.proposition.Proposition

/**
  * Produces a modifier with transactions, not necessary syntatically or semantically valid
   */
trait ArbitraryTransactionsCarryingModifierProducer[
TX <: Transaction,
MPool <: MemoryPool[TX, MPool],
PM <: PersistentNodeViewModifier,
CTM <: PM with TransactionsCarryingPersistentNodeViewModifier[TX]] {

def modifierWithTransactions(memoryPoolOpt: Option[MPool], customTransactionsOpt: Option[Seq[TX]]): CTM
}
