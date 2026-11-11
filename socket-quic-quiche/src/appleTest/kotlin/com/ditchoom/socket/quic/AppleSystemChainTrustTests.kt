@file:OptIn(ExperimentalEncodingApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.deterministic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The Security.framework evaluation answers on a dispatch queue after the caller may be gone. Cancelling
 * it at any instant must neither hang, crash nor resume twice, and one left to finish must still answer.
 * The chain is the test's self-signed certificate, which the system store refuses, so a verdict of
 * [SystemTrustVerdict.Untrusted] is the expected answer wherever the evaluation completes.
 */
class AppleSystemChainTrustTests {
    private fun chain(): List<ReadBuffer> {
        val pem = AppleTestCerts.readText(AppleTestCerts.requireGenerated("cert.crt"))
        return pem
            .split("-----BEGIN CERTIFICATE-----")
            .drop(1)
            .map { block ->
                val der = Base64.Mime.decode(block.substringBefore("-----END CERTIFICATE-----").filterNot { it.isWhitespace() })
                BufferFactory.deterministic().allocate(der.size).apply {
                    der.forEach { writeByte(it) }
                    resetForRead()
                }
            }
    }

    private suspend fun evaluate() = (appleSystemChainTrust as PlatformChainTrust.AfterHandshake).evaluate("localhost", chain())

    @Test
    fun anEvaluationLeftToFinishAnswersUntrusted() =
        runTest(timeout = 60.seconds) {
            AppleTestCerts.skippingWhenSimulatorLacksFixtures {
                withContext(Dispatchers.Default) {
                    val verdict = withTimeoutOrNull(30.seconds) { evaluate() }
                    assertIs<SystemTrustVerdict.Untrusted>(verdict, "a self-signed chain is refused by the system store")
                }
            }
        }

    @Test
    fun anEvaluationCancelledAtAnyInstantReturnsAndLeavesTheNextOneWorking() =
        runTest(timeout = 120.seconds) {
            AppleTestCerts.skippingWhenSimulatorLacksFixtures {
                withContext(Dispatchers.Default) {
                    val random = Random(11)
                    var hung = 0
                    repeat(60) {
                        val scope = CoroutineScope(Dispatchers.Default + Job())
                        val work = scope.async { evaluate() }
                        repeat(random.nextInt(0, 20)) { yield() }
                        if (random.nextBoolean()) delay(random.nextLong(0, 3))
                        work.cancel()
                        if (withTimeoutOrNull(5.seconds) { work.join() } == null) hung++
                    }
                    assertEquals(0, hung, "cancelled evaluations that did not return within 5 s, of 60")
                    // Callbacks from the abandoned evaluations arrive meanwhile; a fresh one is unaffected.
                    assertTrue(withTimeoutOrNull(30.seconds) { evaluate() } is SystemTrustVerdict.Untrusted)
                }
            }
        }
}
