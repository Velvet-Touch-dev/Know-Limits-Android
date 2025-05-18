# Build Instructions for No Safe Word App

This document provides detailed instructions on how to compile the No Safe Word Android app for both development and release purposes.

## Quick Start

### Debug Build
For development and testing:

```bash
./gradlew assembleDebug
```

The debug APK will be created at:
```
app/build/outputs/apk/debug/app-debug.apk
```

### Release Build
To create a release version for publication:

```bash
./build-release.sh
```

This script will:
1. Guide you through creating a keystore if you don't have one
2. Let you update the version number if needed
3. Build both APK and AAB files for release
4. Copy the files to a `releases` folder

## Manual Building Process

### Prerequisites
- Android Studio (or command line tools)
- JDK 8 or newer
- Gradle (included in the project)

### Development Build

Using Android Studio:
1. Open the project in Android Studio
2. Select `Build > Build Bundle(s) / APK(s) > Build APK(s)`

Using command line:
```bash
./gradlew assembleDebug
```

### Release Build

Using Android Studio:
1. Open the project in Android Studio
2. Select `Build > Generate Signed Bundle/APK`
3. Choose APK or Android App Bundle
4. Select your keystore or create a new one
5. Complete the signing process

Using command line (with existing keystore):
```bash
# Make sure signing.properties is configured correctly
./gradlew assembleRelease   # For APK
./gradlew bundleRelease     # For AAB
```

## Important Files

- `app/build.gradle` - Main build configuration
- `app/signing.properties` - Keystore configuration (created by the release script)
- `app/proguard-rules.pro` - ProGuard configuration for release builds

## Testing the Release Build

Before submitting to Google Play:
1. Install the release APK on a test device
2. Verify all functionality works correctly
3. Check that ProGuard hasn't broken any features

## Google Play Submission

For Google Play submission:
1. Use the AAB file (Android App Bundle)
2. Preferred over APK as it optimizes the app size for different devices
3. Found in `app/build/outputs/bundle/release/app-release.aab` after building

## Troubleshooting

If you encounter build issues:
1. Run the clean script: `./scripts/clean.sh`
2. Make sure all dependencies are up to date
3. Check for any compiler warnings or errors

For keystore issues:
- Never lose your keystore file or password
- The same keystore must be used for all updates to your app
- If you lose it, you'll need to publish as a new app

## Keeping Your Keystore Secure

- Store the keystore file in a secure location
- Never share your keystore passwords
- Consider using a password manager to store keystore credentials
- Back up your keystore file in multiple secure locations
