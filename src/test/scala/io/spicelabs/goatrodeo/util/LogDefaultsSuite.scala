/* Copyright 2024-2026 David Pollak, Spice Labs, Inc. & Contributors

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

import io.spicelabs.goatrodeo.testing.GoatRodeoFunSuite
import io.spicelabs.goatrodeo.testsupport.LogCapture
import org.slf4j.LoggerFactory

/** Pins the bundled logback defaults that keep the run log readable.
  *
  * These are behavioral pins on the effective logging configuration loaded from
  * the bundled `logback.xml` at test time, not text comparisons against the
  * configuration file.
  */
class LogDefaultsSuite extends GoatRodeoFunSuite {

  test("bundled defaults keep the DEB/RPM readers at WARN") {
    // One "Read DEB/RPM package" INFO line per package drowns long runs over
    // package-heavy corpora; the bundled defaults keep the third-party
    // readers at WARN. The level is observed through the logging API the same
    // way the run would behave, under the capture lock so another suite's
    // capture window (root raised to ALL) cannot race the check.
    LogCapture.quiescent {
      for {
        loggerName <- Vector(
          "io.spicelabs.baharat.deb.DebReader",
          "io.spicelabs.baharat.rpm.RpmReader"
        )
      } {
        val readerLogger = LoggerFactory.getLogger(loggerName)
        assert(
          !readerLogger.isInfoEnabled(),
          s"$loggerName must not log at INFO by default"
        )
        assert(
          readerLogger.isWarnEnabled(),
          s"$loggerName must still log warnings by default"
        )
      }
    }
  }

  test("bundled defaults keep Goat Rodeo's own loggers at INFO") {
    // The run's own progress and bookkeeping lines are INFO; quieting the
    // third-party readers must never have moved this program's own output.
    LogCapture.quiescent {
      val ownLogger = LoggerFactory.getLogger("io.spicelabs.goatrodeo.Howdy")
      assert(
        ownLogger.isInfoEnabled(),
        "Goat Rodeo's own loggers must log at INFO by default"
      )
    }
  }

  test("bundled defaults keep the git library at WARN") {
    // The git-provenance capture runs jgit on every build; its internal
    // loggers (FileSnapshot, ShutdownHook, util.FS, SystemReader) debug-log
    // every probe and stat. The bundled defaults must keep the whole
    // org.eclipse.jgit tree at WARN, exactly as with the DEB/RPM readers.
    LogCapture.quiescent {
      for {
        loggerName <- Vector(
          "org.eclipse.jgit.internal.storage.file.FileSnapshot",
          "org.eclipse.jgit.util.FS",
          "org.eclipse.jgit.internal.util.ShutdownHook"
        )
      } {
        val jgitLogger = LoggerFactory.getLogger(loggerName)
        assert(
          !jgitLogger.isDebugEnabled(),
          s"$loggerName must not log at DEBUG by default"
        )
        assert(
          jgitLogger.isWarnEnabled(),
          s"$loggerName must still log warnings by default"
        )
      }
    }
  }

  test("the no-raise capture variant leaves the root level alone") {
    // The capture harness raises the root logger to ALL so captured bodies
    // cannot be filtered out; that raise is what leaks concurrently-running
    // suites' DEBUG output into the console during parallel test runs. The
    // no-raise variant exists for assertions that only need INFO and must
    // pin that the root level does not move while it captures.
    LogCapture.quiescent {
      val ctx = LoggerFactory.getILoggerFactory
        .asInstanceOf[ch.qos.logback.classic.LoggerContext]
      val root = ctx.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
      val before = root.getLevel
      val (_, events) = LogCapture.applyWithoutRaise(() => ())
      assertEquals(root.getLevel, before, "root level must not move")
      assert(events.isEmpty, "an empty body captures nothing")
    }
  }
}
