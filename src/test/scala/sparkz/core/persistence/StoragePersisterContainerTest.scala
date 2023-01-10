package sparkz.core.persistence

import org.mockito.MockitoSugar.{mock, times, verify}
import org.scalatest.propspec.AnyPropSpec

class StoragePersisterContainerTest extends AnyPropSpec {
  property("When restoreAllStorages is called the method restore is invoked on each storage") {
    // Arrange
    val firstStoragePersister = mock[ScheduledStoragePersister]
    val secondStoragePersister = mock[ScheduledStoragePersister]
    val thirdStoragePersister = mock[ScheduledStoragePersister]
    val scheduledStoragePersisters: Seq[ScheduledStoragePersister] = Seq(
      firstStoragePersister, secondStoragePersister, thirdStoragePersister
    )
    val mockInitDir = () => {}

    val spc = new StoragePersisterContainer(scheduledStoragePersisters, mockInitDir)

    // Act
    spc.restoreAllStorages()

    // Assert
    scheduledStoragePersisters.foreach(storage => verify(storage, times(1)).restore())
  }
}
