# Sources — `x509/`

Provenance ledger for individual X.509 certificate fixtures (PEM and DER).

See `../README.md` for corpus-wide policies. Add one row per fixture.

| Filename | Source | Retrieved | SHA-256 |
|---|---|---|---|
| _example placeholder — remove when first real fixture lands_ | _e.g., `https://letsencrypt.org/certs/isrgrootx1.pem`_ | _YYYY-MM-DD_ | _sha256:…_ |

## Expected fixtures for Phase 3 (target list from plan)

The following are the canonical Phase 3 X.509 fixtures per
`certificates-strategy/phases-3-4-x509-containers.md`. Add them as they
are downloaded and SHA-256-verified.

- [ ] `rsa2048-isrg-root-x1.pem` — `https://letsencrypt.org/certs/isrgrootx1.pem`
- [ ] `rsa4096-isrg-root-x2.pem` — `https://letsencrypt.org/certs/isrg-root-x2.pem`
- [ ] `ec-p384-isrg-root-x2.pem` or DigiCert Global Root G3
- [ ] `ec-p256-digicert-global-g2.pem`
- [ ] `rsa2048-isrg-root-x1.der` (DER-encoded re-export)
- [ ] `sha1-legacy-root.pem` (Mozilla CCADB historical roots)
- [ ] `ed25519-self-signed.pem` (generated via `generate.sh`)
- [ ] `pqc-ml-dsa-65.pem` (liboqs / NIST PQC)

## Category floor

Phase 0 coverage guidance: at least 60 X.509 CA roots, 30 leaf certs, 20
intermediates, 15 historical/deprecated, 5+ PQC.

## Mozilla CA bundle (fanned out)

