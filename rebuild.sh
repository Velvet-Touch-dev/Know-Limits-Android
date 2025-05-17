#!/bin/bash

# Make script executable
chmod +x scripts/clean.sh

# Run the clean script
./scripts/clean.sh

# Rebuild
./gradlew assembleDebug
