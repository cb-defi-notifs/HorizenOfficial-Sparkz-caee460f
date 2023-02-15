package sparkz.util

/**
  * Trait with bytes to string encoder
  */
trait SparkzEncoding {
  implicit val encoder: SparkzEncoder = SparkzEncoder.default
}
