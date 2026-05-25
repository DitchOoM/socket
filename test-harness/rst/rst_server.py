#!/usr/bin/env python3
"""Deterministic TCP-RST sidecar.

Listens on 0.0.0.0:14998. Each accepted connection:

  1. reads up to one byte (the client's trigger),
  2. sets SO_LINGER=(on=1, linger=0) on the accepted socket,
  3. close()s the socket → kernel sends RST (no FIN).

This shape lets a test park a `read()` on its side, then deterministically
arm the RST by writing a single byte. The wire-level RST is what produces
ECONNRESET → SocketClosedException on the client's read path.

Why not pure socat: socat's default `shut-down` mode issues a
shutdown(SHUT_WR) on EOF *before* close(), which puts FIN on the wire even
when SO_LINGER=0 is set on the listener. The client then sees graceful EOF
(EndOfStream), not the RST we want. setsockopt+close in that exact order is
unambiguous; socat's option matrix is not.
"""
import socket
import struct
import sys
import threading


# struct linger { int l_onoff; int l_linger; }
_SO_LINGER_RST = struct.pack("ii", 1, 0)


def _handle(conn: socket.socket, peer: tuple) -> None:
    try:
        # Best-effort one-byte read. If the client disconnects without
        # sending we still want to clean up, so a short timeout is fine.
        conn.settimeout(30.0)
        try:
            conn.recv(1)
        except (OSError, socket.timeout):
            pass
        # SO_LINGER on=1 linger=0 turns close() into RST.
        conn.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, _SO_LINGER_RST)
        conn.close()
        print(f"rst: closed {peer}", flush=True)
    except Exception as e:  # pragma: no cover — never lose a thread
        print(f"rst: handler error for {peer}: {e}", file=sys.stderr, flush=True)


def main() -> None:
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("0.0.0.0", 14998))
    srv.listen(64)
    print("rst: listening on 0.0.0.0:14998", flush=True)
    while True:
        conn, peer = srv.accept()
        # One thread per connection — handlers are bounded by SO_LINGER's
        # immediate close so this never grows past test concurrency.
        threading.Thread(target=_handle, args=(conn, peer), daemon=True).start()


if __name__ == "__main__":
    main()
