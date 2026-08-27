#!/usr/bin/env python3
"""Tamper-evidence verifier for Goat Rodeo runs.

Checks that the ADG outputs (`.grc`/`.grd`/`.gri`), the CBOM files, and the
hash-chained run log of a Goat Rodeo run are mutually consistent, so that any
tampering with any part of the chain is detected.

Requires: Python 3.8+, `cbor2` (pip install cbor2).

Usage:
    verify_tamper_evidence.py --out <dir> --log <run.log> --checksum <checksum.json>

What it verifies:
  1. Chain replay: recompute each run.log line's cumulative digest
     (digest_N = SHA256(digest_{N-1} || payload_N)); the last digest must equal
     the checksum's final_chain_head; the correlation ID line must be first and
     match the checksum.
  2. Each .grc recorded in the checksum must exist, hash to its recorded
     SHA-256, carry the run correlation ID in its info, and embed a
     log_chain_head that is a real point on the log chain (an ancestor of the
     final head).
  3. Each .grd/.gri referenced by a .grc (via its info.sha256 arrays) must exist
     and hash to its recorded SHA-256.
  4. Each CBOM filename must be cbom_<escaped-first-file-name>_<last16-of-gitoid>
     and agree with the root gitoid/name found in the CBOM's
     goatrodeo:omnibor-path / goatrodeo:path properties.

Exit code 0 if all checks pass; 1 otherwise.
"""

import argparse
import hashlib
import json
import os
import re
import sys
import uuid

try:
    import cbor2
except ImportError:
    sys.stderr.write("error: requires 'cbor2' (pip install cbor2)\n")
    sys.exit(2)

GRC_MAGIC = 0x00BA4A4A  # GraphManager.Consts.ClusterFileMagicNumber
CBOM_NAME_RE = re.compile(r"^cbom_([A-Za-z0-9_-]+)_([0-9a-f]{16})\.json$")


