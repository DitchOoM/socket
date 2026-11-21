package com.ditchoom.socket.quic.core.edge

/** iOS keeps its cellular interface up itself, and Linux and macOS have no standby radio to hold. */
internal actual val standbyRadioOfThisPlatform: StandbyRadio = StandbyRadio.Absent
