/* Copyright 2024 David Pollak, Spice Labs, Inc. & Contributors

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

import java.io.File

enum PackageFileType {

  case GZIP
  case TGZ
  case AR
  case CPIO
  case BZ2
  case PKZIP
  case RAR
  case SIT
  case WINZIP
  case RPM
  case XZ
}

object PackageFileType {
  def determinePackageFileType(in: File): Option[PackageFileType] = {
    val startingBytes = Helpers.slurpBlock(in)

    startingBytes.take(30).toList match {
      case 0x1f :: 0x8b :: 0x08 :: _ => Some(GZIP)
      case 0x1f :: 0x9d :: _         => Some(TGZ)
      case 0x1f :: 0xa0 :: _         => Some(TGZ)
      case 0x21 :: 0x3c :: 0x61 :: 0x72 :: 0x63 :: 0x68 :: 0x3e :: 0x0a :: _ =>
        Some(AR)
      case 0x30 :: 0x37 :: 0x30 :: 0x37 :: 0x30 :: _ => Some(CPIO)
      case 0x42 :: 0x5a :: 0x68 :: _                 => Some(BZ2)
      case 0x50 :: 0x4b :: 0x03 :: 0x04 :: _         => Some(PKZIP)
      case 0x5f :: 0x27 :: 0xa8 :: 0x89 :: _         => Some(PKZIP) // JAR file
      case 0x52 :: 0x61 :: 0x72 :: 0x21 :: 0x1a :: 0x07 :: 0x00 :: _ =>
        Some(RAR)
      case 0x52 :: 0x61 :: 0x72 :: 0x21 :: 0x1a :: 0x07 :: 0x01 :: 0x00 :: _ =>
        Some(RAR)
      case 0x53 :: 0x49 :: 0x54 :: 0x21 :: 0x00 :: _         => Some(SIT)
      case 0xed :: 0xab :: 0xee :: 0xdb :: _                 => Some(RPM)
      case 0xfd :: 0x37 :: 0x7a :: 0x58 :: 0x5a :: 0x00 :: _ => Some(XZ)
      case _                                                 => None
    }
  }

 

}
