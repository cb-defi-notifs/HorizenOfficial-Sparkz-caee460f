package sparkz.core.persistence

class StorageBackupperContainer(
                                 private val scheduledStorageBackupper: Seq[ScheduledStorageBackupper],
                                 private val storageInitializer: () => Unit
                               ) {
  storageInitializer()

  def restoreAllStorages(): Unit = scheduledStorageBackupper.foreach(backupper => backupper.restore())
}