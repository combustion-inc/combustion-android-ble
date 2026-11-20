# Consumer R8/ProGuard rules for combustion-android-ble -- bundled into the AAR and applied
# automatically to any app that minifies with this library.
#
# Intentionally empty (no rules): this library has no reflection (Class.forName, getDeclared*,
# kotlin-reflect), JNI, Java serialization, or class names used as data, and:
# - CombustionService and DfuService are referenced from this library's AndroidManifest.xml, which
#   AAPT turns into keep rules automatically.
# - The @Serializable types (CombustionProductType, DfuProductType) are covered by the rules that
#   kotlinx-serialization ships in its own AAR.
# - Nordic DFU and coroutines ship their own consumer rules. Kable ships none and needs none: its
#   only reflection targets Android's own BluetoothGatt hidden APIs, which R8 doesn't rename.
#
# Side effect to be aware of: LOG_TAG is built from javaClass.simpleName, so in a minified app
# logcat tags read "Combustion.<obfuscated>" (log messages themselves are unchanged). The same goes
# for the ::class.simpleName prefix in the advertising-data toString() output. Stack traces retrace
# via the app's mapping file; logcat tags and toString() text don't. Not kept on purpose:
# -keepnames would give readable names but cost some of the shrinking.
#
# Add a rule here -- not in the consuming app -- whenever this library starts relying on
# something R8 can't see statically (reflection, JNI entry points, names looked up as strings).
