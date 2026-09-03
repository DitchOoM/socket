#!/usr/bin/env bash
# Shared by every script here. Source it.
BUNDLE="com.ditchoom.quicprobe"
DEVICE="${DEVICE:-$(xcrun devicectl list devices 2>/dev/null | awk '/iPhone/ {print $3; exit}')}"
DD="${DD:-/tmp/qp-dd}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
