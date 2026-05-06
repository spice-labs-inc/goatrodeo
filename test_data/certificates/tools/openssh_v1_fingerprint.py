#!/usr/bin/env python3
"""Independent SSH SHA-256 fingerprint extractor for openssh-key-v1 files.

Used to generate ground-truth values for Phase 7 D2 white-box tests
where system ssh-keygen rejects the fixture format ("error in libcrypto").
This script re-implements the openssh-key-v1 envelope unpack from scratch
using only the Python standard library — independent of the Scala
strategy under test.

Wire format per OpenSSH PROTOCOL.key:

  "openssh-key-v1\\0"
  string  ciphername
  string  kdfname
  string  kdfoptions
  uint32  N
  string  publickey_1   (SSH wire-format public key)
  ...

For each input file, prints:
  <basename> <SHA-256(publickey_1) base64-no-padding>

This matches the strategy's `pkg:ssh/sha256@<b64>` pURL primary
identifier (modulo URL-encoding of `+`/`/` characters).

Usage:
  python3 openssh_v1_fingerprint.py <fixture> [<fixture> ...]
"""
import struct
import hashlib
import base64
import sys


def parse_openssh_v1_to_pubkey_sha256_b64(path: str) -> str:
    with open(path) as f:
        lines = f.read().splitlines()
    in_armor = False
    body_lines = []
    for line in lines:
        if line.startswith("-----BEGIN"):
            in_armor = True
            continue
        if line.startswith("-----END"):
            in_armor = False
            continue
        # Skip Comment: header lines (RFC 4716 / OpenSSH armored format).
        if in_armor and line and not line.startswith("Comment:"):
            body_lines.append(line.strip())
    raw = base64.b64decode("".join(body_lines))
    assert raw[:15] == b"openssh-key-v1\0", f"bad magic: {raw[:15]!r}"

    pos = 15

    def rstr() -> bytes:
        nonlocal pos
        (length,) = struct.unpack(">I", raw[pos : pos + 4])
        pos += 4
        s = raw[pos : pos + length]
        pos += length
        return s

    rstr()  # ciphername
    rstr()  # kdfname
    rstr()  # kdfoptions
    (_n,) = struct.unpack(">I", raw[pos : pos + 4])
    pos += 4
    pubkey_wire = rstr()
    sha = hashlib.sha256(pubkey_wire).digest()
    return base64.b64encode(sha).rstrip(b"=").decode()


if __name__ == "__main__":
    for fixture in sys.argv[1:]:
        b64 = parse_openssh_v1_to_pubkey_sha256_b64(fixture)
        name = fixture.rsplit("/", 1)[-1]
        print(f"{name} {b64}")
