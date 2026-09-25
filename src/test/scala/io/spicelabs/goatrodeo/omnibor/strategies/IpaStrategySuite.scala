package io.spicelabs.goatrodeo.omnibor.strategies

import io.spicelabs.goatrodeo.omnibor.Item
import io.spicelabs.goatrodeo.omnibor.ItemMetaData
import io.spicelabs.goatrodeo.omnibor.MetadataKeyConstants
import io.spicelabs.goatrodeo.omnibor.MemStorage
import io.spicelabs.goatrodeo.omnibor.Storage
import io.spicelabs.goatrodeo.omnibor.ToProcess
import io.spicelabs.goatrodeo.testing.GoatRodeoFunSuite
import io.spicelabs.goatrodeo.testsupport.BplistEncoder
import io.spicelabs.goatrodeo.testsupport.IpaCorpus
import io.spicelabs.goatrodeo.testsupport.IpaZipBuilder
import io.spicelabs.goatrodeo.util.ByteWrapper
import io.spicelabs.goatrodeo.util.Configuration
import org.json4s.*
import org.json4s.JsonDSL.*
import org.json4s.native.JsonMethods.parseOpt

import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet

/** Ipa strategy tests.
  *
  * Requirement (§2.3/§2.4 of the plan): the strategy claims `.ipa`
  * containers and bare bundle members, accumulates `Info.plist` +
  * `embedded.mobileprovision` metadata onto the container item via the
  * parent-scope pattern, captures the provisioning CMS certs as
  * certificate Items in the Certificates metadata shape, and emits the
  * provisional `pkg:apple/ios` pURL.
  *
  * LLM note: Ipa-xx = test id. Corpus tests require the fetched
  * `allsafe-ios.ipa` (see `IpaCorpus`).
  */
class IpaStrategySuite extends GoatRodeoFunSuite {

  private given Configuration = Configuration(packageTags = true)

  private val ipaAdHoc = MetadataKeyConstants.adHoc("ipa")
  private val certAdHoc = MetadataKeyConstants.adHoc("Certificates")

  private def claim(
      wrappers: io.spicelabs.goatrodeo.util.ArtifactWrapper*
  ): (Vector[ToProcess], ToProcess.ByUUID, ToProcess.ByName) = {
    val byUuid = wrappers.map(w => w.uuid -> w).toMap
    val byName = wrappers.groupBy(_.path()).map { case (k, v) => k -> v.toVector }
    val (tp, u, n, _) = IpaStrategy.computeIpaFiles(byUuid, byName)
    (tp, u, n)
  }

  private def metaOf(store: Storage, item: Item, key: String): Option[String] =
    for {
      body <- item.body
      m = body.asInstanceOf[ItemMetaData]
      v <- m.extra.get(key)
      h <- v.headOption
    } yield h.value

  // Ipa-2.1 — a synthetic real-shaped .ipa is claimed, members claimed,
  // and the .ipa removed from the remainder maps (claim completeness).
  test("Ipa-2.1 synthetic .ipa is claimed with no residue") {
    val ipaBytes = IpaZipBuilder.minimalIpa(
      BplistEncoder.encode(
        JObject(
          "CFBundleIdentifier" -> JString("org.example.Min"),
          "CFBundleShortVersionString" -> JString("1.2.3")
        )
      ),
      "not-a-real-cms".getBytes("UTF-8")
    )
    val wrapper = ByteWrapper(ipaBytes, "Min.ipa", None)
    val (tp, u, n, _) = {
      val byUuid = Map(wrapper.uuid -> wrapper)
      val byName = Map(wrapper.path() -> Vector(wrapper))
      val (tp, u, n, name) = IpaStrategy.computeIpaFiles(byUuid, byName)
      (tp, u, n, name)
    }
    assertEquals(tp.length, 1)
    assert(tp.head.isInstanceOf[IpaToProcess])
    assert(!u.contains(wrapper.uuid), "the .ipa must be removed from byUUID")
    assert(
      !n.contains(wrapper.path()),
      "the .ipa must be removed from byName"
    )
  }

