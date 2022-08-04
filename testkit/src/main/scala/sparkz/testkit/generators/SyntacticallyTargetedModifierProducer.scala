package sparkz.testkit.generators

import sparkz.core.PersistentNodeViewModifier
import sparkz.core.consensus.{History, SyncInfo}


trait SyntacticallyTargetedModifierProducer[PM <: PersistentNodeViewModifier, SI <: SyncInfo, HT <: History[PM, SI, HT]] {
  def syntacticallyValidModifier(history: HT): PM

  def syntacticallyInvalidModifier(history: HT): PM
}
