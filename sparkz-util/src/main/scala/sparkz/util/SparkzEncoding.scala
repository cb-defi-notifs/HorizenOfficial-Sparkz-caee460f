package sparkz.util

import sparkz.util.encode.{Base16, BytesEncoder}

/**
  * Trait with bytes to string encoder
  */
trait SparkzEncoding {
  implicit val encoder: BytesEncoder = Base16
}
