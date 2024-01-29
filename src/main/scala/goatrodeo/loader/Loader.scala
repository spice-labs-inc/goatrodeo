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

import java.io.File
import java.util.jar.JarFile
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.math.BigInteger
import upickle.default.*
import java.io.FileInputStream
import java.io.ByteArrayInputStream
import scala.collection.JavaConverters._
import ujson.Bool
import java.util.Properties
import org.apache.bcel.classfile.ClassParser
import java.net.URLEncoder
import scala.util.Try
import scala.util.Success
import scala.util.Failure
import ujson.Value
import goatrodeo.util.{Helpers, GitOID}
import goatrodeo.omnibor.{Entry, EntryMetaData, Storage}
import java.io.FileOutputStream
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import os.read
import scala.collection.SortedSet

/** Misc utilities to load a JAR file and do basic GitOID creation
  */
object Loader {

  /** Given a filename, open a JAR file
    *
    * @param name
    *   the name of the file
    * @return
    *   the JAR file if it could be opened
    */
  def openJar(name: String): Option[JarFile] = {
    Try {
      val file = new File(name)
      val ret = new JarFile(file, true)
      ret
    }.toOption
  }

  /** Given an input (either a full path to a file or a `File`), compute a
    * `TopLevel` for the input
    *
    * @param input
    *   the path or File
    * @param fileName
    *   the canonical name of the file
    * @return
    *   the `TopLevel` computed
    */
  def buildPackage(input: String | File, fileName: String): Option[TopLevel] = {
    input match {
      case s: String => buildPackage(new File(s), fileName)
      case f: File =>
        Try {
          val is = new FileInputStream(f)
          val bytes = Helpers.slurpInput(is)
          val fileGitoid = GitOID.url(bytes)
          val jar = new JarFile(f, true)

          // compute the information for each of the files
          val files =
            (for { i <- jar.entries().asScala if !i.isDirectory() } yield {

              val inputStream = jar.getInputStream(i)
              val bytes = Helpers.slurpInput(inputStream)
              val name = i.getName()
              val fileType = FileType.theType(name, Some(bytes))

              val gitoid = GitOID.url(bytes)
              PackageFile(gitoid, Some(name), fileType)

            }).toList

              // compute the PackageIdentifier -- FIXME -- this can be done better... maybe look at pom.xml?
          val packageId: Option[PackageIdentifier] =
            files.filter(_.fileType.isPomProps()).map(_.fileType) match {
              case FileType.POM(POMTypes.Properties, Some(bytes)) :: _ => {
                val is = new ByteArrayInputStream(bytes.getBytes("UTF-8"))
                val props = new Properties()
                props.load(is)
                val artifactId = props.get("artifactId") match {
                  case s: String => s
                  case _         => "???"
                }
                val groupId = props.get("groupId") match {
                  case s: String => s
                  case _         => "????"
                }
                val version = props.get("version") match {
                  case s: String => s
                  case _         => "?????"
                }
                Some(
                  PackageIdentifier(
                    PackageProtocol.Maven,
                    groupId,
                    artifactId,
                    version
                  )
                )
              }
              case _ => None
            }

            // Get the vulnerabilities
          val packageVulns: Option[ujson.Value] =
            for {
              pid <- packageId
              vulns <- pid.getOSV().toOption
            } yield vulns

          TopLevel.Package(
            gitoid = fileGitoid,
            contains = files.toVector,
            containedBy = Vector(),
            identifier = packageId,
            vulnerabilities = packageVulns,
            name = Some(fileName)
          )
        }.toOption
    }
  }
}

/** A set of helpers to manage GitOIDs
  */
object GitOID {

  /** Given a full OmniBOR URI, parse into a triple of first 3 hex chars of
    * hash, second 3 hex chars of hash, rest of the hex chars of hash
    *
    * @param uri
    *   the OmniBOR URI
    * @return
    *   the split filename
    */
  def urlToFileName(uri: String): (String, String, String) = {
    val idx = uri.lastIndexOf(":")
    val str = if (idx >= 0) {
      uri.substring(idx + 1)
    } else uri
    (str.substring(0, 3), str.substring(3, 6), str.substring(6))
  }

  /** The object type
    */
  enum ObjectType {
    case Blob, Tree, Commit, Tag

