#!/bin/bash

echo "=== Building Debug APK ==="

# Make script executable
chmod +x "$0"

# Navigate to project root directory (assuming script is in the project root)
cd "$(dirname "$0")"

# Ensure gradlew is executable
chmod +x gradlew

# Clean the project
echo "Cleaning project..."
./gradlew clean --no-daemon

# Stop Gradle daemon to ensure clean build environment
./gradlew --stop

# Create a temporary build.gradle without signing
echo "Creating unsigned build configuration..."
cp app/build.gradle app/build.gradle.bak
sed -i 's/signingConfig signingConfigs.release/\/\/signingConfig signingConfigs.release/' app/build.gradle

# Build debug version (easier to build than release with all the protections)
echo "Building debug APK..."
./gradlew assembleDebug --no-daemon

# Check if build was successful
if [ $? -eq 0 ]; then
    APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
    if [ -f "$APK_PATH" ]; then
        echo "Build successful!"
        echo "APK location: $APK_PATH"
        
        # Copy to a more accessible location
        mkdir -p releases
        cp "$APK_PATH" "releases/nosafeword-debug.apk"
        echo "APK copied to: releases/nosafeword-debug.apk"
    else
        echo "APK file not found at expected location: $APK_PATH"
    fi
else
    echo "Build failed."
fi

# Restore original build.gradle
mv app/build.gradle.bak app/build.gradle

echo "Done. Check the output above for results."
