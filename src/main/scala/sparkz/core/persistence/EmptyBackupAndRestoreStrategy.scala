package sparkz.core.persistence

/**
  * This is a template for an empty backup and restore strategy that does nothing
  */
class EmptyBackupAndRestoreStrategy() extends BackupAndRestoreStrategy {
  override def restore(): Unit = {}

  override def backup(): Unit = {}
}
