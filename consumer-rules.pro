# Consumer R8/ProGuard rules for com.ditchoom:socket, applied to every Android app that minifies with
# this library (packaged into the AAR as proguard.txt).
#
# com.ditchoom:buffer-android (an api dependency) ships libditchoom_buffer_jni.so, whose
# Java_com_ditchoom_buffer_JniDirectByteBufferAllocator_* symbols are bound by name, but (through 6.30.9)
# no consumer rules of its own. In an app that does not use AGP's proguard-android-optimize.txt, R8
# renames the class and the first direct-buffer allocation fails with UnsatisfiedLinkError — measured by
# :benchmark-android with -PbenchDefaultProguardFile=false. Kept here until buffer carries the rule.
-keepclasseswithmembernames,includedescriptorclasses class com.ditchoom.buffer.** {
    native <methods>;
}
