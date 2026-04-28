# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *; }
-keep,includedescriptorclasses class app.musicplayer.restaurant.**$$serializer { *; }
-keepclassmembers class app.musicplayer.restaurant.** {
    *** Companion;
}
-keepclasseswithmembers class app.musicplayer.restaurant.** {
    kotlinx.serialization.KSerializer serializer(...);
}
