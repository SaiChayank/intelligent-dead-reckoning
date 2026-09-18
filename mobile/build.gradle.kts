plugins {
    id("com.android.application") version "9.1.1" apply false
    // AGP 9.1.1 supplies Kotlin 2.2.10; the Compose compiler must match it.
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}
