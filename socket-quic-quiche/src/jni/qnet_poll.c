/* qnet poller: kqueue (Darwin) or epoll (Linux, Android). Included by qnet.c. */

typedef struct {
    int pfd;
    int wakefd;      /* eventfd on Linux; unused on Darwin (EVFILT_USER) */
    int capacity;
    int count;
    int pwait2;      /* Linux: epoll_pwait2 (nanosecond timeout) works here */
    int32_t *ready;  /* token << 2 | bits, merged per token */
#if QN_KQUEUE
    struct kevent *events;
#else
    struct epoll_event *events;
#endif
} qn_poller;

#define QN_WAKE_IDENT 0x51

static void qn_ready_merge(qn_poller *p, int32_t token, int32_t bits) {
    for (int i = 0; i < p->count; i++) {
        if ((p->ready[i] >> 2) == token) {
            p->ready[i] |= bits;
            return;
        }
    }
    if (p->count < p->capacity) p->ready[p->count++] = (token << 2) | bits;
}

QN_EXPORT int64_t qn_poller_open(int32_t capacity) {
    if (capacity <= 0) return -QN_EINVAL;
    qn_poller *p = calloc(1, sizeof(qn_poller));
    if (p == NULL) return -QN_ENOMEM;
    p->pfd = -1;
    p->wakefd = -1;
    p->capacity = capacity;
    p->ready = calloc((size_t)capacity, sizeof(int32_t));
    p->events = calloc((size_t)capacity + 1, sizeof(*p->events));
    if (p->ready == NULL || p->events == NULL) {
        free(p->ready);
        free(p->events);
        free(p);
        return -QN_ENOMEM;
    }
#if QN_KQUEUE
    p->pfd = kqueue();
    if (p->pfd < 0) goto failed;
    fcntl(p->pfd, F_SETFD, FD_CLOEXEC);
    struct kevent wake;
    EV_SET(&wake, QN_WAKE_IDENT, EVFILT_USER, EV_ADD | EV_CLEAR, 0, 0, NULL);
    if (kevent(p->pfd, &wake, 1, NULL, 0, NULL) < 0) goto failed;
    p->wakefd = -1;
#else
    p->pfd = epoll_create1(EPOLL_CLOEXEC);
    if (p->pfd < 0) goto failed;
    p->wakefd = eventfd(0, EFD_NONBLOCK | EFD_CLOEXEC);
    if (p->wakefd < 0) goto failed;
    struct epoll_event wake = {0};
    wake.events = EPOLLIN;
    wake.data.u64 = UINT64_MAX;
    if (epoll_ctl(p->pfd, EPOLL_CTL_ADD, p->wakefd, &wake) < 0) goto failed;
#if defined(__ANDROID__) || !defined(SYS_epoll_pwait2)
    p->pwait2 = 0; /* Android's app seccomp filter may kill on an unlisted call: never try it there */
#else
    {
        struct timespec zero = {0, 0};
        long r = syscall(SYS_epoll_pwait2, p->pfd, p->events, 1, &zero, NULL, 0);
        p->pwait2 = r >= 0;
        if (r > 0) { /* nothing is registered but the wake: drain what we took */
            uint64_t v;
            if (read(p->wakefd, &v, sizeof v) < 0) { }
        }
    }
#endif
#endif
    return (int64_t)(uintptr_t)p;
failed: {
    int e = errno;
    if (p->pfd >= 0) close(p->pfd);
#if QN_EPOLL
    if (p->wakefd >= 0) close(p->wakefd);
#endif
    free(p->ready);
    free(p->events);
    free(p);
    return -qn_code(e);
}
}

static inline qn_poller *qn_poller_of(int64_t poller) { return (qn_poller *)(uintptr_t)poller; }

QN_EXPORT int32_t qn_poller_add(int64_t poller, int32_t fd, int32_t token) {
    qn_poller *p = qn_poller_of(poller);
#if QN_KQUEUE
    struct kevent ev;
    EV_SET(&ev, fd, EVFILT_READ, EV_ADD, 0, 0, (void *)(intptr_t)token);
    if (kevent(p->pfd, &ev, 1, NULL, 0, NULL) < 0) return -qn_code(errno);
#else
    struct epoll_event ev = {0};
    ev.events = EPOLLIN;
    ev.data.u64 = (uint64_t)(uint32_t)token;
    if (epoll_ctl(p->pfd, EPOLL_CTL_ADD, fd, &ev) < 0) return -qn_code(errno);
#endif
    return 0;
}

QN_EXPORT int32_t qn_poller_modify(int64_t poller, int32_t fd, int32_t token, int32_t writable) {
    qn_poller *p = qn_poller_of(poller);
#if QN_KQUEUE
    struct kevent ev;
    EV_SET(&ev, fd, EVFILT_WRITE, writable ? EV_ADD : EV_DELETE, 0, 0, (void *)(intptr_t)token);
    if (kevent(p->pfd, &ev, 1, NULL, 0, NULL) < 0 && !(errno == ENOENT && !writable)) return -qn_code(errno);
#else
    struct epoll_event ev = {0};
    ev.events = EPOLLIN | (writable ? EPOLLOUT : 0);
    ev.data.u64 = (uint64_t)(uint32_t)token;
    if (epoll_ctl(p->pfd, EPOLL_CTL_MOD, fd, &ev) < 0) return -qn_code(errno);
#endif
    return 0;
}

