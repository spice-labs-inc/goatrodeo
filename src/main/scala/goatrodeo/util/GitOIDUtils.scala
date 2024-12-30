package goatrodeo.util

import java.io.InputStream
import java.security.MessageDigest


/** A set of helpers to manage GitOIDs
  */
object GitOIDUtils {

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
      bytes: InputStream,
      len: Long,
      hashType: HashType = HashType.SHA256,
      tpe: ObjectType = ObjectType.Blob
  ): Array[Byte] = {
    // get the prefix bytes in local encoding... which should be okay given that
    // the string should be ASCII
    val prefix =
      String.format("%s %d\u0000", tpe.gitoidName(), len).getBytes();
    val md = hashType.getDigest();
    md.update(prefix);

    val buf = new Array[Byte](4096)
    var keepRunning = true
    while (keepRunning) {
      val read = bytes.read(buf)
      if (read <= 0) {
        bytes.close()
        keepRunning = false
      } else {
        md.update(buf, 0, read)
      }
    }

    md.digest()
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
      bytes: InputStream,
      len: Long,
      hashType: HashType = HashType.SHA256,
      tpe: ObjectType = ObjectType.Blob
  ): String = {
    Helpers.toHex(
      computeGitOID(bytes, len, hashType, tpe)
    )
  }

  /** A `gitoid` URL. See
    * https://www.iana.org/assignments/uri-schemes/prov/gitoid
    *
    * @return
    *   the `gitoid` URL
    */
  def url(
      inputStream: InputStream,
      len: Long,
      hashType: HashType,
      tpe: ObjectType = ObjectType.Blob,
      swhid: Boolean
  ): String = {
    if (swhid) {
      String.format(
        "swh:1:cnt:%s",
        hashAsHex(inputStream, len, HashType.SHA1, ObjectType.Blob)
      );
    } else {
      String.format(
        "gitoid:%s:%s:%s",
        tpe.gitoidName(),
        hashType.hashTypeName(),
        hashAsHex(inputStream, len, hashType, tpe)
      );
    }
  }

  /**
    * Given an artifact, compute the hashes and gitoids for the artifact
    *
    * @param theFile the artifact
    * @param continue_? the gitoid sha256 is always computed. Given the gitoid, check to see if
             other gitoids need to be computed. This is effectively a check to see if the gitoid has
             been computed and thus the artifact has been seen
    * @return a type of the gitoidsha256 and the other hashes
    */
  def computeAllHashes(
      theFile: ArtifactWrapper,
      continue_? : String => Boolean
  ): (String, Vector[String]) = {
    def is(): InputStream = theFile.asStream()
    val gitoidSha256 = url(is(), theFile.size(), HashType.SHA256, swhid = false)

    if (continue_?(gitoidSha256)) {
      (
        gitoidSha256,
        Vector(
          url(is(), theFile.size(), HashType.SHA1, swhid = false),
          url(is(), theFile.size(), HashType.SHA1, swhid = true),
          String
            .format("sha1:%s", Helpers.toHex(Helpers.computeSHA1(is())))
            .intern(),
          String
            .format("sha256:%s", Helpers.toHex(Helpers.computeSHA256(is())))
            .intern(),
          String
            .format("md5:%s", Helpers.toHex(Helpers.computeMD5(is())))
            .intern()
        )
      )
    } else (gitoidSha256, Vector())
  }
}

