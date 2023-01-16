package sparkz.core.persistence

import sparkz.core.persistence.BackupAndBackupAndRestoreFromFileStrategy.FileBackupStrategyConfig
import sparkz.core.persistence.ScheduledActor.ScheduledActorConfig

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

/**
  * Concrete implementation of backup and restore trait that uses file as storage
  * @param config The configuration need by the strategy
  * @param storagesToBackUp The list of storages that will be backedup and restored
  * @param ec The execution context
  */
class BackupAndBackupAndRestoreFromFileStrategy(
                                                 config: FileBackupStrategyConfig,
                                                 storagesToBackUp: Seq[StorageBackupper[_]])(implicit ec: ExecutionContext)
  extends BackupAndRestoreStrategy {

  private var scheduledBackupperSeq: Seq[ScheduledStorageBackupper] = Seq()

  initBackup()

  private def initBackup(): Unit = backup()

  private def initAllScheduledStorageBackupper: Seq[ScheduledStorageBackupper] = {
    storagesToBackUp.map {
      storage =>
        val actorConfig = ScheduledActorConfig(config.storageBackupDelay, config.storageBackupInterval)
        new ScheduledStorageBackupper(storage, actorConfig)
    }
  }

  override def restore(): Unit = scheduledBackupperSeq.foreach(backupper => backupper.restore())

  override def backup(): Unit = {
    if (scheduledBackupperSeq.isEmpty) {
      scheduledBackupperSeq ++= initAllScheduledStorageBackupper
    }
  }
}

object BackupAndBackupAndRestoreFromFileStrategy {
  case class FileBackupStrategyConfig(storageBackupDelay: FiniteDuration, storageBackupInterval: FiniteDuration)
}