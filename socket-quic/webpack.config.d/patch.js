// Node built-ins the browser bundle can never provide.
//
// Kotlin/JS emits `require("net")` / `require("fs")` / `require("tls")` / `require("os")` from the
// Node socket actuals, and webpack resolves those EAGERLY at bundle time — even for code the browser
// never executes (the browser actuals throw UnsupportedOperationException long before touching them).
// Without an alias the bundle fails to link at all: "Module not found: Can't resolve 'net'".
// Aliasing to `false` resolves each to an empty module, so the bundle links and the browser gap
// tests (which pin that these transports are unsupported) can actually run.
//
// ⚠️ Kotlin's JS plugin reads `webpack.config.d` PER PROJECT, so this cannot live only at the repo
// root — every module with a `browser()` target needs its own copy, and this build has no
// buildSrc/convention plugin to hoist it into. Keep these copies identical. See issue #304.
config.resolve = config.resolve || {}
config.resolve.alias = Object.assign({}, config.resolve.alias, {
    fs: false,
    net: false,
    os: false,
    tls: false,
    util: false,
})
