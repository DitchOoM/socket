#!/usr/bin/env bash
# Downloads the wireshark-dev/stable PPA's tshark .debs for Ubuntu 24.04 (noble) into $1, for the
# interop cells to install from a run artifact (quic-interop-gate.yaml).
#
# The rebind cells need the PPA's tshark. Ubuntu's own 4.2 dissector cannot follow a rebound path, so
# every our-server rebind cell fails with "did not contain a PATH_CHALLENGE" (run 44069038229).
#
# Why the files come from the Launchpad API and file server instead of the PPA's apt archive:
# On 2026-11-23, from 13:57 UTC onward, ppa.launchpadcontent.net answered 503 on every IP it served from.
# Each of the gate's ~35 cells did its own add-apt-repository + apt-get update against it, so every cell
# failed before running a single test (run 45187417720). During the same outage, api.launchpad.net and
# launchpadlibrarian.net served the identical .debs. Fetching once per run, here, also ends the burst of
# 35 simultaneous add-apt-repository calls, which Launchpad sometimes answered with 500
# (GPGKeyTemporarilyNotFoundError).
#
# Only the PPA's own packages are fetched. Every other dependency (libc6, libglib2.0, libpcap, ...) comes
# from Ubuntu's archive when the cell runs `apt-get install ./*.deb`.
set -euo pipefail

out=${1:?usage: fetch-ppa-tshark.sh <output dir>}
series=${SERIES:-noble}
arch=${ARCH:-$(dpkg --print-architecture)}
api=https://api.launchpad.net/devel
ppa="$api/~wireshark-dev/+archive/ubuntu/stable"
# tshark and the PPA-built packages it depends on (tshark's and wireshark-common's Depends, 4.6.x).
# The library sonames are matched by a prefix, so a soname bump in a later release (libwireshark19 ->
# libwireshark20) still resolves. A package this list misses makes the install below fail loudly,
# because apt cannot find a version new enough in Ubuntu's archive.
wanted='^(tshark|wireshark-common|libwireshark-data|libwireshark[0-9]+|libwiretap[0-9]+|libwsutil[0-9]+)_'

fetch() { curl -fsSL --max-time 120 --retry 6 --retry-all-errors --retry-delay 15 "$@"; }

sources=$(fetch "$ppa?ws.op=getPublishedSources&source_name=wireshark&exact_match=true&status=Published&distro_series=$api/ubuntu/$series")
n=$(jq '.entries | length' <<<"$sources")
if [ "$n" -ne 1 ]; then
  echo "::error title=PPA tshark::expected one published wireshark source for $series, found $n"
  jq -r '.entries[] | "\(.source_package_version) \(.pocket)"' <<<"$sources"
  exit 1
fi
version=$(jq -r '.entries[0].source_package_version' <<<"$sources")
sourcepub=$(jq -r '.entries[0].self_link' <<<"$sources")
echo "wireshark-dev PPA publishes wireshark $version for $series"

mapfile -t urls < <(fetch "$sourcepub?ws.op=binaryFileUrls" \
  | jq -r --arg arch "$arch" --arg re "$wanted" \
      '.[] | select(endswith("_\($arch).deb") or endswith("_all.deb")) | select(split("/") | last | test($re))')
if [ "${#urls[@]}" -lt 6 ]; then
  echo "::error title=PPA tshark::only ${#urls[@]} of the expected 6 PPA packages found for $version/$arch"
  printf '%s\n' "${urls[@]}"
  exit 1
fi

mkdir -p "$out"
for url in "${urls[@]}"; do
  file="$out/$(basename "$url")"
  fetch -o "$file" "$url"
  got=$(dpkg-deb -f "$file" Version)
  if [ "$got" != "$version" ]; then
    echo "::error title=PPA tshark::$(basename "$url") is version '$got', expected $version"
    exit 1
  fi
  echo "  $(basename "$url") ($(du -k "$file" | cut -f1) KB)"
done
echo "$version" >"$out/VERSION"
