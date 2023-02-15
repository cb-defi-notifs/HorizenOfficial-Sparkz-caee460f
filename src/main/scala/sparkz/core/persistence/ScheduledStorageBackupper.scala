package sparkz.core.persistence

import sparkz.core.persistence.ScheduledActor.ScheduledActorConfig

import scala.concurrent.ExecutionContext

class ScheduledStorageBackupper(
                                 storageBackupper: StorageBackupper[_],
                                 config: ScheduledActorConfig)(implicit ec: ExecutionContext)
  extends ScheduledActor(config) {
  /**
    * The custom job the actor should run
    */
  override protected def scheduledJob(): Unit = storageBackupper.persist()

  def restore(): Unit = storageBackupper.restore()
}
