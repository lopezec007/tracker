# kotlinx.serialization: keep the generated serializers of our DTOs.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class io.github.lopezec007.tracker.**$$serializer { *; }
-keepclassmembers class io.github.lopezec007.tracker.** { *** Companion; }
-keepclasseswithmembers class io.github.lopezec007.tracker.** { kotlinx.serialization.KSerializer serializer(...); }

# OsmAnd AIDL: the Parcelables cross the process boundary and are rebuilt by
# the Parcel machinery through their CREATOR fields and class names; keep the
# whole API package as it is so what OsmAnd reads matches what it expects.
-keep class net.osmand.aidlapi.** { *; }
-keepclassmembers class * implements android.os.Parcelable { public static final ** CREATOR; }

# OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
