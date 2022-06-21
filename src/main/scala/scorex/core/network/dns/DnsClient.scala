package scorex.core.network.dns

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import scorex.core.network.dns.model.DnsClientInput
import scorex.core.network.dns.strategy.LookupStrategy
import scorex.util.ScorexLogging

class DnsClient(dnsClientParams: DnsClientInput) extends Actor with ScorexLogging {
  import scorex.core.network.dns.DnsClient.ReceivableMessages.GetDnsSeeds

  override def receive: Receive =
    dnsLookup orElse nonsense

  private def dnsLookup: Receive = {
    case GetDnsSeeds(strategy: LookupStrategy, dnsClientParams: DnsClientInput) => sender() ! strategy.apply(dnsClientParams)
  }

  private def nonsense: Receive = {
    case nonsense: Any =>
      log.warn(s"DnsClient: got unexpected input $nonsense")
  }
}

object DnsClient {
  object ReceivableMessages {
    case class GetDnsSeeds(s: LookupStrategy, o: DnsClientInput)
  }
}

object DnsClientRef {
  def props(params: DnsClientInput): Props = {
    Props(new DnsClient(params))
  }
  def apply(dnsClientParams: DnsClientInput)(implicit system: ActorSystem): ActorRef = {
    system.actorOf(props(dnsClientParams))
  }
}