| Filename | Source | Retrieved | SHA-256 |
|---|---|---|---|
| mozilla/entrust-root-certification-authority__73c176434f1b.pem | https://curl.se/ca/cacert.pem (cert `Entrust Root Certification Authority`) | 2026-04-24 | sha256:5e00b41e6e82d82ff55cdb4b39d798870bfc3a91c873db3ab71a9eaeba5994d7 |
| mozilla/quovadis-root-ca-2__85a0dd7dd720.pem | https://curl.se/ca/cacert.pem (cert `QuoVadis Root CA 2`) | 2026-04-24 | sha256:cb124e98feca98d6fe788eb461efc0e99fff3f01683b6763ff9d0800b4963d38 |
| mozilla/quovadis-root-ca-3__18f1fc7f205d.pem | https://curl.se/ca/cacert.pem (cert `QuoVadis Root CA 3`) | 2026-04-24 | sha256:0a2cd99365cac88aff7cdd25ddb957bfa4ddb27afd7f8dd3b4582ba8c75f351c |
| mozilla/digicert-assured-id-root-ca__3e9099b5015e.pem | https://curl.se/ca/cacert.pem (cert `DigiCert Assured ID Root CA`) | 2026-04-24 | sha256:2e7cede83d1b96125c9d054061cb7b8d7fad842030b47b85c46d9601ccea3fd6 |
| mozilla/digicert-global-root-ca__4348a0e9444c.pem | https://curl.se/ca/cacert.pem (cert `DigiCert Global Root CA`) | 2026-04-24 | sha256:b1ff3970d79185aa9695770139cb0bf979f8065d7b1a7b3c3730a338820477b7 |
| mozilla/digicert-high-assurance-ev-root-ca__7431e5f4c3c1.pem | https://curl.se/ca/cacert.pem (cert `DigiCert High Assurance EV Root CA`) | 2026-04-24 | sha256:b8eed749a1da0a659ffae9f76bc19f46581f5f46e8258a24ed5d50c27c0ad770 |
| mozilla/swisssign-gold-ca---g2__62dd0be9b9f5.pem | https://curl.se/ca/cacert.pem (cert `SwissSign Gold CA - G2`) | 2026-04-24 | sha256:91b6a5ab207b92823ad927406456f96c2db7e5c314384f3cc1bd95a01c083a83 |
| mozilla/securetrust-ca__f1c1b50ae5a2.pem | https://curl.se/ca/cacert.pem (cert `SecureTrust CA`) | 2026-04-24 | sha256:7c763beedcce1ac4ec3d471b543384c00f23d39777491085d424c2c3e462f29b |
| mozilla/secure-global-ca__4200f5043ac8.pem | https://curl.se/ca/cacert.pem (cert `Secure Global CA`) | 2026-04-24 | sha256:4fbe9b462a01102043bf2b2ba1bcc088d3b07621dc18a916d0a5e5e91c1c4235 |
| mozilla/comodo-certification-authority__0c2cd63df780.pem | https://curl.se/ca/cacert.pem (cert `COMODO Certification Authority`) | 2026-04-24 | sha256:716ed3e8a3508493d90d2f08acaa99d837431232ab20d72230f24ef7a4b5be5c |
| mozilla/comodo-ecc-certification-authority__1793927a0614.pem | https://curl.se/ca/cacert.pem (cert `COMODO ECC Certification Authority`) | 2026-04-24 | sha256:5882279eec613e7609c6d02f90869e7f3aa069bcb29c8b87bbb313ac9a8f2017 |
| mozilla/certigna__e3b6a2db2ed7.pem | https://curl.se/ca/cacert.pem (cert `Certigna`) | 2026-04-24 | sha256:3c4b81de41273690d66ca29259dd6e68c27ce642301ab8a2ca4d56184878cd5a |
| mozilla/chunghwa-telecom-co.-ltd.__c0a6f4dc63a2.pem | https://curl.se/ca/cacert.pem (cert `Chunghwa Telecom Co., Ltd.`) | 2026-04-24 | sha256:d4c958b34d305367f8b857a6e65112d1e6975d5570fe41c615e5025240415345 |
| mozilla/certsign__eaa962c4fa4a.pem | https://curl.se/ca/cacert.pem (cert `certSIGN`) | 2026-04-24 | sha256:658a7d0edc38d6150771924c76984f98f0b03df57994c4b25c2e62722f0b6228 |
| mozilla/netlock-arany-class-gold-f-tan-s-tv-ny__6c61dac3a2de.pem | https://curl.se/ca/cacert.pem (cert `NetLock Arany (Class Gold) Főtanúsítvány`) | 2026-04-24 | sha256:241d3c0f4e4b664520f35165559a1b571510bf667a5c61f607c7a96f4c22ee6f |
| mozilla/microsec-e-szigno-root-ca-2009__3c5f81fea5fa.pem | https://curl.se/ca/cacert.pem (cert `Microsec e-Szigno Root CA 2009`) | 2026-04-24 | sha256:fef9a754c64df9ddd35a21c95cfb3ff125e8f555a59052c9138fb866daffe064 |
| mozilla/globalsign__cbb522d7b7f1.pem | https://curl.se/ca/cacert.pem (cert `GlobalSign`) | 2026-04-24 | sha256:d7315d53dfb780be987b69335d200fbcdb0d7271e8bc4f531b79e9bb98da79a5 |
| mozilla/izenpe.com__2530cc8e9832.pem | https://curl.se/ca/cacert.pem (cert `Izenpe.com`) | 2026-04-24 | sha256:1eac164719f12d67fe3b3804024d53e6d267b1e5874b5c8384321f5c7aa401d7 |
| mozilla/go-daddy-root-certificate-authority---g2__45140b3247eb.pem | https://curl.se/ca/cacert.pem (cert `Go Daddy Root Certificate Authority - G2`) | 2026-04-24 | sha256:56b276eed0f2f8a20502bc6c488fc12dc09012fda5dbb99205f39212734a6061 |
| mozilla/starfield-root-certificate-authority---g2__2ce1cb0bf9d2.pem | https://curl.se/ca/cacert.pem (cert `Starfield Root Certificate Authority - G2`) | 2026-04-24 | sha256:ee81a20b5d9d864b28da0e1ff50c78ce6df423cf417facd1b95a4d5b460e68ec |
| mozilla/starfield-services-root-certificate-authority---g2__568d6905a2c8.pem | https://curl.se/ca/cacert.pem (cert `Starfield Services Root Certificate Authority - G2`) | 2026-04-24 | sha256:4be8d9675f10dfa30e251b28ba260a456474166faaab296d9167d942e61de847 |
| mozilla/affirmtrust-commercial__0376ab1d54c5.pem | https://curl.se/ca/cacert.pem (cert `AffirmTrust Commercial`) | 2026-04-24 | sha256:63c0540a26c02ef80c1079107e48c713583716562664223558dfaa8f559182f6 |
| mozilla/affirmtrust-networking__0a81ec5a9297.pem | https://curl.se/ca/cacert.pem (cert `AffirmTrust Networking`) | 2026-04-24 | sha256:14851ddb4f129d6c1164e271321bf0df81de82cb32ccba32184d2354ce2dc23e |
| mozilla/affirmtrust-premium__70a73f7f376b.pem | https://curl.se/ca/cacert.pem (cert `AffirmTrust Premium`) | 2026-04-24 | sha256:cc68cea5e1365ea1aef41a385bcec73a7a28c0c7b44b3dca63eed9140f34d3f0 |
| mozilla/affirmtrust-premium-ecc__bd71fdf6da97.pem | https://curl.se/ca/cacert.pem (cert `AffirmTrust Premium ECC`) | 2026-04-24 | sha256:71eef2093a562af1f2ed0dc158916d0134683ec38b948181e63828159394f1f5 |
| mozilla/certum-trusted-network-ca__5c58468d55f5.pem | https://curl.se/ca/cacert.pem (cert `Certum Trusted Network CA`) | 2026-04-24 | sha256:9f062c010e6248e87f352230245c30d87f06ca0ded8f9d91cd546a2ab972a9f6 |
| mozilla/twca-root-certification-authority__bfd88fe1101c.pem | https://curl.se/ca/cacert.pem (cert `TWCA Root Certification Authority`) | 2026-04-24 | sha256:83b5cfba4411cfff26631cd99235bab6dc014a17320916eb9421168a46ca6bc7 |
| mozilla/secom-trust-systems-co.-ltd.__513b2cecb810.pem | https://curl.se/ca/cacert.pem (cert `SECOM Trust Systems CO.,LTD.`) | 2026-04-24 | sha256:dd31cd9362062d5f2d65c27e7113acb5fb6fb68989170100d2d0630c9abe9ad6 |
| mozilla/actalis-authentication-root-ca__55926084ec96.pem | https://curl.se/ca/cacert.pem (cert `Actalis Authentication Root CA`) | 2026-04-24 | sha256:e987385bdab86f0b585502ee2b3cee6b66e91b6e7ef3c82b667ec05b30deed0e |
| mozilla/buypass-class-2-root-ca__9a114025197c.pem | https://curl.se/ca/cacert.pem (cert `Buypass Class 2 Root CA`) | 2026-04-24 | sha256:44a194cc2e4b021353344f7307c673fafc83f458c482bc08d4158cb82bbf1326 |
| mozilla/buypass-class-3-root-ca__edf7ebbca27a.pem | https://curl.se/ca/cacert.pem (cert `Buypass Class 3 Root CA`) | 2026-04-24 | sha256:9cb071f5daed51fbad40d12bcacd69ba442447488502a098f01a568c20485919 |
| mozilla/t-telesec-globalroot-class-3__fd73dad31c64.pem | https://curl.se/ca/cacert.pem (cert `T-TeleSec GlobalRoot Class 3`) | 2026-04-24 | sha256:e26dcd2bad8558935a9fabe68b69cfd16fb21445ac39789c441d0b6dbf3f46be |
| mozilla/d-trust-root-class-3-ca-2-2009__49e7a442acf0.pem | https://curl.se/ca/cacert.pem (cert `D-TRUST Root Class 3 CA 2 2009`) | 2026-04-24 | sha256:fe799651ad1bca6a63c85ff6b1e36db02b146183a5194cac50d8dfc196411e3f |
| mozilla/d-trust-root-class-3-ca-2-ev-2009__eec5496b988c.pem | https://curl.se/ca/cacert.pem (cert `D-TRUST Root Class 3 CA 2 EV 2009`) | 2026-04-24 | sha256:be21cf67a69f65002d549b2de8a00609253c2936e436dc34c2559d25ddaaee23 |
| mozilla/ca-disig-root-r2__e23d4a036d7b.pem | https://curl.se/ca/cacert.pem (cert `CA Disig Root R2`) | 2026-04-24 | sha256:4ecc50436481fbab4435f3f6e7449dfc727f15aceda7f4b85861842a85e5bd85 |
| mozilla/accvraiz1__9a6ec012e1a7.pem | https://curl.se/ca/cacert.pem (cert `ACCVRAIZ1`) | 2026-04-24 | sha256:2fd25896b5491d4fe2659299aa16334c5c75b738d7a697c5a8dc1a1333e62a12 |
| mozilla/twca-global-root-ca__59769007f768.pem | https://curl.se/ca/cacert.pem (cert `TWCA Global Root CA`) | 2026-04-24 | sha256:2d0fc5e58840eb30b1e89896f9b7c3fdc03b0e696f7262bf11f903d7685b6aee |
| mozilla/teliasonera-root-ca-v1__dd6936fe21f8.pem | https://curl.se/ca/cacert.pem (cert `TeliaSonera Root CA v1`) | 2026-04-24 | sha256:a3c999f4c2cc33e962d501d6c8ed25af983fdc525428a408f1adc88877d15c3c |
| mozilla/t-telesec-globalroot-class-2__91e2f5788d58.pem | https://curl.se/ca/cacert.pem (cert `T-TeleSec GlobalRoot Class 2`) | 2026-04-24 | sha256:6abbcc03ab4f66bad7aa956702b19a7e5143914bb4cfa1c349c6f78b9c231858 |
| mozilla/atos-trustedroot-2011__f356bea244b7.pem | https://curl.se/ca/cacert.pem (cert `Atos TrustedRoot 2011`) | 2026-04-24 | sha256:8864fe58fe15e49522b6548b08ae1d26fbddd80e3589023f0cd64cfeed23f3b8 |
| mozilla/quovadis-root-ca-1-g3__8a866fd1b276.pem | https://curl.se/ca/cacert.pem (cert `QuoVadis Root CA 1 G3`) | 2026-04-24 | sha256:a872a5852f3bbf0fa8e856e9bc056c417e14d46e17931c8fe72287eb9628ba7d |
| mozilla/quovadis-root-ca-2-g3__8fe4fb0af93a.pem | https://curl.se/ca/cacert.pem (cert `QuoVadis Root CA 2 G3`) | 2026-04-24 | sha256:493221adbbe6adf32f1016376f9ff15fa8d9de42f118f3c47b3da628f2b3c705 |
| mozilla/quovadis-root-ca-3-g3__88ef81de202e.pem | https://curl.se/ca/cacert.pem (cert `QuoVadis Root CA 3 G3`) | 2026-04-24 | sha256:22f4d9ea0c4c01ff09ebd60efa6dd74e8e71539525b63a2e3cf539bee2db1819 |
| mozilla/digicert-assured-id-root-g2__7d05ebb68233.pem | https://curl.se/ca/cacert.pem (cert `DigiCert Assured ID Root G2`) | 2026-04-24 | sha256:f4f386ef963f6a05ed71130b0ac60890d83196e99439524cec739729fd666dbe |
| mozilla/digicert-assured-id-root-g3__7e37cb8b4c47.pem | https://curl.se/ca/cacert.pem (cert `DigiCert Assured ID Root G3`) | 2026-04-24 | sha256:4e551c8dd28ff54395f3182bb2f4c33d7498b3533de695a68159b69c0b79db78 |
| mozilla/digicert-global-root-g2__cb3ccbb76031.pem | https://curl.se/ca/cacert.pem (cert `DigiCert Global Root G2`) | 2026-04-24 | sha256:6e89751973099613d8796d1220352dd33f2a7e95b7ecba194ebf2c41fba56f45 |
| mozilla/digicert-global-root-g3__31ad6648f810.pem | https://curl.se/ca/cacert.pem (cert `DigiCert Global Root G3`) | 2026-04-24 | sha256:539efdab7f0043ec4ad70cbd9588295c28649a2a3535bc6942a29d2c615cdd5d |
| mozilla/digicert-trusted-root-g4__552f7bdcf1a7.pem | https://curl.se/ca/cacert.pem (cert `DigiCert Trusted Root G4`) | 2026-04-24 | sha256:affe31abf15cee77a2e194496278ac6a86915666de6e49fa3934d44849413640 |
| mozilla/comodo-rsa-certification-authority__52f0e1c4e58e.pem | https://curl.se/ca/cacert.pem (cert `COMODO RSA Certification Authority`) | 2026-04-24 | sha256:d0391fcd67ce8a17e1e6264e6db636a7c99e3864965aae1758d9199393d24592 |
| mozilla/usertrust-rsa-certification-authority__e793c9b02fd8.pem | https://curl.se/ca/cacert.pem (cert `USERTrust RSA Certification Authority`) | 2026-04-24 | sha256:86b36a2f63e518b70ec40ba138636d68fb99b267443fc8b835379ad06227e4a1 |
| mozilla/usertrust-ecc-certification-authority__4ff460d54b9c.pem | https://curl.se/ca/cacert.pem (cert `USERTrust ECC Certification Authority`) | 2026-04-24 | sha256:05a8d932ff83846770aacb0fca560f458013e5b5bc23a8afbbc956ab3ce44cbc |
| mozilla/globalsign__179fbc148a3d.pem | https://curl.se/ca/cacert.pem (cert `GlobalSign`) | 2026-04-24 | sha256:83d70680353c7446a0bc28bde878aad0c322b8943a00bd850fc012acbcd7158d |
| mozilla/identrust-commercial-root-ca-1__5d56499be4d2.pem | https://curl.se/ca/cacert.pem (cert `IdenTrust Commercial Root CA 1`) | 2026-04-24 | sha256:e423cbb92a8032ec9d717fd701649f16378e69d27b01a7835a24f73710fbfb16 |
| mozilla/identrust-public-sector-root-ca-1__30d0895a9a44.pem | https://curl.se/ca/cacert.pem (cert `IdenTrust Public Sector Root CA 1`) | 2026-04-24 | sha256:2369f8abed338739bc589c1ab4e78e6a41f5ba3607cfdfa77f152c318c603f9a |
| mozilla/entrust-root-certification-authority---g2__43df5774b03e.pem | https://curl.se/ca/cacert.pem (cert `Entrust Root Certification Authority - G2`) | 2026-04-24 | sha256:32533cb83a73dd456b94f4a7fbde2d158084b13df89709cca0e37514623edde0 |
| mozilla/entrust-root-certification-authority---ec1__02ed0eb28c14.pem | https://curl.se/ca/cacert.pem (cert `Entrust Root Certification Authority - EC1`) | 2026-04-24 | sha256:2e5d17912f05bbf5d8251cd79b8b95ee695a60a120f109b9312fc436751d645e |
| mozilla/cfca-ev-root__5cc3d78e4e1d.pem | https://curl.se/ca/cacert.pem (cert `CFCA EV ROOT`) | 2026-04-24 | sha256:dfba30660271975c8264843a5ac8eef1da350c53c6fa9c3eee4b61edfafaa8b5 |
| mozilla/oiste-wisekey-global-root-gb-ca__6b9c08e86eb0.pem | https://curl.se/ca/cacert.pem (cert `OISTE WISeKey Global Root GB CA`) | 2026-04-24 | sha256:15fbbd63b6d3644aa2b20cc491b65d0dbd67b976a4f49991739e918464a389af |
| mozilla/szafir-root-ca2__a1339d33281a.pem | https://curl.se/ca/cacert.pem (cert `SZAFIR ROOT CA2`) | 2026-04-24 | sha256:60544920f8747da29ced2d73c255862f24c8b201f1abf3e55571632732be6922 |
| mozilla/certum-trusted-network-ca-2__b676f2eddae8.pem | https://curl.se/ca/cacert.pem (cert `Certum Trusted Network CA 2`) | 2026-04-24 | sha256:5248ef9282c3c21d78e6228e4bedd73e62cac3d162718e05ccc6ad52d8e43093 |
| mozilla/hellenic-academic-and-research-institutions-rootca-2015__a040929a02ce.pem | https://curl.se/ca/cacert.pem (cert `Hellenic Academic and Research Institutions RootCA 2015`) | 2026-04-24 | sha256:93bc2f4f22772f2ac78fcd97842a2478ac33a5e403ec0502d7c14caf56135dc7 |
| mozilla/hellenic-academic-and-research-institutions-ecc-rootca-2015__44b545aa8a25.pem | https://curl.se/ca/cacert.pem (cert `Hellenic Academic and Research Institutions ECC RootCA 2015`) | 2026-04-24 | sha256:c714b2aa6e511f622bd56090b9379c561bf8e6340c9e98d6963da1a9342d7e89 |
| mozilla/isrg-root-x1__96bcec062649.pem | https://curl.se/ca/cacert.pem (cert `ISRG Root X1`) | 2026-04-24 | sha256:253cd971fe376c24a225953cb9a336cbe36e07f7c5becbc4b954be3a256c46df |
| mozilla/fnmt-rcm__ebc5570c2901.pem | https://curl.se/ca/cacert.pem (cert `FNMT-RCM`) | 2026-04-24 | sha256:6bccf48d3ccb60dbe62670c33c501a9320d6c0c34839541ecd0a6a07aae9d3e9 |
| mozilla/amazon-root-ca-1__8ecde6884f3d.pem | https://curl.se/ca/cacert.pem (cert `Amazon Root CA 1`) | 2026-04-24 | sha256:29afc387457b6d73bb97819dcd31eb10158def0101049976aa7d1a4504d83673 |
| mozilla/amazon-root-ca-2__1ba5b2aa8c65.pem | https://curl.se/ca/cacert.pem (cert `Amazon Root CA 2`) | 2026-04-24 | sha256:52621987ee68d21d509597b27b8b46877fd8e2c3bac363c21a81b08dafc343e2 |
| mozilla/amazon-root-ca-3__18ce6cfe7bf1.pem | https://curl.se/ca/cacert.pem (cert `Amazon Root CA 3`) | 2026-04-24 | sha256:1f95db405e400a7647ff79de0e012e11f52f2103d38370f3d0748c9082abe71c |
| mozilla/amazon-root-ca-4__e35d28419ed0.pem | https://curl.se/ca/cacert.pem (cert `Amazon Root CA 4`) | 2026-04-24 | sha256:d1146e92a113d175b51ea6c48d9b9ffb6002377c3042aaec36913db933b884a1 |
| mozilla/tubitak-kamu-sm-ssl-kok-sertifikasi---surum-1__46edc3689046.pem | https://curl.se/ca/cacert.pem (cert `TUBITAK Kamu SM SSL Kok Sertifikasi - Surum 1`) | 2026-04-24 | sha256:e7125b83f65d9d7b79ac47e6a47a035747d7c7a0871deb0c23667c311d50ddaa |
| mozilla/gdca-trustauth-r5-root__bfff8fd04433.pem | https://curl.se/ca/cacert.pem (cert `GDCA TrustAUTH R5 ROOT`) | 2026-04-24 | sha256:3435c21f99a992f6aec91794bd5b1e833c453fdf7d87e186c9cc0b5fec6b2242 |
| mozilla/ssl.com-root-certification-authority-rsa__85666a562ee0.pem | https://curl.se/ca/cacert.pem (cert `SSL.com Root Certification Authority RSA`) | 2026-04-24 | sha256:a70a165b19092b95a4deec184f4f3defd852ce4297f9b97609ee575ab4239e24 |
| mozilla/ssl.com-root-certification-authority-ecc__3417bb06cc60.pem | https://curl.se/ca/cacert.pem (cert `SSL.com Root Certification Authority ECC`) | 2026-04-24 | sha256:51aee96d2c54b2d92af6534c126437e1576df78fe7740c5e0374252b6710cc71 |
| mozilla/ssl.com-ev-root-certification-authority-rsa-r2__2e7bf16cc224.pem | https://curl.se/ca/cacert.pem (cert `SSL.com EV Root Certification Authority RSA R2`) | 2026-04-24 | sha256:30cfef3a85d3626ed0a58d518a161acee3697c93d3f1645546556885770a2113 |
| mozilla/ssl.com-ev-root-certification-authority-ecc__22a2c1f7bded.pem | https://curl.se/ca/cacert.pem (cert `SSL.com EV Root Certification Authority ECC`) | 2026-04-24 | sha256:5ea086f089f8432337cc865669cdb4f6c96f46bc8ef51f50640557bed7c7061c |
| mozilla/globalsign__2cabeafe37d0.pem | https://curl.se/ca/cacert.pem (cert `GlobalSign`) | 2026-04-24 | sha256:475806eed173232cb0ac5d097993198b1d679b531a34bf7d5afff41e0501e701 |
| mozilla/oiste-wisekey-global-root-gc-ca__8560f91c3624.pem | https://curl.se/ca/cacert.pem (cert `OISTE WISeKey Global Root GC CA`) | 2026-04-24 | sha256:877a485002131a4173f2037430b7e714e2c373154a7312d35d90f3da0a318f29 |
| mozilla/uca-global-g2-root__9bea11c976fe.pem | https://curl.se/ca/cacert.pem (cert `UCA Global G2 Root`) | 2026-04-24 | sha256:08f5b96b92c7529193a98dbe84b81ff3ad0e07441228d9f94b38f6b4bccb0e57 |
| mozilla/uca-extended-validation-root__d43af9b35473.pem | https://curl.se/ca/cacert.pem (cert `UCA Extended Validation Root`) | 2026-04-24 | sha256:d66bbbfc2768f311a0bc12b30443e7694e0e43b75f2f9c55769298bbec09d21a |
| mozilla/certigna-root-ca__d48d3d23eedb.pem | https://curl.se/ca/cacert.pem (cert `Certigna Root CA`) | 2026-04-24 | sha256:3be1a46cf57324a4e4b01ae92e2ef6af1a0e28f861d28c3e3004bdc014e8c4d0 |
| mozilla/emsign-root-ca---g1__40f6af0346a9.pem | https://curl.se/ca/cacert.pem (cert `emSign Root CA - G1`) | 2026-04-24 | sha256:82520e9eb3ba146d4bc6eb20bcde177465cec6e73dbcdcaec4f722135617583d |
| mozilla/emsign-ecc-root-ca---g3__86a1ecba089c.pem | https://curl.se/ca/cacert.pem (cert `emSign ECC Root CA - G3`) | 2026-04-24 | sha256:0718c554987fa4c6fd992c76765806989b1256d2763ef76ac36875c7a4b0fe52 |
| mozilla/emsign-root-ca---c1__125609aa301d.pem | https://curl.se/ca/cacert.pem (cert `emSign Root CA - C1`) | 2026-04-24 | sha256:d6ec92eb63a6ade9b1c3468fd54e39bc6c512cc73fec95ba61bbf1624a878ee1 |
| mozilla/emsign-ecc-root-ca---c3__bc4d809b1518.pem | https://curl.se/ca/cacert.pem (cert `emSign ECC Root CA - C3`) | 2026-04-24 | sha256:ed7ccf40dc2869d85d508595fdf9cb6032b8bb1aa52459af7b474dbbe5f6bc49 |
| mozilla/hongkong-post-root-ca-3__5a2fc03f0c83.pem | https://curl.se/ca/cacert.pem (cert `Hongkong Post Root CA 3`) | 2026-04-24 | sha256:7caf707ee3d208b87fc698ea574161ac3175a66318844815245a764bb8e8f247 |
| mozilla/microsoft-ecc-root-certificate-authority-2017__358df39d764a.pem | https://curl.se/ca/cacert.pem (cert `Microsoft ECC Root Certificate Authority 2017`) | 2026-04-24 | sha256:c8fb4f77f6b613c4d6e09d5f1959ce7b2cf17d802e902405dfe518d07dc86a76 |
| mozilla/microsoft-rsa-root-certificate-authority-2017__c741f70f4b2a.pem | https://curl.se/ca/cacert.pem (cert `Microsoft RSA Root Certificate Authority 2017`) | 2026-04-24 | sha256:764d1eda980eeb8b4c9053eeea72ae2d57a68761c6176c0ef7d32f631f7aaaf2 |
| mozilla/e-szigno-root-ca-2017__beb00b30839b.pem | https://curl.se/ca/cacert.pem (cert `e-Szigno Root CA 2017`) | 2026-04-24 | sha256:dcc963d6f08c486d8994ec50af2bdf94fdb86704651921c90215366fed26bbe5 |
| mozilla/certsign-sa__657cfe2fa73f.pem | https://curl.se/ca/cacert.pem (cert `CERTSIGN SA`) | 2026-04-24 | sha256:f0cc33e1f784a72793a50dd915cf88e370275bbabcba421510c17422d99f158e |
| mozilla/trustwave-global-certification-authority__97552015f5dd.pem | https://curl.se/ca/cacert.pem (cert `Trustwave Global Certification Authority`) | 2026-04-24 | sha256:d7bc5f022fc39cefb01619859a8a635be6302aa9c027f12d711e3e7937dd9135 |
| mozilla/trustwave-global-ecc-p256-certification-authority__945bbc825ea5.pem | https://curl.se/ca/cacert.pem (cert `Trustwave Global ECC P256 Certification Authority`) | 2026-04-24 | sha256:600a74fc84413499956e4ebea15f2624a1ae94994bb44802856af79a084e41b4 |
| mozilla/trustwave-global-ecc-p384-certification-authority__55903859c8c0.pem | https://curl.se/ca/cacert.pem (cert `Trustwave Global ECC P384 Certification Authority`) | 2026-04-24 | sha256:9cf56afc0f51947a4f65f6ee1264b5a9aa755eb21846430c302ef3f84022729f |
| mozilla/naver-global-root-certification-authority__88f438dcf8ff.pem | https://curl.se/ca/cacert.pem (cert `NAVER Global Root Certification Authority`) | 2026-04-24 | sha256:ecd03b8836ec973163e881ee10ee771aef1245073a425a247ad8e05f07fa7f07 |
| mozilla/ac-raiz-fnmt-rcm-servidores-seguros__554153b13d2c.pem | https://curl.se/ca/cacert.pem (cert `AC RAIZ FNMT-RCM SERVIDORES SEGUROS`) | 2026-04-24 | sha256:9eeea09ab37af4775bb36a62f5ec0a0aaf9849f6ea233f71c0358ad5a4d97055 |
| mozilla/globalsign-root-r46__4fa3126d8d3a.pem | https://curl.se/ca/cacert.pem (cert `GlobalSign Root R46`) | 2026-04-24 | sha256:6522e830521e56f56fa805399e1bf7e4b948af512eb99d6a236557b6ea8b57f7 |
| mozilla/globalsign-root-e46__cbb9c44d84b8.pem | https://curl.se/ca/cacert.pem (cert `GlobalSign Root E46`) | 2026-04-24 | sha256:c356ba5da27271e253bbd15bd76852d69ff9faad5e5fc2562dc0b3503d0a9a25 |
| mozilla/globaltrust-2020__9a296a5182d1.pem | https://curl.se/ca/cacert.pem (cert `GLOBALTRUST 2020`) | 2026-04-24 | sha256:b0b0d770c89559d5221797749f112fb5d984fdc0395acbcd1e0ee773df53aca2 |
| mozilla/anf-secure-server-root-ca__fb8fec759169.pem | https://curl.se/ca/cacert.pem (cert `ANF Secure Server Root CA`) | 2026-04-24 | sha256:6d3473e3d610b2c68021d7d20a3c53ea63b79fea5bca226204fe0e15ab3c7329 |
| mozilla/certum-ec-384-ca__6b3280856253.pem | https://curl.se/ca/cacert.pem (cert `Certum EC-384 CA`) | 2026-04-24 | sha256:087c723f5a92a72a4b93cfe3c3f011e7f28a57405cdcdec1a5c7fa4a14f0628c |
| mozilla/certum-trusted-root-ca__fe7696573855.pem | https://curl.se/ca/cacert.pem (cert `Certum Trusted Root CA`) | 2026-04-24 | sha256:7c8af6939b226a6e45927a70ef1a0dd92a748f97c38922a6d6cbe3ac3dac0f3b |
| mozilla/tuntrust-root-ca__2e44102ab58c.pem | https://curl.se/ca/cacert.pem (cert `TunTrust Root CA`) | 2026-04-24 | sha256:afd088015551dd44068b63a64d646a32f51a8536f4eff6f5203567d5b722d5e4 |
| mozilla/harica-tls-rsa-root-ca-2021__d95d0e8eda79.pem | https://curl.se/ca/cacert.pem (cert `HARICA TLS RSA Root CA 2021`) | 2026-04-24 | sha256:a2dcd8542937e9f629185c14b98ba9ab875177b40e5bd733f30ba6e6e4bd15c6 |
| mozilla/harica-tls-ecc-root-ca-2021__3f99cc474acf.pem | https://curl.se/ca/cacert.pem (cert `HARICA TLS ECC Root CA 2021`) | 2026-04-24 | sha256:0de6024f88ceea0263b172746ec96e61a1159626a969f8e5f46013a962ce02b5 |
| mozilla/autoridad-de-certificacion-firmaprofesional-cif-a62634068__57de0583efd2.pem | https://curl.se/ca/cacert.pem (cert `Autoridad de Certificacion Firmaprofesional CIF A62634068`) | 2026-04-24 | sha256:cea085c4aaaa686308317e1344f528e7300665f835f2db5c36735690b199fec5 |
| mozilla/vtrus-ecc-root-ca__30fbba2c3223.pem | https://curl.se/ca/cacert.pem (cert `vTrus ECC Root CA`) | 2026-04-24 | sha256:8d1589083afb816ccd7a1514786f825f0ea235691d1c67201eca5bca3f3a2d61 |
| mozilla/vtrus-root-ca__8a71de655933.pem | https://curl.se/ca/cacert.pem (cert `vTrus Root CA`) | 2026-04-24 | sha256:d9c600b91e68ce597255bf39d62e15a5fd3dfb8847d9b14fe8909100fb922a0f |
| mozilla/isrg-root-x2__69729b8e15a8.pem | https://curl.se/ca/cacert.pem (cert `ISRG Root X2`) | 2026-04-24 | sha256:f51348d1586536068882d64922b8a6d69402b14e46aecd766da1d52274619767 |
| mozilla/hipki-root-ca---g1__f015ce3cc239.pem | https://curl.se/ca/cacert.pem (cert `HiPKI Root CA - G1`) | 2026-04-24 | sha256:5671aec2754658420330f8beaf81011d9ab7ba8d037a766089411b4058db56d0 |
| mozilla/globalsign__b085d70b964f.pem | https://curl.se/ca/cacert.pem (cert `GlobalSign`) | 2026-04-24 | sha256:ea4a57233dcc4226e844ff70a196a29e9bffe27c1f85d08cd5f18ee689155ab6 |
| mozilla/gts-root-r1__d947432abde7.pem | https://curl.se/ca/cacert.pem (cert `GTS Root R1`) | 2026-04-24 | sha256:c2a278e10ab284628ed21b673fcc574b31e94e4f57edfcbdb63fdab5d48f846e |
| mozilla/gts-root-r2__8d25cd97229d.pem | https://curl.se/ca/cacert.pem (cert `GTS Root R2`) | 2026-04-24 | sha256:351366a5709c72bc636adb2f856f3ee6092c55fedc05ada46f171862c2adef24 |
| mozilla/gts-root-r3__34d8a73ee208.pem | https://curl.se/ca/cacert.pem (cert `GTS Root R3`) | 2026-04-24 | sha256:0ba6b8750abe22b366cffa7d2d3c92af70b12ef44d27c7a758dced04f3fb8085 |
| mozilla/gts-root-r4__349dfa4058c5.pem | https://curl.se/ca/cacert.pem (cert `GTS Root R4`) | 2026-04-24 | sha256:cc506b1e8f94414acd6afecc6784758cd3204be35426754646b6f1863dfdb158 |
| mozilla/telia-root-ca-v2__242b69742fcb.pem | https://curl.se/ca/cacert.pem (cert `Telia Root CA v2`) | 2026-04-24 | sha256:e75d3b7b5eb3c770820e8164aa3356e5bcd9e1da69dc43a0726bb5410ee4c607 |
| mozilla/d-trust-br-root-ca-1-2020__e59aaa816009.pem | https://curl.se/ca/cacert.pem (cert `D-TRUST BR Root CA 1 2020`) | 2026-04-24 | sha256:04058847a237f6e6b024b4dceb99f578f70b8ddc0882d887fd9fbeb6c49b61bb |
| mozilla/d-trust-ev-root-ca-1-2020__08170d1aa364.pem | https://curl.se/ca/cacert.pem (cert `D-TRUST EV Root CA 1 2020`) | 2026-04-24 | sha256:b6508382972bd254f9669041232f45c93228b2d7a005efd5ba99b81f5d73157e |
| mozilla/digicert-tls-ecc-p384-root-g5__018e13f07725.pem | https://curl.se/ca/cacert.pem (cert `DigiCert TLS ECC P384 Root G5`) | 2026-04-24 | sha256:a533fd5d033b9e11be3cb1e08b6f56e76da7d9adc29087d715ee77815e3459e0 |
| mozilla/digicert-tls-rsa4096-root-g5__371a00dc0533.pem | https://curl.se/ca/cacert.pem (cert `DigiCert TLS RSA4096 Root G5`) | 2026-04-24 | sha256:c04e4d5f3f5fa4aefb8d4917afaf77616da4d65ba7e74fbb89a2e6dd4bdeb7cc |
| mozilla/certainly-root-r1__77b82cd8644c.pem | https://curl.se/ca/cacert.pem (cert `Certainly Root R1`) | 2026-04-24 | sha256:13db7587b696e30a771929fb4bec1767362a3d447f84cb9f7ce140327510f241 |
| mozilla/certainly-root-e1__b4585f22e4ac.pem | https://curl.se/ca/cacert.pem (cert `Certainly Root E1`) | 2026-04-24 | sha256:337a96656547a24aa6e839a5de96ffe3f07d118beaa3c2414c1f2ff6f8e60f22 |
| mozilla/security-communication-ecc-rootca1__e74fbda55bd5.pem | https://curl.se/ca/cacert.pem (cert `Security Communication ECC RootCA1`) | 2026-04-24 | sha256:8c21dfd0f38896d1a96ab4bab4662b723785e80c6a7aa463db9349d443b9336c |
| mozilla/bjca-global-root-ca1__f3896f88fe7c.pem | https://curl.se/ca/cacert.pem (cert `BJCA Global Root CA1`) | 2026-04-24 | sha256:34aa826d98e184b17690f281c6e75cd002601a5de73eb48fa21cf04f05864a72 |
| mozilla/bjca-global-root-ca2__574df6931e27.pem | https://curl.se/ca/cacert.pem (cert `BJCA Global Root CA2`) | 2026-04-24 | sha256:1abe4338b94e9154253fed9440b13929b13dbe5bebaeae065d50349e9669a135 |
| mozilla/sectigo-public-server-authentication-root-e46__c90f26f0fb1b.pem | https://curl.se/ca/cacert.pem (cert `Sectigo Public Server Authentication Root E46`) | 2026-04-24 | sha256:eee37bd2903505a405355ca860b46f588f155bf3c4c21bdd1a9f8ab90a2324b4 |
| mozilla/sectigo-public-server-authentication-root-r46__7bb647a62aee.pem | https://curl.se/ca/cacert.pem (cert `Sectigo Public Server Authentication Root R46`) | 2026-04-24 | sha256:1bc1c83ec5a7ce5896715740f133cad548f842e5f0aec59688dd1209548c01ff |
| mozilla/ssl.com-tls-rsa-root-ca-2022__8faf7d2e2cb4.pem | https://curl.se/ca/cacert.pem (cert `SSL.com TLS RSA Root CA 2022`) | 2026-04-24 | sha256:5fef4ad7e6e55d5445f669d431325196597602a9f556c4ae9b8879acc6ebb33c |
| mozilla/ssl.com-tls-ecc-root-ca-2022__c32ffd9f46f9.pem | https://curl.se/ca/cacert.pem (cert `SSL.com TLS ECC Root CA 2022`) | 2026-04-24 | sha256:4090dd38b77f37678f2f3d09d268147b6724831b663b0506385efa2893d855a5 |
| mozilla/atos-trustedroot-root-ca-ecc-tls-2021__b2fae53e14cc.pem | https://curl.se/ca/cacert.pem (cert `Atos TrustedRoot Root CA ECC TLS 2021`) | 2026-04-24 | sha256:f1ee8bf0c6bbf77fc7f7c4138635d06f8ab5e2ae2edab88f5896aa6eb904968b |
| mozilla/atos-trustedroot-root-ca-rsa-tls-2021__81a9088ea59f.pem | https://curl.se/ca/cacert.pem (cert `Atos TrustedRoot Root CA RSA TLS 2021`) | 2026-04-24 | sha256:153b04f01b405ee7040f166cf0ebd895f4ed3abfbfaafcf81003e18e1cb4d571 |
| mozilla/trustasia-global-root-ca-g3__e0d3226aeb11.pem | https://curl.se/ca/cacert.pem (cert `TrustAsia Global Root CA G3`) | 2026-04-24 | sha256:ca5a5f53210535ddab4638f75e1c83093eebf7c5364fe1ce01a89c445ef40856 |
| mozilla/trustasia-global-root-ca-g4__be4b56cb5056.pem | https://curl.se/ca/cacert.pem (cert `TrustAsia Global Root CA G4`) | 2026-04-24 | sha256:af4ffbc4d7667d2511c40df67cd5b03177fb0a508ae7791b27778fe424f31665 |
| mozilla/telekom-security-tls-ecc-root-2020__578af4ded085.pem | https://curl.se/ca/cacert.pem (cert `Telekom Security TLS ECC Root 2020`) | 2026-04-24 | sha256:412edfde422cc3d79e026e8d9ea58d1ee681fdc6d78b88d918ec0466bed41cbf |
| mozilla/telekom-security-tls-rsa-root-2023__efc65cadbb59.pem | https://curl.se/ca/cacert.pem (cert `Telekom Security TLS RSA Root 2023`) | 2026-04-24 | sha256:6c049c3e4e8de2b6eefc1e7c79e0a907ced51caa29a5167419bf172ab1115302 |
| mozilla/firmaprofesional-ca-root-a-web__bef256daf26e.pem | https://curl.se/ca/cacert.pem (cert `FIRMAPROFESIONAL CA ROOT-A WEB`) | 2026-04-24 | sha256:0eb05151b100bd0e5c31f38a5e24d65cd2993d6ffec4f3c5e7c70d029b4ebebd |
| mozilla/twca-cyber-root-ca__3f63bb2814be.pem | https://curl.se/ca/cacert.pem (cert `TWCA CYBER Root CA`) | 2026-04-24 | sha256:86827c614c90747550f55993f45c59651dbac917100b73b19b5dd4f71c81b44e |
| mozilla/securesign-root-ca12__3f034bb5704d.pem | https://curl.se/ca/cacert.pem (cert `SecureSign Root CA12`) | 2026-04-24 | sha256:9f5ff541179db8f5a20def088da5ac7adbf5aa5426b3825ebc08a589af9d95b4 |
| mozilla/securesign-root-ca14__4b009c103449.pem | https://curl.se/ca/cacert.pem (cert `SecureSign Root CA14`) | 2026-04-24 | sha256:49623d38ae062faa6139d5002226a5c39753e2cdc893391013ec94000ab2a637 |
| mozilla/securesign-root-ca15__e778f0f095fe.pem | https://curl.se/ca/cacert.pem (cert `SecureSign Root CA15`) | 2026-04-24 | sha256:d725030c7e4a1724930741e75cab78039b488e8fdc5ef8f0f46563c030f85b76 |
| mozilla/d-trust-br-root-ca-2-2023__0552e6f83fdf.pem | https://curl.se/ca/cacert.pem (cert `D-TRUST BR Root CA 2 2023`) | 2026-04-24 | sha256:5efec0bc44f3ec45fc82d287931084fd0a6d35a8b5ccecda4e3157fea9671932 |
| mozilla/trustasia-tls-ecc-root-ca__c0076b9ef053.pem | https://curl.se/ca/cacert.pem (cert `TrustAsia TLS ECC Root CA`) | 2026-04-24 | sha256:1004852b3bd959eec0675754f9b8426ff030cd690a44649019a71ada42045b1a |
| mozilla/trustasia-tls-rsa-root-ca__06c08d7dafd8.pem | https://curl.se/ca/cacert.pem (cert `TrustAsia TLS RSA Root CA`) | 2026-04-24 | sha256:41718c0628f805209109249ab0daa6589edc1f68581e5baac29cae97b754c59e |
| mozilla/d-trust-ev-root-ca-2-2023__8e8221b2e7d4.pem | https://curl.se/ca/cacert.pem (cert `D-TRUST EV Root CA 2 2023`) | 2026-04-24 | sha256:38a9725afa0f4af8e7c73d21b05a0bf45ce739238e4953b53b762a7e188a25d6 |
| mozilla/swisssign-rsa-tls-root-ca-2022---1__193144f431e0.pem | https://curl.se/ca/cacert.pem (cert `SwissSign RSA TLS Root CA 2022 - 1`) | 2026-04-24 | sha256:d0698a781e84c655da0b74873d500c7894944c1fa572a424e9c8494795c6904b |
| mozilla/oiste-server-root-ecc-g1__eec997c0c30f.pem | https://curl.se/ca/cacert.pem (cert `OISTE Server Root ECC G1`) | 2026-04-24 | sha256:40720822deba5a28af4bf927f0c78ba548d63ad6b78e85f24818dc4383199010 |
| mozilla/oiste-server-root-rsa-g1__9ae36232a518.pem | https://curl.se/ca/cacert.pem (cert `OISTE Server Root RSA G1`) | 2026-04-24 | sha256:e585f15fabd9904f9b7034e19b7dcfa572cc603b8a6c9907c62a1108c3f28c37 |
| mozilla/e-szigno-tls-root-ca-2023__b49141502d00.pem | https://curl.se/ca/cacert.pem (cert `e-Szigno TLS Root CA 2023`) | 2026-04-24 | sha256:af92e39fb130eade77fffa6e094e7bebc6d5f71d117fbd44c0137f43234157cf |

