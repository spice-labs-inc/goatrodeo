package io.spicelabs.goatrodeo.util

package object filetypes {

  object MIMETypeMappings {
    val MIME_ZIP = "application/zip"
    val MIME_JAR = "application/java-archive"
    val MIME_WAR = "application/x-tika-java-web-archive"
    val MIME_EAR = "application/x-tika-java-enterprise-archive"
    val MIME_ISO = "application/x-iso9660-image"
    val MIME_DEB = "application/x-debian-package"
    val MIME_RPM = "application/x-rpm"
    val MIME_GEM = "application/x-tar" // TODO - we should add a custom detecter to custom-types.xml for gems based on .gem
    //  val MIME_GEM = "application/x-ruby-gem-package" // Not working right now with the custom mime types, return later
    val MIME_APK = "application/vnd.android.package-archive"
    val MIME_TAR = "application/x-gtar"
    val MIME_GZIP = "application/gzip"
  }
}

