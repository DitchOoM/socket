package com.ditchoom.socket.quic.core.observe

/** What a server did with the datagrams that reached it: its loop counts, any thread reads. */
internal class ServerCounts {
    private val routedCount = Counter()
    private val acceptedCount = Counter()
    private val unknown = Counter()
    private val admission = Counter()
    private val backlogFull = Counter()
    private val held = Counter()
    private val token = Counter()
    private val runt = Counter()
    private val replies = Counter()
    private val retries = Counter()
    private val negotiations = Counter()
    private val recvInfos = Counter()

    /** Datagrams handed to a connection. */
    val routed: Long get() = routedCount.value()

    val accepted: Long get() = acceptedCount.value()

    /** Datagrams that named no connection and were not an Initial able to start one. */
    val droppedUnknown: Long get() = unknown.value()

    /** Initials dropped because the bound of handshakes in progress was reached. */
    val droppedAdmission: Long get() = admission.value()

    /** Initials that arrived with the backlog full and were dropped. */
    val droppedBacklog: Long get() = backlogFull.value()

    /** Initials held in the backlog until a pass started their handshake. */
    val backlogged: Long get() = held.value()

    /** Initials dropped for carrying a token that is malformed, forged or expired. */
    val droppedToken: Long get() = token.value()

    /** Initials smaller than 1200 bytes, which RFC 9000 section 14.1 says to discard. */
    val droppedRunt: Long get() = runt.value()

    /** Retry packets and Version Negotiation packets that could not be queued or written. */
    val droppedReplies: Long get() = replies.value()

    val retriesSent: Long get() = retries.value()

    val versionNegotiationsSent: Long get() = negotiations.value()

    /** `recv_info` structures built: one per accepted connection, never per datagram. */
    val recvInfosBuilt: Long get() = recvInfos.value()

    fun routedOne() = routedCount.increment()

    fun acceptedOne() = acceptedCount.increment()

    fun unknownDropped() = unknown.increment()

    fun admissionDropped() = admission.increment()

    fun backlogDropped() = backlogFull.increment()

    fun backlogHeld() = held.increment()

    fun tokenDropped() = token.increment()

    fun runtDropped() = runt.increment()

    fun replyDropped() = replies.increment()

    fun retrySent() = retries.increment()

    fun negotiationSent() = negotiations.increment()

    fun recvInfoBuilt() = recvInfos.increment()
}
