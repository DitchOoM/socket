# The rules a desktop application would plausibly ship for ShrunkConsumerProbe (see build.gradle.kts):
# its entry point, the JNI natives (bound by name: Java_com_ditchoom_socket_quic_JniQuicheApi_n...), and
# warnings silenced for the platforms the fat jar carries code for but this JVM does not have.
# Deliberately NOTHING about META-INF/versions/**: the question is what a consumer gets by default.

-keep class com.ditchoom.socket.benchmark.ShrunkConsumerProbeKt { public static void main(java.lang.String[]); }

-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

-dontwarn android.**
-dontwarn dalvik.**
-dontwarn androidx.**
-dontwarn org.slf4j.**
-dontwarn kotlinx.coroutines.debug.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
-dontwarn javax.annotation.**
-dontwarn java.lang.invoke.StringConcatFactory
-ignorewarnings
-dontnote **