QN_EXPORT int32_t qn_poller_remove(int64_t poller, int32_t fd) {
    qn_poller *p = qn_poller_of(poller);
#if QN_KQUEUE
    struct kevent ev[2];
    EV_SET(&ev[0], fd, EVFILT_READ, EV_DELETE, 0, 0, NULL);
    EV_SET(&ev[1], fd, EVFILT_WRITE, EV_DELETE, 0, 0, NULL);
    /* EV_RECEIPT reports each change's own result, so an unregistered write filter is not an error. */
    ev[0].flags |= EV_RECEIPT;
    ev[1].flags |= EV_RECEIPT;
    struct kevent out[2];
    kevent(p->pfd, ev, 2, out, 2, NULL);
#else
    struct epoll_event ev = {0};
    epoll_ctl(p->pfd, EPOLL_CTL_DEL, fd, &ev);
#endif
    return 0;
}

QN_EXPORT int32_t qn_poller_wait(int64_t poller, int32_t mode, int64_t nanos) {
    qn_poller *p = qn_poller_of(poller);
    int woken = 0;
    int n;
    p->count = 0;
    if (nanos < 0) nanos = 0;
#if QN_KQUEUE
    struct timespec ts = {(time_t)(nanos / 1000000000LL), (long)(nanos % 1000000000LL)};
    struct timespec zero = {0, 0};
    const struct timespec *timeout = mode == QN_WAIT_FOREVER ? NULL : (mode == QN_WAIT_NOW ? &zero : &ts);
    n = kevent(p->pfd, NULL, 0, p->events, p->capacity + 1, timeout);
    if (n < 0) return errno == EINTR ? 0 : -qn_code(errno);
    for (int i = 0; i < n; i++) {
        struct kevent *e = &p->events[i];
        if (e->filter == EVFILT_USER) {
            woken = 1;
        } else if (!(e->flags & EV_ERROR)) {
            qn_ready_merge(p, (int32_t)(intptr_t)e->udata, e->filter == EVFILT_WRITE ? QN_WRITABLE : QN_READABLE);
        }
    }
#else
    if (mode == QN_WAIT_TIMED && p->pwait2) {
#ifdef SYS_epoll_pwait2
        struct timespec ts = {(time_t)(nanos / 1000000000LL), (long)(nanos % 1000000000LL)};
        n = (int)syscall(SYS_epoll_pwait2, p->pfd, p->events, p->capacity + 1, &ts, NULL, 0);
#else
        n = 0;
#endif
    } else {
        int ms = mode == QN_WAIT_FOREVER ? -1 : 0;
        if (mode == QN_WAIT_TIMED) {
            int64_t rounded = (nanos + 999999LL) / 1000000LL; /* never early */
            ms = rounded > 0x7fffffffLL ? 0x7fffffff : (int)rounded;
        }
        n = epoll_wait(p->pfd, p->events, p->capacity + 1, ms);
    }
    if (n < 0) return errno == EINTR ? 0 : -qn_code(errno);
    for (int i = 0; i < n; i++) {
        struct epoll_event *e = &p->events[i];
        if (e->data.u64 == UINT64_MAX) {
            uint64_t v;
            if (read(p->wakefd, &v, sizeof v) < 0) { }
            woken = 1;
            continue;
        }
        int32_t bits = 0;
        if (e->events & (EPOLLIN | EPOLLERR | EPOLLHUP)) bits |= QN_READABLE;
        if (e->events & EPOLLOUT) bits |= QN_WRITABLE;
        qn_ready_merge(p, (int32_t)e->data.u64, bits);
    }
#endif
    return (p->count << 1) | woken;
}

QN_EXPORT int32_t qn_poller_ready_at(int64_t poller, int32_t index) {
    qn_poller *p = qn_poller_of(poller);
    return index < p->count ? p->ready[index] : 0;
}

QN_EXPORT int32_t qn_poller_wake(int64_t poller) {
    qn_poller *p = qn_poller_of(poller);
#if QN_KQUEUE
    struct kevent ev;
    EV_SET(&ev, QN_WAKE_IDENT, EVFILT_USER, 0, NOTE_TRIGGER, 0, NULL);
    if (kevent(p->pfd, &ev, 1, NULL, 0, NULL) < 0) return -qn_code(errno);
#else
    uint64_t one = 1;
    if (write(p->wakefd, &one, sizeof one) < 0 && errno != EAGAIN) return -qn_code(errno);
#endif
    return 0;
}

QN_EXPORT void qn_poller_close(int64_t poller) {
    qn_poller *p = qn_poller_of(poller);
    close(p->pfd);
#if QN_EPOLL
    close(p->wakefd);
#endif
    free(p->ready);
    free(p->events);
    free(p);
}
