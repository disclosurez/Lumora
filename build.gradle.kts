plugins {
    id("com.android.application") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    id("com.google.devtools.ksp") version "1.9.22-1.0.17" apply false
    // Both are here for com.lumora.scraper only: its Video.Type models are @Parcelize sealed
    // classes and several site providers deserialise their JSON APIs with kotlinx.serialization.
    id("org.jetbrains.kotlin.plugin.parcelize") version "1.9.22" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.22" apply false
}
