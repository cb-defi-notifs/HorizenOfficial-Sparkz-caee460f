package sparkz.util

import com.typesafe.scalalogging.StrictLogging

trait SparkzLogging extends StrictLogging {
  @inline protected def log = logger
}