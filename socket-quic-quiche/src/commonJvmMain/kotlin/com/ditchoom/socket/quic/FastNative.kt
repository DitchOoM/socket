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
 *
 * BINARY retention, not RUNTIME. ART looks for this annotation only with build visibility
 * (`dex_file_annotations.cc`, IsMethodBuildAnnotationPresent), which is what D8 and R8 emit for a
 * class-retained (RuntimeInvisible) annotation. A RUNTIME annotation lands in the dex as
 * VISIBILITY_RUNTIME, which ART ignores, so with RUNTIME retention none of these methods ever got the
 * fast transition. R8 also strips invisible annotations unless told to keep them; consumer-rules.pro
 * keeps them and this class's name.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class FastNative
