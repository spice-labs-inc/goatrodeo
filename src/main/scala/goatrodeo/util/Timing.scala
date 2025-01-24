package io.spicelabs.goatrodeo.util

import com.typesafe.scalalogging.Logger

import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

object Timing {
  private val logger = Logger("Timing$")
  // this is a private singleton instance of the timing class, which will record all the timing events
  private val timingInstance = new Timing()

  /**
   * wrapper for timing the execution of a function block
   * @param caller some string to identify the calling program
   * @param callerInfo additional info for the calling program such as calling arguments (`caller` should be just the fn name)
   * @param f the function block to run
   * @tparam T the return type of `f`
   * @return The result of executing `f`
   */
  def time[T](caller: String, callerInfo: String)(f: => T): T = {
    val start = System.nanoTime()
    val result = f
    val end = System.nanoTime()
    val nanoTiming = end - start
    val msTiming = nanoTiming / 1_000_000
    logger.trace(s"Took ${msTiming}ms to run '$caller'")
    timingInstance.logTime(caller, callerInfo, nanoTiming)
    result
  }

}

protected case class TimingEvent(caller: String, callerInfo: String, timeInNanos: Long)
/**
 * Class for storing a list of the calls we've made
 */
private class Timing {
  private val timerQ = new ConcurrentLinkedQueue[TimingEvent]()
  private val logger = Logger("Timing")

  // todo - dump results to JSON, log with groupBys?
  // when we shut down, calculate and log the averages of timings we found
  sys.addShutdownHook {
    // i'm not sure we'll always get to run before Logging shuts down so just print to console
    println(s"Running shutdownHook in `Timing` to average out the ${timerQ.size} timing entries")
    // we need to eentually group this by maybe caller, so we can sort out
    // - each distinct function
    // - in functions like the wrapper on Tika detect, we want both the distinct function name AND a filename
    // depending on how we want to look at the data
    // a simple average; I think we may actually want a proper median though. todo - fix me
    val numEntries = timerQ.size
    val avgTiming = timerQ.asScala.map(_.timeInNanos).sum / numEntries
    val avgMsTiming = avgTiming / 1_000_000
    println(s"! Average Timing for $numEntries timing entries: ${avgTiming}ns / ${avgMsTiming}ms")

    // some further summaries
    val groupedCallers = timerQ.asScala.groupBy(_.caller)
    for ((group, timings) <- groupedCallers) {
      val groupAvg = timings.map(_.timeInNanos).sum / timings.size
      val groupAvgMs = groupAvg / 1_000_000
      println(s"*** Average timing for caller ${group}: ${timings.size} averages: ${groupAvg}ns / ${groupAvgMs}ms")
      // some explicit sorting / grouping by file extension, somewhat hard coding something that was generic
      val infoGrouped = timings.groupBy(x => ArtifactWrapper.suffix(x.callerInfo))
      for ((subGroup, subTimings) <- infoGrouped if subGroup.isDefined) {
        val subGroupAvg = subTimings.map(_.timeInNanos).sum / subTimings.size
        val subGroupAvgMs = subGroupAvg / 1_000_000
        println(s"\t\t ~~~ Average timing for ${subGroup.get} - ${subTimings.size} entries, averages: ${subGroupAvg}ns / ${subGroupAvgMs}ms")
      }
    }
  }

  /**
   * Records the time it took to execute `caller`
   * @param caller
   * @param timing
   */
  def logTime(caller: String, callerInfo: String, timeInNanos: Long): Unit = {
    val event = TimingEvent(caller, callerInfo, timeInNanos)
    timerQ.add(event)
  }

}