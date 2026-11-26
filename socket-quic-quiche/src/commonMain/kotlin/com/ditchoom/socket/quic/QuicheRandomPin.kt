package com.ditchoom.socket.quic

/**
 * The caller-entropy FFI (`patchQuicheForCallerEntropy`): pin the calling thread's seeded random stream in
 * libquiche, and release it. Implemented by each real backend's binding; reached only through
 * `QuicheEntropy.Seeded`.
 */
internal interface QuicheRandomPin {
    fun setThreadRandomState(state: Long)

    fun clearThreadRandomState()
}
