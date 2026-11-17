# Kotlin
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
	public static void check*(...);
	public static void throw*(...);
}
-assumenosideeffects class java.util.Objects {
    public static ** requireNonNull(...);
}
-assumenosideeffects public class kotlin.coroutines.jvm.internal.DebugMetadataKt {
   private static ** getDebugMetadataAnnotation(...) return null;
}

# Strip Timber verbose and debug logging
-assumenosideeffects class timber.log.Timber$Tree {
  public void v(**);
  public void d(**);
}

# Module WebUI JavaScript bridge
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

-dontwarn org.junit.**
-dontwarn org.apache.**
