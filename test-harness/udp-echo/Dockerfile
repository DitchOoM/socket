# L1 — UDP datagram echo. The datagram analogue of the `echo` sidecar
# (RFC_UNIFIED_NETWORK_TEST_HARNESS §5). One process; per-datagram fork via socat,
# each reply piped through `cat`.
#
# Why UDP-RECVFROM and not UDP-LISTEN: RECVFROM replies from the *listening* port
# (14434), which is what a request/response echo needs — a connected client
# (UdpSocket.connect / socat UDP:) only accepts replies from the port it sent to.
# UDP-LISTEN,fork would reply from an ephemeral child port and the reply would be
# dropped by such a client. `reuseaddr` lets overlapping forks re-bind 14434.
#
# Same rationale as the TCP `echo` image: socat+cat is zero-build-deps, and the same
# binary serves the healthcheck (no extra package).
FROM alpine:3.20

RUN apk add --no-cache socat

EXPOSE 14434/udp

# -T 15 : per-fork inactivity timeout — reaps the child after the datagram is echoed.
CMD ["socat", "-T", "15", "UDP-RECVFROM:14434,fork,reuseaddr", "EXEC:cat"]
