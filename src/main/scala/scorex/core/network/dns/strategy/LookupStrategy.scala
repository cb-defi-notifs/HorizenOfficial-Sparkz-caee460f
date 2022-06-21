package scorex.core.network.dns.strategy

import scorex.core.network.dns.model.DnsClientInput
import scorex.core.network.dns.strategy.Response.LookupResponse

import java.net.{Inet4Address, Inet6Address, InetAddress}

trait LookupStrategy {
  def apply(dnsClientParams: DnsClientInput): LookupResponse
}

object Strategy {
  case class LeastNodeQuantity() extends LookupStrategy {
    private val LEAST_NODE_QUANTITY = 1

    override def apply(dnsClientParams: DnsClientInput): LookupResponse = thresholdNodeQuantity(dnsClientParams, LEAST_NODE_QUANTITY)
  }

  case class MaxNodeQuantity() extends LookupStrategy {
    private val MAX_NODE_QUANTITY = Int.MaxValue

    override def apply(dnsClientParams: DnsClientInput): LookupResponse = {
      thresholdNodeQuantity(dnsClientParams, MAX_NODE_QUANTITY)
    }
  }

  case class ThresholdNodeQuantity(nodeThreshold: Int) extends LookupStrategy {
    override def apply(dnsClientParams: DnsClientInput): LookupResponse = thresholdNodeQuantity(dnsClientParams, nodeThreshold)
  }

  private def thresholdNodeQuantity(dnsClientParams: DnsClientInput, nodesThreshold: Int): LookupResponse = {
    if (nodesThreshold <= 0) throw new IllegalArgumentException("The nodes threshold must be greater than 0")
    val seeders = dnsClientParams.dnsSeeders
    val lookupFunction = dnsClientParams.lookupFunction

    val (ipv4Addresses, ipv6Addresses) = seeders.foldLeft(Seq[InetAddress]()) { (acc, curr) =>
      val newElements = if (acc.size < nodesThreshold) {
        lookupFunction(curr)
      } else {
        Seq[InetAddress]()
      }
      acc ++ newElements
    }.partition {
      case _: Inet4Address => true
      case _: Inet6Address => false
    }

    LookupResponse(ipv4Addresses, ipv6Addresses)
  }
}

object Response {
  case class LookupResponse(ipv4Addresses: Seq[InetAddress], ipv6Addresses: Seq[InetAddress])
}