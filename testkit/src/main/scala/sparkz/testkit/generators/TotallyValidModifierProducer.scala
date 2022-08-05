package sparkz.testkit.generators

import sparkz.core.PersistentNodeViewModifier
import sparkz.core.consensus.{History, SyncInfo}
import sparkz.core.transaction.state.MinimalState


trait TotallyValidModifierProducer[PM <: PersistentNodeViewModifier, ST <: MinimalState[PM, ST],
SI <: SyncInfo, HT <: History[PM, SI, HT]] {

  def totallyValidModifier(history: HT, state: ST): PM

  def totallyValidModifiers(history: HT, state: ST, count: Int): Seq[PM]
}