    /** Get the canonical name for the Object Type
      *
      * @return
      *   the canonical name for the object type
      */
    def gitoidName(): String = {
      this match {
        case Blob   => "blob"
        case Tree   => "tree"
        case Commit => "commit"
        case Tag    => "tag"
      }
    }
  }

  /** The hash type
    */
  enum HashType {
    case SHA1, SHA256

    /** Based on the hash type, get the MessageDigest
      *
      * @return
      *   the MessageDigest for the type
      */
    def getDigest(): MessageDigest = {
      this match {
        case SHA1   => MessageDigest.getInstance("SHA-1")
        case SHA256 => MessageDigest.getInstance("SHA-256")
      }
    }

    /** Get the canonical name for the hash type
      *
      * @return
      *   the canonical name
      */
    def hashTypeName(): String = {
      this match {
        case SHA1   => "sha1"
        case SHA256 => "sha256"
      }
    }
  }

  /** Given an object type, a pile of bytes, and a hash type, compute the GitOID
    *
    * @param hashType
    *   the hash type
    * @param type
    *   the type of object
    * @param bytes
    *   the bytes to compute the gitoid for
    * @return
    *   the gitoid
    */
  def computeGitOID(
      bytes: Array[Byte],
      hashType: HashType = HashType.SHA256,
      tpe: ObjectType = ObjectType.Blob
  ): Array[Byte] = {
    // get the prefix bytes in local encoding... which should be okay given that
    // the string should be ASCII
    val prefix =
      String.format("%s %d\u0000", tpe.gitoidName(), bytes.length).getBytes();
    val md = hashType.getDigest();
    md.update(prefix);

    md.digest(bytes);
  }



  /** Take bytes, compute the GitOID and return the hexadecimal bytes
    * representing the GitOID
    *
    * @param bytes
    *   the bytes to compute gitoid for
    * @param hashType
    *   the hash type... default SHA256
    * @param tpe
    *   the object type... default Blob
    * @return
    *   the hex representation of the GitOID
    */
  def hashAsHex(
      bytes: Array[Byte],
      hashType: HashType = HashType.SHA256,
      tpe: ObjectType = ObjectType.Blob
  ): String = {
    Helpers.toHex(
      computeGitOID(bytes, hashType, tpe)
    )
  }

  /** Take bytes, compute the GitOID and return the full OmniBOR URI
    *
    * @param bytes
    *   the bytes to compute gitoid for
    * @param hashType
    *   the hash type... default SHA256
    * @param tpe
    *   the object type... default Blob
    * @return
    *   the omnibor URI for the bytes, hash type, and object type
    */
  // def asString(
  //     bytes: Array[Byte],
  //     hashType: HashType = HashType.SHA256,
  //     tpe: ObjectType = ObjectType.Blob
  // ): String = {
  //   String.format(
  //     "%s:%s",
  //     hashType.hashTypeName(),
  //     hashAsHex(bytes, hashType, tpe)
  //   );
  // }

  /** A `gitoid` URL. See
    * https://www.iana.org/assignments/uri-schemes/prov/gitoid
    *
    * @return
    *   the `gitoid` URL
    */
  def url(
      bytes: Array[Byte],
      hashType: HashType = HashType.SHA256,
      tpe: ObjectType = ObjectType.Blob
  ): String = {
    String.format(
      "gitoid:%s:%s:%s",
      tpe.gitoidName(),
      hashType.hashTypeName(),
      hashAsHex(bytes, hashType, tpe)
    );
  }
}

enum PackageProtocol derives ReadWriter {
  case Maven, NPM, Docker, Deb, Gem

  def name: String = {
    this match {
      case Maven  => "maven"
      case NPM    => "npm"
      case Docker => "docker"
      case Deb    => "deb"
      case Gem    => "gem"
    }
  }
}

case class PackageIdentifier(
    protocol: PackageProtocol,
    groupId: String,
    artifactId: String,
    version: String
) derives ReadWriter {
  def purl(): String = {
    f"pkg:${protocol.name}/${URLEncoder.encode(groupId, "UTF-8")}/${URLEncoder
        .encode(artifactId, "UTF-8")}@${URLEncoder.encode(version, "UTF-8")}"
  }

  def getOSV(): Try[ujson.Value] = {
    val body: Try[(Int, String)] = Try {
      val purl = this.purl()
      import sttp.client4.quick.*
      import sttp.client4.Response
      val response = quickRequest
        .post(uri"https://api.osv.dev/v1/query")
        .body(f"""{"package": {"purl": "${purl}"}}""")
        .contentType("application/json")
        .send()

      (response.code.code, response.body)
    }

    body match {
      case Failure(exception) => Failure(exception)
      case Success((code, body)) if code / 100 == 2 && body.length() > 2 =>
        Try {
          ujson.read(body)
        }
      case Success((code, body)) =>
        Failure(new Exception(f"HTTP Response ${code}, body ${body}"))
    }
  }
}

