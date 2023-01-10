package sparkz.core.persistence

import sparkz.core.persistence.ScheduledActor.ScheduledActorConfig

import scala.concurrent.ExecutionContext

class ScheduledStoragePersister(
                                 storagePersister: StoragePersister[_],
                                 config: ScheduledActorConfig)(implicit ec: ExecutionContext)
  extends ScheduledActor(config) {
  /**
    * The custom job the actor should run
    */
  override protected def scheduledJob(): Unit = storagePersister.persist()

  def restore(): Unit = storagePersister.restore()
}
