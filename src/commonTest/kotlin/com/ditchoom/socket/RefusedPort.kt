package com.ditchoom.socket

/**
 * A port on `127.0.0.1` that nothing listens on and that no test's listener can ever be handed, for
 * tests that need a connect to be refused.
 *
 * These tests used to draw a random port in 59000–59999. That is inside every ephemeral range the
 * test hosts use (IANA and Darwin 49152–65535, Linux 32768–60999), so any listener on the host could
 * hold it: a server in a suite running alongside, the iOS simulator included, since a simulator
 * shares the host's network stack. When it did, the "refused" connect succeeded and then closed at
 * once, so that server accepted a stranger and read end-of-stream within milliseconds. That is the
 * failure `DataIntegrityTests.nullBytesInData` once showed on the iOS simulator under four concurrent
 * suites (see [AcceptLog]).
 *
 * Port 1 (`tcpmux`) is below 1024, so no OS hands it out as an ephemeral port, and every test listener
 * binds an ephemeral port. Nothing on a test host listens there.
 */
internal const val REFUSED_PORT = 1

/** Every OS's ephemeral range starts at or above this; ports below it are never handed to a `bind(0)`. */
internal const val FIRST_NON_RESERVED_PORT = 1024
