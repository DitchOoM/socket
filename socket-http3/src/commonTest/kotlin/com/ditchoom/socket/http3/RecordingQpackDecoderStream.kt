package com.ditchoom.socket.http3

/**
 * Test double for [QpackDecoderStream] that records what would have reached the wire.
 *
 * It overrides only [write] — the accounting (which instruction advances the acknowledged count, and
 * by how much) comes from the real base class. That is the point of the base being abstract over the
 * write rather than taking an emit lambda: a double that reimplemented the delta rule would agree
 * with itself no matter what the production path did.
 *
 * [onWrite] is `suspend` so a test can inject scheduling — `yield()` inside it reproduces the
 * interleaving a real, suspending stream write produces.
 */
internal class RecordingQpackDecoderStream(
    private val onWrite: suspend (QpackDecoderInstruction) -> Unit = {},
) : QpackDecoderStream() {
    val written = mutableListOf<QpackDecoderInstruction>()

    override suspend fun write(instruction: QpackDecoderInstruction): DecoderStreamWrite {
        onWrite(instruction)
        written += instruction
        return DecoderStreamWrite.Sent
    }
}
