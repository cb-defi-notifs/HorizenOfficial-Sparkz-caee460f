package sparkz.core.utils

import java.net.InetSocketAddress

class NetworkAddressWrapper(address: InetSocketAddress) {
  def isLocal: Boolean = NetworkUtils.isLocalAddress(address.getAddress)
  def isRoutable: Boolean = {
    false
  }

}
