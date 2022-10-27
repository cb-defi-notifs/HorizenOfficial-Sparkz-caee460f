package sparkz.core.debug

import java.util.concurrent.atomic.AtomicInteger
import akka.actor.Actor
import org.slf4j.LoggerFactory
import org.slf4j.Logger

object MessageCounters {
  val counter:AtomicInteger = new AtomicInteger(0)  
  def logger:Logger = LoggerFactory.getLogger(this.getClass)

  def log(receiver: Any, x: Any) : Unit = {
    val current = MessageCounters.counter.incrementAndGet()
    logger.debug(current+ " "+receiver+" > " + x)
    if (current % 100 == 0){
      logger.warn("Processed: "+current)
    }
  }
}


