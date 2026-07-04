plugins {
    id("com.android.application") version "8.7.3" apply false
    // Billing KTX and one of its Kotlin transitive artifacts use Kotlin 2.2 metadata.
    // Keep the compiler aligned instead of forcing an incompatible Kotlin stdlib downgrade.
    id("org.jetbrains.kotlin.android") version "2.2.0" apply false
}
