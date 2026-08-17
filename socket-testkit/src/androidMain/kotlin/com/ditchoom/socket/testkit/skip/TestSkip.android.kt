package com.ditchoom.socket.testkit.skip

import androidx.test.platform.app.InstrumentationRegistry

/**
 * On Android an instrumented test process is forked from zygote, so it inherits the **device's**
 * environment — not the environment of the machine that launched the run. `System.getenv` alone
 * therefore makes [REQUIRE_ALL_TESTS_ENV] structurally unreachable on every instrumented lane:
 * setting `SOCKET_REQUIRE_ALL_TESTS=1` beside `./gradlew connectedAndroidTest` changes nothing on
 * the device, so the gate that turns a skip into a failure could never fire there. That is not a
 * gate with a bug in it, it is a gate that was never connected — and it is why the Android lane was
 * the last place a silent skip could still hide.
 *
 * The channel that *does* cross from host to instrumented process is the instrumentation argument
 * set, so that is consulted first, under the same name as the environment variable:
 *
 * ```
 * ./gradlew :m:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.SOCKET_REQUIRE_ALL_TESTS=1
 * ```
 *
 * `System.getenv` remains the fallback so a JVM-hosted consumer of the Android artifact (and any
 * variable a device genuinely does export) keeps working.
 *
 * [InstrumentationRegistry] is a `compileOnly` dependency: this module is published and must not
 * drag `androidx.test` onto a consumer's runtime classpath, and outside an instrumented run the
 * class is simply absent. `runCatching` therefore covers `NoClassDefFoundError` as well as the
 * `IllegalStateException` the registry raises before the runner has registered its arguments.
 */
internal actual fun testkitEnv(name: String): String? = instrumentationArgument(name) ?: System.getenv(name)?.takeIf { it.isNotEmpty() }

private fun instrumentationArgument(name: String): String? =
    runCatching { InstrumentationRegistry.getArguments().getString(name) }
        .getOrNull()
        ?.takeIf { it.isNotEmpty() }
