plugins {
    kotlin("jvm") version "2.2.10"
}

repositories {
    mavenCentral()
    google()
}

kotlin {
    // Use the installed Android Studio JBR so offline builds need no toolchain download.
    jvmToolchain(25)
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    sourceSets.main {
        kotlin.setSrcDirs(listOf("../contracts/v1/kotlin", "../mobile/app/src/main/java", "src/main/kotlin"))
        kotlin.include(
            "**/contracts/v1/**",
            "**/app/fusion/**",
            "**/app/calibration/Rotations.kt",
            "**/app/acquisition/GnssQuality.kt",
            "**/app/acquisition/Acquisition.kt",
            "**/app/navigation/NavigationRuntime.kt",
            "**/app/navigation/UninitializedNavigationEngine.kt",
            "**/edge/**",
        )
    }
    sourceSets.test {
        kotlin.srcDir("src/test/kotlin")
    }
}

tasks.withType<JavaCompile>().configureEach {
    sourceCompatibility = "17"
    targetCompatibility = "17"
}

dependencies {
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
}

// The standalone edge target compiles the exact production mobile engine and typed contract,
// rather than maintaining a divergent Python/Kotlin navigation implementation.
tasks.register<JavaExec>("runEdgeBenchmark") {
    group = "benchmark"
    description = "Run the edge runtime against a real-time timestamped contract JSONL stream."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.intelligentdeadreckoning.edge.BenchmarkHarnessKt")
    args(
        providers.gradleProperty("input").orElse("").get(),
        providers.gradleProperty("output").orElse("").get(),
        providers.gradleProperty("reference").orElse("").get(),
    )
}