## Synthetic X.509 fixtures

| Filename | Source | Retrieved | SHA-256 |
|---|---|---|---|
| synthetic/ed25519-selfsigned.pem | generated by test_data/certificates/tools/bootstrap_synthetic.py | 2026-04-24 | sha256:9ba08d005a59be47afd6ef4b1475ad19e1d8315ccb364bc20a8a1bb54d2b70cb |
| synthetic/ed25519-selfsigned-der.der | generated by test_data/certificates/tools/bootstrap_synthetic.py | 2026-04-24 | sha256:b7e3adad0e872434eb3744babdb82373628a6d7aa0ae3825a86c956ed8d1e39e |
| synthetic/ec-p256-selfsigned.pem | generated by test_data/certificates/tools/bootstrap_synthetic.py | 2026-04-24 | sha256:b7978a86cbf7e812dfc42b28162659f71918c8498c7191b2da32eb4640d2045d |
| synthetic/ec-p384-selfsigned.pem | generated by test_data/certificates/tools/bootstrap_synthetic.py | 2026-04-24 | sha256:3e2b3ec59d6e9cc50cf22a6494d4221f9719e2eda5dd20bf17950c79c06f719d |
| synthetic/rsa-2048-selfsigned.pem | generated by test_data/certificates/tools/bootstrap_synthetic.py | 2026-04-24 | sha256:65d33f124ca25dc9cda1b2d1ecfb6b9163fe55244a90f355c2b84ebe85dd11bb |
| synthetic/rsa-2048-sha1-selfsigned.pem | generated by test_data/certificates/tools/bootstrap_synthetic.py | 2026-04-24 | sha256:ce55057c91b77e2a79bf625c91f81138c072475edf80afe254c97a28b685d589 |
| synthetic/rsa-2048-intermediate.pem | generated by test_data/certificates/tools/bootstrap_synthetic.py | 2026-04-24 | sha256:292e9d4a368fa0b6d4afd98b6e2f9774dcf05e416e60360c2106c3bae2b4aa47 |
| (pem-bundles/synthetic/goatrodeo-test-chain.pem) | generated by test_data/certificates/tools/bootstrap_synthetic.py | 2026-04-24 | sha256:a978d9ae007ed3b664e527a0dfd98ded0bf1736a14391c6d1f8b24acfc32a279 |

