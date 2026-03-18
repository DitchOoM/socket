config.resolve.alias = {
    "fs": false,
    "net": false,
    "util": false,
    "tls": false,
}
if (config.devServer != null) {
    config.devServer.headers = {
        "Cross-Origin-Opener-Policy": "same-origin",
        "Cross-Origin-Embedder-Policy": "require-corp"
    }
}