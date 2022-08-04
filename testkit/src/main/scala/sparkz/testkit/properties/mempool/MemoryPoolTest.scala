package sparkz.testkit.properties.mempool

import org.scalacheck.Gen
import sparkz.core.transaction.box.proposition.Proposition
import sparkz.core.transaction.{MemoryPool, Transaction}


trait MemoryPoolTest[TX <: Transaction, MPool <: MemoryPool[TX, MPool]] {
  val memPool: MPool
  val memPoolGenerator: Gen[MPool]
  val transactionGenerator: Gen[TX]
}
