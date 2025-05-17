#!/bin/bash

# Navigate to project root directory
cd "$(dirname "$0")/.."

# Stop any running Gradle daemon
./gradlew --stop

# Clean the project
./gradlew clean

# Clear Gradle caches
rm -rf ~/.gradle/caches/

# Create the .gradle directory in the project if it doesn't exist
mkdir -p .gradle

# Create a touch file to indicate cleaning was done
touch .gradle/.cleaned

echo "Clean completed. Please rebuild your project."
