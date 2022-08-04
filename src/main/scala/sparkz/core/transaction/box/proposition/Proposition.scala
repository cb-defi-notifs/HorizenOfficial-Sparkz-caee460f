package sparkz.core.transaction.box.proposition

import sparkz.core.serialization.BytesSerializable
import sparkz.core.transaction.state.Secret

trait Proposition extends BytesSerializable

trait ProofOfKnowledgeProposition[S <: Secret] extends Proposition

