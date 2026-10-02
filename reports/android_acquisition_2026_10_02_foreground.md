# Android foreground acquisition spot check — 2026-10-02

## Scope and safety boundary

This is a short live acquisition spot check, not a replacement for the >=30-minute stationary endurance acceptance in [ACQUISITION.md](../mobile/ACQUISITION.md). It covers only the current locally built/installed Android app on one attached phone. The user authorized bringing the app forward and starting/stopping a non-recording sensor session. No APK was installed, no app data was cleared, no permission was changed, and no recording, replay, export or upload was started. The session was explicitly stopped after observation.

The current checkout contains numerous pre-existing modified and untracked files; no source files were edited for this spot check. The existing debug APK was byte-identical to the installed package before launch (SHA-256 `cdef05b78dded570c5456e523d37894c78f8e1e1f81b47fc329eb5b2627564f5`).

## Device and app

- Device: OnePlus 12R, model CPH2585 / OP5D35L1
- Android 16 / API 36
- ADB serial: `5c7d81bb` (authorized)
- Package: `com.intelligentdeadreckoning.app`, version `0.1.0-demo`, versionCode 1
- App APK last updated 2026-10-02 13:08:18 (device local time); APK hash matched checked-out `mobile/app/build/outputs/apk/debug/app-debug.apk`.
- Location access was already precise; it was not changed.
- Device battery showed 32%, USB powered. Thermal status 1 (light); battery temperature about 36.0°C and skin temperature about 40.4–41.5°C in sampled thermal-service readings. No charging state was changed by this run.

## Live sensor observation

The UI was switched from Simulation to Phone sensors, then explicitly started at about 14:20:46 device time and stopped at about 14:30:04 (approximately 9 minutes 18 seconds). The screen showed 4/4 available sensors, `Running`, and navigation not running. Sensor-service registrations for the app used 10,000 µs periods for accelerometer and gyroscope, and 20,000 µs for gravity and magnetometer, with no requested batching. The Diagnostics screen reported:

| Channel | Device sensor / vendor | Requested | Measured during short sample |
|---|---|---:|---:|
| Accelerometer | bmi26x Accelerometer Non-wakeup / BOSCH | 100 Hz | 98.785–98.792 Hz |
| Gyroscope | bmi26x Gyroscope Non-wakeup / BOSCH | 100 Hz | 98.785–98.792 Hz |
| Gravity | gravity Non-wakeup / QTI | 50 Hz | 49.394–49.396 Hz |
| Magnetometer | mmc56x3x Magnetometer Non-wakeup / memsic | 50 Hz | 50.000 Hz |

The user confirmed the phone was stationary before starting; it remained undisturbed during acquisition. Gravity was about +9.806 m/s² on Z; accelerometer values were near gravity at rest; gyro values were near zero. Accuracy showed high for accel/gyro/gravity and medium for magnetometer.

At the explicit-stop observation, capture counters showed 146,707 accepted, 1 delayed, 0 duplicates, 0 dropped, 0 invalid; queue depth 0/256, peak 24. Sensor event rates remained close to target near the end of this run (accelerometer/gyro 98.792 Hz, gravity 49.396 Hz, magnetometer 50.000 Hz). Earlier snapshots showed 78,262 accepted and 73,060 accepted across separate visits to Dashboard/Signals while that same UI still reported Running. The lower later value suggests the live snapshot/counter may have reset or the view was stale during navigation; therefore counts across views were inconsistent and require a focused follow-up before they can be used as run totals. The session lasted less than 10 minutes, not the 30-minute endurance interval.

Precise permission was already granted, but only a network-provider location was observed in this indoor session: measured rate 0.050 Hz, stale during some readings, horizontal accuracy 100 m, unavailable speed and bearing, satellites unavailable. This is not evidence of GPS-provider behavior or good GNSS. TIME_GAP diagnostics appeared, consistent with the slower location channel. No navigation solution was active.

Displayed event/receipt timestamps were monotonic nanoseconds. In sampled records, receipt followed event time (for example accelerometer event `714119058539515`, receipt `714119060762574`). These are samples, not a latency distribution; no p50/p95 sensor-to-navigation or sensor-to-screen latency was measured.

## Resource observations

Short-run app TOTAL PSS readings during acquisition varied from 158,795 to 160,965 kB across three samples about five seconds apart; RSS was 322,652–324,804 kB. An earlier launch sample was 139,483 kB; after Stop it was 162,093 kB. These brief snapshots cannot establish memory stability or leak absence. Battery percentage remained 32% over the few-minute observation while USB powered, so battery drain was not measurable. The device stayed at thermal status 1 (light) during sampled observations.

## Stop and listener cleanup

The Stop button was tapped on the Signals screen while the phone remained stationary. The UI then reported `Stopped` and `Stopped. Last samples retained; Start creates a new session.` Sensor-service entries changed to `samplingPeriod=N/A` / `batchingPeriod=N/A` for all four app sensors, with removal timestamps at about 14:30:04 device time; this confirms sensor unregistration for this run. Location history showed a matching `removeLocation` registration at 14:30:04.511, following the session's `addLocation` at 14:20:46.939; this confirms location-listener cleanup. The stale measurement snapshot remained visible, as the acquisition protocol permits. IDR Demo remained foregrounded, not running acquisition; the app was not force-stopped or backgrounded.

## Result and limits

**Spot check: partial pass for live sensor presence, expected requested/measured cadence, and listener cleanup; not endurance-qualified.** This confirms that this installed build receives real IMU streams on this OnePlus and that rates are near the requested rates in a short stationary observation. It does not establish comparable long-run counters, sustained queue behavior, stable memory, battery cost, p50/p95 latency, navigation stability, or GPS quality. No changes to acquisition instrumentation were justified by this short run.

The historical >=30-minute acceptance in [android_acquisition_2026_09_19.md](android_acquisition_2026_09_19.md) remains historical evidence for that session/build only. Do not resume edge qualification on the basis of this spot check; the requested prerequisite remains a fresh, stable, foreground run of at least 30 minutes with reconciled counters, resource samples, and confirmed cleanup.
