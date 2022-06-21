package scorex.core.network.dns.model

import scorex.core.network.dns.model.DnsClientInput.defaultLookupFunction

import java.net.InetAddress

case class DnsClientInput(dnsSeeders: Seq[DnsSeederDomain], lookupFunction: DnsSeederDomain => Seq[InetAddress] = defaultLookupFunction)

object DnsClientInput {
  def defaultLookupFunction(url: DnsSeederDomain): Seq[InetAddress] = {
    InetAddress.getAllByName(url.domainName)
  }
}