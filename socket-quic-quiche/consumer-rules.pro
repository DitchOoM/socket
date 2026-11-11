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
