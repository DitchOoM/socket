# Deterministic TCP-RST sidecar.
#
# Why this exists: toxiproxy 2.12's `reset_peer` toxic sets SO_LINGER=0 then
# close()s on the proxy, but the FIN/RST ordering is kernel-scheduling-
# dependent. On busy CI runners (GH ubuntu-24.04) a parked read sometimes
# observes EOF (graceful) before the RST arrives, which flakes the
# pendingReadDuringPeerReset assertion. See
# src/commonTest/.../harness/ExceptionConformanceTests.kt for the original
# `@Ignore`'d test and TODO.md for the rationale.
#
# This sidecar removes toxiproxy from that path entirely: the test connects
# directly here. socat is configured with `so-linger=0` on the accepted FD,
# and SYSTEM:'head -c 1' reads exactly one byte from the client then exits,
# triggering socat's close() — which with SO_LINGER=0 sends a real TCP RST
# (no FIN). The test parks a read(), then writes one byte to arm the RST.
#
# Why socat and not a Go binary: zero build deps, the image is ~10 MB,
# the same binary is used elsewhere in the harness (echo / netem-blackhole),
# and the behavior is well-understood. A custom binary would only buy us
# control we don't need here.
FROM alpine:3.20

RUN apk add --no-cache python3

EXPOSE 14998

# Why a Python script instead of pure socat: socat's default `shut-down` mode
# does a shutdown(SHUT_WR) on EOF *before* close(), which sends a FIN on the
# wire even when SO_LINGER=0 is set on the listening socket. The client then
# sees graceful EOF (EndOfStream), not the RST we want. Forcing the issue
# through socat is possible (nofork+EXEC with manual FD takeover) but
# fragile — a 12-line Python server lets us call setsockopt(SO_LINGER, on=1,
# linger=0) and close() in that exact order, which is exactly what produces
# RST on every kernel.
COPY rst_server.py /rst_server.py

EXPOSE 14998

# -u : unbuffered stdout (so `docker logs` shows accept/close events live)
CMD ["python3", "-u", "/rst_server.py"]
