package com.ditchoom.socket.quic

/** Apple K/Native member of [TrafficSecretsLogTestSuite]: the cinterop binding of `quiche_conn_set_keylog_path`. */
class AppleTrafficSecretsLogTests : TrafficSecretsLogTestSuite() {
    override fun testTlsConfig() = AppleTestCerts.tlsConfig

    override fun newDirectory(): String = NativeTestFiles.newDirectory("traffic-secrets")

    override fun filesIn(dir: String): Map<String, String> = NativeTestFiles.filesIn(dir)
}
