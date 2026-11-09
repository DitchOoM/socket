package com.ditchoom.socket

/**
 * A server socket that can describe what happened to it — port selection, listener states, each
 * arriving connection — for a failure message. Rendering is the only cost; nothing is formatted
 * until [diagnostics] is called.
 */
internal interface ServerSocketDiagnostics {
    fun diagnostics(): String
}
