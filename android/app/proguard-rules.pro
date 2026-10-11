# kotlinx.serialization ships its own R8 rules; the wire models and the
# @Serializable navigation keys are covered by them.

# ML Kit (the QR code scanner) finds its components by reflection, through
# their no-argument constructors.
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }
-keep class com.google.mlkit.**.*Registrar { <init>(); }
