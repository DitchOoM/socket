@file:Suppress("MatchingDeclarationName")

package dalvik.annotation.optimization

/**
 * Stub for ART's @FastNative annotation.
 *
 * On Android 8+, ART recognizes this annotation by fully qualified name
 * and uses a faster JNI transition (~3x faster, no managed→native bridge).
 *
 * On JVM (non-Android), this annotation is a no-op — the class exists
 * but has no effect on HotSpot.
 *
 * Requirements for @FastNative methods:
 * - Must be `external` (native)
 * - Must not hold JNI monitors (no synchronized)
 * - Must execute quickly (doesn't block GC)
 *
 * All quiche JNI functions qualify — they're pure pointer forwarding.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class FastNative
