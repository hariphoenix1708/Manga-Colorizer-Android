# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in C:\Users\hariv\AppData\Local\Android\sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.kts.

# ONNX Runtime rules
-keep class ai.onnxruntime.** { *; }

# Coil rules
-keep class coil.** { *; }
