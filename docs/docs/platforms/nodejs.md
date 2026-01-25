---
sidebar_position: 3
title: Node.js
---

# Node.js

On Node.js, socket uses the built-in `net.Socket` API for TCP connections.

## Implementation Details

The JavaScript target wraps Node.js `net.Socket`:

- Async connect/read/write via Node.js event-driven API
- Buffer data bridged between Kotlin `ReadBuffer`/`WriteBuffer` and Node.js `Buffer`

## Browser

The browser target throws `UnsupportedOperationException` for socket operations, as browsers do not provide raw TCP socket access.

## Requirements

- Node.js runtime (for `net` module access)

## Testing

```bash
./gradlew jsNodeTest
```

Note: The `tcp-port-used` npm package is used in tests to verify port availability.
