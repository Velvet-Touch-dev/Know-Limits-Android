#!/bin/bash

# Update imports in all Kotlin files
find . -name "*.kt" | xargs sed -i 's/import com.velvettouch.nosafeword.R/import com.velvettouch.nosafeword.R/g'

# Add import to files that use R without importing it
find . -name "*.kt" | xargs grep -l "R\\." | xargs grep -L "import.*\\.R" | xargs -I{} sed -i '1s/^/import com.velvettouch.nosafeword.R;\n/' {}
