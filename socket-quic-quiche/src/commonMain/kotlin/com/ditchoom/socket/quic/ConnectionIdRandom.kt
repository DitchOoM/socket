package com.ditchoom.socket.quic

import kotlin.random.Random

/**
 * The entropy every connection id and stateless-reset token this endpoint issues is drawn from, unless a
 * test injects its own ([QuicheDriverTuning.random]).
 *
 * One instance is shared by every connection in the process: the server's receive loop draws a SCID for
 * each connection it accepts while every established connection's driver, on another thread, draws the
 * spare ids it announces in NEW_CONNECTION_ID. A source shared that way has to be safe to draw from on
 * several threads at once, and [Random.Default] is not on Kotlin/Native: its generator updates its
 * state without synchronisation, so two threads can read the same state and return the same bytes. Two
 * connections then own one id, the server routes it to whichever claimed it last, and the other
 * connection's datagrams go to the wrong driver for the rest of its life (a handshake that never
 * completes). See [ConnectionIdRandomTests].
 */
internal expect val connectionIdRandom: Random
