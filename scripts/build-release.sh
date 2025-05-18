#!/bin/bash
# Make script executable
chmod +x "$0"

# Navigate to project root directory
cd "$(dirname "$0")/.."

# Set terminal colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}=== Building Release Version for No Safe Word ===${NC}"

# Run clean script first
echo -e "${YELLOW}Cleaning project...${NC}"
./scripts/clean.sh

# Delete build caches
echo -e "${YELLOW}Deleting build caches...${NC}"
rm -rf ~/.gradle/caches/transforms-*
rm -rf ~/.gradle/caches/modules-*/files-*/com.android.tools.build

# Run Gradle clean
echo -e "${YELLOW}Running Gradle clean...${NC}"
./gradlew clean --no-daemon

# Ensure gradlew is executable
chmod +x gradlew

# Check if keystore file exists
KEYSTORE_FILE="app/nosafeword-keystore.jks"
if [ ! -f "$KEYSTORE_FILE" ]; then
    echo -e "${RED}Error: Keystore file not found at $KEYSTORE_FILE${NC}"
    echo -e "${YELLOW}Do you want to create a new keystore? (y/n)${NC}"
    read -r CREATE_KEYSTORE
    
    if [[ $CREATE_KEYSTORE == "y" || $CREATE_KEYSTORE == "Y" ]]; then
        echo -e "${YELLOW}Creating new keystore...${NC}"
        
        # Prompt for keystore information
        echo -e "${YELLOW}Enter keystore password:${NC}"
        read -r KEYSTORE_PASSWORD
        
        echo -e "${YELLOW}Enter key alias:${NC}"
        read -r KEY_ALIAS
        
        echo -e "${YELLOW}Enter key password:${NC}"
        read -r KEY_PASSWORD
        
        echo -e "${YELLOW}Enter your name (CN):${NC}"
        read -r NAME
        
        # Create keystore directory if it doesn't exist
        mkdir -p "$(dirname "$KEYSTORE_FILE")"
        
        # Generate keystore
        keytool -genkey -v -keystore "$KEYSTORE_FILE" -alias "$KEY_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass "$KEYSTORE_PASSWORD" -keypass "$KEY_PASSWORD" -dname "CN=$NAME, O=Velvet Touch, L=Unknown, C=Unknown"
        
        if [ $? -ne 0 ]; then
            echo -e "${RED}Failed to create keystore. Exiting.${NC}"
            exit 1
        fi
        
        # Create or update signing config in gradle properties
        echo -e "${YELLOW}Updating gradle properties with signing configuration...${NC}"
        
        cat > app/signing.properties << EOF
storeFile=../app/nosafeword-keystore.jks
storePassword=$KEYSTORE_PASSWORD
keyAlias=$KEY_ALIAS
keyPassword=$KEY_PASSWORD
EOF
        
        echo -e "${GREEN}Keystore created successfully at $KEYSTORE_FILE${NC}"
    else
        echo -e "${RED}Keystore is required for release builds. Exiting.${NC}"
        exit 1
    fi
fi

# Update version and code
echo -e "${YELLOW}Current version information:${NC}"
grep "versionCode\|versionName" app/build.gradle

echo -e "${YELLOW}Do you want to update version? (y/n)${NC}"
read -r UPDATE_VERSION

if [[ $UPDATE_VERSION == "y" || $UPDATE_VERSION == "Y" ]]; then
    echo -e "${YELLOW}Enter new version code (integer):${NC}"
    read -r VERSION_CODE
    
    echo -e "${YELLOW}Enter new version name (e.g., 1.0.1):${NC}"
    read -r VERSION_NAME
    
    # Update version in build.gradle
    sed -i "s/versionCode [0-9]*/versionCode $VERSION_CODE/" app/build.gradle
    sed -i "s/versionName \".*\"/versionName \"$VERSION_NAME\"/" app/build.gradle
    
    echo -e "${GREEN}Version updated to: Code $VERSION_CODE, Name $VERSION_NAME${NC}"
