package sparkz.testkit.generators

import sparkz.core.{PersistentNodeViewModifier, TransactionsCarryingPersistentNodeViewModifier}
import sparkz.core.transaction.Transaction
import sparkz.core.transaction.box.proposition.Proposition
import sparkz.core.transaction.state.MinimalState


trait SemanticallyValidTransactionsCarryingModifier[TX <: Transaction,
                                                    PM <: PersistentNodeViewModifier,
                                                    CTM <: PM with TransactionsCarryingPersistentNodeViewModifier[TX],
                                                    ST <: MinimalState[PM, ST]] {

  def semanticallyValidModifier(state: ST): CTM
  def genValidTransactionPair(state: ST): Seq[TX]
  def semanticallyValidModifierWithCustomTransactions(state: ST, transactions: Seq[TX]): CTM
}