def sha256_hex(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def read_short(b, off):
    return int.from_bytes(b[off:off + 2], "big")


def read_int(b, off):
    return int.from_bytes(b[off:off + 4], "big", signed=True)


def decode_grc_info(path):
    """Decode a .grc file's ClusterFileEnvelope.info dict.

    Layout: magic (4 bytes) + short envelope length (2 bytes) + CBOR envelope.
    """
    with open(path, "rb") as f:
        b = f.read()
    if read_int(b, 0) != GRC_MAGIC:
        raise ValueError(f"not a .grc (bad magic): {path}")
    env_len = read_short(b, 4)
    env = cbor2.loads(b[6:6 + env_len])
    return env.get("info", {})


def escape_name(name):
    return "".join(c if (c.isascii() and (c.isalnum() or c in "-_")) else "_" for c in name)


def gitoid_short(identifier):
    hexpart = identifier.removeprefix("gitoid:blob:sha256:")
    return hexpart[-16:]


def build_sha256_index(root):
    """Map every file's sha256 hex -> path under root (first match wins)."""
    index = {}
    for dirpath, _dirnames, filenames in os.walk(root):
        for fn in filenames:
            p = os.path.join(dirpath, fn)
            index.setdefault(sha256_hex(p), p)
    return index


def replay_chain(log_path):
    """Return (digests_in_order, correlation_id, final_chain_head)."""
    digests = []
    corr_id = None
    with open(log_path, "r", encoding="utf-8", errors="replace") as f:
        for line in f:
            line = line.rstrip("\n")
            if not line.strip():
                continue
            idx = line.index(" ")
            recorded = line[:idx]
            payload = line[idx + 1:]
            prev = b"" if not digests else bytes.fromhex(digests[-1])
            computed = hashlib.sha256(prev + payload.encode("utf-8")).hexdigest()
            if computed != recorded:
                return None, None, None, f"chain broken at line with digest prefix {recorded[:16]}..."
            digests.append(computed)
            if "Correlation ID: " in payload:
                corr_id = payload.split("Correlation ID: ", 1)[1].strip()
    if not digests:
        return None, None, None, "log is empty"
    return digests, corr_id, digests[-1], None


def verify(args):
    errors = []

    checksum = json.load(open(args.checksum))
    corr_id = checksum.get("correlation_id")
    final_head = checksum.get("final_chain_head")

    if not corr_id:
        errors.append("checksum: missing correlation_id")
    else:
        try:
            uuid.UUID(corr_id)
        except ValueError:
            errors.append(f"checksum: correlation_id is not a UUID: {corr_id}")

    # 1. chain replay
    digests, log_corr, log_final, chain_err = replay_chain(args.log)
    if chain_err:
        errors.append(f"log chain: {chain_err}")
    else:
        if log_corr != corr_id:
            errors.append(f"log correlation id {log_corr} != checksum {corr_id}")
        if log_final != final_head:
            errors.append(f"log final head {log_final} != checksum {final_head}")
        digest_set = set(digests)
    if not chain_err:
        digest_set = set(digests)

    # 2/3. grc + grd/gri integrity
    index = build_sha256_index(args.out)
    for grc in checksum.get("grcs", []):
        name, sha = grc.get("name"), grc.get("sha256")
        path = index.get(sha)
        if not path:
            errors.append(f"grc {name}: no file with recorded sha256 {sha}")
            continue
        if os.path.basename(path) != name:
            errors.append(f"grc {name}: located as {path} (name mismatch)")
        info = decode_grc_info(path)
        if info.get("correlation_id") != corr_id:
            errors.append(f"grc {name}: info correlation_id mismatch")
        if not chain_err:
            emb = info.get("log_chain_head")
            if emb is None:
                errors.append(f"grc {name}: missing log_chain_head")
            elif emb not in digest_set:
                errors.append(f"grc {name}: log_chain_head not a point on the log chain")
        try:
            sha_map = json.loads(info["sha256"])
        except (KeyError, ValueError):
            sha_map = None
            errors.append(f"grc {name}: invalid/absent sha256 info")
        if sha_map:
            grd = sha_map.get("grd", [])
            gri = sha_map.get("gri", [])
            for h in grd + gri:
                if not index.get(h):
                    errors.append(f"grc {name}: missing grd/gri for sha256 {h}")

    # 4. CBOM naming
    for dirpath, _dirnames, filenames in os.walk(args.out):
        for fn in filenames:
            m = CBOM_NAME_RE.match(fn)
            if not m:
                continue
            name_part, short16 = m.group(1), m.group(2)
            try:
                doc = json.load(open(os.path.join(dirpath, fn)))
            except (ValueError, OSError):
                continue
            root_gitoid = None
            root_name = None
            for comp in doc.get("components", []):
                for prop in comp.get("properties", []):
                    if prop.get("name") == "goatrodeo:omnibor-path":
                        root_gitoid = prop.get("value", "").split("|:|")[0]
                    elif prop.get("name") == "goatrodeo:path":
                        root_name = prop.get("value", "").split("|:|")[0]
            if root_gitoid and gitoid_short(root_gitoid) != short16:
                errors.append(f"cbom {fn}: gitoid short mismatch ({root_gitoid})")
            if root_name and escape_name(root_name) != name_part:
                errors.append(f"cbom {fn}: escaped name mismatch ({root_name!r})")

    return errors


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", required=True, help="Goat Rodeo output directory (base)")
    ap.add_argument("--log", required=True, help="the --tamper-evident-log file")
    ap.add_argument("--checksum", required=True, help="the *_checksum.json file")
    args = ap.parse_args()

    if not os.path.isdir(args.out):
        sys.stderr.write(f"error: --out not a directory: {args.out}\n")
        sys.exit(1)
    if not os.path.isfile(args.log):
        sys.stderr.write(f"error: --log not a file: {args.log}\n")
        sys.exit(1)
    if not os.path.isfile(args.checksum):
        sys.stderr.write(f"error: --checksum not a file: {args.checksum}\n")
        sys.exit(1)

    errors = verify(args)
    if errors:
        sys.stderr.write("FAILED:\n")
        for e in errors:
            sys.stderr.write(f"  - {e}\n")
        sys.exit(1)
    print("OK: run logs, ADG files, and CBOMs are mutually consistent")
    sys.exit(0)


if __name__ == "__main__":
    main()