# L8 — W6 harness controller (RFC_DETERMINISTIC_SIMULATION §7). Serves the
# scenario manifest (`GET /describe`) + `GET /health` over hand-rolled
# HTTP/1.1 on the library's own ServerSocket, so consumer test code
# (`withNetworkHarness` in :socket-testsuite) discovers endpoints
# programmatically instead of reading harness.env. CORS-permissive for
# future browser targets.
#
# Build artefact is produced by `:socket-testsuite:controllerJar` (a
# dependsOn of the root `harnessUp` task):
#   - ./harness-controller.jar — fat jar (socket-testsuite jvmMain + deps)
#
# eclipse-temurin:21-jre-noble matches the repo's JDK-21 toolchain and the
# quic-echo image's base (see that Dockerfile for the noble/glibc rationale;
# the controller itself loads no natives, but sharing the base keeps one
# image layer cached for both services).
FROM eclipse-temurin:21-jre-noble

WORKDIR /app

COPY harness-controller.jar /app/harness-controller.jar

# EXPOSE is documentation only — the host-port mapping lives in
# docker-compose.yml, and the port itself comes from CONTROLLER_PORT
# (harness.env via compose `env_file`).
EXPOSE 14100

# `--enable-native-access=ALL-UNNAMED` mirrors the quic-echo image: the
# bundled buffer/socket deps probe the JDK-21 FFM API at class-init even
# though the controller itself never loads natives — this silences the
# restricted-method warning.
CMD ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "/app/harness-controller.jar"]
