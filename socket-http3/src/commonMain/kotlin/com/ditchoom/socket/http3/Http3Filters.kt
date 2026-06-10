package com.ditchoom.socket.http3

/*
 * Composable server-side middleware for withHttp3Server, in the http4k `Filter` style but fitted to
 * this library's imperative, zero-copy request handling.
 *
 * Http3RequestHandler is EXACTLY the type withHttp3Server's onRequest already takes — a suspend lambda
 * with Http3ServerExchange as receiver — so a composed chain is handed straight to onRequest with no
 * adapter. A Http3RequestFilter wraps one handler to produce another, so cross-cutting concerns (auth,
 * logging, metrics, error mapping) are written once and stacked with `then`:
 *
 *   val timing: Http3RequestFilter = { next -> {
 *       val start = monotonicNow()         // this: Http3ServerExchange
 *       try { next(this) } finally { record(request.path, monotonicNow() - start) }
 *   } }
 *   val requireApiKey: Http3RequestFilter = { next -> {
 *       if (request.headers.none { it.name == "x-api-key" }) response.send(401)  // short-circuit: skip next
 *       else next(this)
 *   } }
 *
 *   withHttp3Server(..., onRequest = timing.then(requireApiKey).then { response.send(200) }) { ... }
 *
 * Because handlers write to Http3ServerExchange.response IMPERATIVELY (zero-copy — there is no
 * materialized response *value* to transform), a filter runs *around* the inner handler. It can act
 * before/after it, catch its exceptions, or short-circuit by sending a response and NOT calling `next`.
 * It deliberately CANNOT post-edit headers/body the inner handler already wrote to the wire — that's the
 * trade for streaming, and the reason this is a filter set rather than a value-oriented (Request)->Response
 * API. The same `(H) -> H` shape works for onWebTransport if you need WebTransport-side middleware.
 */

/** The request-handler shape [withHttp3Server] already accepts for `onRequest`. */
typealias Http3RequestHandler = suspend Http3ServerExchange.() -> Unit

/** Wraps a [Http3RequestHandler] to produce another. Compose with [then]; apply by [then]-ing a handler. */
typealias Http3RequestFilter = (Http3RequestHandler) -> Http3RequestHandler

/** Compose two filters: `a.then(b)` runs `a`'s wrapping OUTSIDE `b`'s (outermost-first). */
infix fun Http3RequestFilter.then(next: Http3RequestFilter): Http3RequestFilter = { handler -> this(next(handler)) }

/** Apply this filter (chain) to the terminal [handler], yielding the value to hand to `onRequest`. */
fun Http3RequestFilter.then(handler: Http3RequestHandler): Http3RequestHandler = this(handler)
