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


package goatrodeo.loader

import goatrodeo.omnibor.Entry
import goatrodeo.omnibor.EntryMetaData
import upickle.default.*

case class PackageFile(gitoid: String, name: Option[String], fileType: FileType)
    /*derives ReadWriter*/ {
  def toTopLevelFile(from: TopLevel): TopLevel = {
    TopLevel.File(
      gitoid = gitoid,
      contains = Vector(),
      containedBy = Vector(from.intoPackageFile()),
      identifier = None,
      fileType = fileType,
      name = name
    )
  }

  def subContents(): List[PackageFile] = {
    fileType.subContents()
  }

  def toEntry(from: TopLevel): Entry = {
    Entry(
      identifier = this.gitoid,
      contains = fileType.subContents().map(_.gitoid).toVector,
      containedBy = Vector(from.gitoid),
      metadata = EntryMetaData(
        this.name,
        None,
        None,
        this.fileType.typeName(),
        this.fileType.subType(),
        None,
        None,
        _version = 1
      ),
      _timestamp = System.currentTimeMillis(),
      _version = 1,
      _type = "gitoid"
    )
  }
}