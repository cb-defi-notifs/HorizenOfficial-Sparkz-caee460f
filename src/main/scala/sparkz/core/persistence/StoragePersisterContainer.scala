package sparkz.core.persistence

import sparkz.core.persistence.StoragePersisterContainer.StoragePersisterContainerConfig

import java.io.File
import java.nio.file.Files

class StoragePersisterContainer(
                                 private val scheduledStoragePersister: Seq[ScheduledStoragePersister],
                                 private val storageInitializer: () => Unit
                               ) {
  storageInitializer()

  def restoreAllStorages(): Unit = scheduledStoragePersister.foreach(persister => persister.restore())
}

object StoragePersisterContainer {
  case class StoragePersisterContainerConfig(directoryPath: File)
}