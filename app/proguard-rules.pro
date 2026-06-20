# Keep kotlinx-serialization metadata
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keep,includedescriptorclasses class io.github.garemat.crumpet.**$$serializer { *; }
-keepclassmembers class io.github.garemat.crumpet.** { *** Companion; }