enum POMTypes derives ReadWriter {
  case XML
  case Properties
}

enum FileType derives ReadWriter {
  case ClassFile(source: Option[String])
  case SourceFile(language: Option[String])
  case POM(subtype: POMTypes, contents: Option[String])
  case Package
  case Other

  def isPomProps(): Boolean =
    this match {
      case POM(POMTypes.Properties, Some(bytes)) => true
      case _                                     => false
    }

  def typeName(): Option[String] = {
    this match {
      case ClassFile(source)      => Some("class")
      case SourceFile(language)   => Some("source")
      case POM(subtype, contents) => Some("pom")
      case Package                => Some("package")
      case Other                  => Some("other")
    }
  }

  def subType(): Option[String] = {
    this match {

      case SourceFile(language)   => language
      case POM(subtype, contents) => Some(subtype.toString())
      case _                      => None
    }
  }

  def theContents(): Option[String] = {
    this match {
      case POM(subtype, contents) => contents
      case _                      => None
    }
  }
}

object FileType {
  def stringIt(in: Option[Array[Byte]]): Option[String] = {
    try {
      in.map(b => new String(b, "UTF-8"))
    } catch {
      case e: Exception => None
    }
  }
  def theType(name: String, contents: Option[Array[Byte]]): FileType = {
    name match {
      case s
          if s.startsWith("META-INF/maven/") &&
            s.endsWith("/pom.properties") =>
        POM(POMTypes.Properties, stringIt(contents))
      case s
          if s.startsWith("META-INF/maven/") &&
            s.endsWith("/pom.xml") =>
        POM(POMTypes.XML, stringIt(contents))
      case s if s.endsWith(".class") => {
        val sourceName: Option[String] = contents.map(bytes => {
          val is = new ByteArrayInputStream(bytes)
          val cp = new ClassParser(is, name)
          val clz = cp.parse()
          clz.getSourceFilePath()
        })
        ClassFile(sourceName)
      }
      case s if s.endsWith(".java")  => SourceFile(Some("java"))
      case s if s.endsWith(".scala") => SourceFile(Some("scala"))
      case s if s.endsWith(".clj")   => SourceFile(Some("clojure"))
      case _                         => Other
    }
  }
}

