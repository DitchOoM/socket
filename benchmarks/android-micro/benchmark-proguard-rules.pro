# R8 rules for the benchmark test APK. Deliberately NO -dontobfuscate (the Microbenchmark template has
# one, for readable traces): an app that ships with R8 obfuscates, and the point of this module is to run
# quiche's JNI binding through exactly that. Nothing about quiche is kept here either; that is
# :socket-quic-quiche's consumer-rules.pro's job (or AGP's default file's, when it is used).

# The test runner finds the benchmark classes and methods reflectively.
-keep @org.junit.runner.RunWith public class * {
    public <init>();
    @org.junit.* public <methods>;
    @org.junit.* public <fields>;
}
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

-dontwarn androidx.test.**
-dontwarn org.junit.**
-dontwarn org.hamcrest.**
-dontwarn com.squareup.javawriter.JavaWriter
-dontwarn java.lang.management.**
-dontwarn kotlinx.coroutines.debug.**
