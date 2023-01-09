package sparkz.core.api.http

import akka.http.scaladsl.server.directives.{AuthenticationDirective, Credentials}
import akka.http.scaladsl.server.{AuthorizationFailedRejection, Directive0}
import org.mindrot.jbcrypt.BCrypt
import sparkz.core.settings.RESTApiSettings
import sparkz.util.SparkzEncoding
import sparkz.crypto.hash.Blake2b256

trait ApiDirectives extends CorsHandler with SparkzEncoding {
  val settings: RESTApiSettings
  val apiKeyHeaderName: String

  lazy val withAuth: Directive0 = optionalHeaderValueByName(apiKeyHeaderName).flatMap {
    case _ if settings.apiKeyHash.isEmpty => pass
    case None => reject(AuthorizationFailedRejection)
    case Some(key) =>
      val keyHashStr: String = encoder.encode(Blake2b256(key))
      if (settings.apiKeyHash.contains(keyHashStr)) pass
      else reject(AuthorizationFailedRejection)
  }

  val DEFAULT_USER: String = "user"

  lazy val withBasicAuth: AuthenticationDirective[String] = authenticateBasic(realm = "secure api", basicAuthentication)

  def basicAuthentication(credentials: Credentials): Option[String] = {
    if (settings.apiKeyHash.isEmpty) {
      Some(DEFAULT_USER)
    } else {
      credentials match {
        case p@Credentials.Provided(id) if p.provideVerify(verifyApiKey) => Some(id)
        case _ => None
      }
    }
  }

  def verifyApiKey(apiKey: String): Boolean = {
    val apiKeyHash = settings.apiKeyHash.fold("")(key => key)
    if (apiKeyHash.equals(""))
      false
    else
      BCrypt.checkpw(apiKey, apiKeyHash)
  }

}
