package sparkz.core.persistence

import sparkz.core.network.peer.PeerDatabase

trait PersistablePeerDatabase extends PeerDatabase {
  def storagesToBackup(pathToBackup: String): Seq[StorageBackupper[_]]
}

