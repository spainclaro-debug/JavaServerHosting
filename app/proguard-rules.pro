# Keep line numbers for console post-mortem diagnostics.
-keepattributes SourceFile,LineNumberTable

# Compose / Kotlin serialization are handled by their consumer rules.
# The app does not reflectively load its own classes, so no extra keep rules.
