package sparkz.testkit

import sparkz.core.PersistentNodeViewModifier
import sparkz.core.consensus.{History, SyncInfo}
import sparkz.core.transaction.box.proposition.Proposition
import sparkz.core.transaction.state.MinimalState
import sparkz.core.transaction.{MemoryPool, Transaction}
import scorex.testkit.properties.mempool.MempoolFilterPerformanceTest

/**
  * Performance test for implementations
  */
trait BlockchainPerformance[
TX <: Transaction,
PM <: PersistentNodeViewModifier,
SI <: SyncInfo,
MPool <: MemoryPool[TX, MPool],
ST <: MinimalState[PM, ST],
HT <: History[PM, SI, HT]] extends MempoolFilterPerformanceTest[TX, MPool]
