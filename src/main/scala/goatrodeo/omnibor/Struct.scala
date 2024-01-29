package goatrodeo.omnibor

/* Copyright 2024 David Pollak & Contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License. */

import upickle.default.*
import scala.collection.SortedSet

type GitOID = String

object Entry {

  /** Choose a string
    *
    * @param a
    *   choice 1
    * @param b
    *   choice 2
    * @return
    *   if only one exists, use that one, if both exist, use the longest
    */
  def choose(a: Option[String], b: Option[String]): Option[String] =
    (a, b) match {
      case (Some(_), None) => a
      case (None, Some(_)) => b
      case (Some(as), Some(bs)) =>
        if (as.length() > bs.length()) Some(as) else Some(bs)
      case (None, None) => None
    }

    /** Choose the correct JSON value
      *
      * @param a
      *   choice 1
      * @param b
      *   choice 2
      * @return
      *   this is biasing toward choosing the "longest" of two choice... the
      *   correct case for vulnerabilities
      */
  def chooseJson(
      a: Option[ujson.Value],
      b: Option[ujson.Value]
  ): Option[ujson.Value] =
    (a, b) match {
      case (Some(_), None) => a
      case (None, Some(_)) => b
      case (Some(as: ujson.Arr), Some(bs: ujson.Arr)) =>
        if (as.value.length > bs.value.length) Some(as) else Some(bs)
      case (Some(as: ujson.Obj), Some(bs: ujson.Obj)) =>
        if (as.value.size > bs.value.size) Some(as) else Some(bs)
      case (Some(as), Some(bs)) => Some(ujson.Arr(as, bs))
      case (None, None)         => None
    }

    /** Merge two vectors of values. Ensure unique values
      *
      * @param a
      *   set 1
      * @param b
      *   set 2
      * @param ord
      *   the implicit ordering so the thing can be sorted
      * @return
      *   the vector with unique, sorted elements
      */
  def mergeInfo[T](a: Vector[T], b: Vector[T])(implicit
      ord: Ordering[T]
  ): Vector[T] =
    Set(a ++ b: _*).toVector.sorted
}

/** An entry for a GitOID. Contains some information about the file that
  * generated the GitOID as well as GitOIDs that contain this entry and GitOIDs
  * contained by the entry.
  *
  * @param identifier
  *   -- the GitOID or PURL for the entry
  * @param contains
  *   -- the GitOIDs that are contained by this entry
  * @param containedBy
  *   -- the GitOIDs that contain this entry
  * @param metadata
  *   -- more information about this GitOID
  * @param _timestamp
  *   -- the timestamp (milliseconds since epoch) that the Entry was created or
  *   modified
  * @param _version
  *   -- the version of `Entry`... currently 1, but can be evolved as the schema
  *   evolves
  * @param _type
  *   -- either "gitiod" or "purl"
  */
case class Entry(
    identifier: GitOID,
    contains: Vector[GitOID],
    containedBy: Vector[GitOID],
    metadata: EntryMetaData,
    _timestamp: Long,
    _version: Int,
    _type: String
) derives ReadWriter {
  def merge(other: Entry): Entry = {
    import Entry._

    // if the only difference is the timestamp, just return this
    if (this == other.copy(_timestamp = this._timestamp)) { this }
    else {

      Entry(
        identifier = this.identifier,
        contains = mergeInfo(this.contains, other.contains),
        containedBy = mergeInfo(this.containedBy, other.containedBy),
        metadata = this.metadata.merge(other.metadata),
        _timestamp = System.currentTimeMillis(),
        _version = 1,
        _type = this._type
      )
    }
  }
}

/** Additional Metadata about an entry
  *
  * @param filename
  *   the optional filename
  * @param purl
  *   the optional Package URL
  * @param vulnerabilities
  *   Optional OSV-structured vulnerabilties
  * @param filetype
  *   the optional file type
  * @param filesubtype
  *   the optional file subtype (e.g. "java" if the type is "source")
  * @param contents
  *   optional content... this is usually to cache things like a POM file
  * @param other
  *   unstructured "other" information
  * @param version
  *   the version
  */
case class EntryMetaData(
    filename: Option[String] = None,
    purl: Option[String] = None,
    vulnerabilities: Option[ujson.Value],
    filetype: Option[String] = None,
    filesubtype: Option[String] = None,
    contents: Option[String] = None,
    other: Option[ujson.Value],
    _version: Int
) derives ReadWriter {

  def merge(other: EntryMetaData): EntryMetaData = {
    // don't merge if they're the same
    if (this == other) { this }
    else {
      import Entry._
      EntryMetaData(
        filename = choose(this.filename, other.filename),
        purl = choose(this.purl, other.purl),
        vulnerabilities =
          chooseJson(this.vulnerabilities, other.vulnerabilities),
        filetype = choose(this.filetype, other.filetype),
        filesubtype = choose(this.filesubtype, other.filesubtype),
        contents = choose(this.contents, other.contents),
        other = chooseJson(this.other, other.other),
        _version = 1
      )
    }
  }
}
