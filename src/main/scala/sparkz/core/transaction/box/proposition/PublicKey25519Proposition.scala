package sparkz.core.transaction.box.proposition

import sparkz.util.serialization._
import sparkz.core.serialization.SparkzSerializer
import sparkz.core.transaction.state.PrivateKey25519
import sparkz.util.SparkzEncoding
import sparkz.crypto.hash.Blake2b256
import sparkz.crypto.signatures.{Ed25519, PublicKey, Signature}

import scala.util.{Failure, Success, Try}

case class PublicKey25519Proposition(pubKeyBytes: PublicKey)
  extends ProofOfKnowledgeProposition[PrivateKey25519] with SparkzEncoding {

  require(pubKeyBytes.length == Ed25519.KeyLength,
    s"Incorrect pubKey length, ${Ed25519.KeyLength} expected, ${pubKeyBytes.length} found")

  import PublicKey25519Proposition._

  private def bytesWithVersion: Array[Byte] = AddressVersion +: pubKeyBytes

  lazy val address: String = encoder.encode(bytesWithVersion ++ calcCheckSum(bytesWithVersion))

  override def toString: String = address

  def verify(message: Array[Byte], signature: Signature): Boolean = Ed25519.verify(signature, message, pubKeyBytes)

  override type M = PublicKey25519Proposition

  override def serializer: SparkzSerializer[PublicKey25519Proposition] = PublicKey25519PropositionSerializer

  override def equals(obj: scala.Any): Boolean = obj match {
    case p: PublicKey25519Proposition => java.util.Arrays.equals(p.pubKeyBytes, pubKeyBytes)
    case _ => false
  }

  override def hashCode(): Int = (BigInt(pubKeyBytes) % Int.MaxValue).toInt

}

object PublicKey25519PropositionSerializer extends SparkzSerializer[PublicKey25519Proposition] {

  override def serialize(obj: PublicKey25519Proposition, w: Writer): Unit = {
    w.putBytes(obj.pubKeyBytes)
  }

  override def parse(r: Reader): PublicKey25519Proposition = {
    PublicKey25519Proposition(PublicKey @@ r.getBytes(Ed25519.KeyLength))
  }
}

object PublicKey25519Proposition extends SparkzEncoding {
  val AddressVersion: Byte = 1
  val ChecksumLength: Int = 4
  val AddressLength: Int = 1 + Constants25519.PubKeyLength + ChecksumLength

  def calcCheckSum(bytes: Array[Byte]): Array[Byte] = Blake2b256.hash(bytes).take(ChecksumLength)

  def validPubKey(address: String): Try[PublicKey25519Proposition] =
    encoder.decode(address).flatMap { addressBytes =>
      if (addressBytes.length != AddressLength)
        Failure(new Exception("Wrong address length"))
      else {
        val checkSum = addressBytes.takeRight(ChecksumLength)

        val checkSumGenerated = calcCheckSum(addressBytes.dropRight(ChecksumLength))

        if (java.util.Arrays.equals(checkSum, checkSumGenerated))
          Success(PublicKey25519Proposition(PublicKey @@ addressBytes.dropRight(ChecksumLength).tail))
        else Failure(new Exception("Wrong checksum"))
      }
    }
}

object Constants25519 {
  val PrivKeyLength: Int = 32
  val PubKeyLength: Int = 32
}