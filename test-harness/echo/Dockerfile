# Tiny TCP echo server. One process; fork-per-connection via socat;
# each connection pipes socket↔stdin/stdout through `cat`.
#
# Why socat+cat and not a Go binary: zero build deps, the same binary
# is used for healthcheck (no extra package), behavior is well-understood,
# and the L0 echo path doesn't need anything more.
FROM alpine:3.20

RUN apk add --no-cache socat

EXPOSE 14000

# -T 60   : per-connection inactivity timeout — reaps zombie peers
# reuseaddr,fork : standard "long-lived listener, child per connection"
# EXEC:cat       : pipe socket through cat (echo)
CMD ["socat", "-T", "60", "TCP-LISTEN:14000,reuseaddr,fork", "EXEC:cat"]
