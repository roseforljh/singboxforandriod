# General rules
-keepattributes Signature,Exceptions,*Annotation*

# SnakeYAML rules
-dontwarn java.beans.**

# Go (Gomobile) rules
-keep class go.** { *; }
-dontwarn go.**

# sing-box (libbox) rules
-keep class io.nekohasekai.libbox.** { *; }
-dontwarn io.nekohasekai.libbox.**

# App specific rules for JNI callbacks
# Optimize App classes - allow shrinking/obfuscation where possible, but keep entry points
-keep class com.kunk.singbox.MainActivity { *; }
-keep class com.kunk.singbox.service.** { *; }
# Keep data models used in JSON/Reflection
-keep class com.kunk.singbox.model.** { *; }

# Generic rules for missing classes reported in the error
-dontwarn d0.**

# Gson rules
-keepattributes Signature, EnclosingMethod, InnerClasses
-keep class com.google.gson.** { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keep public class * implements java.lang.reflect.Type

