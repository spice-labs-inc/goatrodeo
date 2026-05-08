/* Generate a PGP v6 (RFC 9580) public key fixture using Bouncy Castle.
 *
 * Plan Phase 8 coverage matrix requires PGP `version` v4 AND v6.
 * GPG 2.4.4 v6 support is unstable and the v6 generation path
 * requires `--rfc4880bis` plus a tty-equipped agent. BC 1.79's
 * `OpenPGPV6KeyGenerator` is a clean alternative.
 *
 * Run:
 *   java -cp <bcprov-jar>:<bcpg-jar> --source 21 GeneratePgpV6.java \
 *     <output.asc>
 *
 * Generates a v6 Ed25519 primary key. Exports the armored public-key
 * ring (primary + subkeys; the secret half is never written to disk).
 *
 * Determinism: BC's keypair generation uses live entropy. The committed
 * `.asc` is one-time-canonical 
 */

import java.io.File;
import java.io.FileOutputStream;
import java.security.Security;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import org.bouncycastle.openpgp.PGPSecretKeyRing;
import org.bouncycastle.openpgp.api.bc.BcOpenPGPV6KeyGenerator;

public class GeneratePgpV6 {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println(
                "usage: java -cp <bc-jars> --source 21 GeneratePgpV6.java " +
                "<output.asc>"
            );
            System.exit(2);
        }
        File outFile = new File(args[0]);

        Security.addProvider(new BouncyCastleProvider());

        // BC 1.80's BcOpenPGPV6KeyGenerator() — default ctor uses
        // current time and a fresh SecureRandom internally.
        BcOpenPGPV6KeyGenerator gen = new BcOpenPGPV6KeyGenerator();
        // `classicKey` produces a primary signing key + encryption
        // subkey using v6 conventions (Ed25519 + X25519 by default).
        // The `null` passphrase means the secret key in the returned
        // ring is unencrypted — but we never write the secret half
        // anywhere, so this is safe.
        PGPSecretKeyRing skr = gen.classicKey(
            "GoatRodeo Test v6 <goatrodeo-v6@test.invalid>",
            (char[]) null
        );

        // Build a public-only ring from the secret-ring's public projection.
        List<PGPPublicKey> pubKeys = new ArrayList<>();
        Iterator<PGPPublicKey> it = skr.getPublicKeys();
        while (it.hasNext()) {
            pubKeys.add(it.next());
        }
        PGPPublicKeyRing publicRing = new PGPPublicKeyRing(pubKeys);

        try (FileOutputStream fos = new FileOutputStream(outFile);
             ArmoredOutputStream aos = new ArmoredOutputStream(fos)) {
            publicRing.encode(aos);
        }

        PGPPublicKey primary = pubKeys.get(0);
        System.out.println("Wrote v6 PGP public key to " + outFile +
                           " (primary version=" + primary.getVersion() +
                           ", " + pubKeys.size() + " key(s) total)");
    }
}
