package sparkz.core.persistence

import org.mockito.MockitoSugar.{mock, times, verify}
import org.scalatest.propspec.AnyPropSpec
import sparkz.core.persistence.BackupAndBackupAndRestoreFromFileStrategy.FileBackupStrategyConfig

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt

class BackupAndBackupAndRestoreFromFileStrategyTest extends AnyPropSpec  {
  property("When the strategy.restore() method is called, each storage restore its content") {
    // Arrange
    implicit val ec: ExecutionContext = mock[ExecutionContext]
    val firstStorageBackupper = mock[StorageBackupper[_]]
    val secondStorageBackupper = mock[StorageBackupper[_]]
    val thirdStorageBackupper = mock[StorageBackupper[_]]
    val scheduledStorageBackupper: Seq[StorageBackupper[_]] = Seq(
      firstStorageBackupper, secondStorageBackupper, thirdStorageBackupper
    )
    val config = FileBackupStrategyConfig(1.minutes, 15.minutes)

    val strategy = new BackupAndBackupAndRestoreFromFileStrategy(
      config,
      scheduledStorageBackupper
    )

    // Act
    strategy.restore()

    // Assert
    scheduledStorageBackupper.foreach(storage => verify(storage, times(1)).restore())
  }
}
