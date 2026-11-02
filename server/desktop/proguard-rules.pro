# ProGuard for the release desktop app (createReleaseDistributable / packageDesktop).
# Compose adds its own rules on top: Kotlin, skiko, coroutines, kotlinx.serialization.
# Only shrinking runs here: no obfuscation and no optimization (see build.gradle.kts), so names stay
# readable in stack traces and reflection by name keeps working.
#
# The rule of thumb below: libraries that load classes by name, through ServiceLoader or JNI are kept
# whole. Compose, AndroidX, Koin and the like are shrunk; that is where most of the size goes.
# If the release app fails and the debug one (./gradlew :desktop:run) works, build without ProGuard
# with -Pdesktop.proguard=false and add a -keep rule for the class in the error.

-keepattributes Signature,InnerClasses,EnclosingMethod,Exceptions,*Annotation*,SourceFile,LineNumberTable

# This app: Koin factories, JNA interfaces, kotlinx.serialization models in :shared, reflective socket access.
-keep class com.remotecontroller.** { *; }

# Ktor: server engine, websockets, content negotiation and the OkHttp client find parts by ServiceLoader.
-keep class io.ktor.** { *; }

# Netty: channels are created by reflection, native transports bind through JNI,
# and SocketFrames reads fd() / javaChannel() by name.
-keep class io.netty.** { *; }

# BouncyCastle registers algorithms by class name.
-keep class org.bouncycastle.** { *; }

# JNA maps native structures and callbacks through reflection.
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.Structure { public *; }

# OkHttp / Okio (relay client): platform detection by class name.
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# cryptography-kotlin picks its JDK provider through ServiceLoader.
-keep class dev.whyoleg.cryptography.** { *; }

# SLF4J finds slf4j-simple through ServiceLoader.
-keep class org.slf4j.** { *; }

# kotlinx.coroutines: shrinking rewrites the interface lists of JobSupport and friends, and the JVM then
# refuses their invokespecial calls to Job's default methods (VerifyError: "Bad invokespecial instruction:
# interface method reference is in an indirect superinterface"). Compose's own rule keeps only Job.
-keep class kotlinx.coroutines.** { *; }
-keep interface kotlinx.coroutines.** { *; }

# kotlinx.serialization runtime: serializers are looked up reflectively for Ktor's typeInfo.
-keep class kotlinx.serialization.** { *; }

# Every ServiceLoader implementation that is only named in META-INF/services.
-keep class * implements kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keep class * implements kotlinx.coroutines.CoroutineExceptionHandler { *; }

# Optional dependencies of the libraries above that are not on the classpath.
-dontwarn io.netty.**
-dontwarn io.ktor.**
-dontwarn org.bouncycastle.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.slf4j.**
-dontwarn org.apache.log4j.**
-dontwarn org.apache.logging.log4j.**
-dontwarn reactor.blockhound.**
-dontwarn com.aayushatharva.brotli4j.**
-dontwarn com.github.luben.zstd.**
-dontwarn com.jcraft.jzlib.**
-dontwarn com.ning.compress.**
-dontwarn lzma.sdk.**
-dontwarn net.jpountz.**
-dontwarn org.jboss.marshalling.**
-dontwarn com.google.protobuf.**
-dontwarn org.eclipse.jetty.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn org.graalvm.**
-dontwarn com.oracle.svm.**
-dontwarn javax.annotation.**
-dontwarn org.jetbrains.annotations.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
-dontwarn kotlinx.coroutines.debug.**
-dontwarn kotlin.reflect.jvm.internal.**
-dontwarn sun.security.**
-dontnote **
