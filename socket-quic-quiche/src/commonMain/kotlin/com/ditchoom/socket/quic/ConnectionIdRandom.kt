package com.ditchoom.socket.quic

import kotlin.random.Random

/**
 * The entropy every connection id and stateless-reset token this endpoint issues is drawn from, unless a
 * test injects its own ([QuicheDriverTuning.random]).
 *
 * **Secure.** A stateless-reset token is a credential: whoever can produce it can end the connection
 * (RFC 9000 §10.3), and connection ids must not let one connection be linked to another (§5.1). Both are
 * therefore drawn from the platform's cryptographically secure source, never from a generator whose
 * output predicts its next: `SecureRandom` (the JDK's DRBG, or Conscrypt on Android) on the JVM and
 * Android, the operating system's random source on Kotlin/Native (`/dev/urandom` on Linux,
 * `arc4random_buf` on Apple).
 *
 * **Thread-safe, with no lock of ours.** One instance is shared by every connection in the process: the
 * server's receive loop draws the SCID of each connection it accepts while every established
 * connection's driver, on another thread, draws the spare ids it announces. [Random.Default] is not safe
 * for that on Kotlin/Native: its generator updates its state without synchronisation, so two threads can
 * return the same bytes. Two connections then own one id, the server routes it to whichever claimed it
 * last, and the other connection's datagrams go to a driver that cannot read them, so its handshake never
 * completes ([ConnectionIdRandomTests]). Each source here is safe to call from any thread: `SecureRandom`
 * synchronises itself, and every native draw is its own system call.
 *
 * A source that cannot produce bytes throws [EntropyUnavailableException]; nothing falls back to a weaker
 * one.
 */
internal expect val connectionIdRandom: Random

/** Why the operating system's random source could not be read. */
internal enum class EntropyFailure {
    /** The source could not be opened. */
    Unopenable,

    /** A read failed. */
    ReadFailed,

    /** A read returned end of file or fewer bytes than asked for with no error. */
    ReadTruncated,
}

/** The operating system's random source failed with [failure], reporting [errno] (0 when none was set). */
internal class EntropyUnavailableException(
    val failure: EntropyFailure,
    val errno: Int,
) : IllegalStateException("operating system entropy unavailable: $failure (errno $errno)")
