package sparkz.testkit.generators

import sparkz.core.consensus.{History, SyncInfo}
import sparkz.core.{PersistentNodeViewModifier, TransactionsCarryingPersistentNodeViewModifier}
import sparkz.core.transaction.{MemoryPool, Transaction}
import sparkz.core.transaction.state.MinimalState
import sparkz.core.{PersistentNodeViewModifier, TransactionsCarryingPersistentNodeViewModifier}


trait AllModifierProducers[
TX <: Transaction,
MPool <: MemoryPool[TX, MPool],
PM <: PersistentNodeViewModifier,
CTM <: PM with TransactionsCarryingPersistentNodeViewModifier[TX],
ST <: MinimalState[PM, ST],
SI <: SyncInfo, HT <: History[PM, SI, HT]]
  extends SemanticallyValidModifierProducer[PM, ST]
    with SyntacticallyTargetedModifierProducer[PM, SI, HT]
    with ArbitraryTransactionsCarryingModifierProducer[TX, MPool, PM, CTM]
    with TotallyValidModifierProducer[PM, ST, SI, HT]
    with SemanticallyValidTransactionsCarryingModifier[TX, PM, CTM, ST]
