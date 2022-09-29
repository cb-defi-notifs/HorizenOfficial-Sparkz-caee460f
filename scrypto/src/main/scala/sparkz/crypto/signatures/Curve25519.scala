package sparkz.crypto.signatures

import java.lang.reflect.Constructor

import org.whispersystems.curve25519.OpportunisticCurve25519Provider
import sparkz.crypto.hash.Sha256
import sparkz.util.SparkzLogging

import scala.util.{Failure, Try}

object Curve25519 extends EllipticCurveSignatureScheme with SparkzLogging {

  val SignatureLength25519: Int = 64
  val KeyLength25519: Int = 32

  override val SignatureLength: Int = SignatureLength25519
  override val KeyLength: Int = KeyLength25519

  /* todo: dirty hack, switch to logic as described in WhisperSystem's Curve25519 tutorial when
              it would be possible to pass a random seed from outside, see
              https://github.com/WhisperSystems/curve25519-java/pull/7
  */
  private val provider: OpportunisticCurve25519Provider = {
    val constructor = classOf[OpportunisticCurve25519Provider]
      .getDeclaredConstructors
      .head
      .asInstanceOf[Constructor[OpportunisticCurve25519Provider]]
    constructor.setAccessible(true)
    constructor.newInstance()
  }

  override def createKeyPair(seed: Array[Byte]): (PrivateKey, PublicKey) = {
    val hashedSeed = Sha256.hash(seed)
    val privateKey = PrivateKey @@ provider.generatePrivateKey(hashedSeed)
    privateKey -> PublicKey @@ provider.generatePublicKey(privateKey)
  }

  override def sign(privateKey: PrivateKey, message: MessageToSign): Signature = {
    require(privateKey.length == KeyLength)
    Signature @@ provider.calculateSignature(provider.getRandom(SignatureLength), privateKey, message)
  }

  override def verify(signature: Signature, message: MessageToSign, publicKey: PublicKey): Boolean = Try {
    require(signature.length == SignatureLength)
    require(publicKey.length == KeyLength)
    provider.verifySignature(publicKey, message, signature)
  }.recoverWith { case e =>
    log.debug("Error while message signature verification", e)
    Failure(e)
  }.getOrElse(false)

  override def createSharedSecret(privateKey: PrivateKey, publicKey: PublicKey): SharedSecret = {
    SharedSecret @@ provider.calculateAgreement(privateKey, publicKey)
  }
}