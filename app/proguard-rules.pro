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
# -keep class com.velvettouch.nosafeword.model.** { *; } # This rule might be unused or for other models

# Keep specific model classes used with Gson and TypeToken or Firebase
# Note: TaskItem is in com.velvettouch.nosafeword.data.model
# -keep class com.velvettouch.nosafeword.TaskItem { *; } # Incorrect path, removed/commented
-keep class com.velvettouch.nosafeword.PlannedItem { *; } # Assuming this is at root package
-keep class com.velvettouch.nosafeword.Scene { *; }       # Assuming this is at root package
# Add other models used with Gson here if needed

# Keep TaskItem class for Firebase (Realtime Database / Firestore)
-keepclassmembers class com.velvettouch.nosafeword.data.model.TaskItem {
    public <init>();
    public *;
}
-keep class com.velvettouch.nosafeword.data.model.TaskItem { *; }

# Keep UserProfile class for Firebase Firestore
-keepclassmembers class com.velvettouch.nosafeword.data.model.UserProfile {
    public <init>();
    public *;
}
-keep class com.velvettouch.nosafeword.data.model.UserProfile { *; }

# Keep RuleItem class for Firebase (Realtime Database / Firestore)
-keepclassmembers class com.velvettouch.nosafeword.data.model.RuleItem {
    public <init>();
    public *;
}
-keep class com.velvettouch.nosafeword.data.model.RuleItem { *; }

# Keep TypeToken and its subclasses for Gson
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

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
# Preserve generic signatures (important for Gson TypeToken)
-keepattributes Signature

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

# Keep UpdateInfo model class for Gson
-keep class com.velvettouch.nosafeword.model.UpdateInfo { *; }
-keepclassmembers class com.velvettouch.nosafeword.model.UpdateInfo {
    public <init>();
    public *;
}