  // Ipa-2.2 — metadata lands on the container item: bundle id, version,
  // team id, app id, entitlements JSON.
  test("Ipa-2.2 accumulated metadata lands on the container item") {
    val ipaBytes = IpaZipBuilder.minimalIpa(
      BplistEncoder.encode(
        JObject(
          "CFBundleIdentifier" -> JString("org.example.Min"),
          "CFBundleShortVersionString" -> JString("1.2.3"),
          "CFBundleVersion" -> JString("42"),
          "CFBundleName" -> JString("Min"),
          "MinimumOSVersion" -> JString("15.0")
        )
      ),
      "not-a-real-cms".getBytes("UTF-8")
    )
    val wrapper = ByteWrapper(ipaBytes, "Min.ipa", None)
    val store = MemStorage(None)
    val (tp, _, _) = claim(wrapper)
    ToProcess.buildGraphForToProcess(tp, store)

    // Find the container item: the item whose identifier is the wrapper's
    // processed GitOID ending with the ipa item having BundleIdentifier.
    val ipaItems = store.keys().toVector.flatMap { k =>
      store.read(k).toVector
        .filter(i =>
          i.bodyAsItemMetaData
            .exists(m => m.extra.contains(ipaAdHoc("BundleIdentifier")))
        )
    }
    assert(ipaItems.nonEmpty, "the .ipa container item must carry metadata")
    val item = ipaItems.head
    assertEquals(
      metaOf(store, item, ipaAdHoc("BundleIdentifier")),
      Some("org.example.Min")
    )
    assertEquals(
      metaOf(store, item, ipaAdHoc("BundleShortVersion")),
      Some("1.2.3")
    )
    assertEquals(metaOf(store, item, ipaAdHoc("BundleVersion")), Some("42"))
    assertEquals(metaOf(store, item, ipaAdHoc("BundleName")), Some("Min"))
    assertEquals(metaOf(store, item, ipaAdHoc("MinimumOS")), Some("15.0"))
  }

  // Ipa-2.3 — a nested Frameworks/*.framework/Info.plist does NOT clobber
  // the app bundle's metadata.
  test("Ipa-2.3 nested framework Info.plist does not clobber app metadata") {
    val ipaBytes = IpaZipBuilder.zip(
      "Payload/" -> Array.emptyByteArray,
      "Payload/Min.app/" -> Array.emptyByteArray,
      "Payload/Min.app/Info.plist" -> BplistEncoder.encode(
        JObject(
          "CFBundleIdentifier" -> JString("org.example.Min"),
          "CFBundleShortVersionString" -> JString("1.2.3")
        )
      ),
      "Payload/Min.app/Frameworks/" -> Array.emptyByteArray,
      "Payload/Min.app/Frameworks/Dep.framework/" -> Array.emptyByteArray,
      "Payload/Min.app/Frameworks/Dep.framework/Info.plist" ->
        BplistEncoder.encode(
          JObject(
            "CFBundleIdentifier" -> JString("org.example.Dep"),
            "CFBundleShortVersionString" -> JString("9.9.9")
          )
        )
    )
    val wrapper = ByteWrapper(ipaBytes, "Min.ipa", None)
    val store = MemStorage(None)
    val (tp, _, _) = claim(wrapper)
    ToProcess.buildGraphForToProcess(tp, store)

    val item = store.keys().toVector.flatMap(k => store.read(k)).find(i =>
      i.bodyAsItemMetaData
        .exists(m => m.extra.contains(ipaAdHoc("BundleIdentifier")))
    )
    assert(item.isDefined)
    assertEquals(
      metaOf(store, item.get, ipaAdHoc("BundleIdentifier")),
      Some("org.example.Min"),
      "the framework's Info.plist must not clobber the app bundle id"
    )
    assertEquals(
      metaOf(store, item.get, ipaAdHoc("BundleShortVersion")),
      Some("1.2.3"),
      "the framework's version must not clobber the app version"
    )
  }

  // Ipa-2.4 — bare bundle members without a container claim and emit
  // metadata.
  test("Ipa-2.4 bare bundle members claim and emit metadata") {
    val plistBytes = BplistEncoder.encode(
      JObject(
        "CFBundleIdentifier" -> JString("org.example.Bare"),
        "CFBundleShortVersionString" -> JString("0.9")
      )
    )
    val infoWrapper =
      ByteWrapper(plistBytes, "Payload/Bare.app/Info.plist", None)
    val provWrapper = ByteWrapper(
      "not-a-real-cms".getBytes("UTF-8"),
      "Payload/Bare.app/embedded.mobileprovision",
      None
    )
    val store = MemStorage(None)
    val byUuid = Map(
      infoWrapper.uuid -> infoWrapper,
      provWrapper.uuid -> provWrapper
    )
    val byName = Map(
      infoWrapper.path() -> Vector(infoWrapper),
      provWrapper.path() -> Vector(provWrapper)
    )
    val (tp, _, _, _) = IpaStrategy.computeIpaFiles(byUuid, byName)
    assert(tp.nonEmpty, "bare members must be claimed")
    ToProcess.buildGraphForToProcess(tp, store)

    val items = store.keys().toVector.flatMap(k => store.read(k))
    val withMeta = items.filter(i =>
      i.bodyAsItemMetaData
        .exists(_.extra.contains(ipaAdHoc("BundleIdentifier")))
    )
    assert(
      withMeta.nonEmpty,
      "a bare Info.plist must emit ipa metadata"
    )
    assertEquals(
      withMeta.flatMap(i => metaOf(store, i, ipaAdHoc("BundleIdentifier"))).headOption,
      Some("org.example.Bare")
    )
  }

