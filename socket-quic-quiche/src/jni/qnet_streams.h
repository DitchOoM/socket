/*
 * One crossing per connection turn for the streams quiche reports: the readable and the writable ones, written as
 * records into memory the caller owns, so the caller walks them without calling back into native code.
 *
 * Integers only, like qnet: the connection and the block are addresses, the result a count. Every value in the block
 * is in the platform's native byte order. Static inline, so each binding compiles it where it already compiles quiche's
 * header: the JNI shim (quiche_jni.c, which also exports qn_conn_streams for FFM) and the Kotlin/Native Quiche cinterop.
 */
#ifndef QNET_STREAMS_H
#define QNET_STREAMS_H

#include <quiche.h>
#include <stdint.h>
#include <string.h>

/* The block: a header, then the stream ids, readable ones first and the writable ones after them. */
#define QN_STREAMS_READABLE 0  /* int32: readable ids written, or QN_STREAMS_SPILLED when more were readable than fit */
#define QN_STREAMS_WRITABLE 4  /* int32: writable ids written (taken from quiche's writable set) */
#define QN_STREAMS_FULL 8      /* int32: 1 when the ids filled the block, so quiche may still hold writable streams */
#define QN_STREAMS_IDS 16      /* int64 per id */
#define QN_STREAMS_ID_BYTES 8

/* Nothing of the readable list is written: the caller walks quiche's readable iterator itself. */
#define QN_STREAMS_SPILLED (-1)

/* The layout above folded as h = h * 31 + value over READABLE, WRITABLE, FULL, IDS, ID_BYTES, SPILLED. */
#define QN_STREAMS_FOLD(h, v) ((h) * 31u + (uint32_t)(v))
#define QN_STREAMS_LAYOUT                                                                                               \
    ((int32_t)QN_STREAMS_FOLD(                                                                                          \
        QN_STREAMS_FOLD(QN_STREAMS_FOLD(QN_STREAMS_FOLD(QN_STREAMS_FOLD(QN_STREAMS_FOLD(0u, QN_STREAMS_READABLE),     \
                                                                        QN_STREAMS_WRITABLE),                           \
                                                        QN_STREAMS_FULL),                                               \
                                        QN_STREAMS_IDS),                                                                \
                        QN_STREAMS_ID_BYTES),                                                                           \
        QN_STREAMS_SPILLED))

/* QN_STREAMS_LAYOUT as this library was built, for the caller to compare with its own copy. */
static inline int32_t qn_streams_layout(void) { return QN_STREAMS_LAYOUT; }

static inline void qn_streams_put32(uint8_t *block, int32_t offset, int32_t value) {
    memcpy(block + offset, &value, sizeof value);
}

static inline void qn_streams_put_id(uint8_t *block, int32_t index, int64_t id) {
    memcpy(block + QN_STREAMS_IDS + (int64_t)index * QN_STREAMS_ID_BYTES, &id, sizeof id);
}

/*
 * Writes the connection's readable streams, then takes its writable ones, into the block at `block`, which holds
 * `capacity` ids after its header. The readable list is quiche's readable iterator, whole or not at all: when it does
 * not fit, the header says QN_STREAMS_SPILLED and the ids hold writable streams only. Writable streams are taken from
 * quiche until it has none or the block is full. Returns the ids written.
 */
static inline int32_t qn_streams_take(int64_t conn_address, int64_t block_address, int32_t capacity) {
    quiche_conn *conn = (quiche_conn *)(uintptr_t)conn_address;
    uint8_t *block = (uint8_t *)(uintptr_t)block_address;
    int32_t readable = 0;
    quiche_stream_iter *iter = quiche_conn_readable(conn);
    if (iter != NULL) {
        uint64_t id;
        while (quiche_stream_iter_next(iter, &id)) {
            if (readable == capacity) {
                readable = QN_STREAMS_SPILLED;
                break;
            }
            qn_streams_put_id(block, readable++, (int64_t)id);
        }
        quiche_stream_iter_free(iter);
    }
    int32_t written = readable == QN_STREAMS_SPILLED ? 0 : readable;
    int32_t writable = 0;
    while (written < capacity) {
        int64_t id = quiche_conn_stream_writable_next(conn);
        if (id < 0) break;
        qn_streams_put_id(block, written++, id);
        writable++;
    }
    qn_streams_put32(block, QN_STREAMS_READABLE, readable);
    qn_streams_put32(block, QN_STREAMS_WRITABLE, writable);
    qn_streams_put32(block, QN_STREAMS_FULL, written == capacity ? 1 : 0);
    return written;
}

#endif
