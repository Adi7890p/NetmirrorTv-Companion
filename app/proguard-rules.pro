# ProGuard rules for NetMirror Companion App
-keepclassmembers class com.netmirror.companion.WebAppInterface {
    @android.webkit.JavascriptInterface <methods>;
}

-keepattributes JavascriptInterface
-keepattributes *Annotation*