## Canonical real-world roots (individually pinned)

| Filename | Source | Retrieved | SHA-256 |
|---|---|---|---|
| canonical/letsencrypt-isrgrootx1.pem | https://letsencrypt.org/certs/isrgrootx1.pem | 2026-04-24 | sha256:22b557a27055b33606b6559f37703928d3e4ad79f110b407d04986e1843543d1 |
| canonical/letsencrypt-isrg-root-x2.pem | https://letsencrypt.org/certs/isrg-root-x2.pem | 2026-04-24 | sha256:a13d881e11fe6df181b53841f9fa738a2d7ca9ae7be3d53c866f722b4242b013 |
| canonical/letsencrypt-r3.pem | https://letsencrypt.org/certs/lets-encrypt-r3.pem | 2026-04-24 | sha256:177e1b8fc43b722b393f4200ff4d92e32deeffbb76fef5ee68d8f49c88cf9d32 |
| canonical/letsencrypt-e1.pem | https://letsencrypt.org/certs/lets-encrypt-e1.pem | 2026-04-24 | sha256:a0f7541863bf1c9e816ec22dc602e13993b0a23bddf4213e781187499b6199ff |
| canonical/letsencrypt-e2.pem | https://letsencrypt.org/certs/lets-encrypt-e2.pem | 2026-04-24 | sha256:b42688d73bac5099d9cf4fdb7b05f5e54e98c5aa8ab56ee06c297a9a84d2d5f1 |
| canonical/digicert-global-root-g2.pem | https://cacerts.digicert.com/DigiCertGlobalRootG2.crt.pem | 2026-04-24 | sha256:5d550643b6400d4341550a9b14aedd0b4fac33ae5deb7d8247b6b4f799c13306 |

