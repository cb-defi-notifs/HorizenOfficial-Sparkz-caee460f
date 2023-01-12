package sparkz.core.persistence

import java.io._
import scala.util.{Failure, Success, Try}

/**
  * This abstract class represents an actor that is responsible to persist and recover data
  *
  * @tparam D The type of the data stored
  */
abstract class StorageBackupper[D] {

  /**
    * Custom method that returns the data to persist as byte array
    *
    * @return The data to be stored as byte array
    */
  protected def getDataToPersist: Array[Byte]

  /**
    * Custom method that returns the specific output stream the class wants to use to write the data
    *
    * @return The output stream to write the data
    */
  protected def getOutputStream: OutputStream

  /**
    * Custom method to retrieve data to restore from the specified source
    *
    * @return The stored data as an array of bytes
    */
  protected def tryGetSourceDataToRestore: Try[Array[Byte]]

  /**
    * Custom method to restore the data into the data structure
    */
  def restore(): Unit

  /**
    * Persist data using the custom output stream
    */
  def persist(): Unit = {
    val dataToPersist: Array[Byte] = getDataToPersist
    val bos = new BufferedOutputStream(getOutputStream)
    bos.write(dataToPersist)
    bos.close()
  }

  /**
    * Retrieve the data previously stored and return them
    *
    * @return The data previously stored as an Option
    */
  def recoverStoredData: Option[D] = {
    tryGetSourceDataToRestore match {
      case Success(bytes) =>
        val ois = new ObjectInputStream(new ByteArrayInputStream(bytes))
        val value = ois.readObject
        ois.close()
        Some(value.asInstanceOf[D])
      case Failure(_) => None
    }
  }
}