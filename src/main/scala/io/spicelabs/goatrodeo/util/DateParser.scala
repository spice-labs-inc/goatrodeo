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

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoUnit
import java.util.Date
import scala.util.Failure
import scala.util.Success
import scala.util.Try

/** Utility for parsing dates from flexible input formats.
  *
  * Uses java.time.format.DateTimeFormatter with multiple format patterns. All
  * output is in ISO 8601 UTC format.
  *
  * Supported formats:
  *   - ISO 8601: 2024-01-15, 2024-01-15T10:30:00Z, 2024-01-15T10:30:00+00:00
  *   - Common formats: 01/15/2024, 15/01/2024, Jan 15, 2024, 15 Jan 2024
  *   - Relative: today, yesterday, now
  */
object DateParser {

  // Build a formatter that tries multiple patterns in order
  private val dateTimeFormatter = new DateTimeFormatterBuilder()
    // ISO 8601 formats
    .appendOptional(DateTimeFormatter.ISO_INSTANT)
    .appendOptional(DateTimeFormatter.ISO_DATE_TIME)
    .appendOptional(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    .appendOptional(DateTimeFormatter.ISO_LOCAL_DATE)
    // Date with time and space separator
    .appendOptional(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
    .appendOptional(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"))
    // US formats
    .appendOptional(DateTimeFormatter.ofPattern("M/d/yyyy"))
    .appendOptional(DateTimeFormatter.ofPattern("MM/dd/yyyy"))
    .appendOptional(DateTimeFormatter.ofPattern("M-d-yyyy"))
    .appendOptional(DateTimeFormatter.ofPattern("MM-dd-yyyy"))
    // EU formats (will try after US)
    .appendOptional(DateTimeFormatter.ofPattern("d/M/yyyy"))
    .appendOptional(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
    .appendOptional(DateTimeFormatter.ofPattern("d-M-yyyy"))
    .appendOptional(DateTimeFormatter.ofPattern("dd-MM-yyyy"))
    // Textual month formats
    .appendOptional(DateTimeFormatter.ofPattern("MMM d yyyy"))
    .appendOptional(DateTimeFormatter.ofPattern("MMM d, yyyy"))
    .appendOptional(DateTimeFormatter.ofPattern("d MMM yyyy"))
    .appendOptional(DateTimeFormatter.ofPattern("d MMM, yyyy"))
    .appendOptional(DateTimeFormatter.ofPattern("MMMM d yyyy"))
    .appendOptional(DateTimeFormatter.ofPattern("MMMM d, yyyy"))
    .appendOptional(DateTimeFormatter.ofPattern("d MMMM yyyy"))
    .appendOptional(DateTimeFormatter.ofPattern("d MMMM, yyyy"))
    .toFormatter()

  /** Parse a date string into a Date object.
    *
    * Tries multiple date formats in order of preference. Returns Left with
    * error message if parsing fails.
    *
    * @param input
    *   the date string to parse
    * @return
    *   Either Right(Date) on success or Left(String) with error message
    */
  def parse(input: String): Either[String, Date] = {
    val trimmed = input.trim

    // Try relative dates first
    parseRelative(trimmed) match {
      case Some(date) => Right(date)
      case None       =>
        // Try parsing with the multi-format formatter
        parseWithFormatter(trimmed)
    }
  }

  /** Format a Date as ISO 8601 UTC string.
    *
    * @param date
    *   the date to format
    * @return
    *   ISO 8601 formatted string (e.g., "2024-01-15T10:30:00Z")
    */
  def toIso8601(date: Date): String = {
    val instant = date.toInstant
    DateTimeFormatter.ISO_INSTANT.format(instant)
  }

  /** Parse relative date keywords.
    *
    * @param input
    *   the input string
    * @return
    *   Some(Date) for recognized keywords, None otherwise
    */
  private def parseRelative(input: String): Option[Date] = {
    input.toLowerCase match {
      case "now" | "today" =>
        Some(Date.from(Instant.now()))
      case "yesterday" =>
        Some(Date.from(Instant.now().minus(1, ChronoUnit.DAYS)))
      case _ => None
    }
  }

  /** Parse using the multi-format DateTimeFormatter.
    *
    * Tries to parse as various temporal types and converts to Date.
    *
    * @param input
    *   the input string
    * @return
    *   Either Right(Date) on success or Left(String) on failure
    */
  private def parseWithFormatter(input: String): Either[String, Date] = {
    // Try Instant first (handles "Z" suffix)
    Try(Instant.parse(input)) match {
      case Success(instant) => return Right(Date.from(instant))
      case Failure(_)       => // Continue
    }

    // Try ZonedDateTime
    Try(ZonedDateTime.parse(input, dateTimeFormatter)) match {
      case Success(zdt) => return Right(Date.from(zdt.toInstant))
      case Failure(_)   => // Continue
    }

    // Try LocalDateTime
    Try(LocalDateTime.parse(input, dateTimeFormatter)) match {
      case Success(ldt) =>
        return Right(Date.from(ldt.toInstant(ZoneOffset.UTC)))
      case Failure(_) => // Continue
    }

    // Try LocalDate (date-only)
    Try(LocalDate.parse(input, dateTimeFormatter)) match {
      case Success(ld) =>
        return Right(Date.from(ld.atStartOfDay(ZoneOffset.UTC).toInstant))
      case Failure(_) => // Failed all formats
    }

    Left(
      s"Unable to parse date: '$input'. Supported formats include: YYYY-MM-DD, YYYY-MM-DDTHH:MM:SSZ, MM/DD/YYYY, DD/MM/YYYY, MMM D YYYY, 'today', 'yesterday', 'now'"
    )
  }
}
