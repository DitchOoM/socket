# Third-party HTTP/3 server (aioquic + lsqpack) for QPACK eviction interop testing.
# See server.py for the rationale; run via run-server.sh.
FROM python:3.12-slim

# aioquic pulls in `cryptography` (used for the self-signed cert) and the lsqpack QPACK codec.
RUN pip install --no-cache-dir "aioquic>=1.0.0"

WORKDIR /app
COPY server.py .

# QUIC is UDP. Publish 4433/udp when running (see run-server.sh).
EXPOSE 4433/udp

ENV HTTP3_HOST=0.0.0.0 \
    HTTP3_PORT=4433 \
    QPACK_MAX_TABLE_CAPACITY=4096 \
    QPACK_BLOCKED_STREAMS=16

CMD ["python", "server.py"]
