package com.ditchoom.socket.quic

import com.ditchoom.socket.udp.SockAddrLayout
import com.ditchoom.socket.udp.hostOsSockAddrLayout

internal actual val hostSockAddrLayout: SockAddrLayout = hostOsSockAddrLayout()
