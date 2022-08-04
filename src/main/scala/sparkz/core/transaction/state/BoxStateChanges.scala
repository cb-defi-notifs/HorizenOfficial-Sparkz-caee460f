package sparkz.core.transaction.state

import sparkz.core.transaction.box.Box
import sparkz.core.transaction.box.proposition.Proposition
import sparkz.core.utils.SparkzEncoding
import scorex.crypto.authds._
import sparkz.core.transaction.box.proposition.Proposition

abstract class BoxStateChangeOperation[P <: Proposition, BX <: Box[P]]

case class Removal[P <: Proposition, BX <: Box[P]](boxId: ADKey) extends BoxStateChangeOperation[P, BX] with SparkzEncoding {
  override def toString: String = s"Removal(id: ${encoder.encode(boxId)})"
}

case class Insertion[P <: Proposition, BX <: Box[P]](box: BX) extends BoxStateChangeOperation[P, BX]

case class BoxStateChanges[P <: Proposition, BX <: Box[P]](operations: Seq[BoxStateChangeOperation[P, BX]]) {
  lazy val toAppend: Seq[Insertion[P, BX]] = operations.filter { op =>
    op match {
      case _: Insertion[P, BX] => true
      case _ => false
    }
  }.asInstanceOf[Seq[Insertion[P, BX]]]

  lazy val toRemove: Seq[Removal[P, BX]] = operations.filter { op =>
    op match {
      case _: Removal[P, BX] => true
      case _ => false
    }
  }.asInstanceOf[Seq[Removal[P, BX]]]
}
