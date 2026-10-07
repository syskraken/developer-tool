-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# The helper is started by name with app_process on the target phone, so nothing
# in the app references it and R8 would otherwise strip or rename it.
-keep class dev.franklin.devbridge.server.** { *; }
