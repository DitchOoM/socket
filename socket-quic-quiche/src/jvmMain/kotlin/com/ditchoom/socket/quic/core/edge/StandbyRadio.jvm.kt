package com.ditchoom.socket.quic.core.edge

/** A desktop JVM has no standby radio. */
internal actual val standbyRadioOfThisPlatform: StandbyRadio = StandbyRadio.Absent
