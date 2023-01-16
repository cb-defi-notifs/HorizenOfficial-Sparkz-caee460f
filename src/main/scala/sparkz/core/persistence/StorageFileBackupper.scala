package sparkz.core.persistence

import sparkz.core.persistence.StorageFileBackupper.StorageFileBackupperConfig

import java.io.{File, FileOutputStream, OutputStream}
import java.nio.file.{Files, Paths}
import scala.util.Try

/**
  * This abstract class writes and reads from file
  *
  * @param config The configuration
  * @tparam T The type of the data structure holding the data to persist
  * @tparam D The type of the data stored
  */
abstract class StorageFileBackupper[T, D](config: StorageFileBackupperConfig)
  extends StorageBackupper[D] {

  private val absolutePath = Paths.get(config.directoryPath.getPath, config.fileName)

  override protected def getOutputStream: OutputStream = new FileOutputStream(absolutePath.toFile)

  override protected def tryGetSourceDataToRestore: Try[Array[Byte]] = Try(Files.readAllBytes(absolutePath))
}

object StorageFileBackupper {
  case class StorageFileBackupperConfig(directoryPath: File, fileName: String)
}