case class PackageFile(gitoid: String, name: Option[String], fileType: FileType)
    derives ReadWriter {
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

  def toEntry(from: TopLevel): Entry = {
    Entry(
      identifier = this.gitoid,
      contains = Vector(),
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

trait TopLevelStuff {
  def identifier: Option[PackageIdentifier]
  def contains: Vector[PackageFile]
  def containedBy: Vector[PackageFile]
  def gitoid: String
  def name: Option[String]
}

object TopLevel {
  val indexLock: Object = new Object()
  private val beingFixed = new java.util.HashSet[String]()

  def lockGitOid[T](gitoid: String)(f: => T): T = {
    beingFixed.synchronized {
      while (beingFixed.contains(gitoid)) {
        beingFixed.wait(100)
      }
      beingFixed.add(gitoid)
      beingFixed.notifyAll()
    }
    try {
      f
    } finally {
      beingFixed.synchronized {
        beingFixed.remove(gitoid)
        beingFixed.notifyAll()
      }
    }
  }
}

enum TopLevel extends TopLevelStuff derives ReadWriter {
  case Package(
      gitoid: String,
      contains: Vector[PackageFile],
      containedBy: Vector[PackageFile],
      identifier: Option[PackageIdentifier],
      vulnerabilities: Option[ujson.Value],
      name: Option[String],
      version: Int = 1
  )

  case File(
      gitoid: String,
      contains: Vector[PackageFile],
      containedBy: Vector[PackageFile],
      identifier: Option[PackageIdentifier],
      fileType: FileType,
      name: Option[String],
      version: Int = 1
  )

  def purl(): Option[String] = {
    identifier.map(_.purl())
  }

  def toEntry(): Entry = {
    this match {
      case Package(
            gitoid,
            contains,
            containedBy,
            identifier,
            vulnerabilities,
            name,
            version
          ) =>
        Entry(
          gitoid,
          contains.map(_.gitoid).sorted,
          containedBy.map(_.gitoid).sorted,
          EntryMetaData(
            filename = name,
            purl = this.purl(),
            vulnerabilities,
            Some("package"),
            Some("jar"),
            None,
            None,
            _version = 1
          ),
          _timestamp = System.currentTimeMillis(),
          _version = 1,
          _type = "gitoid"
        )
      case File(
            gitoid,
            contains,
            containedBy,
            identifier,
            fileType,
            name,
            version
          ) =>
        Entry(
          identifier = gitoid,
          contains = contains.map(_.gitoid).sorted,
          containedBy = containedBy.map(_.gitoid).sorted,
          metadata = EntryMetaData(
            name,
            None,
            None,
            fileType.typeName(),
            fileType.subType(),
            fileType.theContents(),
            None,
            _version = 1
          ),
          _timestamp = System.currentTimeMillis(),
          _version = 1,
          _type = "gitoid"
        )
    }
  }

  def vulns(): ujson.Value = {
    this match {
      case Package(_, _, _, _, Some(vuln), _, _) =>
        val ret = for {

          vo <- vuln.objOpt.toList
          vo <- vo.get("vulns").toList
          va <- vo.arrOpt.toList
          vo <- va
          vo <- vo.objOpt
          sev <- vo.get("severity")
        } yield sev
        ret
      case _ => ujson.Null
    }
  }

  def gitoids(): Seq[String] = {
    gitoid :: (contains.toList ::: containedBy.toList).map(_.gitoid)
  }

  def updateIndex(storage: Storage): Unit = {

    this.purl() match {
      case Some(purl) =>
        TopLevel.indexLock.synchronized {

          val purlFile = f"purl/${purl}.json"

          lazy val defaultPurlHolder = 
            Entry(identifier = purl,
            contains = Vector(this.gitoid),
            containedBy = Vector(),
            metadata = EntryMetaData(
              filename = Some(purl),
              purl = Some(purl),
              vulnerabilities = None,
              filetype = Some("purl"),
              filesubtype = None,
              contents = None,
              other = None,
              _version = 1
            ),
            _timestamp = System.currentTimeMillis(),
            _version = 1,
            _type = "purl"
          )

          val ph: Entry = if (storage.exists(purlFile)) {
            try {
              storage.read(purlFile) match {
                case Some(bytes) => upickle.default.read(bytes)
                case _           => defaultPurlHolder
              }
            } catch {
              case _: Exception => defaultPurlHolder

            }
          } else
            defaultPurlHolder

          val ph2 = ph.copy(
            _timestamp = System.currentTimeMillis(),
            contains = (Set(ph.contains :+ this.gitoid :_*).toVector.sorted)
          )
          storage.write(purlFile, f"${write(ph2, indent = 2)}\n")
        }
      case _ =>
    }
  }

  def fixDependents(storage: Storage): Unit = {
    val thispf = this.intoPackageFile()
    for { pf <- contains } {
      TopLevel.lockGitOid(pf.gitoid) {
        val (path_1, path_2, fileName) = GitOID.urlToFileName(pf.gitoid)

        val toDo = f"${path_1}/${path_2}/${fileName}.json"
        val core: Entry = if (storage.exists(toDo)) {
          Try {
            storage.read(toDo) match {
              case Some(bytes) => upickle.default.read[Entry](bytes)
              case _           => pf.toEntry(this)
            }
          } match {

            case Success(value) => value
            case _              => pf.toEntry(this)
          }
        } else {
          pf.toEntry(this)
        }

        val updated: Vector[GitOID] =
          if (core.containedBy.contains(thispf.gitoid)) core.containedBy
          else if (core.containedBy.length > 4096)
            core.containedBy // avoid reverse indexes of super common files
          else core.containedBy :+ thispf.gitoid

        val res = core.copy(containedBy = updated)
        storage.write(toDo, write(res, indent = 2))
      }
    }
  }

  def intoPackageFile(): PackageFile = {
    PackageFile(
      gitoid,
      name,
      this match {
        case File(_, _, _, _, fileType, _, _) => fileType
        case Package(_, _, _, _, _, _, _)     => FileType.Package
      }
    )
  }

}
