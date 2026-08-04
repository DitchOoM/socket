package com.ditchoom.socket

/**
 * Node-vs-browser detection for this module: the browser has `window`, Node does not.
 *
 * Shared by [JsNetworkMonitor] (which polls `os.networkInterfaces()` under Node and listens for
 * `online`/`offline` in a browser) and [enumerateNetworkInterfaces] (which returns an empty list off
 * Node) so the two can never disagree about which runtime this is.
 *
 * **Name it distinctly, and never `isNodeJs`.** `:socket` declares a public top-level
 * `val isNodeJs` in this same package, and both klibs land on one Kotlin/JS IR link in any consumer of
 * `:socket` (which re-exports this module via `api`). Two non-private top-level declarations sharing a
 * package and a name produce identical IR signatures, and the linker rejects the second:
 *
 * ```
 * e: java.lang.IllegalStateException: IrPropertySymbolImpl is already bound.
 *    Signature: com.ditchoom.socket/isNodeJs|{}isNodeJs[0]
 * ```
 *
 * `private` would also avoid it, but then the two callers above each need their own copy. This is the
 * general hazard of the two modules sharing the `com.ditchoom.socket` package (issue #269 kept it that
 * way so nothing downstream changed an import): any `internal` or public top-level added here must not
 * collide by name with one in `:socket`. Note the failure surfaces only at the JS **IR link**
 * (`compileTestDevelopmentExecutableKotlinJs` / `jsNodeTest`), not at `compileTestKotlinJs`.
 */
internal val isNodeJsRuntime: Boolean = js("global.window") == null
