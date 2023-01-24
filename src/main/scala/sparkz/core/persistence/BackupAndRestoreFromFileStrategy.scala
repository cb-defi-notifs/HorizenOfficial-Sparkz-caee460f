package sparkz.core.persistence

import sparkz.core.persistence.BackupAndRestoreFromFileStrategy.FileBackupStrategyConfig
import sparkz.core.persistence.ScheduledActor.ScheduledActorConfig

import java.nio.file.{Files, Path}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

/**
  * Concrete implementation of backup and restore trait that uses file as storage
  *
  * @param config           The configuration need by the strategy
  * @param storagesToBackUp The list of storages that will be backedup and restored
  * @param ec               The execution context
  */
class BackupAndRestoreFromFileStrategy(
                                        config: FileBackupStrategyConfig,
                                        storagesToBackUp: Seq[StorageBackupper[_]])(implicit ec: ExecutionContext)
  extends BackupAndRestoreStrategy {

  private var scheduledBackupperSeq: Seq[ScheduledStorageBackupper] = Seq()

  initBackup()

  private def initBackup(): Unit = {
    Files.createDirectories(config.directoryPath)
    backup()
  }

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

object BackupAndRestoreFromFileStrategy {
  case class FileBackupStrategyConfig(
                                       directoryPath: Path,
                                       storageBackupDelay: FiniteDuration,
                                       storageBackupInterval: FiniteDuration
                                     )
}