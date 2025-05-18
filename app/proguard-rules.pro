# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep the application class
-keep public class com.velvettouch.nosafeword.NoSafeWordApplication

# Keep Activities, Fragments, etc.
-keep public class com.velvettouch.nosafeword.MainActivity
-keep public class com.velvettouch.nosafeword.SettingsActivity
-keep public class com.velvettouch.nosafeword.PositionsActivity
-keep public class com.velvettouch.nosafeword.BodyWorshipActivity

# Keep model classes (if any)
-keep class com.velvettouch.nosafeword.model.** { *; }

# For native methods, see http://proguard.sourceforge.net/manual/examples.html#native
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep setters in Views so that animations can still work
-keepclassmembers public class * extends android.view.View {
   void set*(***);
   *** get*();
}

# We want to keep methods in Activity that could be used in the XML attribute onClick
-keepclassmembers class * extends android.app.Activity {
   public void *(android.view.View);
}

# For enumeration classes, see http://proguard.sourceforge.net/manual/examples.html#enumerations
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Preserve all annotations
-keepattributes *Annotation*

# Keep the support library components
-keep class androidx.** { *; }
-keep interface androidx.** { *; }

# Keep Parcelable implementations
-keep class * implements android.os.Parcelable {
  public static final android.os.Parcelable$Creator *;
}

# Keep Serializable implementations
-keep class * implements java.io.Serializable { *; }

# Keep R classes
-keep class **.R
-keep class **.R$* {
    <fields>;
}
