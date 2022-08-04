package sparkz.testkit.generators

import sparkz.core.PersistentNodeViewModifier
import sparkz.core.consensus.{History, SyncInfo}
import sparkz.core.transaction.state.MinimalState

sealed trait ModifierProducerTemplateItem

case object SynInvalid extends ModifierProducerTemplateItem
case object Valid extends ModifierProducerTemplateItem

trait CustomModifierProducer[PM <: PersistentNodeViewModifier, ST <: MinimalState[PM, ST],
SI <: SyncInfo, HT <: History[PM, SI, HT]] {

  def customModifiers(history: HT,
                      state: ST,
                      template: Seq[ModifierProducerTemplateItem]): Seq[PM]
}
