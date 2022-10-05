package sparkz.core.app

import java.net.InetSocketAddress

import sparkz.core.network.PeerFeature
import sparkz.core.network.message.MessageSpec
import sparkz.core.utils.TimeProvider

case class SparkzContext(messageSpecs: Seq[MessageSpec[_]],
                         features: Seq[PeerFeature],
                         timeProvider: TimeProvider,
                         externalNodeAddress: Option[InetSocketAddress])
