package com.ditchoom.socket.quic.core.udp

import com.ditchoom.buffer.BaseJvmBuffer
import com.ditchoom.buffer.unwrapFully
import java.nio.ByteBuffer

/** The `ByteBuffer` of memory the JDK stack's channel reads and writes; one stack, one view per slot. */
internal class JdkBytes private constructor(
    val view: ByteBuffer,
) {
    companion object {
        /** Whether [bytes] is backed by a JDK buffer, decided once when the stack is built. */
        fun of(bytes: NativeBytes): JdkBacked {
            val inner = bytes.owner.unwrapFully()
            return if (inner is BaseJvmBuffer) JdkBacked.Backed(JdkBytes(inner.byteBuffer)) else JdkBacked.NotJdkBacked
        }
    }
}

internal sealed interface JdkBacked {
    class Backed(
        val bytes: JdkBytes,
    ) : JdkBacked

    data object NotJdkBacked : JdkBacked
}
