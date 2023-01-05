package sparkz.crypto.signatures

import org.bouncycastle.crypto.params.{Ed25519PrivateKeyParameters, Ed25519PublicKeyParameters}
import org.bouncycastle.math.ec.rfc8032
import sparkz.crypto.hash.Sha256
import sparkz.util.SparkzLogging

import scala.util.{Failure, Try}

object Ed25519 extends EllipticCurveSignatureScheme with SparkzLogging {
  override val SignatureLength: Int = rfc8032.Ed25519.SIGNATURE_SIZE
  override val KeyLength: Int = rfc8032.Ed25519.SECRET_KEY_SIZE

  override def createKeyPair(seed: Array[Byte]): (PrivateKey, PublicKey) = {
    val privateKey: Ed25519PrivateKeyParameters = new Ed25519PrivateKeyParameters(Sha256.hash(seed), 0)
    val publicKey: Ed25519PublicKeyParameters = privateKey.generatePublicKey

    (PrivateKey @@ privateKey.getEncoded, PublicKey @@ publicKey.getEncoded)
  }

  override def sign(privateKey: PrivateKey, message: MessageToSign): Signature = {
    val signature = new Array[Byte](64)
    rfc8032.Ed25519.sign(privateKey, 0, null.asInstanceOf[Array[Byte]], message, 0, message.length, signature, 0)
    Signature @@ signature
  }

  override def verify(signature: Signature, message: MessageToSign, publicKey: PublicKey): Boolean =
    Try {
      rfc8032.Ed25519.verify(signature, 0, publicKey, 0, message, 0, message.length)
    }
      .recoverWith { case e =>
        log.debug("Error while message signature verification", e)
        Failure(e)
      }
      .getOrElse(false)
}
