package sparkz.core.persistence

import org.mockito.MockitoSugar.{mock, times, verify}
import org.scalatest.propspec.AnyPropSpec

class StorageBackupperContainerTest extends AnyPropSpec {
  property("When restoreAllStorages is called the method restore is invoked on each storage") {
    // Arrange
    val firstStorageBackupper = mock[ScheduledStorageBackupper]
    val secondStorageBackupper = mock[ScheduledStorageBackupper]
    val thirdStorageBackupper = mock[ScheduledStorageBackupper]
    val scheduledStorageBackupper: Seq[ScheduledStorageBackupper] = Seq(
      firstStorageBackupper, secondStorageBackupper, thirdStorageBackupper
    )
    val mockInitDir = () => {}

    val spc = new StorageBackupperContainer(scheduledStorageBackupper, mockInitDir)

    // Act
    spc.restoreAllStorages()

    // Assert
    scheduledStorageBackupper.foreach(storage => verify(storage, times(1)).restore())
  }
}
