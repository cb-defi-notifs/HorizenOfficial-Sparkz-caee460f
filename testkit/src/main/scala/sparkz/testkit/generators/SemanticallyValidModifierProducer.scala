package sparkz.testkit.generators

import sparkz.core.PersistentNodeViewModifier
import sparkz.core.transaction.state.MinimalState


trait SemanticallyValidModifierProducer[PM <: PersistentNodeViewModifier, ST <: MinimalState[PM, ST]] {
  def semanticallyValidModifier(state: ST): PM
}


