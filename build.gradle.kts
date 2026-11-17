// Versiones fijadas aquí (sin catálogo de versiones, para que sea fácil de leer).
plugins {
    id("com.android.application") version "9.5.1" apply false
    // AGP 9 compila Kotlin por sí mismo; este plugin solo añade el compilador de Compose.
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
