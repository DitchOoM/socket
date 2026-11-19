package com.ditchoom.socket.quic

import com.ditchoom.socket.udp.SockAddrLayout
import com.ditchoom.socket.udp.linuxSockAddrLayout

internal actual val hostSockAddrLayout: SockAddrLayout = linuxSockAddrLayout
