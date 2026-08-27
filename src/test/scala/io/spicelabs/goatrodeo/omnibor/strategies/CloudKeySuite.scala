/* Copyright 2026 David Pollak, Spice Labs, Inc. & Contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License. */

package io.spicelabs.goatrodeo.omnibor.strategies

import io.spicelabs.goatrodeo.util.ByteWrapper
import munit.FunSuite

/** Tests for [[CloudKeyStrategy]]: cloud-managed key references in config/IaC
  * files (AWS KMS, Azure Key Vault, GCP Cloud KMS, HashiCorp Vault).
  *
  * Hard rule under test: identifiers (ARNs/URLs) are emitted; key material and
  * credentials are never emitted (presence flag only; Vault URLs sanitized).
  */
class CloudKeySuite extends FunSuite {

  private val awsArn =
    "arn:aws:kms:us-west-2:123456789012:key/1234abcd-12ab-34cd-56ef-1234567890ab"
  private val awsAlias = "arn:aws:kms:us-west-2:123456789012:alias/my-key"
  private val azureUrl =
    "https://myvault.vault.azure.net/keys/mykey/1a2b3c4d5e6f"
  private val gcpName =
    "projects/my-proj/locations/us/keyRings/myring/cryptoKeys/mykey/cryptoKeyVersions/1"
  private val vaultUrl = "vault:secret/data/foo"

  // CK-01 — provider signatures are detected; random text is not.
  test("CK-01 detects cloud-key signatures, not random text") {
    assert(CloudKeyStrategy.detects(awsArn), "aws key arn")
    assert(CloudKeyStrategy.detects(awsAlias), "aws alias arn")
    assert(CloudKeyStrategy.detects(azureUrl), "azure url")
    assert(CloudKeyStrategy.detects(gcpName), "gcp resource name")
    assert(CloudKeyStrategy.detects(vaultUrl), "vault url")
    assert(
      CloudKeyStrategy.detects(
        """resource "aws_kms_key" "example" {
          |  description = "k"
          |}
          |""".stripMargin
      ),
      "terraform aws_kms_key resource"
    )
    assert(
      CloudKeyStrategy.detects(
        """resource "google_kms_crypto_key" "example" {
          |  name = "k"
          |}
          |""".stripMargin
      ),
      "terraform google_kms_crypto_key resource"
    )
    assert(!CloudKeyStrategy.detects("hello world"), "plain text")
    assert(
      !CloudKeyStrategy.detects("key: value\nfoo: bar"),
      "generic yaml key lines must not claim"
    )
    assert(
      !CloudKeyStrategy.detects("api_key=abc123"),
      "generic api key must not claim"
    )
  }

  // CK-02 — the name gate only admits config/IaC extensions.
  test("CK-02 cloudKeyName admits only config/IaC extensions") {
    assert(CloudKeyStrategy.cloudKeyName("main.tf"))
    assert(CloudKeyStrategy.cloudKeyName("main.tf.json"))
    assert(CloudKeyStrategy.cloudKeyName("app.yaml"))
    assert(CloudKeyStrategy.cloudKeyName("app.yml"))
    assert(CloudKeyStrategy.cloudKeyName("app.json"))
    assert(CloudKeyStrategy.cloudKeyName("app.properties"))
    assert(CloudKeyStrategy.cloudKeyName(".env"))
    assert(!CloudKeyStrategy.cloudKeyName("Main.java"))
    assert(!CloudKeyStrategy.cloudKeyName("main.scala"))
    assert(!CloudKeyStrategy.cloudKeyName("README.md"))
  }

  // CK-03 — parse extracts providers, resource ids, key specs, rotation,
  // purpose; Terraform resources produce type.name ids.
  test("CK-03 parse extracts provider and resource details") {
    val text =
      s"""resource "aws_kms_key" "example" {
         |  key_spec = "RSA_2048"
         |  rotation_period = "90d"
         |}
         |$awsArn
         |resource "google_kms_crypto_key" "g" {
         |  purpose = "ENCRYPT_DECRYPT"
         |}
         |""".stripMargin
    val p = CloudKeyStrategy.parse(text)
    assertEquals(p.providers, Set("aws", "gcp"))
    assert(p.resourceIds.contains(awsArn))
    assert(p.resourceIds.contains("aws_kms_key.example"))
    assert(p.resourceIds.contains("google_kms_crypto_key.g"))
    assertEquals(p.keySpecs, Set("RSA_2048"))
    assertEquals(p.rotations, Set("90d"))
    assertEquals(p.purposes, Set("ENCRYPT_DECRYPT"))
    assert(!p.keyMaterialPresent)
  }

  // CK-04 — hostile: key material is presence-only; Vault URLs are sanitized
  // of query strings (tokens).
  test("CK-04 key material and vault tokens never leak") {
    val text =
      s"""resource "aws_kms_key" "example" {
         |  key_material = "AAAAAAAABBBBBBBBCCCCCCCCsecretblobbase64=="
         |}
         |vault:transit/keys/mykey?token=hunter2
         |""".stripMargin
    val p = CloudKeyStrategy.parse(text)
    assert(p.keyMaterialPresent, "key_material presence must be recorded")
    assert(
      !p.resourceIds.exists(_.contains("secretblob")),
      "key material value must never be captured"
    )
    assert(
      !p.resourceIds.exists(_.contains("hunter2")),
      "vault token must never be captured"
    )
    assert(
      p.resourceIds.contains("vault:transit/keys/mykey"),
      "sanitized vault url must be captured"
    )
  }

  // CK-05 — metadata: presence flag, no values; and empty parse is empty.
  test("CK-05 metadata carries provider/id/flag, never key material") {
    val wrapper = ByteWrapper(
      """resource "aws_kms_key" "example" {
        |  key_material = "AAAAAAAABBBBBBBBCCCCCCCCsecretblobbase64=="
        |}
        |""".stripMargin.getBytes("UTF-8"),
      "main.tf",
      None
    )
    val meta = new CloudKeyState(wrapper).invokeBuildMetadata(wrapper)
    val values = meta.values.flatten.map {
      case io.spicelabs.goatrodeo.omnibor.StringOf(s)   => s
      case io.spicelabs.goatrodeo.omnibor.PairOf(_, s2) => s2
    }.toSet
    assert(
      values.contains("true") && meta.contains("CloudKey:key_material_present"),
      "presence flag expected"
    )
    assert(
      !meta.values.flatten.exists(v => v.value.contains("secretblob")),
      "key material must never appear in metadata"
    )
  }

  // CK-06 — property: parse never throws on arbitrary strings and is pure.
  test("CK-06 parse never throws and is deterministic") {
    val junk = Vector("", "kms", "arn:", "vault:", "{\"key\":1}", "a" * 10000)
    junk.foreach { s =>
      val a = CloudKeyStrategy.parse(s)
      val b = CloudKeyStrategy.parse(s)
      assertEquals(a, b, "parse must be deterministic")
    }
    val nothing = CloudKeyStrategy.parse("completely unrelated text")
    assert(nothing.isEmpty)
  }
}
