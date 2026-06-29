# Builds the h3spec HTTP/3 conformance client (github.com/kazu-yamamoto/h3spec) from source.
#
# h3spec is a Haskell tool: it opens a QUIC/HTTP3 connection to a target server, sends a fixed battery
# of malformed / edge-case HTTP/3 (bad SETTINGS, frames on the wrong stream, reserved-HTTP2 frames,
# missing-SETTINGS, …) and checks the server closes the connection with the RFC 9114 §8.1 error code
# the spec mandates. It is the external counterpart to our deterministic Http3ServerConnectionTests.
#
# Two-stage: build the static-ish binary on the Haskell image, then ship it on a slim runtime. The
# Haskell build pulls h3spec's deps (the `quic`/`http3` Haskell libs, also kazu-yamamoto's) from
# Hackage and is SLOW (~10+ min cold) — acceptable for a dormant, opt-in conformance lane.
FROM haskell:9.6 AS build
RUN git clone --depth 1 https://github.com/kazu-yamamoto/h3spec.git /src
WORKDIR /src
RUN cabal update \
 && cabal build exe:h3spec \
 && cabal install exe:h3spec --install-method=copy --installdir=/out --overwrite-policy=always

FROM debian:stable-slim
RUN apt-get update \
 && apt-get install -y --no-install-recommends libgmp10 zlib1g ca-certificates \
 && rm -rf /var/lib/apt/lists/*
COPY --from=build /out/h3spec /usr/local/bin/h3spec
# h3spec takes the target host + port as positional args; the runner appends `127.0.0.1 <port>`.
ENTRYPOINT ["h3spec"]
