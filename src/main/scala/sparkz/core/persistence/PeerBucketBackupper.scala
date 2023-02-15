package sparkz.core.persistence

import sparkz.core.network.peer.BucketManager.PeerBucketValue
import sparkz.core.network.peer.PeerBucketStorage
import sparkz.core.persistence.StorageFileBackupper.StorageFileBackupperConfig

import java.io.{ByteArrayOutputStream, ObjectOutputStream}

/**
  * This class is responsible to persist any PeerBucketStorage
  *
  * @param peerBucketStorage The PeerBucketStorage data structure to backup
  * @param config            The configuration
  * @tparam T The type of the data structure holding the data to persist
  */
class PeerBucketBackupper[T <: PeerBucketStorage[_]](
                                                      private val peerBucketStorage: T,
                                                      private val config: StorageFileBackupperConfig
                                                    )
  extends StorageFileBackupper[T, Seq[PeerBucketValue]](config) {

  override protected def getDataToPersist: Array[Byte] = {
    val peers = peerBucketStorage.getPeersAsBucketValue

    val stream: ByteArrayOutputStream = new ByteArrayOutputStream()
    val oos = new ObjectOutputStream(stream)
    oos.writeObject(peers)
    oos.close()
    stream.toByteArray
  }

  override def restore(): Unit = {
    val storedData = recoverStoredData
    storedData match {
      case Some(peersList) => peersList.foreach(peer => peerBucketStorage.add(peer))
      case None =>
    }
  }
}