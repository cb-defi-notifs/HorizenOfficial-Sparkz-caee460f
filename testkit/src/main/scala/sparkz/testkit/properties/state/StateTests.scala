package sparkz.testkit.properties.state

import org.scalacheck.Gen
import org.scalatest.matchers.should.Matchers
import org.scalatest.propspec.AnyPropSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import sparkz.core.PersistentNodeViewModifier
import sparkz.core.transaction.state.MinimalState
import sparkz.testkit.TestkitHelpers
import sparkz.testkit.generators.{CoreGenerators, SemanticallyInvalidModifierProducer, SemanticallyValidModifierProducer}
import sparkz.core.PersistentNodeViewModifier
import sparkz.testkit.generators.{SemanticallyInvalidModifierProducer, SemanticallyValidModifierProducer}

trait StateTests[PM <: PersistentNodeViewModifier, ST <: MinimalState[PM, ST]]
  extends AnyPropSpec
    with ScalaCheckPropertyChecks
    with Matchers
    with CoreGenerators
    with TestkitHelpers
    with SemanticallyValidModifierProducer[PM, ST]
    with SemanticallyInvalidModifierProducer[PM, ST] {

  val checksToMake = 10

  val stateGen: Gen[ST]
}
