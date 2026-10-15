/* Copyright 2026 David Pollak, Spice Labs, Inc. & Contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License. */

package io.spicelabs.goatrodeo.util

import org.json4s.JsonDSL.*
import org.json4s.native.JsonMethods.*

import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference

/** Run-scoped holder for the tamper-evident logging state.
  *
  * A single run has one [[correlationId]] (a UUID generated at run start), an
  * optional provider of the current log-chain head (from the [[ChainAppender]],
  * absent when tamper-evidence is disabled), and an accumulating list of the
  * `.grc` clusters written during the run (name + full 256-bit SHA-256) used to
  * build the final checksum file.
  *
  * Mutable process state is deliberate here: the value is set once at run start
  * and read from many worker threads (each batch writes its own `.grc`), which
  * is not practical to thread through the deep call chain.
  */
case class TamperEvidentLog(correlationId: String, dest: File) {

  private val headRef: AtomicReference[() => Option[String]] =
    new AtomicReference(() => None)
  private val grcRef: AtomicReference[Vector[(String, String)]] =
    new AtomicReference(Vector())
  private val cleanupRef: AtomicReference[() => Unit] =
    new AtomicReference(() => ())

  /** Initialize the run state. Call once at the start of a Goat Rodeo run. the
    * run's correlation ID
    * @param headProvider
    *   supplies the current log-chain head (None when disabled)
    * @param cleanup
    *   releases run-scoped logging resources (e.g. detach+stop the chain
    *   appender); invoked by [[reset]]
    */
  def start(
      headProvider: () => Option[String],
      cleanup: () => Unit = () => ()
  ): Unit = {
    headRef.set(headProvider)
    grcRef.set(Vector())
    cleanupRef.set(cleanup)
  }

  /** Clear all run state and release run-scoped logging resources. Called at
    * the end of a run so the correlation ID and any attached log appender do
    * not leak into subsequent work in the same JVM (e.g. other test suites or a
    * library consumer running multiple builds).
    */
  def reset(): Unit = {
    val c = cleanupRef.getAndSet(() => ())
    try c()
    catch {
      case _: Throwable => // never let cleanup failure break the run
    }
    headRef.set(() => None)
    grcRef.set(Vector())
  }

  /** The current log-chain head, if tamper-evident logging is active. */
  def currentChainHead: Option[String] = headRef.get()()

  /** Record a `.grc` cluster written during the run. */
  def addGrc(name: String, sha256Hex: String): Unit =
    grcRef.updateAndGet(_ :+ (name -> sha256Hex))

  /** All `.grc` clusters written this run, in write order. */
  def grcs: Vector[(String, String)] = grcRef.get()

  /** Write the run-level tamper-evident checksum JSON to `dest`: the
    * correlation ID, the final log chain head, and every `.grc` cluster written
    * this run (name + full 256-bit SHA-256) across all batch directories. This
    * is the out-of-band anchor a verifier checks.
    *
    * @return
    *   the written file
    */
  def writeChecksum(): File = {
    val grcsJson = grcs.map { case (n, s) => ("name" -> n) ~ ("sha256" -> s) }
    val doc = compact(
      render(
        ("correlation_id" -> correlationId) ~
          ("final_chain_head" -> currentChainHead.getOrElse("")) ~
          ("grcs" -> grcsJson)
      )
    )
    Files.writeString(dest.toPath(), doc)
    dest
  }
}
