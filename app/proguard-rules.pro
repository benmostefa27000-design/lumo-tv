# Lumo : on garde tout notre code tel quel (sûr), R8 optimise surtout Compose et les librairies
-keep class com.lumo.tv.** { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn okhttp3.internal.platform.**
-dontwarn javax.annotation.**
-dontwarn kotlin.reflect.jvm.internal.**
