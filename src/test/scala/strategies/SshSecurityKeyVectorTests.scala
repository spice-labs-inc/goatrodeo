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

import io.spicelabs.goatrodeo.util.FileWrapper
import munit.FunSuite

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64

/** Hand-crafted security-key (sk-*) wire-format vectors.
  *
  * ## What these tests test
  *
  * Phase-5 plan §125-135: the `sk-ed25519.pub` fixture is called out as
  * "manually-crafted test vector" because security keys can't be generated
  * without physical hardware. The plan still requires coverage of the
  * `sk-ssh-ed25519@openssh.com` and `sk-ecdsa-sha2-nistp256@openssh.com` wire
  * names because they drive the `sk=true` qualifier and
  * `Certificates:SshIsSecurityKey` metadata path — both otherwise dead in the
  * corpus.
  *
  * ## Why hand-craft
  *
  * These tests build the exact wire bytes the strategy expects and write them
  * to a temp file with the right `.pub` shape. They then verify (a) the parser
  * accepts the security-key wire alg, (b) `purlForSshPubkey` emits `sk=true` in
  * the qualifier set, and (c) `sshPubkeyMetadata` emits
  * `Certificates:SshIsSecurityKey=true`.
  *
  * Wire format per `PROTOCOL.u2f` in OpenSSH source:
  * sk-ssh-ed25519@openssh.com: string("sk-ssh-ed25519@openssh.com")
  * string(public_key) // 32-byte Ed25519 raw public key string(application) //
  * typically "ssh:"
  *
  * sk-ecdsa-sha2-nistp256@openssh.com:
  * string("sk-ecdsa-sha2-nistp256@openssh.com") string("nistp256") string(Q) //
  * SEC1 uncompressed point string(application)
  */
class SshSecurityKeyVectorTests extends FunSuite {

  private def writeSshString(
      out: ByteArrayOutputStream,
      b: Array[Byte]
  ): Unit = {
    val n = b.length
    out.write((n >>> 24) & 0xff)
    out.write((n >>> 16) & 0xff)
    out.write((n >>> 8) & 0xff)
    out.write(n & 0xff)
    out.write(b)
  }

  private def writeSshString(out: ByteArrayOutputStream, s: String): Unit =
    writeSshString(out, s.getBytes(StandardCharsets.UTF_8))

  /** Build a hand-crafted plain-pubkey line and dump to a temp file. */
  private def writeKeyFile(
      algo: String,
      wire: Array[Byte],
      comment: String
  ): File = {
    val b64 = Base64.getEncoder.nn.encodeToString(wire).nn
    val tmp = File.createTempFile("sk-vector", ".pub")
    tmp.deleteOnExit()
    java.nio.file.Files.write(
      tmp.toPath,
      s"$algo $b64 $comment\n".getBytes(StandardCharsets.UTF_8)
    )
    tmp
  }

  test("parseSshPubkey: sk-ssh-ed25519@openssh.com vector parses (G4)") {
    val out = new ByteArrayOutputStream()
    writeSshString(out, "sk-ssh-ed25519@openssh.com")
    writeSshString(out, Array.fill[Byte](32)(0x42)) // dummy 32-byte pk
    writeSshString(out, "ssh:")
    val tmp = writeKeyFile(
      "sk-ssh-ed25519@openssh.com",
      out.toByteArray,
      "fido-key@host"
    )
    val w = FileWrapper(tmp, tmp.getName, None)

    val pk = Certificates.parseSshPubkey(w)
    assert(pk.isDefined, "sk-ssh-ed25519 vector should parse")
    assertEquals(pk.get.algName, "sk-ssh-ed25519@openssh.com")
  }

  test("purlForSshPubkey: sk-ed25519 emits sk=true qualifier (G4)") {
    val out = new ByteArrayOutputStream()
    writeSshString(out, "sk-ssh-ed25519@openssh.com")
    writeSshString(out, Array.fill[Byte](32)(0x42))
    writeSshString(out, "ssh:")
    val tmp =
      writeKeyFile("sk-ssh-ed25519@openssh.com", out.toByteArray, "fido-key")
    val w = FileWrapper(tmp, tmp.getName, None)
    val pk = Certificates.parseSshPubkey(w).get

    val state = new CertificatesState(w)
    val purl = state.purlForSshPubkey(pk).canonicalize().nn
    assert(
      purl.contains("sk=true"),
      s"expected sk=true in qualifier set, got $purl"
    )
    assert(purl.contains("alg=ed25519"), s"expected alg=ed25519, got $purl")
  }

  test("sshPubkeyMetadata: sk-ed25519 emits SshIsSecurityKey=true (G4)") {
    val out = new ByteArrayOutputStream()
    writeSshString(out, "sk-ssh-ed25519@openssh.com")
    writeSshString(out, Array.fill[Byte](32)(0x42))
    writeSshString(out, "ssh:")
    val tmp =
      writeKeyFile("sk-ssh-ed25519@openssh.com", out.toByteArray, "fido-key")
    val w = FileWrapper(tmp, tmp.getName, None)
    val pk = Certificates.parseSshPubkey(w).get

    val state = new CertificatesState(w)
    val tm = state.invokeSshPubkeyMetadata(w, pk)
    val skKey = "Certificates:SshIsSecurityKey"
    assert(
      tm.contains(skKey),
      s"expected $skKey in metadata, got keys=${tm.keys.toSeq}"
    )
    assertEquals(tm(skKey).head.value, "true")
  }

  test(
    "parseSshPubkey: sk-ecdsa-sha2-nistp256@openssh.com vector parses (G4)"
  ) {
    val out = new ByteArrayOutputStream()
    writeSshString(out, "sk-ecdsa-sha2-nistp256@openssh.com")
    writeSshString(out, "nistp256")
    // SEC1 uncompressed point: 0x04 || X(32) || Y(32) — dummy bytes are
    // fine; the parser doesn't validate point-on-curve.
    writeSshString(out, Array[Byte](0x04) ++ Array.fill[Byte](64)(0x33))
    writeSshString(out, "ssh:")
    val tmp = writeKeyFile(
      "sk-ecdsa-sha2-nistp256@openssh.com",
      out.toByteArray,
      "fido-ecdsa@host"
    )
    val w = FileWrapper(tmp, tmp.getName, None)

    val pk = Certificates.parseSshPubkey(w)
    assert(pk.isDefined, "sk-ecdsa-sha2-nistp256 vector should parse")
    assertEquals(pk.get.algName, "sk-ecdsa-sha2-nistp256@openssh.com")

    val state = new CertificatesState(w)
    val purl = state.purlForSshPubkey(pk.get).canonicalize().nn
    assert(purl.contains("sk=true"), s"sk=true missing from $purl")
    assert(purl.contains("alg=ec"), s"alg=ec missing from $purl")
    assert(purl.contains("curve=p-256"), s"curve=p-256 missing from $purl")
  }
}
