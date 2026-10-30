plugins {
    id("org.jetbrains.kotlin.jvm") version "2.2.10"
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
            "**/app/matching/RoadGraph.kt",
            "**/app/matching/MapMatcher.kt",
            "**/app/matching/HmmMapMatcher.kt",
            "**/app/matching/MapMatchTypes.kt",
            "**/app/calibration/**",
            "**/app/acquisition/GnssQuality.kt",
            "**/app/acquisition/Acquisition.kt",
            "**/app/navigation/NavigationRuntime.kt",
            "**/app/navigation/UninitializedNavigationEngine.kt",
            "**/app/navigation/CalibratingFusionEngine.kt",
            "**/edge/**",
        )
        // The shipped Model A weights, byte-identical to the app asset, for the AI arms.
        resources.srcDir("../models")
        resources.include("speed_estimator_weights.json", "speed_estimator_v3_weights.json", "gnss_trust_weights.json")
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
        providers.gradleProperty("profile").orElse("").get(),
        providers.gradleProperty("pace").orElse("").get(),
    )
}

// Replays converted IO-VNBD recordings through the app's calibrating engine; driven by
// tools/iovnbd_outage_eval.py, which writes the jobs file and scores the outputs.
tasks.register<JavaExec>("runIovnbdEval") {
    group = "evaluation"
    description = "Replay IO-VNBD contract recordings through the calibrating engine per arm and outage plan."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.intelligentdeadreckoning.edge.IovnbdEvalKt")
    maxHeapSize = "2g"
    args(
        providers.gradleProperty("jobs").orElse("").get(),
        providers.gradleProperty("results").orElse("").get(),
    )
}
