# kotlinx.serialization keeps its generated serializers via the bundled rules.
# Retrofit interfaces with suspend functions need their generic signatures.
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# JavaScript bridges used by the captcha WebViews.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
