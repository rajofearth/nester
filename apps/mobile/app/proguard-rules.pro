-keepattributes *Annotation*, InnerClasses, Signature
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class app.nester.**$$serializer { *; }
-keepclassmembers class app.nester.** {
    *** Companion;
}
-keepclasseswithmembers class app.nester.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-dontwarn okhttp3.internal.platform.**
