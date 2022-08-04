package sparkz.core.transaction.wallet

import scorex.util.serialization._
import sparkz.core.serialization.{BytesSerializable, SparkzSerializer}
import sparkz.core.{NodeViewModifier, PersistentNodeViewModifier}
import sparkz.core.transaction.Transaction
import sparkz.core.transaction.box.Box
import sparkz.core.transaction.box.proposition.{ProofOfKnowledgeProposition, Proposition}
import sparkz.core.transaction.state.Secret
import sparkz.core.utils.SparkzEncoding
import scorex.util.{ModifierId, bytesToId, idToBytes}
import sparkz.core.PersistentNodeViewModifier
import sparkz.core.transaction.box.proposition.{ProofOfKnowledgeProposition, Proposition}

/**
  * TODO WalletBox is not used in Scorex and should be moved to `mid` layer.
  * It may be used in systems where a box does not contain a link to a corresponding transaction,
  * e.g. could be useful for developments of the Twinscoin protocol and wallet.
  *
  */
case class WalletBox[P <: Proposition, B <: Box[P]](box: B, transactionId: ModifierId, createdAt: Long)
                                                   (subclassDeser: SparkzSerializer[B]) extends BytesSerializable
  with SparkzEncoding {

  override type M = WalletBox[P, B]

  override def serializer: SparkzSerializer[WalletBox[P, B]] = new WalletBoxSerializer(subclassDeser)

  override def toString: String = s"WalletBox($box, ${encoder.encodeId(transactionId)}, $createdAt)"
}

class WalletBoxSerializer[P <: Proposition, B <: Box[P]](subclassDeser: SparkzSerializer[B]) extends SparkzSerializer[WalletBox[P, B]] {

  override def serialize(box: WalletBox[P, B], w: Writer): Unit = {
    w.putBytes(idToBytes(box.transactionId))
    w.putLong(box.createdAt)
    subclassDeser.serialize(box.box, w)
  }

  override def parse(r: Reader): WalletBox[P, B] = {
    val txId = bytesToId(r.getBytes(NodeViewModifier.ModifierIdSize))
    val createdAt = r.getLong()
    val box = subclassDeser.parse(r)
    WalletBox[P, B](box, txId, createdAt)(subclassDeser)
  }
}

case class BoxWalletTransaction[P <: Proposition, TX <: Transaction](proposition: P,
                                                                     tx: TX,
                                                                     blockId: Option[ModifierId],
                                                                     createdAt: Long)


/**
  * Abstract interface for a wallet
  */
trait BoxWallet[P <: Proposition, TX <: Transaction, PMOD <: PersistentNodeViewModifier, W <: BoxWallet[P, TX, PMOD, W]]
  extends Vault[TX, PMOD, W] {
  self: W =>

  type S <: Secret
  type PI <: ProofOfKnowledgeProposition[S]

  def generateNewSecret(): W

  def historyTransactions: Seq[BoxWalletTransaction[P, TX]]

  def boxes(): Seq[WalletBox[P, _ <: Box[P]]]

  def publicKeys: Set[PI]

  //todo: protection?
  def secrets: Set[S]

  def secretByPublicImage(publicImage: PI): Option[S]
}
