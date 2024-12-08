package goatrodeo.model

import goatrodeo.model.PackageMetadata.PackageType

// todo - we probably need to wrangle PackageIdentifier and its computePURL code to merger with this structure and reduce duplicative work
object PackageMetadata {
  enum PackageType {
    case JAR, EAR, WAR, ISO, DEB, RPM, GEM, AndroidAPK,
        GenericZIP, GenericTAR, GenericTGZ, GenericTARBZ2, Unknown // differentiate APKs because alpine linux *also* uses .apk for their package format…
    def name: String = {
      this match
        case PackageType.JAR => "jar"
        case PackageType.EAR => "ear"
        case PackageType.WAR => "war"
        case PackageType.ISO => "iso"
        case PackageType.DEB => "deb"
        case PackageType.RPM => "rpm"
        case PackageType.GEM => "gem"
        case PackageType.AndroidAPK => "android_apk"
        case PackageType.GenericZIP => "zip"
        case PackageType.GenericTAR => "tar"
        case PackageType.GenericTGZ => "tar.gz"
        case PackageType.GenericTARBZ2 => "tar.bz2"
        case PackageType.Unknown => "unknown_format"
    }
  }
}

// todo - should this be sealed, or do we want people outside this file to be able to define their own / custom Metadatas?
trait PackageMetadata {
  type _type: PackageType
  // todo - can we more concretely get these to not be Option while still being flexible?
  def packageName: Option[String]
  def version: Option[String]
  def arch: Option[String] // todo - enum? can we guess at all the archs we'll run up against?
  def maintainer: Option[String]
  def dependencies: Set[String] // todo - make these a proper type too?
  def description: Option[String]
  def otherAttributes: Map[String, String]
}

// todo - apply method that can generate the instance from the raw metadata file
object DEBPackageMetadata { }
final case class DEBPackageMetadata(packageName: Option[String], version: Option[String], arch: Option[String],
                                    maintainer: Option[String], description: Option[String],
                                    dependencies: Set[String] = Set.empty, otherAttributes: Map[String, String] = Map.empty) extends PackageMetadata {
  type _type = PackageType.DEB
}

// todo - apply method that can generate the instance from the raw metadata file
object RPMPackageMetadata {}

final case class RPMPackageMetadata(packageName: Option[String], version: Option[String], arch: Option[String],
                                    maintainer: Option[String], description: Option[String],
                                    dependencies: Set[String] = Set.empty, otherAttributes: Map[String, String] = Map.empty) extends PackageMetadata {
  type _type = PackageType.RPM
}

// todo - apply method that can generate the instance from the raw metadata file
object GEMPackageMetadata {}

final case class GEMPackageMetadata(packageName: Option[String], version: Option[String], arch: Option[String],
                                    maintainer: Option[String], description: Option[String],
                                    dependencies: Set[String] = Set.empty, otherAttributes: Map[String, String] = Map.empty) extends PackageMetadata {
  type _type = PackageType.GEM
}


// todo - apply method that can generate the instance from the raw metadata file
object JARPackageMetadata {}

final case class JARPackageMetadata(packageName: Option[String], version: Option[String], arch: Option[String],
                                    maintainer: Option[String], description: Option[String],
                                    dependencies: Set[String] = Set.empty, otherAttributes: Map[String, String] = Map.empty) extends PackageMetadata {
  type _type = PackageType.JAR
}

// todo - apply method that can generate the instance from the raw metadata file
object EARPackageMetadata {}

final case class EARPackageMetadata(packageName: Option[String], version: Option[String], arch: Option[String],
                                    maintainer: Option[String], description: Option[String],
                                    dependencies: Set[String] = Set.empty, otherAttributes: Map[String, String] = Set.empty) extends PackageMetadata {
  type _type = PackageType.EAR
}


// todo - apply method that can generate the instance from the raw metadata file
object WARPackageMetadata {}

final case class WARPackageMetadata(packageName: Option[String], version: Option[String], arch: Option[String],
                                    maintainer: Option[String], description: Option[String],
                                    dependencies: Set[String] = Set.empty, otherAttributes: Map[String, String] = Set.empty) extends PackageMetadata {
  type _type = PackageType.WAR
}

// I don't think there's really any metadata we can use in ISOs…
object ISOPackageMetadata {}

final case class ISOPackageMetadata(packageName: Option[String], version: Option[String], maintainer: Option[String],
                                    otherAttributes: Map[String, String] = Set.empty) extends PackageMetadata {
  type _type = PackageType.ISO

  override def arch: Option[String] = None
  override def dependencies: Set[String] = Set.empty
  override def description: Option[String] = None
}

// todo - apply method that can generate the instance from the raw metadata file
object AndroidAPKPackageMetadata {}

final case class AndroidAPKPackageMetadata(packageName: Option[String], version: Option[String], arch: Option[String],
                                    maintainer: Option[String], description: Option[String],
                                    dependencies: Set[String] = Set.empty, otherAttributes: Map[String, String] = Map.empty) extends PackageMetadata {
  type _type = PackageType.AndroidAPK
}

// right now I don't think there's any metadata we can grab from a Generic Zip
case object GenericZIPPackageMetadata extends PackageMetadata {
  type _type = PackageType.GenericZIP
}

// right now I don't think there's any metadata we can grab from a Generic tar
case object GenericTARPackageMetadata extends PackageMetadata {
  type _type = PackageType.GenericTAR
}

// right now I don't think there's any metadata we can grab from a Generic tarball
case object GenericTGZPackageMetadata extends PackageMetadata {
  type _type = PackageType.GenericTGZ
}

// right now I don't think there's any metadata we can grab from a Generic tar.bz2
case object GenericTBZ2PackageMetadata extends PackageMetadata {
  type _type = PackageType.GenericTBZ2
}
