package sparkz.crypto.authds.avltree.batch

import sparkz.util.SparkzEncoding

trait ToStringHelper extends SparkzEncoding {
  //Needed for debug (toString) only
  protected def arrayToString(a: Array[Byte]): String = encoder.encode(a).take(8)
}
