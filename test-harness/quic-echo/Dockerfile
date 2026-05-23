# L7 — QUIC echo server. Wraps the JVM `QuicEchoTestServer` from
# socket-quic/src/jvmTest/. Listens on UDP 14433, echoes data back on
# each stream. Replaces the public `cloudflare-quic.com` host for
# QuicIntegrationTests.
#
# Build artefacts are produced by `:socket-quic:quicEchoJar`:
#   - ./quic-echo.jar           — fat jar (test classpath + jvmMain + natives)
#   - ./testcerts/cert.crt|.key — self-signed cert (CN=quic.tech) shipped
#                                  in-repo at socket-quic/testcerts/
#
# JDK 21 matches the repo's toolchain (CLAUDE.md → JDK 21 toolchain).
# `-noble` (Ubuntu 24.04, glibc 2.39) is required:
#   - alpine/musl: JNI shim links against ld-linux-x86-64.so.2 → fails at load.
#   - jammy (Ubuntu 22.04, glibc 2.35): JNI shim is built against the host
#     glibc (2.39 on the standard Ubuntu 24.04 CI runner) so jammy throws
#     `GLIBC_2.39 not found`. Noble matches.
#
# The Multi-Release jar ships JDK-21 FFM bindings under
# META-INF/versions/21/. Native libs sit under META-INF/native/linux-x64/
# (or linux-arm64/ on aarch64 builders) and are extracted to /tmp by
# NativeLibLoader on first load.
FROM eclipse-temurin:21-jre-noble

WORKDIR /app

COPY quic-echo.jar /app/quic-echo.jar
COPY testcerts/cert.crt /app/cert.crt
COPY testcerts/cert.key /app/cert.key

# QUIC is UDP. EXPOSE is documentation only — the actual host-port mapping
# lives in docker-compose.yml.
EXPOSE 14433/udp

# `--enable-native-access=ALL-UNNAMED` + `--add-opens java.base/java.nio`
# mirror the jvm-test JVM args (see `startQuicTestServer` in
# socket-quic/build.gradle.kts) — needed by the JDK-21 FFM bindings and
# the off-heap buffer pinning the buffer dependency does.
CMD ["java", \
     "--enable-native-access=ALL-UNNAMED", \
     "--add-opens", "java.base/java.nio=ALL-UNNAMED", \
     "-jar", "/app/quic-echo.jar", \
     "/app/cert.crt", "/app/cert.key", \
     "14433"]
