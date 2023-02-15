package sparkz.testkit.properties

import org.scalacheck.Gen
import org.scalatest.matchers.should.Matchers
import org.scalatest.propspec.AnyPropSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import sparkz.core.PersistentNodeViewModifier
import sparkz.core.consensus.{History, SyncInfo}
import sparkz.core.consensus.ModifierSemanticValidity.Valid
import sparkz.core.transaction.Transaction
import sparkz.testkit.TestkitHelpers
import sparkz.testkit.generators.SyntacticallyTargetedModifierProducer
import sparkz.util.SparkzLogging


trait HistoryTests[TX <: Transaction, PM <: PersistentNodeViewModifier, SI <: SyncInfo, HT <: History[PM, SI, HT]]
  extends AnyPropSpec
    with ScalaCheckPropertyChecks
    with Matchers
    with SparkzLogging
    with TestkitHelpers
    with SyntacticallyTargetedModifierProducer[PM, SI, HT] {

  val historyGen: Gen[HT]

  lazy val generatorWithValidModifier: Gen[(HT, PM)] = historyGen.map { h => (h, syntacticallyValidModifier(h))}
  lazy val generatorWithInvalidModifier: Gen[(HT, PM)] = historyGen.map { h => (h, syntacticallyInvalidModifier(h))}

  private def propertyNameGenerator(propName: String): String = s"HistoryTests: $propName"

  property(propertyNameGenerator("applicable with valid modifier")) {
    forAll(generatorWithValidModifier) { case (h, m) => h.applicableTry(m) shouldBe 'success}
  }

  property(propertyNameGenerator("append valid modifier")) {
    forAll(generatorWithValidModifier) { case (h, m) => h.append(m).isSuccess shouldBe true }
  }

  property(propertyNameGenerator("contain valid modifier after appending")) {
    forAll(generatorWithValidModifier) { case (h, m) =>
      h.append(m)
      h.contains(m) shouldBe true
    }
  }

  property(propertyNameGenerator("find valid modifier after appending by modifierId")) {
    forAll(generatorWithValidModifier) { case (h, m) =>
      h.append(m)
      h.modifierById(m.id) shouldBe defined
    }
  }

  property(propertyNameGenerator("report semantically validation after appending valid modifier")) {
    forAll(generatorWithValidModifier) { case (h, m) =>
      h.append(m)
      h.reportModifierIsValid(m).get
      h.isSemanticallyValid(m.id) shouldBe Valid
    }
  }

  property(propertyNameGenerator("not applicable with invalid modifier")) {
    forAll(generatorWithInvalidModifier) { case (h, m) => h.applicableTry(m) shouldBe 'failure}
  }

  property(propertyNameGenerator("not append invalid modifier")) {
    forAll(generatorWithInvalidModifier) { case (h, m) => h.append(m).isSuccess shouldBe false }
  }

  property(propertyNameGenerator("not contain invalid modifier after appending")) {
    forAll(generatorWithInvalidModifier) { case (h, m) =>
      h.append(m)
      h.contains(m) shouldBe false
    }
  }

  property(propertyNameGenerator("not finds valid modifier after appending by modifierId")) {
    forAll(generatorWithInvalidModifier) { case (h, m) =>
      h.append(m)
      h.modifierById(m.id) shouldBe None
    }
  }
}
