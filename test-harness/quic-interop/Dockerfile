# This library's QUIC client and server as a quic-interop-runner endpoint
# (https://github.com/quic-interop/quic-interop-runner). Built and run by
# .github/workflows/quic-interop.yaml; see QuicInteropEndpoint.kt for the contract.
#
# Build context = this directory, after `:socket-quic-quiche:quicInteropJar` has written
# ./quic-interop.jar (the echo fat jar repackaged with QuicInteropEndpointKt as Main-Class).
#
# The runner requires the network-simulator endpoint image as the base (its /setup.sh routes traffic
# through the simulator). It ships no JDK, so the JRE comes from the same temurin noble image the
# quic-echo harness uses; the JNI shim is built against the CI host's glibc 2.39, which the endpoint
# base (Ubuntu 24.04) matches. If the base ever moves to an older Ubuntu, the jar fails at load with
# "GLIBC_2.39 not found" — run_endpoint.sh prints the glibc version first so that is visible.
FROM eclipse-temurin:21-jre-noble AS jre

FROM martenseemann/quic-network-simulator-endpoint:latest

COPY --from=jre /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="/opt/java/openjdk/bin:${PATH}"

COPY quic-interop.jar /quic-interop.jar
COPY run_endpoint.sh /run_endpoint.sh
RUN chmod +x /run_endpoint.sh

ENTRYPOINT ["/run_endpoint.sh"]
