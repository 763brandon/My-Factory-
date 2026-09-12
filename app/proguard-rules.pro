# ---------------------------------------------------------------------------
# R8 configuration.
#
# The goal here is a smaller dex, which matters more on a 2 GB device than on
# a flagship: less to load, less to verify at install, less resident.
# ---------------------------------------------------------------------------

# JNI. The native library resolves these by name at runtime, so R8 must not
# rename or remove them. Losing this rule breaks the terminal in release only,
# which is the worst kind of bug to find.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class com.myfactory.forge.runtime.pty.NativePty { *; }

# kotlinx.serialization generates serializers as nested classes and looks them
# up reflectively through the companion.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.myfactory.forge.core.**$$serializer { *; }
-keepclassmembers class com.myfactory.forge.core.** {
    *** Companion;
}
-keepclasseswithmembers class com.myfactory.forge.core.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Room builds its implementations at compile time but resolves them by name.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**

# OkHttp. These are optional integrations the library references but does not
# require; without this R8 warns on every build.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Coroutines internals referenced only by the debug agent.
-dontwarn kotlinx.coroutines.debug.**

# Enum valueOf is used to read persisted names back out of the database.
-keepclassmembers enum com.myfactory.forge.core.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep the line numbers so a user-reported stack trace is readable, but hide
# the original file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
