package com.ditchoom.socket.quic

import com.ditchoom.socket.udp.DatagramSendError

internal actual fun withheldAccess(error: DatagramSendError): WithheldAccess = WithheldAccess.None
