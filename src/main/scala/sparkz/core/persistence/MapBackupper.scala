package sparkz.core.persistence

import sparkz.core.persistence.StorageFileBackupper.StorageFileBackupperConfig

import java.io.{ByteArrayOutputStream, ObjectOutputStream}
import scala.collection.mutable

/**
  * This class is responsible to persist any Map
  *
  * @param storage The map data structure to backup
  * @param config  The configuration
  * @tparam K The type of the map's keys
  * @tparam V The type of the map's values
  */
class MapBackupper[K, V](
                          storage: mutable.Map[K, V],
                          config: StorageFileBackupperConfig
                        )
  extends StorageFileBackupper[mutable.Map[K, V], mutable.Map[K, V]](config) {

  override protected def getDataToPersist: Array[Byte] = {
    val stream: ByteArrayOutputStream = new ByteArrayOutputStream()
    val oos = new ObjectOutputStream(stream)
    oos.writeObject(storage)
    oos.close()
    stream.toByteArray
  }

  override def restore(): Unit = {
    val storedData = recoverStoredData
    storedData match {
      case Some(entries) => entries.foreach(entry => storage += entry._1 -> entry._2)
      case None =>
    }
  }
}