package com.ditchoom.socket

internal actual fun platformHostResolver(): HostResolver = PosixHostResolver
