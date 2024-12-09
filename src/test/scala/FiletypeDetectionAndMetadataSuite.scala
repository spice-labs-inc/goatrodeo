import com.typesafe.scalalogging.Logger
import org.apache.tika.config.TikaConfig
import org.apache.tika.io.TikaInputStream
import org.apache.tika.metadata.Metadata
import org.apache.tika.metadata.TikaCoreProperties
import org.apache.tika.mime.MediaType
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import java.io.File

class FiletypeDetectionAndMetadataSuite extends AnyFlatSpec with Matchers {
  val tika = new TikaConfig()
  val logger = Logger("FiletypeDetectionAndMetadataSuite")

  val MIME_ZIP = "application/zip"
  val MIME_JAR = "application/java-archive"
  val MIME_WAR = "application/x-tika-java-web-archive"
  val MIME_EAR = "application/x-tika-java-enterprise-archive"
  val MIME_ISO = "application/x-iso9660-image"
  val MIME_DEB = "application/x-debian-package"
  val MIME_RPM = "application/x-rpm"
  val MIME_GEM = "application/x-tar" // TODO - we should add a custom detecter to custom-types.xml for gems based on .gem
  val MIME_APK = "application/vnd.android.package-archive"
  val MIME_TAR = "application/x-gtar"
  val MIME_GZIP = "application/gzip"

  def detectFiletype(f: File): MediaType = {
    val metadata = new Metadata()
    metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, f.toString)
    val detected = tika.getDetector.detect(TikaInputStream.get(f), metadata)
    println(s"Detected filetype for ${f.toString} media type: $detected Main Type: ${detected.getType} Subtype: ${detected.getSubtype}")
    detected
  }
  // File formats that are packaged in zip (jar, war, ear, apk, etc.)
  "A plain old .zip file" must "be detected" in {
    val zip1 = new File("test_data/HP1973-Source.zip")
    detectFiletype(zip1).toString mustBe MIME_ZIP
  }

  it must "extract the ZIP Metadata (if any)" in {

  }

  "A .jar file" must "be detected as such" in {
    val jar1 = new File("test_data/hidden1.jar")
    detectFiletype(jar1).toString mustBe MIME_JAR

    val jar2 = new File("test_data/hidden2.jar")
    detectFiletype(jar2).toString mustBe MIME_JAR

    val jar3 = new File("test_data/log4j-core-2.22.1.jar")
    detectFiletype(jar3).toString mustBe MIME_JAR
  }

  it must "extract the Jar metadata" in {
    true mustBe true
  }

  "A .war file" must "be detected as such" in {
    val war1 = new File("test_data/sample-tomcat-6.war")
    detectFiletype(war1).toString mustBe MIME_WAR
  }

  it must "extract the WAR Metadata" in {

  }

  "An .ear file" must "be detected as such" in {
    val ear1 = new File("test_data/EnterpriseHelloWorld.ear")
    detectFiletype(ear1).toString mustBe MIME_EAR
  }

  it must "extract the EAR Metadata" in {

  }


  "An .iso file" must "be detected as such" in {
    val iso1 = new File("test_data/iso_tests/iso_of_archives.iso")
    val iso2 = new File("test_data/iso_tests/simple.iso")
    detectFiletype(iso1).toString mustBe MIME_ISO
    detectFiletype(iso2).toString mustBe MIME_ISO
  }

  it must "extract the ISO Metadata" in {

  }

  // OS Packages
  "A .deb file" must "be detected as such" in {
    val deb1 = new File("test_data/tk8.6_8.6.14-1build1_amd64.deb")
    detectFiletype(deb1).toString mustBe MIME_DEB
  }

  it must "extract the DEB Metadata" in {

  }

  "An .rpm file" must "be detected as such" in {
    val rpm1 = new File("test_data/tk-8.6.8-1.el8.x86_64.rpm")
    detectFiletype(rpm1).toString mustBe MIME_RPM
  }

  it must "extract the RPM Metadata" in {

  }

  // Tar based formats
  "A .gem file" must "be detected as such" in {
    val gem1 = new File("test_data/gem_tests/java-properties-0.3.0.gem")
    detectFiletype(gem1).toString mustBe MIME_GEM
  }

  it must "extract the GEM Metadata" in {

  }

  "An android .apk archive" must "be detected as such" in {
    val apk1 = new File("./test_data/apk_tests/bitbar-sample-app.apk")
    val apk2 = new File("./test_data/tk-8.6.13-r2.apk") // this one just detects as a gzip, it looks like it might be an ALPINE apk…
    detectFiletype(apk1).toString mustBe MIME_APK
    //detectFiletype(apk2).toString mustBe MIME_APK
  }

  it must "extract the APK Metadata" in {

  }

  "A regular old .tar file" must "be detected as such" in {
    val tar1 = new File("./test_data/nested.tar")
    val tar2 = new File("./test_data/hidden.tar")
    val tar3 = new File("./test_data/ics_test.tar")
    detectFiletype(tar1).toString mustBe MIME_TAR
    detectFiletype(tar2).toString mustBe MIME_TAR
    detectFiletype(tar3).toString mustBe MIME_TAR
  }

  it must "extract the TAR Metadata (if any)" in {

  }

  "A tarball (.tar.gz / .tgz) " must "be detected as such" in {
    val gz1 = new File("test_data/empty.tgz")
    detectFiletype(gz1).toString mustBe MIME_GZIP
  }

  it must "extract the Tarball Metadata (if any)" in {

  }

  // todo - define "appropriately"
  "An unknown file type" must "be handled appropriately" in {


  }
}
