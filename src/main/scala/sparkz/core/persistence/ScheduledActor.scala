package sparkz.core.persistence

import akka.actor.{ActorSystem, Cancellable}
import sparkz.core.persistence.ScheduledActor.ScheduledActorConfig

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

/**
  * An abstract class representing an actor that runs a scheduled job
  *
  * @param config - The scheduled job configuration
  * @param ec     - The actor's execution context
  */
abstract class ScheduledActor(config: ScheduledActorConfig)(implicit ec: ExecutionContext) {
  private val job: Cancellable = ActorSystem("scheduledActor")
    .scheduler
    .scheduleWithFixedDelay(config.initialDelay, config.scheduleInterval) { () => scheduledJob() }

  /**
    * Method to cancel the current job
    *
    * @return - Whether the job is cancelled successfully
    */
  def stopJob: Boolean = job.cancel()

  /**
    * The custom job the actor should run
    */
  protected def scheduledJob(): Unit
}

object ScheduledActor {
  case class ScheduledActorConfig(initialDelay: FiniteDuration, scheduleInterval: FiniteDuration)
}