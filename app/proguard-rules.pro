# ML Kit registers its components by reflection (firebase-components). Without these keep rules R8 strips them, the
# registration breaks and the camera crashed on start in the 0.6 test builds. Do not remove; if they are ever
# narrowed, check the result with a release build.
-keep class com.google.mlkit.** { *; }
-keep class com.google.firebase.components.** { *; }