## PQC trust-anchor certs (from IETF Hackathon BC r5)

Each cert is a self-signed trust anchor produced by Bouncy Castle for the IETF Hackathon PQC interop suite. The `source` URL pins the zip; the `#path` fragment names the specific entry.

### composite

| Filename | Source | Retrieved | SHA-256 |
|---|---|---|---|
| pqc/composite/mldsa44-rsa2048-pss-sha256.der | https://github.com/IETF-Hackathon/pqc-certificates/raw/master/providers/bc/artifacts_certs_r5.zip#artifacts/MLDSA44-RSA2048-PSS-SHA256-1.3.6.1.5.5.7.6.37_ta.der | 2026-04-28 | sha256:ca9586121dc01e8f473e0f800f10f2548ee9e1d1831f827f0ee3c1f4a435111f |
| pqc/composite/mldsa44-ed25519-sha512.der | https://github.com/IETF-Hackathon/pqc-certificates/raw/master/providers/bc/artifacts_certs_r5.zip#artifacts/MLDSA44-Ed25519-SHA512-1.3.6.1.5.5.7.6.39_ta.der | 2026-04-28 | sha256:28101553d1160ab402c495772a6379d4b8a7f08ad0527bc2770c8c8b54f773d5 |
| pqc/composite/mldsa44-ecdsa-p256-sha256.der | https://github.com/IETF-Hackathon/pqc-certificates/raw/master/providers/bc/artifacts_certs_r5.zip#artifacts/MLDSA44-ECDSA-P256-SHA256-1.3.6.1.5.5.7.6.40_ta.der | 2026-04-28 | sha256:22d48250183ea5131583ff0e45dfe0c5f6dac4a76d43b270c7feba52c58c5f2c |
| pqc/composite/mldsa65-rsa3072-pss-sha512.der | https://github.com/IETF-Hackathon/pqc-certificates/raw/master/providers/bc/artifacts_certs_r5.zip#artifacts/MLDSA65-RSA3072-PSS-SHA512-1.3.6.1.5.5.7.6.41_ta.der | 2026-04-28 | sha256:f8ca29724b7bd0612a3559bedaebc4e7b088db23811e69d2c85cac2c0f97271e |
| pqc/composite/mldsa87-ecdsa-p384-sha512.der | https://github.com/IETF-Hackathon/pqc-certificates/raw/master/providers/bc/artifacts_certs_r5.zip#artifacts/MLDSA87-ECDSA-P384-SHA512-1.3.6.1.5.5.7.6.49_ta.der | 2026-04-28 | sha256:e843297e4d9353289b85d595b778407ee05864675549a93c0d9e9470cdf146bf |

