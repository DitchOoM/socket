# Independent QPACK (ls-qpack via aioquic's pylsqpack) oracle for differential fuzzing.
# See qpack-diff-server.py for the rationale; run via run-qpack-diff.sh.
FROM python:3.12-slim

# pylsqpack is the binding to ls-qpack — a QPACK implementation independent of ours.
RUN pip install --no-cache-dir "pylsqpack>=0.3.18"

WORKDIR /app
COPY qpack-diff-server.py .

# Plain TCP/HTTP line protocol (not QUIC) — publish 4434/tcp when running (see run-qpack-diff.sh).
EXPOSE 4434/tcp

ENV QPACK_DIFF_HOST=0.0.0.0 \
    QPACK_DIFF_PORT=4434

CMD ["python", "qpack-diff-server.py"]
