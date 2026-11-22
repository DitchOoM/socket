@file:Suppress("unused") // Loaded at runtime via multi-release JAR

package com.ditchoom.socket.quic

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.SymbolLookup
import java.lang.invoke.MethodHandle

/**
 * The process-wide libquiche and the [Linker] that binds it.
 *
 * Downcall handles are built here, once, into `static final` fields of the call-holder objects in
 * `FfmQuicheApi.kt`. HotSpot treats only a constant method handle as inlinable: reached through an instance
 * field or a `Lazy`, every call is a generic handle invocation through a LambdaForm, which cost the FFM backend
 * about 6% of a QUIC echo's CPU against JNI.
 *
 * `openFfmQuicheLibrary` extracts and loads the library on first use and fails the class initialiser if no
 * candidate loads, which [loadQuicheApi] reports as the packaging bug it is.
 */
internal object FfmLibrary {
    private val linker: Linker = Linker.nativeLinker()
    private val quiche: SymbolLookup = openFfmQuicheLibrary()

    /** The downcall handle for the libquiche export [name]. */
    fun downcall(
        name: String,
        desc: FunctionDescriptor,
    ): MethodHandle = linker.downcallHandle(quiche.find(name).orElseThrow { UnsatisfiedLinkError("quiche: $name") }, desc)

    /**
     * The address of a C function that calls [target] as [desc] says, made once for the life of the process.
     * `upcallStub` and `Arena.global` are spelled identically on JDK 21's preview FFM and on JDK 22+.
     */
    fun upcall(
        target: MethodHandle,
        desc: FunctionDescriptor,
    ): Long = linker.upcallStub(target, desc, Arena.global()).address()

    /**
     * The downcall handle for the C-runtime function [name], which the linker's default lookup finds on every
     * supported platform without a native dependency.
     *
     * `defaultLookup`, `find` and `downcallHandle` are spelled identically on JDK 21's preview FFM and on
     * JDK 22+; see `cString` in `FfmQuicheApi` for the renamed method that is not.
     */
    fun libcDowncall(
        name: String,
        desc: FunctionDescriptor,
    ): MethodHandle =
        linker.downcallHandle(
            linker.defaultLookup().find(name).orElseThrow { UnsatisfiedLinkError("libc: $name") },
            desc,
        )
}
