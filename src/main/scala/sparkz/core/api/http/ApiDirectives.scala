package sparkz.core.api.http

import akka.http.scaladsl.server.directives.{AuthenticationDirective, Credentials}
import akka.http.scaladsl.server.{AuthorizationFailedRejection, Directive0}
import sparkz.core.settings.RESTApiSettings
import sparkz.core.utils.SparkzEncoding
import scorex.crypto.hash.Blake2b256

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

  lazy val withBasicAuth: AuthenticationDirective[String] = authenticateBasic(realm = "secure api", basicAuthentication)

  def basicAuthentication(credentials: Credentials): Option[String] = {
    settings.apiKeyHash match {
      case None => None
      case Some(key) =>
        key.isEmpty match {
          case true =>
            None
          case false =>
            credentials match {
              case p @ Credentials.Provided(id) if p.verify(key) => Some(id)
              case _ => None
            }
        }
    }
  }

}
