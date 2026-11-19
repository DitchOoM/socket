/*
 * qnet on Darwin: sendmsg_x / recvmsg_x, the kernel's batched calls. They are exported by libsystem_kernel but not
 * declared in the public SDK, so they are resolved at run time (dlsym) and never linked: a system without them
 * reports no QN_CAP_MULTI, and a consumer that may not use private calls (an app store build) asks for one datagram
 * per call instead. Included by qnet_udp.c on Darwin only.
 */
#if QN_PUBLIC_SDK_ONLY

static int qn_msg_x_available(void) { return 0; }

/* Never called: qn_msg_x_available() answers no, so every send and receive goes one datagram per call. */
static int64_t qn_send_x(int fd, int family, uint8_t *base, int32_t stride, uint8_t *records, int32_t count) {
    return qn_send_one(fd, family, base, stride, records, count);
}

static int64_t qn_recv_x(int fd, uint8_t *recs, int32_t count, int32_t size) {
    (void)fd;
    (void)recs;
    (void)count;
    (void)size;
    return 0;
}

#else

#include <dlfcn.h>
#include <pthread.h>

struct qn_msghdr_x {
    void *msg_name;
    socklen_t msg_namelen;
    struct iovec *msg_iov;
    int msg_iovlen;
    void *msg_control;
    socklen_t msg_controllen;
    int msg_flags;
    size_t msg_datalen;
};

typedef ssize_t (*qn_msg_x_fn)(int, const struct qn_msghdr_x *, unsigned int, int);

static qn_msg_x_fn qn_sendmsg_x;
static qn_msg_x_fn qn_recvmsg_x;
static pthread_once_t qn_msg_x_once = PTHREAD_ONCE_INIT;

static void qn_msg_x_resolve(void) {
    qn_sendmsg_x = (qn_msg_x_fn)dlsym(RTLD_DEFAULT, "sendmsg_x");
    qn_recvmsg_x = (qn_msg_x_fn)dlsym(RTLD_DEFAULT, "recvmsg_x");
}

static int qn_msg_x_available(void) {
    pthread_once(&qn_msg_x_once, qn_msg_x_resolve);
    return qn_sendmsg_x != NULL && qn_recvmsg_x != NULL;
}

/* sendmsg_x on a connected socket: every record targets the connected peer (sendmsg_x takes no destination).
 * A batch naming destinations goes one datagram per call. */
static int64_t qn_send_x(int fd, int family, uint8_t *base, int32_t stride, uint8_t *records, int32_t count) {
    for (int32_t i = 0; i < count; i++) {
        int32_t target_family;
        memcpy(&target_family, records + (size_t)i * QN_SEND_RECORD + 8, 4);
        if (target_family != 0) return qn_send_one(fd, family, base, stride, records, count);
    }
    struct qn_msghdr_x msgs[QN_MAX_BATCH];
    struct iovec iov[QN_MAX_BATCH];
    int32_t taken = 0;
    while (taken < count) {
        int n = count - taken > QN_MAX_BATCH ? QN_MAX_BATCH : count - taken;
        memset(msgs, 0, sizeof(struct qn_msghdr_x) * (size_t)n);
        for (int i = 0; i < n; i++) {
            int32_t length;
            memcpy(&length, records + (size_t)(taken + i) * QN_SEND_RECORD, 4);
            iov[i].iov_base = base + (size_t)(taken + i) * stride;
            iov[i].iov_len = (size_t)length;
            msgs[i].msg_iov = &iov[i];
            msgs[i].msg_iovlen = 1;
        }
        ssize_t r;
        do {
            r = qn_sendmsg_x(fd, msgs, (unsigned)n, 0);
        } while (r < 0 && errno == EINTR);
        if (r < 0) return qn_result(taken, qn_code(errno), 0);
        if (r == 0) return qn_result(taken, QN_EAGAIN, 0);
        taken += (int32_t)r;
    }
    return qn_result(taken, 0, 0);
}

static int64_t qn_recv_x(int fd, uint8_t *recs, int32_t count, int32_t size) {
    struct qn_msghdr_x msgs[QN_MAX_BATCH];
    struct iovec iov[QN_MAX_BATCH];
    struct sockaddr_storage from[QN_MAX_BATCH];
    union {
        char buf[QN_PKTINFO_SPACE];
        struct cmsghdr align;
    } control[QN_MAX_BATCH];
    memset(msgs, 0, sizeof(struct qn_msghdr_x) * (size_t)count);
    for (int32_t i = 0; i < count; i++) {
        int64_t address;
        memcpy(&address, recs + (size_t)i * QN_RECV_RECORD, 8);
        iov[i].iov_base = qn_at(address);
        iov[i].iov_len = (size_t)size;
        msgs[i].msg_iov = &iov[i];
        msgs[i].msg_iovlen = 1;
        msgs[i].msg_name = &from[i];
        msgs[i].msg_namelen = sizeof from[i];
        msgs[i].msg_control = control[i].buf;
        msgs[i].msg_controllen = sizeof control[i].buf;
    }
    ssize_t r;
    do {
        r = qn_recvmsg_x(fd, msgs, (unsigned)count, MSG_DONTWAIT);
    } while (r < 0 && errno == EINTR);
    if (r < 0) return errno == EAGAIN || errno == EWOULDBLOCK ? 0 : qn_result(0, qn_code(errno), 0);
    for (ssize_t i = 0; i < r; i++) {
        uint8_t *rec = recs + (size_t)i * QN_RECV_RECORD;
        int32_t length = (int32_t)msgs[i].msg_datalen;
        memcpy(rec + 8, &length, 4);
        qn_from_sockaddr(&from[i], rec + 16);
        struct msghdr h;
        memset(&h, 0, sizeof h);
        h.msg_control = msgs[i].msg_control;
        h.msg_controllen = msgs[i].msg_controllen;
        qn_dest_of(&h, rec + QN_RECV_DEST);
    }
    return qn_result((int32_t)r, 0, 0);
}

#endif /* QN_PUBLIC_SDK_ONLY */
