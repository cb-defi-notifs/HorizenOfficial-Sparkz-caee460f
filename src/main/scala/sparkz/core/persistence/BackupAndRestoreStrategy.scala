package sparkz.core.persistence

/**
  * The strategy to backup and restore data
  */
trait BackupAndRestoreStrategy {
  def restore(): Unit
  def backup(): Unit
}