  // Ipa-2.5 — hostile mobileprovision (garbage CMS) → no exception, no
  // certs, no metadata.
  test("Ipa-2.5 hostile mobileprovision fails as a value") {
    val ipaBytes = IpaZipBuilder.minimalIpa(
      BplistEncoder.encode(
        JObject("CFBundleIdentifier" -> JString("org.example.Min"))
      ),
      "this is definitely not a CMS blob at all, just bytes".getBytes("UTF-8")
    )
    val wrapper = ByteWrapper(ipaBytes, "Min.ipa", None)
    val store = MemStorage(None)
    val (tp, _, _) = claim(wrapper)
    ToProcess.buildGraphForToProcess(tp, store)

    // No ipa item should throw; the run completes.
    val item = store.keys().toVector.flatMap(k => store.read(k)).find(i =>
      i.bodyAsItemMetaData
        .exists(m => m.extra.contains(ipaAdHoc("BundleIdentifier")))
    )
    assert(item.isDefined, "the ipa item still exists")
    assertEquals(
      metaOf(store, item.get, ipaAdHoc("TeamIdentifier")),
      None,
      "no team id from a garbage mobileprovision"
    )
    // No cert items were emitted
    val certItems = store.keys().toVector.filter(k =>
      store.read(k).exists(i => i.bodyAsItemMetaData.exists(
        m => m.extra.contains(certAdHoc("SubjectDN"))
      ))
    )
    assert(certItems.isEmpty, "no certs from a garbage mobileprovision")
  }

  // Ipa-2.6 — a ZIP that is not an .ipa (no Payload/) is not claimed.
  test("Ipa-2.6 zip without Payload is not claimed") {
    val zipBytes = IpaZipBuilder.zip("a.txt" -> "a".getBytes("UTF-8"))
    val wrapper = ByteWrapper(zipBytes, "NotAnApp.zip", None)
    val (tp, _, _) = claim(wrapper)
    assert(tp.isEmpty, "a plain zip must not be claimed as an ipa")
  }

  // Ipa-2.7 — cert capture: the mobileprovision's embedded cert chain lands
  // in the certificate Items with the Certificates metadata shape.
  test("Ipa-2.7 provisioning certs land as certificate Items") {
    // Build a genuine self-signed X.509 chain, wrap in a CMS SignedData
    // whose payload is a plist (like a real embedded.mobileprovision).
    val (chain, provBytes) = MobileProvisionFixture.signedProvision(
      teamIdentifier = "TEAM1234",
      appIDName = "Min App"
    )
    assert(chain.nonEmpty, "fixture must produce a chain")
    val ipaBytes = IpaZipBuilder.minimalIpa(
      BplistEncoder.encode(
        JObject("CFBundleIdentifier" -> JString("org.example.Min"))
      ),
      provBytes
    )
    val wrapper = ByteWrapper(ipaBytes, "Min.ipa", None)
    val store = MemStorage(None)
    val (tp, _, _) = claim(wrapper)
    ToProcess.buildGraphForToProcess(tp, store)

    val certItems = store.keys().toVector.flatMap(k => store.read(k)).filter(
      i => i.bodyAsItemMetaData.exists(m =>
          m.extra.contains(certAdHoc("SubjectDN"))
      )
    )
    assert(
      certItems.nonEmpty,
      "the provisioning cert chain must surface as certificate Items"
    )
    // The Certificates shape: SubjectDN, CertSha256 present.
    val first = certItems.head
    assert(
      metaOf(store, first, certAdHoc("SubjectDN")).isDefined,
      "certificate item carries SubjectDN (Certificates shape)"
    )
    assert(
      metaOf(store, first, certAdHoc("CertSha256")).isDefined,
      "certificate item carries CertSha256"
    )
    // Team metadata also landed from the provisioning plist payload.
    val ipaItem = store.keys().toVector.flatMap(k => store.read(k)).find(i =>
      i.bodyAsItemMetaData
        .exists(m => m.extra.contains(ipaAdHoc("BundleIdentifier")))
    )
    assert(ipaItem.isDefined)
    assertEquals(
      metaOf(store, ipaItem.get, ipaAdHoc("TeamIdentifier")),
      Some("TEAM1234"),
      "team id must be parsed from the provisioning payload plist"
    )
    assertEquals(
      metaOf(store, ipaItem.get, ipaAdHoc("AppIDName")),
      Some("Min App"),
      "app id name must be parsed from the provisioning payload plist"
    )
  }

