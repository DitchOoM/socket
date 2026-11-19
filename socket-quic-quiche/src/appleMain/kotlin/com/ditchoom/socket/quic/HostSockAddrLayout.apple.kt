package com.ditchoom.socket.quic

import com.ditchoom.socket.udp.SockAddrLayout
import com.ditchoom.socket.udp.appleSockAddrLayout

internal actual val hostSockAddrLayout: SockAddrLayout = appleSockAddrLayout
