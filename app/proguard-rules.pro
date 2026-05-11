# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in the SDK tools.

# Keep file I/O classes for Agent communication
-keep class com.openclaw.car.util.FileHelper { *; }
-keep class com.openclaw.car.util.PreferenceHelper { *; }