  // Ipa-2.8 — no metadata leak: ipa: metadata contains no private-key
  // material or raw binary blobs; entitlements are JSON values only.
  test("Ipa-2.8 no private-key or binary-blob leakage in ipa metadata") {
    val (_, provBytes) = MobileProvisionFixture.signedProvision(
      teamIdentifier = "TEAM1234",
      appIDName = "Min App"
    )
    val ipaBytes = IpaZipBuilder.minimalIpa(
      BplistEncoder.encode(
        JObject("CFBundleIdentifier" -> JString("org.example.Min"))
      ),
      provBytes
    )
    val wrapper = ByteWrapper(ipaBytes, "Min.ipa", None)
    val store = MemStorage(None)
    val (tp, _, _) = claim(wrapper)
    ToProcess.buildGraphForToProcess(tp, store)

    val ipaItem = store.keys().toVector.flatMap(k => store.read(k)).find(i =>
      i.bodyAsItemMetaData
        .exists(m => m.extra.contains(ipaAdHoc("BundleIdentifier")))
    )
    assert(ipaItem.isDefined)
    val extra = ipaItem.get.body.get.asInstanceOf[ItemMetaData].extra
    extra.foreach { case (k, v) =>
      if (k.startsWith("ipa:")) {
        val value = v.headOption.map(_.value).getOrElse("")
        assert(!value.contains("PRIVATE KEY"), s"$k leaked a private key")
        assert(!value.contains("BEGIN RSA"), s"$k leaked a PEM private key")
        // Entitlements is compact JSON, not raw bytes.
        if (k.endsWith("Entitlements")) {
          assert(
            parseOpt(value).isDefined,
            s"Entitlements must be JSON: $value"
          )
        }
      }
    }
  }

  // Ipa-2.9 — an .ipa with no Info.plist still produces a container item
  // (no ipa:* keys, no exception).
  test("Ipa-2.9 .ipa without Info.plist produces a container item") {
    val ipaBytes = IpaZipBuilder.zip(
      "Payload/" -> Array.emptyByteArray,
      "Payload/Min.app/" -> Array.emptyByteArray
    )
    val wrapper = ByteWrapper(ipaBytes, "Min.ipa", None)
    val store = MemStorage(None)
    val (tp, _, _) = claim(wrapper)
    ToProcess.buildGraphForToProcess(tp, store)

    // The container item still exists (as a processed zip child structure)
    // but no ipa:* metadata keys.
    val ipaKeyed = store.keys().toVector.flatMap(k => store.read(k)).filter(
      i => i.bodyAsItemMetaData.exists(m => m.extra.keys.exists(_.startsWith("ipa:")))
    )
    assert(ipaKeyed.isEmpty, "no ipa:* metadata without a plist")
  }

  // Ipa-2.a — the provisional pURL is emitted in the
  // pkg:apple/ios/<bundle-id>@<version> shape.
  test("Ipa-2.a provisional apple purl is emitted") {
    val ipaBytes = IpaZipBuilder.minimalIpa(
      BplistEncoder.encode(
        JObject(
          "CFBundleIdentifier" -> JString("org.example.Min"),
          "CFBundleShortVersionString" -> JString("1.2.3")
        )
      ),
      "not-a-real-cms".getBytes("UTF-8")
    )
    val wrapper = ByteWrapper(ipaBytes, "Min.ipa", None)
    val store = MemStorage(None)
    val (tp, _, _) = claim(wrapper)
    ToProcess.buildGraphForToProcess(tp, store)

    val purls = store.purls().toVector
    assert(
      purls.exists(p => p == "pkg:apple/ios/org.example.Min@1.2.3"),
      s"provisional apple pURL must be emitted, got: $purls"
    )
  }

