import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class FiletypeDetectionAndMetadataSuite extends AnyFlatSpec with Matchers {

  // File formats that are packaged in zip (jar, war, ear, apk, etc.)
  "A plain old .zip file" must "be detected" in {
    true mustBe true
  }

  it must "extract the ZIP Metadata (if any)" in {

  }

  "A .jar file" must "be detected as such" in {
    true mustBe true
  }

  it must "extract the Jar metadata" in {
    true mustBe true
  }

  "A .war file" must "be detected as such" in {

  }

  it must "extract the WAR Metadata" in {

  }

  "An .ear file" must "be detected as such" in {

}

  it must "extract the EAR Metadata" in {

  }


  "An .iso file" must "be detected as such" in {

  }

  it must "extract the ISO Metadata" in {

  }

  // OS Packages
  "A .deb file" must "be detected as such" in {

  }

  it must "extract the DEB Metadata" in {

  }

  "An .rpm file" must "be detected as such" in {

  }

  it must "extract the RPM Metadata" in {

  }

  // Tar based formats
  "A .gem file" must "be detected as such" in {

  }

  it must "extract the GEM Metadata" in {

  }

  "An android .apk archive" must "be detected as such" in {

  }

  it must "extract the APK Metadata" in {

  }

  "A regular old .tar file" must "be detected as such" in {

  }

  it must "extract the TAR Metadata (if any)" in {

  }

  "A tarball (.tar.gz / .tgz) " must "be detected as such" in {

  }

  it must "extract the Tarball Metadata (if any)" in {

  }

  // todo - define "appropriately"
  "An unknown file type" must "be handled appropriately" in {


  }
}
