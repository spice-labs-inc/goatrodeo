/* Generate a BKS (Bouncy Castle Key Store) trust-only fixture.
 *
 * BKS is the Bouncy-Castle-native keystore format. The plan's Phase 4
 * coverage matrix lists BKS as nice-to-have. Modern `keytool` does
 * not support BKS natively — it requires the BC provider on the
 * keystore-format extension list. This program uses the BC provider
 * via classpath to create a small BKS trust store with one
 * certificate.
 *
 * Run:
 *   java -cp <bcprov-jar> --source 21 GenerateBks.java \
 *     <input-cert.pem> <output-bks> <storepass> [alias]
 *
 * Exit codes:
 *   0 — success, file written and round-trip verified
 *   1 — input cert missing or unreadable
 *   2 — argument count wrong
 *   3 — round-trip load/store mismatch
 *
 * Output bytes are deterministic given identical input cert bytes
 * (BKS does not embed timestamps in the keystore, and the BC PRNG is
 * seeded with the JCA default — for our purposes the produced bytes
 * are stable enough across re-runs to land in the corpus once).
 */

import java.io.*;
import java.security.KeyStore;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Collections;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

public class GenerateBks {
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println(
                "usage: java -cp <bcprov-jar> --source 21 GenerateBks.java " +
                "<input-cert.pem> <output-bks> <storepass> [alias]"
            );
            System.exit(2);
        }
        File inFile = new File(args[0]);
        File outFile = new File(args[1]);
        char[] pw = args[2].toCharArray();
        String alias = args.length >= 4 ? args[3] : "trust-entry";

        Security.addProvider(new BouncyCastleProvider());

        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        Certificate cert;
        try (InputStream in = new FileInputStream(inFile)) {
            cert = cf.generateCertificate(in);
        }

        KeyStore ks = KeyStore.getInstance("BKS", "BC");
        ks.load(null, pw);
        ks.setCertificateEntry(alias, cert);

        try (OutputStream out = new FileOutputStream(outFile)) {
            ks.store(out, pw);
        }

        // Round-trip verify
        KeyStore rt = KeyStore.getInstance("BKS", "BC");
        try (InputStream in = new FileInputStream(outFile)) {
            rt.load(in, pw);
        }
        int entries = Collections.list(rt.aliases()).size();
        if (entries != 1) {
            System.err.println("Round-trip failed: expected 1 entry, got " + entries);
            System.exit(3);
        }
        System.out.println("Wrote " + outFile + " with " + entries +
                           " entry; BKS round-trip OK");
    }
}