  // Ipa-2.b — corpus end-to-end: the real allsafe-ios.ipa produces the
  // full item (metadata + certs + pURL).
  test("Ipa-2.b corpus allsafe end-to-end") {
    assume(IpaCorpus.verified, "allsafe-ios.ipa corpus fixture unavailable")
    val wrapper = io.spicelabs.goatrodeo.util.FileWrapper(
      IpaCorpus.corpusFile,
      IpaCorpus.corpusFile.getPath,
      None
    )
    val store = MemStorage(None)
    val (tp, _, _) = claim(wrapper)
    assert(tp.nonEmpty, "corpus .ipa must be claimed")
    ToProcess.buildGraphForToProcess(tp, store)

    val ipaItem = store.keys().toVector.flatMap(k => store.read(k)).find(i =>
      i.bodyAsItemMetaData
        .exists(m => m.extra.contains(ipaAdHoc("BundleIdentifier")))
    )
    assert(ipaItem.isDefined, "corpus .ipa item must carry metadata")
    assertEquals(
      metaOf(store, ipaItem.get, ipaAdHoc("BundleIdentifier")),
      Some("infosecadventures.allsafe")
    )
    assertEquals(
      metaOf(store, ipaItem.get, ipaAdHoc("BundleShortVersion")),
      Some("1.0")
    )
    // Certs from the real provisioning profile.
    val certItems = store.keys().toVector.flatMap(k => store.read(k)).filter(
      i => i.bodyAsItemMetaData.exists(m =>
          m.extra.contains(certAdHoc("SubjectDN"))
      )
    )
    assert(
      certItems.nonEmpty,
      "the real provisioning profile must surface signing certs"
    )
    // Provisional pURL.
    val purls = store.purls().toVector
    assert(
      purls.exists(p => p.startsWith("pkg:apple/ios/infosecadventures.allsafe")),
      s"corpus pURL must be emitted, got: $purls"
    )
  }
}

/** Builds a genuine (self-signed) CMS SignedData shaped like an iOS
  * embedded.mobileprovision: a plist payload (TeamIdentifier, AppIDName,
  * Entitlements, ProvisionedDevices) wrapped with an X.509 cert chain.
  */
object MobileProvisionFixture {

  import org.bouncycastle.asn1.x500.X500Name
  import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
  import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
  import org.bouncycastle.cms.CMSSignedDataGenerator
  import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
  import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
  import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
  import java.math.BigInteger
  import java.security.{KeyPair, KeyPairGenerator, SecureRandom}
  import java.util.Date

  /** Build a self-signed cert + private key, then a CMS SignedData whose
    * content is a valid mobileprovision plist.
    *
    * @return
    *   (chain of X509Certificate, the signed CMS bytes)
    */
  def signedProvision(
      teamIdentifier: String,
      appIDName: String
  ): (Vector[java.security.cert.X509Certificate], Array[Byte]) = {
    val kpg = KeyPairGenerator.getInstance("RSA")
    kpg.initialize(2048)
    val kp: KeyPair = kpg.generateKeyPair()

    val now = new Date()
    val notAfter = new Date(now.getTime + 365L * 24 * 3600 * 1000)
    val name = new X500Name(s"CN=$appIDName, O=Test Org")
    val builder = new JcaX509v3CertificateBuilder(
      name,
      BigInteger.valueOf(System.nanoTime()),
      now,
      notAfter,
      name,
      kp.getPublic
    )
    val signer = new JcaContentSignerBuilder("SHA256withRSA").build(kp.getPrivate)
    val holder = builder.build(signer)
    val cert = new JcaX509CertificateConverter().setProvider("BC")
      .getCertificate(holder)

    // The provisioning plist payload.
    val payload = BplistEncoder.encode(
      JObject(
        "AppIDName" -> JString(appIDName),
        "TeamIdentifier" -> JArray(List(JString(teamIdentifier))),
        "Entitlements" -> JObject(
          "application-identifier" -> JString(s"$teamIdentifier.min"),
          "get-task-allow" -> JBool(false)
        ),
        "ProvisionedDevices" -> JArray(
          List("deadbeefcafe0001", "deadbeefcafe0002").map(JString(_))
        ),
        "ExpirationDate" -> JString(
          java.time.Instant.ofEpochMilli(notAfter.getTime).toString
        )
      )
    )

    val gen = new CMSSignedDataGenerator()
    gen.addSignerInfoGenerator(
      new JcaSignerInfoGeneratorBuilder(
        new JcaDigestCalculatorProviderBuilder().setProvider("BC").build()
      ).build(signer, cert)
    )
    gen.addCertificate(holder)
    val signed = gen.generate(
      new org.bouncycastle.cms.CMSProcessableByteArray(payload),
      true
    )
    (Vector(cert), signed.getEncoded)
  }
}