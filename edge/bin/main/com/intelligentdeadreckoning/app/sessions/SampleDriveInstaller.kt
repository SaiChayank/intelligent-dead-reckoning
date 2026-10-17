package com.intelligentdeadreckoning.app.sessions

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream

/**
 * Installs the bundled Hyderabad sample drive into the private recordings directory
 * so evaluators and users can replay and observe the 15-state EKF Dead Reckoning pipeline
 * without requiring a vehicle drive.
 */
object SampleDriveInstaller {
    const val DEMO_SESSION_ID = "hyderabad-demo-drive"
    val ALL_DEMO_SESSIONS = listOf(
        "hyderabad-demo-drive",
        "urban-canyon-multipath",
        "stop-and-go-outage",
    )
    val DEMO_SCENARIOS = listOf(
        DemoScenario(
            id = "hyderabad-demo-drive",
            title = "Highway Tunnel Outage",
            subtitle = "70s Total GNSS Blackout at 60 km/h",
            durationText = "70 sec",
            badge = "Tunnel EKF",
            description = "Simulates entering a long underground tunnel with complete GNSS blackout. Stresses 15-state error-state EKF forward course propagation and gyro bias tracking under Non-Holonomic Constraints (NHC).",
            keyFeatures = listOf(
                "Complete 70s GNSS Outage",
                "15-State EKF with Gyro Bias Estimation",
                "Non-Holonomic Constraints (NHC)"
            )
        ),
        DemoScenario(
            id = "urban-canyon-multipath",
            title = "Urban Canyon Multipath",
            subtitle = "Hitec City Multipath Jumps & Shadowing",
            durationText = "60 sec",
            badge = "Multipath Rejection",
            description = "Simulates severe GPS multipath reflections and satellite occlusion among high-rise glass towers. Evaluates Normalized Innovation Squared (NIS) 3-sigma gating to reject spurious jumps.",
            keyFeatures = listOf(
                "3-Sigma NIS Innovation Gating",
                "Rejection of 20-50m Multipath Jumps",
                "Adaptive Process Noise Inflation"
            )
        ),
        DemoScenario(
            id = "stop-and-go-outage",
            title = "Stop & Go Underpass",
            subtitle = "PVNR Flyover Traffic Jam with 13s Full Stop",
            durationText = "65 sec",
            badge = "ZUPT Verification",
            description = "Simulates traffic jams under elevated metro pillars with a 38s GNSS blackout including a 13s complete stationary stop. Evaluates Zero Velocity Updates (ZUPT) preventing runaway accelerometer integration drift.",
            keyFeatures = listOf(
                "Dual-Axis Accelerometer Stationarity Check",
                "Zero Velocity Update (ZUPT) Drift Arrest",
                "Zero Accel Integration Runaway"
            )
        )
    )

    private const val BASE_ASSET_DIR = "sample_drive"

    fun getScenario(id: String): DemoScenario? = DEMO_SCENARIOS.find { it.id == id }

    /**
     * Unpacks all bundled asset fixtures into [recordingsRoot] if not already present.
     * Thread-safe and runs on an I/O dispatcher.
     */
    fun installIfMissing(context: Context, recordingsRoot: File): Boolean {
        var installedAny = false
        for (sessionId in ALL_DEMO_SESSIONS) {
            val targetDir = File(recordingsRoot, sessionId)
            val metadataFile = File(targetDir, "metadata.json")
            val measurementsFile = File(targetDir, "measurements.jsonl")

            if (!(targetDir.exists() && metadataFile.exists() && measurementsFile.exists() && measurementsFile.length() > 0)) {
                try {
                    installSession(context, recordingsRoot, sessionId)
                    installedAny = true
                } catch (_: Exception) {
                    // Ignore failure per-session
                }
            }
        }
        return installedAny
    }

    /**
     * Re-installs or overwrites a single sample drive fixture.
     */
    fun installSession(context: Context, recordingsRoot: File, sessionId: String): Boolean {
        val assetDir = "$BASE_ASSET_DIR/$sessionId"
        val targetDir = File(recordingsRoot, sessionId)
        targetDir.mkdirs()

        val metadataFile = File(targetDir, "metadata.json")
        val tempMeta = File(targetDir, "metadata.json.tmp")
        val measurementsFile = File(targetDir, "measurements.jsonl")
        val tempMeasurements = File(targetDir, "measurements.jsonl.tmp")

        // 1. Copy metadata.json
        context.assets.open("$assetDir/metadata.json").use { input ->
            FileOutputStream(tempMeta).use { output ->
                input.copyTo(output)
            }
        }

        // 2. Decompress measurements.jsonl.gz -> measurements.jsonl
        context.assets.open("$assetDir/measurements.jsonl.gz").use { input ->
            GZIPInputStream(input).use { gzInput ->
                FileOutputStream(tempMeasurements).use { output ->
                    gzInput.copyTo(output)
                }
            }
        }

        if (!tempMeta.renameTo(metadataFile)) {
            tempMeta.copyTo(metadataFile, overwrite = true)
            tempMeta.delete()
        }

        if (!tempMeasurements.renameTo(measurementsFile)) {
            tempMeasurements.copyTo(measurementsFile, overwrite = true)
            tempMeasurements.delete()
        }

        // 3. Copy evaluation_report.json into evaluation directory if available
        try {
            val evalDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "evaluation")
            evalDir.mkdirs()
            val evalName = if (sessionId == DEMO_SESSION_ID) "hyderabad_ai_evaluation.json" else "${sessionId}_evaluation.json"
            val evalTarget = File(evalDir, evalName)
            context.assets.open("$assetDir/evaluation_report.json").use { input ->
                FileOutputStream(evalTarget).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (_: Exception) {
            // Non-fatal
        }

        return true
    }

    /**
     * Re-installs or overwrites the primary demo drive fixture.
     */
    fun forceInstall(context: Context, recordingsRoot: File): Boolean {
        return installSession(context, recordingsRoot, DEMO_SESSION_ID)
    }

    /**
     * Re-installs or overwrites all bundled benchmark drive fixtures.
     */
    fun forceInstallAll(context: Context, recordingsRoot: File): Boolean {
        var allSuccess = true
        for (sessionId in ALL_DEMO_SESSIONS) {
            try {
                if (!installSession(context, recordingsRoot, sessionId)) {
                    allSuccess = false
                }
            } catch (_: Exception) {
                allSuccess = false
            }
        }
        return allSuccess
    }
}

/**
 * Metadata and technical challenge description for a pre-recorded benchmark driving scenario.
 */
data class DemoScenario(
    val id: String,
    val title: String,
    val subtitle: String,
    val durationText: String,
    val badge: String,
    val description: String,
    val keyFeatures: List<String>,
)
