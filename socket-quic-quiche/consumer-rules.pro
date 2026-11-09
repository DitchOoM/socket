# Consumer R8/ProGuard rules for com.ditchoom:socket-quic-quiche, applied to every Android app that
# minifies with this library (packaged into the AAR as proguard.txt).
#
# JniQuicheApi's natives are bound BY NAME: libquiche_jni.so exports
# Java_com_ditchoom_socket_quic_JniQuicheApi_n<Method>, and no JNI_OnLoad/RegisterNatives exists to
# rebind them. Renaming the class or any native method, or removing the class's static initializer
# path, turns the first QUIC call into an UnsatisfiedLinkError. AGP's proguard-android-optimize.txt has a
# generic "keep native method names" rule that covers this today, but an app is free not to use that
# file; this rule makes the library correct on its own.
-keepclasseswithmembernames,includedescriptorclasses class com.ditchoom.socket.quic.JniQuicheApi {
    native <methods>;
}

# @FastNative on JniQuicheApi's per-packet and per-stream natives (conn_recv, conn_send, stream_recv,
# stream_send, ...). ART honours it only as a build-visibility annotation of type
# Ldalvik/annotation/optimization/FastNative;, so R8 must keep the class-retained annotations
# (RuntimeInvisibleAnnotations) and must not rename the annotation class, which this library ships as a
# stub because the platform's is hidden from the SDK. Without these two lines R8 removes every
# @FastNative from the app's dex and the natives take the regular JNI transition.
-keepattributes RuntimeInvisibleAnnotations
-keep class dalvik.annotation.optimization.FastNative
