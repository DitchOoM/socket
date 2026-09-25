#!/usr/bin/env python3
"""Fail if a tracked file names an address that belongs in local config, not in the repo.

The device-walk tooling reads its server from an untracked walk-server.env
(socket-quic-quiche/device-probe/walk-server.env.example); tests and docs use documentation
addresses (192.0.2.0/24, 198.51.100.0/24, 2001:db8::/32). This check keeps it that way.

The forbidden prefixes are listed only as SHA-256 hashes of a normalised form, so this file never
spells them out:

  v4/24:<a>.<b>.<c>          the first three octets of an IPv4 literal (or a bare prefix)
  v6/32:<h1>:<h2>            the first two hextets of an IPv6 literal
  v6/48:<h1>:<h2>:<h3>       the first three

Hextets are lower-case with leading zeros stripped. To forbid another prefix, add the hash of its
normalised form: printf %s 'v4/24:192.0.2' | shasum -a 256

  assert-no-walk-server-address.py [--root <git-dir>] [--hashes <file>]

--hashes replaces the built-in list with one hash per line (the tests use it). A hit prints only the
file, the line and the kind of prefix, never the address itself.
"""
import argparse
import hashlib
import re
import subprocess
import sys

FORBIDDEN = {
    "71e5ca2ddf1c76f48873667f2438c26578c9cca998fbdc6d69598a4c7268ec9e",
    "6b7d48575c41594a20081d88a5717dcd137426be3d6cfbcca7fbb88b43f44ac3",
    "363c6a9fa8a3aa0ac29cbea8de12e55490138d8596a5f2bf13a6082c12a6ac8f",
    "2807cd7b289c1ef1c9ccf17b2afee632dbdc899edbe4d644785acfbe1b283101",
    "2cf825af70996ca7a68118784d3839b7704383e48696973cd5a7cfc7c38355ab",
}

V4 = re.compile(r"(?<![0-9.])(\d{1,3})\.(\d{1,3})\.(\d{1,3})(?![0-9])")
V6 = re.compile(r"(?<![0-9A-Fa-f:])([0-9A-Fa-f]{1,4}):([0-9A-Fa-f]{1,4}):([0-9A-Fa-f]{0,4})")


def hextet(h):
    return h.lower().lstrip("0") or "0"


def keys(line):
    for m in V4.finditer(line):
        yield "v4/24:" + ".".join(str(int(o)) for o in m.groups())
    for m in V6.finditer(line):
        h1, h2, h3 = m.groups()
        yield f"v6/32:{hextet(h1)}:{hextet(h2)}"
        if h3:
            yield f"v6/48:{hextet(h1)}:{hextet(h2)}:{hextet(h3)}"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--hashes")
    args = ap.parse_args()
    forbidden = FORBIDDEN
    if args.hashes:
        with open(args.hashes, encoding="utf-8") as f:
            forbidden = {l.strip() for l in f if l.strip()}

    files = subprocess.run(["git", "-C", args.root, "ls-files", "-z"], check=True,
                           capture_output=True).stdout.split(b"\0")
    hits = 0
    for name in filter(None, files):
        path = f"{args.root}/{name.decode()}"
        try:
            with open(path, "rb") as f:
                data = f.read()
        except (FileNotFoundError, IsADirectoryError):
            continue
        if b"\0" in data[:8192]:
            continue
        for n, line in enumerate(data.decode("latin-1").splitlines(), 1):
            for key in keys(line):
                if hashlib.sha256(key.encode()).hexdigest() in forbidden:
                    print(f"{name.decode()}:{n}: a forbidden {key.split(':')[0]} address prefix")
                    hits += 1
    if hits:
        print(f"FAIL: {hits} forbidden address(es). The walk server belongs in walk-server.env "
              "(untracked); tests and docs use 192.0.2.0/24, 198.51.100.0/24 or 2001:db8::/32.")
        return 1
    print("ok: no forbidden address in tracked files")
    return 0


if __name__ == "__main__":
    sys.exit(main())