### falcon

| Filename | Source | Retrieved | SHA-256 |
|---|---|---|---|
| pqc/falcon/falcon-512.der | https://github.com/IETF-Hackathon/pqc-certificates/raw/master/providers/bc/artifacts_certs_r5.zip#artifacts/falcon-512-1.3.9999.3.11_ta.der | 2026-04-28 | sha256:00cafb388889cf07ec6e2c5abef9b5f99b03e41ffa982af749ce1c0807b3f847 |
| pqc/falcon/falcon-1024.der | https://github.com/IETF-Hackathon/pqc-certificates/raw/master/providers/bc/artifacts_certs_r5.zip#artifacts/falcon-1024-1.3.9999.3.14_ta.der | 2026-04-28 | sha256:17624f7f5519dc1fca1bfff22d587ead3b57c15644f91d2eb5f57649eb678ef7 |

### ml-dsa

| Filename | Source | Retrieved | SHA-256 |
|---|---|---|---|
| pqc/ml-dsa/ml-dsa-44.der | https://github.com/IETF-Hackathon/pqc-certificates/raw/master/providers/bc/artifacts_certs_r5.zip#artifacts/ml-dsa-44-2.16.840.1.101.3.4.3.17_ta.der | 2026-04-28 | sha256:444a68965d3dd29173fd954195252887d7f91a04fdb66bb4004d8966a17c8e54 |
| pqc/ml-dsa/ml-dsa-65.der | https://github.com/IETF-Hackathon/pqc-certificates/raw/master/providers/bc/artifacts_certs_r5.zip#artifacts/ml-dsa-65-2.16.840.1.101.3.4.3.18_ta.der | 2026-04-28 | sha256:a184f62cbfa477b54df83db964a4f6c2c7a0f3530c1895ed0376d31fd8e3d62b |
| pqc/ml-dsa/ml-dsa-87.der | https://github.com/IETF-Hackathon/pqc-certificates/raw/master/providers/bc/artifacts_certs_r5.zip#artifacts/ml-dsa-87-2.16.840.1.101.3.4.3.19_ta.der | 2026-04-28 | sha256:519cb08f6162db758ef57537bc5c7feef962431722a0c41f04bc9347d90d7514 |
| pqc/ml-dsa/ml-dsa-44-prehash-sha512.der | https://github.com/IETF-Hackathon/pqc-certificates/raw/master/providers/bc/artifacts_certs_r5.zip#artifacts/ml-dsa-44-with-sha512-2.16.840.1.101.3.4.3.32_ta.der | 2026-04-28 | sha256:3eae64d6c418111a65f407eded7db6ce084233f96222d4fe4cebe0d83f6adbb0 |
| pqc/ml-dsa/ml-dsa-65-prehash-sha512.der | https://github.com/IETF-Hackathon/pqc-certificates/raw/master/providers/bc/artifacts_certs_r5.zip#artifacts/ml-dsa-65-with-sha512-2.16.840.1.101.3.4.3.33_ta.der | 2026-04-28 | sha256:23a779b06ef24eb268d2962a4ce547f0a5828e86af6595c0522f128cbe786cb5 |
| pqc/ml-dsa/ml-dsa-87-prehash-sha512.der | https://github.com/IETF-Hackathon/pqc-certificates/raw/master/providers/bc/artifacts_certs_r5.zip#artifacts/ml-dsa-87-with-sha512-2.16.840.1.101.3.4.3.34_ta.der | 2026-04-28 | sha256:23772afb653b74a5df3af119b699a0b08ec9a2e099312ef30881f3da234abf10 |

### slh-dsa

| Filename | Source | Retrieved | SHA-256 |
|---|---|---|---|
| pqc/slh-dsa/slh-dsa-sha2-128s.der | https://github.com/IETF-Hackathon/pqc-certificates/raw/master/providers/bc/artifacts_certs_r5.zip#artifacts/slh-dsa-sha2-128s-2.16.840.1.101.3.4.3.20_ta.der | 2026-04-28 | sha256:e0a1e765232ad84fa3596e687bb41f6c6c2103e60bbf4d4994bc7d564e64132d |
| pqc/slh-dsa/slh-dsa-sha2-128f.der | https://github.com/IETF-Hackathon/pqc-certificates/raw/master/providers/bc/artifacts_certs_r5.zip#artifacts/slh-dsa-sha2-128f-2.16.840.1.101.3.4.3.21_ta.der | 2026-04-28 | sha256:6dff8ddb39335a887c9d22a9962318ecac4586e8714e748e5b0a2b7287cce3ea |
| pqc/slh-dsa/slh-dsa-sha2-192s.der | https://github.com/IETF-Hackathon/pqc-certificates/raw/master/providers/bc/artifacts_certs_r5.zip#artifacts/slh-dsa-sha2-192s-2.16.840.1.101.3.4.3.22_ta.der | 2026-04-28 | sha256:81ac6777f06ea108955567191ee00a5d07b26d67b5edc56dd76e912d6c15acb4 |
| pqc/slh-dsa/slh-dsa-sha2-192f.der | https://github.com/IETF-Hackathon/pqc-certificates/raw/master/providers/bc/artifacts_certs_r5.zip#artifacts/slh-dsa-sha2-192f-2.16.840.1.101.3.4.3.23_ta.der | 2026-04-28 | sha256:f5239542851a818fb559f3f10e708e0ab99fcdaa31cdee631b1501f08f84f2eb |
| pqc/slh-dsa/slh-dsa-sha2-256s.der | https://github.com/IETF-Hackathon/pqc-certificates/raw/master/providers/bc/artifacts_certs_r5.zip#artifacts/slh-dsa-sha2-256s-2.16.840.1.101.3.4.3.24_ta.der | 2026-04-28 | sha256:012ee3aee4261d263a158d9563d0882d723b0da9dd9ac1908c1c569a0dd0b774 |
| pqc/slh-dsa/slh-dsa-sha2-256f.der | https://github.com/IETF-Hackathon/pqc-certificates/raw/master/providers/bc/artifacts_certs_r5.zip#artifacts/slh-dsa-sha2-256f-2.16.840.1.101.3.4.3.25_ta.der | 2026-04-28 | sha256:e6a2897852615d0a4efaab90604c9b94315ad03de9223f06c483256b33bdbb1f |
| pqc/slh-dsa/slh-dsa-shake-128s.der | https://github.com/IETF-Hackathon/pqc-certificates/raw/master/providers/bc/artifacts_certs_r5.zip#artifacts/slh-dsa-shake-128s-2.16.840.1.101.3.4.3.26_ta.der | 2026-04-28 | sha256:7eed0767e8bb4b293dcabdb96938cd43a46732de4a798df736f40eda16fb6b2d |
| pqc/slh-dsa/slh-dsa-shake-256f.der | https://github.com/IETF-Hackathon/pqc-certificates/raw/master/providers/bc/artifacts_certs_r5.zip#artifacts/slh-dsa-shake-256f-2.16.840.1.101.3.4.3.31_ta.der | 2026-04-28 | sha256:626aa76ebaa2f3c32f7dcdb9a2a1544f160f57ae63b425a81c450adf067c510f |


