package sparkz.core.transaction.state

import sparkz.core.{NodeViewComponent, VersionTag}

trait StateReader extends NodeViewComponent {

  //must be ID of last applied modifier
  def version: VersionTag

  def maxRollbackDepth: Int

}
