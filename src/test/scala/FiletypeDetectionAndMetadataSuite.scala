import com.typesafe.scalalogging.Logger
import io.spicelabs.goatrodeo.util.filetypes
import io.spicelabs.goatrodeo.util.filetypes.*
import org.apache.tika.config.TikaConfig
import org.apache.tika.mime.MediaType
import org.scalatest.TryValues._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import java.io.File

class FiletypeDetectionAndMetadataSuite extends AnyFlatSpec with Matchers {
  import MIMETypeMappings.*

  val tika = new TikaConfig()
  val logger = Logger("FiletypeDetectionAndMetadataSuite")


  // File formats that are packaged in zip (jar, war, ear, apk, etc.)
  "A plain old .zip file" must "be detected" in {
    val zip1 = new File("test_data/HP1973-Source.zip")
    MIMETypeMappings.detectMIMEType(zip1) mustBe MIME_ZIP
  }

  it must "extract the ZIP Metadata (if any)" in {

  }

  "A .jar file" must "be detected as such" in {
    val jar1 = new File("test_data/hidden1.jar")
    MIMETypeMappings.detectMIMEType(jar1) mustBe MIME_JAR

    val jar2 = new File("test_data/hidden2.jar")
    MIMETypeMappings.detectMIMEType(jar2) mustBe MIME_JAR

    val jar3 = new File("test_data/log4j-core-2.22.1.jar")
    MIMETypeMappings.detectMIMEType(jar3) mustBe MIME_JAR
  }

  it must "extract the Jar metadata" in {
  }

  "A .war file" must "be detected as such" in {
    val war1 = new File("test_data/sample-tomcat-6.war")
    MIMETypeMappings.detectMIMEType(war1) mustBe MIME_WAR
  }

  it must "extract the WAR Metadata" in {

  }

  "An .ear file" must "be detected as such" in {
    val ear1 = new File("test_data/EnterpriseHelloWorld.ear")
    MIMETypeMappings.detectMIMEType(ear1) mustBe MIME_EAR
  }

  it must "extract the EAR Metadata" in {

  }


  "An .iso file" must "be detected as such" in {
    val iso1 = new File("test_data/iso_tests/iso_of_archives.iso")
    val iso2 = new File("test_data/iso_tests/simple.iso")
    MIMETypeMappings.detectMIMEType(iso1) mustBe MIME_ISO
    MIMETypeMappings.detectMIMEType(iso2) mustBe MIME_ISO
  }

  it must "extract the ISO Metadata" in {

  }

  // OS Packages
  "A .deb file" must "be detected as such" in {
    val deb1 = new File("test_data/tk8.6_8.6.14-1build1_amd64.deb")
    MIMETypeMappings.detectMIMEType(deb1) mustBe MIME_DEB
  }

  it must "extract the DEB Metadata" in {
    val f = new File("test_data/tk8.6_8.6.14-1build1_amd64.deb")
    val meta = MIMETypeMappings.resolveMetadata(f)
    meta.success.value mustBe Map("Architecture" -> "amd64", "Maintainer" -> "Ubuntu Developers <ubuntu-devel-discuss@lists.ubuntu.com>", "Description" -> "Tk toolkit for Tcl and X11 v8.6 - windowing shell Tk is a cross-platform graphical toolkit which provides the Motif look-and-feel and is implemented using the Tcl scripting language. This package contains the windowing Tcl/Tk shell (wish).", "Section" -> "interpreters", "Package" -> "tk8.6", "Priority" -> "optional", "Installed-Size" -> "41", "Homepage" -> "http://www.tcl.tk/", "Depends" -> "libc6 (>= 2.34), libtcl8.6 (>= 8.6.0), libtk8.6 (>= 8.6.0)", "Conflicts" -> "libtk-img (<< 1.2.5), tk40 (<= 4.0p3-2)", "Version" -> "8.6.14-1build1", "Multi-Arch" -> "foreign", "Original-Maintainer" -> "Debian Tcl/Tk Packagers <pkg-tcltk-devel@lists.alioth.debian.org>")
  }

  "An .rpm file" must "be detected as such" in {
    val rpm1 = new File("test_data/tk-8.6.8-1.el8.x86_64.rpm")
    MIMETypeMappings.detectMIMEType(rpm1) mustBe MIME_RPM
  }

  it must "extract the RPM Metadata" in {

  }

  // Tar based formats
  "A .gem file" must "be detected as such" in {
    val gem1 = new File("test_data/gem_tests/java-properties-0.3.0.gem")
    MIMETypeMappings.detectMIMEType(gem1) mustBe MIME_GEM
  }

  it must "extract the GEM Metadata" in {
    val f = new File("test_data/gem_tests/java-properties-0.3.0.gem")
    val meta = MIMETypeMappings.resolveMetadata(f)
    meta.success.value mustBe Map("gem" -> "ruby", "spam" -> "eggs")
  }

  "An android .apk archive" must "be detected as such" in {
    val apk1 = new File("./test_data/apk_tests/bitbar-sample-app.apk")
    val apk2 = new File("./test_data/tk-8.6.13-r2.apk") // this one just detects as a gzip, it looks like it might be an ALPINE apk…
    MIMETypeMappings.detectMIMEType(apk1) mustBe MIME_APK
    //MIMETypeMappings.detectMIMEType(apk2).toString mustBe MIME_APK
  }

  it must "extract the APK Metadata" in {

  }

  "A regular old .tar file" must "be detected as such" in {
    val tar1 = new File("./test_data/nested.tar")
    val tar2 = new File("./test_data/hidden.tar")
    val tar3 = new File("./test_data/ics_test.tar")
    MIMETypeMappings.detectMIMEType(tar1) mustBe MIME_TAR
    MIMETypeMappings.detectMIMEType(tar2) mustBe MIME_TAR
    MIMETypeMappings.detectMIMEType(tar3) mustBe MIME_TAR
  }

  it must "extract the TAR Metadata (if any)" in {

  }

  "A tarball (.tar.gz / .tgz) " must "be detected as such" in {
    val gz1 = new File("test_data/empty.tgz")
    MIMETypeMappings.detectMIMEType(gz1) mustBe MIME_GZIP
  }

  it must "extract the Tarball Metadata (if any)" in {

  }

  // todo - define "appropriately"
  "An unknown file type" must "be handled appropriately" in {


  }
}