## Historical / distrusted CA roots

| Filename | Source | Retrieved | SHA-256 |
|---|---|---|---|
| historical/crtsh-id-8395.pem | https://crt.sh/?d=8395 | 2026-04-28 | sha256:139a5e4a4e0fa505378c72c5f700934ce8333f4e6b1b508886c4b0eb14f4be99 |

## TLS-chain leaf certs (live capture)

| Filename | Source | Retrieved | SHA-256 |
|---|---|---|---|
| leaves/letsencrypt.org__letsencrypt.org__5f9521021b.der | openssl s_client letsencrypt.org:443 chain[0] | 2026-04-28 | sha256:5f9521021b41667e26acc7adf500a81111d878dce5f00ccc767d1c621b9e3560 |
| leaves/github.com__github.com__9716d39441.der | openssl s_client github.com:443 chain[0] | 2026-04-28 | sha256:9716d39441ca651c51be78e969ca385ec213ec17715b8c91f01ee652f90fc62c |
| leaves/google.com__.google.com__99e14b5060.der | openssl s_client google.com:443 chain[0] | 2026-04-28 | sha256:99e14b50600ec394cb2c15858e68fff19cb70c9ee08cb7295218128167c43823 |
| leaves/kernel.org__tor.source.kernel.org__fe54679a04.der | openssl s_client kernel.org:443 chain[0] | 2026-04-28 | sha256:fe54679a04a0a1caac2bfd3ec19c6e28cd761009a74b04e996e919a1a7882d26 |
| leaves/mozilla.org__mozilla.org__b4ce1dc88a.der | openssl s_client mozilla.org:443 chain[0] | 2026-04-28 | sha256:b4ce1dc88a2bbfd82a17b29b0fb83b06d8f14a4151e99c2685c3fc08035fac95 |
| leaves/debian.org__www-fastly.debian.org__13a776b5f4.der | openssl s_client debian.org:443 chain[0] | 2026-04-28 | sha256:13a776b5f4a08e28396d76bd3033e7cedeea9f889784032eaef593a5df3ac9e5 |
| leaves/wikipedia.org__.wikipedia.org__4720a86f44.der | openssl s_client wikipedia.org:443 chain[0] | 2026-04-28 | sha256:4720a86f440eb48c9792e5cba9c8fe8b127c806f15ea60750724bf3325497a9b |
| leaves/cloudflare.com__cloudflare.com__da9fca34e8.der | openssl s_client cloudflare.com:443 chain[0] | 2026-04-28 | sha256:da9fca34e821865e3066db0f029492013b6517f14aaf5a693abde9a48a174c19 |
| leaves/amazon.com__.peg.a2z.com__50f6e40f40.der | openssl s_client amazon.com:443 chain[0] | 2026-04-28 | sha256:50f6e40f406a9583a3f82b5b7036b7766451175955171d1e4a44517d2f7dbf79 |
| leaves/microsoft.com__microsoft.com__24c0737a55.der | openssl s_client microsoft.com:443 chain[0] | 2026-04-28 | sha256:24c0737a55fe979a494a9e510ce80c829225b91592cad7c60f3eaacf85b3590d |
| leaves/apple.com__apple.com__41035d2c46.der | openssl s_client apple.com:443 chain[0] | 2026-04-28 | sha256:41035d2c46bf24ff738501e27f860487993a687649f0f80e2c34407f9756f3a0 |
| leaves/rust-lang.org__rust-lang.org__800b82bf98.der | openssl s_client rust-lang.org:443 chain[0] | 2026-04-28 | sha256:800b82bf98a4a76caaae18c4bda55bc20ab1e6488b822e2d0c98cb11084b2956 |
| leaves/python.org__www.python.org__a162964cfe.der | openssl s_client python.org:443 chain[0] | 2026-04-28 | sha256:a162964cfe4209e308f700e88028757eb83d227b2bb35f67f186a6e70e1e201a |
| leaves/scala-lang.org__scala-lang.org__c1468f350c.der | openssl s_client scala-lang.org:443 chain[0] | 2026-04-28 | sha256:c1468f350c1efeb761d9a28540e7a4511420c70a2424535df31cf073adc7386a |
| leaves/openjdk.org__bugs.openjdk.org__e78a4c3931.der | openssl s_client openjdk.org:443 chain[0] | 2026-04-28 | sha256:e78a4c3931e072ee6ee1f18f92fdf89eaff4e299f88fd60e32f9a207cb073376 |
| leaves/github.io__.github.io__ea69bc711c.der | openssl s_client github.io:443 chain[0] | 2026-04-28 | sha256:ea69bc711cb9d45698d2fdaa4854d7dc086acd3a9c350164909b688ac7c0631f |
| leaves/stackoverflow.com__stackoverflow.com__ba807e2019.der | openssl s_client stackoverflow.com:443 chain[0] | 2026-04-28 | sha256:ba807e20197d99ae9d0e3600d2bb207f7296519fd3d743c280e582a0a8bf7068 |
| leaves/ietf.org__ietf.org__7e8971c344.der | openssl s_client ietf.org:443 chain[0] | 2026-04-28 | sha256:7e8971c3440e12cffcee60c133fb4e7bf2deb5e725357084f4cda9bed5fe7cd4 |
| leaves/rfc-editor.org__rfc-editor.org__e1b75d7ee9.der | openssl s_client rfc-editor.org:443 chain[0] | 2026-04-28 | sha256:e1b75d7ee989b44b7d1436d28e84c93478004b9b0bb4d43cada22ce251d9cbb1 |
| leaves/ca.gov__ca.gov__7e555ca372.der | openssl s_client ca.gov:443 chain[0] | 2026-04-28 | sha256:7e555ca3723c3ad45d3eaacabaf89b94486015e404c75b21722c6468e13d2e7d |
| leaves/gov.uk__www.gov.uk__7b86b9317e.der | openssl s_client gov.uk:443 chain[0] | 2026-04-28 | sha256:7b86b9317eae2af253d3e34cda9517b997a5a9adbc53f165cff55476e3f61b56 |
| leaves/europa.eu__europa.eu__803ed6ec7f.der | openssl s_client europa.eu:443 chain[0] | 2026-04-28 | sha256:803ed6ec7f4f1c863b9a7b1e8c51f4f71f119e071aa2b9618fb6e29dee230059 |
| leaves/un.org__.un.org__beb177054b.der | openssl s_client un.org:443 chain[0] | 2026-04-28 | sha256:beb177054b0cd0856c0404fc49a3bc0b0fd2bbd8cd7f01721f2b9f93d141e96e |
| leaves/who.int__who.int__2ee6b7cf09.der | openssl s_client who.int:443 chain[0] | 2026-04-28 | sha256:2ee6b7cf099e48917e369a6d500172e5282d154f7e502c55e1157e0e39ba1ae4 |
| leaves/nih.gov__www.nih.gov__5f8f953b8f.der | openssl s_client nih.gov:443 chain[0] | 2026-04-28 | sha256:5f8f953b8f5be28e26eb88a16a6b71cc13ccd75bf4db779ffbcdf20a33ce7a35 |
| leaves/nist.gov__nist.gov__952f598529.der | openssl s_client nist.gov:443 chain[0] | 2026-04-28 | sha256:952f59852931baa00d09b50aba17b296f080e65e020ec914c58c27f0ebcebaf8 |
| leaves/openssh.com__www.openbsd.org__534d07a270.der | openssl s_client openssh.com:443 chain[0] | 2026-04-28 | sha256:534d07a27099e8a903363ed5d9c9aadca375935aa9284f0e61498e88f1387fc3 |
| leaves/curl.se__curl.se__8b021b88b8.der | openssl s_client curl.se:443 chain[0] | 2026-04-28 | sha256:8b021b88b866d4de3b3bf1c24d07c212c8532f002b36b13fba7e05034e66af7e |
| leaves/openssl.org__openssl.org__6fdfe29392.der | openssl s_client openssl.org:443 chain[0] | 2026-04-28 | sha256:6fdfe293922eb0054a2f6489a9ec08a18d586eab80d108eafb8b122a9ceb0631 |

## TLS-chain intermediates

