#!/usr/bin/env python3
"""
Independent QPACK (RFC 9204) reference oracle for DIFFERENTIAL fuzzing of our hand-rolled codec.

Why this exists: the in-process round-trip fuzzer (Http3RoundTripFuzzTests) runs our QPACK encoder
through OUR OWN decoder, so a bug symmetric across both halves hides. aioquic's `pylsqpack` wraps
ls-qpack — a completely independent C implementation — so `QpackDifferentialInteropTests` can cross-check
BOTH directions that the loopback cannot isolate:

  - ours-encode -> ref-decode: our encoder's field section + encoder-stream inserts must decode in
    ls-qpack back to the exact header list. (The existing Http3DockerInteropTests proves this only over a
    full H3/QUIC connection; here it's raw QPACK, per-section, fuzzed.)
  - ref-encode -> ours-decode: ls-qpack's field section + encoder-stream must decode in OUR decoder back
    to the exact header list. (This direction has no other coverage at all.)

Wire protocol (deliberately hex+decimal, no JSON — no escaping to get wrong). One request per line-set,
POSTed as the body; `op=` selects the operation. Every encoder/decoder is FRESH per request (stateless,
deterministic — matches the test's fresh-pair-per-section model):

  POST /qpack   op=encode               ->   encoder_stream=<hex>\nframe=<hex>
                capacity=<int>
                blocked=<int>
                stream=<int>
                h=<nameHex>:<valueHex>   (repeated, in order)

  POST /qpack   op=decode               ->   h=<nameHex>:<valueHex>   (repeated, in order)
                capacity=<int>
                blocked=<int>
                stream=<int>
                encoder_stream=<hex>
                frame=<hex>

  GET  /health  -> 200 "ok"             (liveness; the test SKIPS, never fails, if unreachable)

Run directly (a venv with `pip install pylsqpack` is enough — no Docker needed) or via the Dockerfile.
"""
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pylsqpack

HOST = os.environ.get("QPACK_DIFF_HOST", "127.0.0.1")
PORT = int(os.environ.get("QPACK_DIFF_PORT", "4434"))


def parse_request(body: str):
    """Parse the line protocol into (op, capacity, blocked, stream, headers, encoder_stream, frame)."""
    op = None
    capacity = blocked = stream = 0
    headers = []
    encoder_stream = b""
    frame = b""
    for line in body.splitlines():
        line = line.strip()
        if not line:
            continue
        key, _, value = line.partition("=")
        if key == "op":
            op = value
        elif key == "capacity":
            capacity = int(value)
        elif key == "blocked":
            blocked = int(value)
        elif key == "stream":
            stream = int(value)
        elif key == "encoder_stream":
            encoder_stream = bytes.fromhex(value)
        elif key == "frame":
            frame = bytes.fromhex(value)
        elif key == "h":
            name_hex, _, value_hex = value.partition(":")
            headers.append((bytes.fromhex(name_hex), bytes.fromhex(value_hex)))
    return op, capacity, blocked, stream, headers, encoder_stream, frame


def do_encode(capacity, blocked, stream, headers):
    encoder = pylsqpack.Encoder()
    # apply_settings emits the Set Dynamic Table Capacity instruction on the encoder stream (empty for 0).
    prefix = encoder.apply_settings(capacity, blocked)
    enc_stream, frame = encoder.encode(stream, headers)
    return (prefix + enc_stream), frame


def do_decode(capacity, blocked, stream, encoder_stream, frame):
    decoder = pylsqpack.Decoder(capacity, blocked)
    if encoder_stream:
        decoder.feed_encoder(encoder_stream)  # apply the inserts before resolving the section
    _decoder_stream, headers = decoder.feed_header(stream, frame)
    return headers


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass  # quiet

    def _send(self, code, body: str):
        payload = body.encode("ascii")
        self.send_response(code)
        self.send_header("Content-Type", "text/plain")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self):
        if self.path == "/health":
            self._send(200, "ok")
        else:
            self._send(404, "not found")

    def do_POST(self):
        if self.path != "/qpack":
            self._send(404, "not found")
            return
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length).decode("ascii")
        try:
            op, capacity, blocked, stream, headers, encoder_stream, frame = parse_request(body)
            if op == "encode":
                enc_stream, frame_out = do_encode(capacity, blocked, stream, headers)
                self._send(200, f"encoder_stream={enc_stream.hex()}\nframe={frame_out.hex()}\n")
            elif op == "decode":
                out = do_decode(capacity, blocked, stream, encoder_stream, frame)
                self._send(200, "".join(f"h={n.hex()}:{v.hex()}\n" for n, v in out))
            else:
                self._send(400, f"unknown op: {op}")
        except Exception as exc:  # a ref-side QPACK error is a meaningful differential signal
            self._send(422, f"error={type(exc).__name__}: {exc}")


def main():
    server = ThreadingHTTPServer((HOST, PORT), Handler)
    print(f"qpack-diff oracle (pylsqpack/ls-qpack {pylsqpack.__version__}) on http://{HOST}:{PORT}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
