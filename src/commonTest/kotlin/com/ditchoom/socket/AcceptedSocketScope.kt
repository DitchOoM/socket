package com.ditchoom.socket

import kotlinx.coroutines.flow.Flow

/** Collects accepted sockets, running [handle] on each and then closing it — the shape every server handler here has. */
internal suspend fun Flow<ClientSocket>.serveEach(handle: suspend (ClientSocket) -> Unit) =
    collect { client ->
        handle(client)
        client.close()
    }
