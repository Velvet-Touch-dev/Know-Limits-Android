#!/bin/bash
# Make script executable
chmod +x "$0"

# Set terminal colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${GREEN}=== No Safe Word App - Release Build Script ===${NC}"
echo -e "${YELLOW}This script will build a release version of your app${NC}"

# Make sure the build-release.sh script is executable
chmod +x scripts/build-release.sh

# Run the build script
./scripts/build-release.sh
