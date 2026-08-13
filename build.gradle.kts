buildscript {
    dependencies {
        // GPlayApi 3.6.4 is built with a newer Kotlin compiler than AGP's default.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
    }
}

plugins {
    id("com.android.application") version "9.3.1" apply false
}
