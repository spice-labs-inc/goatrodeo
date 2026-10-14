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

package io.spicelabs.goatrodeo.testsupport

import java.util.regex.Pattern

/** The Appendix C forbidden private-key patterns.
  *
  * Two suites sweep for private-key material: `CertificatesLeakSuite` sweeps
  * the `Item`s the certificate strategy emits, and `RunOutputContractSuite`
  * sweeps the serialised JSON dump a run leaves on disk. A leak is only
  * meaningful if both agree on what a leak looks like, so the list lives here
  * once. Duplicating it would let the two suites drift, and a pattern added to
  * only one of them would leave a gap that neither suite reports.
  *
  * The patterns match the header, footer and body forms of the key formats Goat
  * Rodeo encounters: PEM (RSA/EC/DSA/OpenSSH/PGP/encrypted), PGP armour, and
  * the fixed base64 prefixes that PKCS#8 and PKCS#1 bodies share regardless of
  * the surrounding text. `openssh-key-v1` is the binary OpenSSH private-key
  * magic.
  */
object ForbiddenPatterns {

  /** Compiled patterns, each of which must never appear in emitted output.
    */
  val appendixC: Seq[Pattern] = Seq(
    "-----BEGIN (RSA |EC |DSA |OPENSSH |PGP )?PRIVATE KEY-----",
    "-----BEGIN ENCRYPTED PRIVATE KEY-----",
    "-----BEGIN PGP PRIVATE KEY BLOCK-----",
    "-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]+?-----END [A-Z ]*PRIVATE KEY-----",
    "MIIEvQIBADAN",
    "MIIEpAIBAAKCAQEA",
    "MIIB[A-Za-z0-9+/]{8}QIB[A-Za-z0-9+/]+",
    "openssh-key-v1"
  ).map(Pattern.compile)

  /** Which patterns match a piece of text.
    *
    * @param text
    *   the text to sweep
    * @return
    *   the matching patterns' source strings
    */
  def matches(text: String): Vector[String] =
    appendixC.filter(_.matcher(text).find()).map(_.pattern()).toVector

  /** A synthetic string that every sweep must detect, used to prove a sweep can
    * fail. Without it, "the sweep found nothing" is not evidence the sweep
    * works.
    */
  val sentinel: String = "-----BEGIN RSA PRIVATE KEY-----test"
}
