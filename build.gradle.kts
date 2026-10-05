// The root project builds nothing itself. The library lives in the trace module.
plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
}
