# kotlinx.serialization: keep generated serializers for our @Serializable types.
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keep,includedescriptorclasses class dev.aarso.mail.**$$serializer { *; }
-keepclassmembers class dev.aarso.mail.** { *** Companion; }
-keepclasseswithmembers class dev.aarso.mail.** { kotlinx.serialization.KSerializer serializer(...); }

# The Anthropic SDK maps JSON onto its model classes with Jackson (reflection).
-keep class com.anthropic.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keep class kotlin.Metadata { *; }
-dontwarn com.fasterxml.jackson.**
-dontwarn io.opentelemetry.**
-dontwarn io.swagger.**
-dontwarn com.standardwebhooks.**
-dontwarn org.slf4j.**
-dontwarn java.beans.**
-dontwarn javax.annotation.**

# The local-model runtime is found by name (it may be an on-demand module), then MediaPipe's
# LLM inference (JNI) and protobuf lite.
-keep class dev.aarso.mail.ondevice.** { *; }
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**

# OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# The Anthropic SDK's class-to-JSON-Schema helper (and victools' generator under it) reads type
# annotations, which Android's reflection does not have. This app passes raw JSON schemas, so
# that path never runs on a phone.
-dontwarn java.lang.reflect.AnnotatedType
-dontwarn java.lang.reflect.AnnotatedParameterizedType
-dontwarn com.github.victools.jsonschema.**