fi

# Build signed APK
echo -e "${YELLOW}Building signed APK...${NC}"
./gradlew assembleRelease --no-daemon

# Check if APK build failed
if [ $? -ne 0 ]; then
    echo -e "${RED}APK build failed.${NC}"
    
    # Ask if the user wants to disable signing and try again
    echo -e "${YELLOW}Do you want to try building without signing? (y/n)${NC}"
    read -r TRY_UNSIGNED
    
    if [[ $TRY_UNSIGNED == "y" || $TRY_UNSIGNED == "Y" ]]; then
        # Create a temporary backup of build.gradle
        cp app/build.gradle app/build.gradle.bak
        
        # Remove signing config from release build type
        sed -i 's/signingConfig signingConfigs.release/\/\/signingConfig signingConfigs.release/' app/build.gradle
        
        # Try building unsigned APK
        echo -e "${YELLOW}Building unsigned APK...${NC}"
        ./gradlew assembleRelease --no-daemon
        
        # Restore original build.gradle
        mv app/build.gradle.bak app/build.gradle
        
        if [ $? -ne 0 ]; then
            echo -e "${RED}Unsigned APK build failed too. Exiting.${NC}"
            exit 1
        fi
        
        echo -e "${GREEN}Unsigned APK built successfully.${NC}"
        echo -e "${YELLOW}WARNING: This APK is unsigned and cannot be published to Google Play.${NC}"
    else
        exit 1
    fi
fi

# Build Android App Bundle (AAB)
echo -e "${YELLOW}Building Android App Bundle (AAB)...${NC}"
./gradlew bundleRelease --no-daemon

# Check if AAB build failed
if [ $? -ne 0 ]; then
    echo -e "${RED}AAB build failed.${NC}"
    
    # If APK build succeeded but AAB build failed, still continue
    echo -e "${YELLOW}APK was built successfully. You can continue with just the APK for testing.${NC}"
    echo -e "${YELLOW}Do you want to continue without the AAB? (y/n)${NC}"
    read -r CONTINUE_WITHOUT_AAB
    
    if [[ $CONTINUE_WITHOUT_AAB == "n" || $CONTINUE_WITHOUT_AAB == "N" ]]; then
        exit 1
    fi
fi

# Get the paths to the output files
APK_PATH="app/build/outputs/apk/release/app-release.apk"
AAB_PATH="app/build/outputs/bundle/release/app-release.aab"

# Verify the files exist
if [ -f "$APK_PATH" ] && [ -f "$AAB_PATH" ]; then
    echo -e "${GREEN}Build completed successfully!${NC}"
    echo -e "${GREEN}APK location: ${BLUE}$APK_PATH${NC}"
    echo -e "${GREEN}AAB location: ${BLUE}$AAB_PATH${NC}"
    
    # Create output directory if it doesn't exist
    mkdir -p releases
    
    # Copy files to releases directory with versioned names
    VERSION_NAME=$(grep "versionName" app/build.gradle | sed 's/.*versionName "\(.*\)".*/\1/')
    VERSION_CODE=$(grep "versionCode" app/build.gradle | sed 's/.*versionCode \(.*\).*/\1/')
    
    cp "$APK_PATH" "releases/nosafeword-$VERSION_NAME-$VERSION_CODE.apk"
    cp "$AAB_PATH" "releases/nosafeword-$VERSION_NAME-$VERSION_CODE.aab"
    
    echo -e "${GREEN}Files copied to releases directory:${NC}"
    echo -e "${BLUE}releases/nosafeword-$VERSION_NAME-$VERSION_CODE.apk${NC}"
    echo -e "${BLUE}releases/nosafeword-$VERSION_NAME-$VERSION_CODE.aab${NC}"
else
    echo -e "${RED}Build output files not found. Check for errors.${NC}"
    exit 1
fi

echo -e "${GREEN}Release build process completed successfully.${NC}"
