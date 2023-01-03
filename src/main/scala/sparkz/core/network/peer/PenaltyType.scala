package sparkz.core.network.peer

import sparkz.core.settings.NetworkSettings

/**
  * A trait describing all possible types of the network participant misbehavior.
  * `penaltyScore` - a number defining how bad concrete kind of misbehavior is,
  * `isPermanent`  - a flag defining whether a penalty is permanent.
  */
sealed trait PenaltyType {
  val penaltyScore: Int
  val isPermanent: Boolean = false
}

object PenaltyType {

  case object NonDeliveryPenalty extends PenaltyType {
    override val penaltyScore: Int = 2
  }

  case object MisbehaviorPenalty extends PenaltyType {
    override val penaltyScore: Int = 10
  }

  case object SpamPenalty extends PenaltyType {
    override val penaltyScore: Int = 25
  }

  case object PermanentPenalty extends PenaltyType {
    override val penaltyScore: Int = 1000000000
    override val isPermanent: Boolean = true
  }

  case class DisconnectPenalty(networkSettings: NetworkSettings) extends PenaltyType {
    // We want to ban right away a peer with this penalty: +1 to go beyond the threshold
    override val penaltyScore: Int = networkSettings.penaltyScoreThreshold + 1
  }

  case class CustomPenaltyDuration(penaltyDurationInMinutes: Long) extends PenaltyType {
    override val penaltyScore: Int = 0
  }
}
