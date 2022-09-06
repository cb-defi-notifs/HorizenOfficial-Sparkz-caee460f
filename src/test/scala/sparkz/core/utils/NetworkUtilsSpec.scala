package sparkz.core.utils

import org.scalatest.matchers.should.Matchers
import org.scalatest.propspec.AnyPropSpec

import java.net.InetAddress

class NetworkUtilsSpec extends AnyPropSpec with Matchers {
  property("local and external addresses should be detected by isLocalAddress") {
    // loopback, local and wildcard addresses
    val localAddresses = List(InetAddress.getByName("127.0.0.1"), InetAddress.getByName("192.168.0.1"), InetAddress.getByName("0.0.0.0"))
    localAddresses.foreach(addr => NetworkUtils.isLocalAddress(addr) should be(true))

    // external addresses
    val externalAddresses = List(InetAddress.getByName("142.250.180.174"), InetAddress.getByName("151.101.193.69"))
    externalAddresses.foreach(addr => NetworkUtils.isLocalAddress(addr) should be(false))
  }
}
