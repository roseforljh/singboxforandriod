# General rules
-keepattributes Signature,Exceptions,*Annotation*

# SnakeYAML rules
-dontwarn java.beans.**

# Go (Gomobile) rules
-keep class go.** { *; }
-dontwarn go.**

# Generic rules for missing classes reported in the error
-dontwarn d0.**
