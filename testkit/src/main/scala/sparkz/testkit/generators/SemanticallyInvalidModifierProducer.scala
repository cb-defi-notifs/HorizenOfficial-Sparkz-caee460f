package sparkz.testkit.generators

import sparkz.core.PersistentNodeViewModifier
import sparkz.core.transaction.state.MinimalState


trait SemanticallyInvalidModifierProducer[PM <: PersistentNodeViewModifier, ST <: MinimalState[PM, ST]] {
  def semanticallyInvalidModifier(state: ST): PM
}
