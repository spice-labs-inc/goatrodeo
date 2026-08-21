# Carved-cert ELF corpus ground truth

Generated with ubuntu:24.04 gcc + openssl (see gen_carved_elf_corpus.sh).

- elf-rsa1024-cert: one RSA-1024 DER X.509, CN=carved-rsa1024, in .rodata
- elf-rsa2048-cert: one RSA-2048 DER X.509, CN=carved-rsa2048, in .rodata
- elf-two-certs: both of the above in one binary
- elf-deep-cert: the RSA-2048 cert at an offset beyond the 256 KB MIME-probe window
- elf-no-certs: no certificate bytes

openssl cross-checks:
        Subject: CN = carved-rsa1024
                Public-Key: (1024 bit)
        Subject: CN = carved-rsa2048
                Public-Key: (2048 bit)

cert1024.der offset in elf-rsa1024-cert:
