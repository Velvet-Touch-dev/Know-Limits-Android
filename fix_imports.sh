#!/bin/bash

# Find all Kotlin files
find app/src -name "*.kt" -type f | while read file; do
  echo "Processing $file"
  # Replace the imports for R
  sed -i 's/import com.velvettouch.nosafeword.R/import com.velvettouch.nosafeword.R/g' "$file"
  
  # Check for files that use R without an explicit import and add the import
  if grep -q "R\." "$file" && ! grep -q "import.*\.R" "$file"; then
    echo "Adding R import to $file"
    sed -i '1s/^/import com.velvettouch.nosafeword.R\n/' "$file"
  fi
done

echo "Done updating R imports"
