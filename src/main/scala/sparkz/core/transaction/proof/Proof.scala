package sparkz.core.transaction.proof

import sparkz.core.serialization.BytesSerializable
import sparkz.core.transaction.box.proposition.{ProofOfKnowledgeProposition, Proposition}
import sparkz.core.transaction.state.Secret

/**
  * The most general abstraction of fact a prover can provide a non-interactive proof
  * to open a box or to modify an account
  *
  * A proof is non-interactive and thus serializable
  */

trait Proof[P <: Proposition] extends BytesSerializable {
  def isValid(proposition: P, message: Array[Byte]): Boolean
}

trait ProofOfKnowledge[S <: Secret, P <: ProofOfKnowledgeProposition[S]] extends Proof[P]
