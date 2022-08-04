package sparkz.core.utils

/**
  * Trait with bytes to string encoder
  * TODO extract to ScorexUtils project
  */
trait SparkzEncoding {
  implicit val encoder: SparkzEncoder = SparkzEncoder.default
}