| Filename | Source | Retrieved | SHA-256 |
|---|---|---|---|
| intermediates/letsencrypt.org__e8__83624fd338.der | openssl s_client letsencrypt.org:443 chain[1] | 2026-04-28 | sha256:83624fd338c8d9b023c18a67cb7a9c0519da43d11775b4c6cbdad45c3d997c52 |
| intermediates/github.com__sectigo-public-server-authentication-ca-dv-e36__873f0ba80e.der | openssl s_client github.com:443 chain[1] | 2026-04-28 | sha256:873f0ba80e3ac222656dfd04158cc15c2927d42d5d05f01dee4a47eb43a916df |
| intermediates/github.com__sectigo-public-server-authentication-root-e46__ea6b89ed69.der | openssl s_client github.com:443 chain[2] | 2026-04-28 | sha256:ea6b89ed6907a209ff9188676fb164e7aced894b8996dfbe5ce5bbcc22de4ddd |
| intermediates/google.com__wr2__e6fe22bf45.der | openssl s_client google.com:443 chain[1] | 2026-04-28 | sha256:e6fe22bf45e4f0d3b85c59e02c0f495418e1eb8d3210f788d48cd5e1cb547cd4 |
| intermediates/google.com__gts-root-r1__3ee0278df7.der | openssl s_client google.com:443 chain[2] | 2026-04-28 | sha256:3ee0278df71fa3c125c4cd487f01d774694e6fc57e0cd94c24efd769133918e5 |
| intermediates/mozilla.org__wr3__2fe357db13.der | openssl s_client mozilla.org:443 chain[1] | 2026-04-28 | sha256:2fe357db13751ff9160e87354975b3407498f41c9bd16a48657866e6e5a9b4c7 |
| intermediates/debian.org__r13__d3b128216a.der | openssl s_client debian.org:443 chain[1] | 2026-04-28 | sha256:d3b128216a843f8ef1321501f5df52a5df52939ee2c19297712cd3de4d419354 |
| intermediates/wikipedia.org__e7__aeb1fd7410.der | openssl s_client wikipedia.org:443 chain[1] | 2026-04-28 | sha256:aeb1fd7410e83bc96f5da3c6a7c2c1bb836d1fa5cb86e708515890e428a8770b |
| intermediates/cloudflare.com__we1__1dfc1605fb.der | openssl s_client cloudflare.com:443 chain[1] | 2026-04-28 | sha256:1dfc1605fbad358d8bc844f76d15203fac9ca5c1a79fd4857ffaf2864fbebf96 |
| intermediates/cloudflare.com__gts-root-r4__76b27b80a5.der | openssl s_client cloudflare.com:443 chain[2] | 2026-04-28 | sha256:76b27b80a58027dc3cf1da68dac17010ed93997d0b603e2fadbe85012493b5a7 |
| intermediates/amazon.com__digicert-global-ca-g2__8fac576439.der | openssl s_client amazon.com:443 chain[1] | 2026-04-28 | sha256:8fac576439c9fd3ef153b51f9edd0d381b5df7b87559cebeca04297dd44a639b |
| intermediates/amazon.com__digicert-global-root-g2__aadadd5a87.der | openssl s_client amazon.com:443 chain[2] | 2026-04-28 | sha256:aadadd5a879d2eb8c41a89597291292709d42052f5b6399541c694c3b7353cd1 |
| intermediates/microsoft.com__microsoft-tls-g2-rsa-ca-ocsp-02__ea7a25255d.der | openssl s_client microsoft.com:443 chain[1] | 2026-04-28 | sha256:ea7a25255d111fc3ce4cb8fabe3adf9c27bbe6db203f955066bab4c5a71f3d08 |
| intermediates/microsoft.com__microsoft-tls-rsa-root-g2__ddcd1e8a20.der | openssl s_client microsoft.com:443 chain[2] | 2026-04-28 | sha256:ddcd1e8a20638d4aaff7201bb1d56452acd2c759f1686bdc38f73dd15732bdc2 |
| intermediates/microsoft.com__digicert-global-root-g2__cb3ccbb760.der | openssl s_client microsoft.com:443 chain[3] | 2026-04-28 | sha256:cb3ccbb76031e5e0138f8dd39a23f9de47ffc35e43c1144cea27d46a5ab1cb5f |
| intermediates/apple.com__apple-public-ev-server-ecc-ca-1---g1__2585928d2c.der | openssl s_client apple.com:443 chain[1] | 2026-04-28 | sha256:2585928d2c5bfd952e025bd12e27c6776224cf752ec362d3031cdd49351844d4 |
| intermediates/apple.com__digicert-global-root-g3__31ad6648f8.der | openssl s_client apple.com:443 chain[2] | 2026-04-28 | sha256:31ad6648f8104138c738f39ea4320133393e3a18cc02296ef97c2ac9ef6731d0 |
| intermediates/python.org__globalsign-atlas-r3-dv-tls-ca-2025-q4__f5165fc624.der | openssl s_client python.org:443 chain[1] | 2026-04-28 | sha256:f5165fc624453361e3a131c6ad90893a8de40158921a94e8a4b445398eedf6e0 |

## More historical / pinned-vendor CA roots

| Filename | Source | Retrieved | SHA-256 |
|---|---|---|---|
| historical/amazon-root-ca-1.pem | https://www.amazontrust.com/repository/AmazonRootCA1.pem | 2026-04-28 | sha256:2c43952ee9e000ff2acc4e2ed0897c0a72ad5fa72c3d934e81741cbd54f05bd1 |
| historical/amazon-root-ca-2.pem | https://www.amazontrust.com/repository/AmazonRootCA2.pem | 2026-04-28 | sha256:a3a7fe25439d9a9b50f60af43684444d798a4c869305bf615881e5c84a44c1a2 |
| historical/amazon-root-ca-3.pem | https://www.amazontrust.com/repository/AmazonRootCA3.pem | 2026-04-28 | sha256:3eb7c3258f4af9222033dc1bb3dd2c7cfa0982b98e39fb8e9dc095cfeb38126c |
| historical/amazon-root-ca-4.pem | https://www.amazontrust.com/repository/AmazonRootCA4.pem | 2026-04-28 | sha256:b0b7961120481e33670315b2f843e643c42f693c7a1010eb9555e06ddc730214 |
| historical/apple-inc-root.cer | https://www.apple.com/appleca/AppleIncRootCertificate.cer | 2026-04-28 | sha256:b0b1730ecbc7ff4505142c49f1295e6eda6bcaed7e2c68c5be91b5a11001f024 |
| historical/apple-root-ca-g2.cer | https://www.apple.com/certificateauthority/AppleRootCA-G2.cer | 2026-04-28 | sha256:c2b9b042dd57830e7d117dac55ac8ae19407d38e41d88f3215bc3a890444a050 |
| historical/apple-root-ca-g3.cer | https://www.apple.com/certificateauthority/AppleRootCA-G3.cer | 2026-04-28 | sha256:63343abfb89a6a03ebb57e9b3f5fa7be7c4f5c756f3017b3a8c488c3653e9179 |
| historical/microsoft-rsa-root-2017.crt | https://www.microsoft.com/pkiops/certs/Microsoft%20RSA%20Root%20Certificate%20Authority%202017.crt | 2026-04-28 | sha256:c741f70f4b2a8d88bf2e71c14122ef53ef10eba0cfa5e64cfa20f418853073e0 |
| historical/microsoft-ecc-root-2017.crt | https://www.microsoft.com/pkiops/certs/Microsoft%20ECC%20Root%20Certificate%20Authority%202017.crt | 2026-04-28 | sha256:358df39d764af9e1b766e9c972df352ee15cfac227af6ad1d70e8e4a6edcba02 |
| historical/gts-root-r1.pem | https://pki.goog/repo/certs/gtsr1.pem | 2026-04-28 | sha256:4195ea007a7ef8d3e2d338e8d9ff0083198e36bfa025442ddf41bb5213904fc2 |
| historical/gts-root-r2.pem | https://pki.goog/repo/certs/gtsr2.pem | 2026-04-28 | sha256:1a49076630e489e4b1056804fb6c768397a9de52b236609aaf6ec5b94ce508ec |
| historical/gts-root-r3.pem | https://pki.goog/repo/certs/gtsr3.pem | 2026-04-28 | sha256:39238e09bb7d30e39fbf87746ceac206f7ec206cff3d73c743e3f818ca2ec54f |
| historical/gts-root-r4.pem | https://pki.goog/repo/certs/gtsr4.pem | 2026-04-28 | sha256:7e8b80d078d3dd77d3ed2108dd2b33412c12d7d72cb0965741c70708691776a2 |
| historical/isrg-root-x1-cross-signed.pem | https://letsencrypt.org/certs/isrg-root-x1-cross-signed.pem | 2026-04-28 | sha256:95cc1c24329edc615041294538dfb21329930d8dffdcf33136173aa94734a28c |

## Topup TLS-chain leaves

| Filename | Source | Retrieved | SHA-256 |
|---|---|---|---|
| leaves/iana.org__.iana.org__146e78ef81.der | openssl s_client iana.org:443 chain[0] | 2026-04-28 | sha256:146e78ef81434a17a4a787fa41a2e5410e6b677ba030761eaef1f3008f5fae01 |

## Topup TLS-chain intermediates

| Filename | Source | Retrieved | SHA-256 |
|---|---|---|---|
| intermediates/iana.org__sectigo-public-server-authentication-ca-ov-r36__6542d176be.der | openssl s_client iana.org:443 chain[1] | 2026-04-28 | sha256:6542d176bed50f193c0ce297ae44ecd8a0a86bec2ede682769344059b4e78530 |
| intermediates/iana.org__sectigo-public-server-authentication-root-r46__92f351bf3d.der | openssl s_client iana.org:443 chain[2] | 2026-04-28 | sha256:92f351bf3d54164dfa8dd8f9e1139d3150349786485d2b9eecd00e2971c1e6c5 |
