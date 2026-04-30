/* Build a PKCS#12 trust-only bundle that loads with a null password.
 *
 * Ran once to produce `trust-only-null-password.p12`. The sidecar
 * asserts that the Certificates strategy can open this keystore with
 * `null` and enumerate its trust entries (i.e.,
 * `Certificates:KeystoreEncrypted` is absent, `Certificates:EntryCount`
 * > 0).
 *
 * `keytool` refuses empty passwords (>= 6 chars enforced), so we use
 * the JDK's `KeyStore` API directly — it explicitly allows
 * `load(null, null)` and `store(out, null)`.
 *
 * Run with JDK 21+:
 *   java --source 21 GenerateTrustOnlyNullPassword.java \
 *     <input-cert.pem> <output-p12> [alias1 [alias2 ...]]
 *
 * Reproducibility: deterministic given identical input cert bytes.
 */

import java.io.*;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Collections;

public class GenerateTrustOnlyNullPassword {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println(
                "usage: java --source 21 GenerateTrustOnlyNullPassword.java " +
                "<input-cert.pem> <output-p12> [alias]"
            );
            System.exit(2);
        }
        File inFile = new File(args[0]);
        File outFile = new File(args[1]);
        String alias = args.length >= 3 ? args[2] : "trust-entry";

        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        Certificate cert;
        try (InputStream in = new FileInputStream(inFile)) {
            cert = cf.generateCertificate(in);
        }

        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null); // null password init
        ks.setCertificateEntry(alias, cert);

        try (OutputStream out = new FileOutputStream(outFile)) {
            ks.store(out, null); // null password store
        }

        // Verify load-with-null round-trip
        KeyStore rt = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(outFile)) {
            rt.load(in, null);
        }
        int entries = Collections.list(rt.aliases()).size();
        System.out.println("Wrote " + outFile + " with " + entries + " entry/entries; "
                           + "round-trip load(null) OK");
    }
}
