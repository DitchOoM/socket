# L4 — udp-toxi relay. The UDP/QUIC analogue of toxiproxy (which is TCP-only),
# RFC_UNIFIED_NETWORK_TEST_HARNESS §5. JVM/Kotlin sidecar wrapping :socket-testsuite's
# jvmMain `UdpToxiServer`: an HTTP/REST control plane over TCP provisions a
# FaultSchedule per named relay, and a per-datagram UDP data plane impairs+forwards to
# an upstream (e.g. udp-echo). It drives the SAME testkit ImpairmentEngine the
# in-process Tier-A pipe does, which is the A⇄C parity guarantee.
#
# Build artefact is produced by `:socket-testsuite:udpToxiJar` (a dependsOn of the root
# `harnessUp` task):
#   - ./udp-toxi.jar — fat jar (socket-testsuite jvmMain + deps, incl. :socket-udp)
#
# eclipse-temurin:21-jre-noble matches the repo's JDK-21 toolchain and the controller /
# quic-echo images' base. The relay loads no natives (pure NIO UDP + ServerSocket), so
# the one jar is arch-neutral and runs under both halves of the multi-arch base image.
FROM eclipse-temurin:21-jre-noble

WORKDIR /app

COPY udp-toxi.jar /app/udp-toxi.jar

# EXPOSE is documentation only — host-port mappings live in docker-compose.yml.
EXPOSE 8475/tcp
EXPOSE 14435/udp

# `--enable-native-access=ALL-UNNAMED` mirrors the controller image: the bundled
# buffer/socket deps probe the JDK-21 FFM API at class-init even though the relay never
# loads natives, which silences the restricted-method warning.
CMD ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "/app/udp-toxi.jar"]
