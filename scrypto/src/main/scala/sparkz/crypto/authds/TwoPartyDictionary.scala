package sparkz.crypto.authds

import sparkz.crypto.authds.avltree.batch.Operation
import sparkz.util.SparkzEncoding

import scala.util.Try

trait TwoPartyDictionary extends SparkzEncoding {

  /**
    * Run an operation, whether a lookup or a modification, against the tree
    *
    * @param operation - tree modification
    * @return modification proof
    */
  def run[O <: Operation](operation: O): Try[TwoPartyProof]

  /**
    * @return current digest of structure
    */
  def rootHash(): ADDigest
}
