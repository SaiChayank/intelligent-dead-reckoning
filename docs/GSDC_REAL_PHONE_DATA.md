# Real phone-in-car drives: the Google Smartphone Decimeter Challenge data

This is the project's only evidence from real phones in real cars with a reference-grade truth. It is public data recorded in California, not a drive recorded with this app, so read the limits at the end.

## Licence and citation

The competition data is licensed **CC BY-NC-SA 3.0 IGO** (attribution, non-commercial, share-alike). The raw and converted files are not in this repository (they are large and git-ignored); the code and the small aggregate result files are. Cite:

- Fu, Guoyu (Michael), Khider, Mohammed, Gulati, Vivek, van Diggelen, Frank (2023), *Google Smartphone Decimeter Challenge*, https://kaggle.com/competitions/smartphone-decimeter-2023/data
- Fu, Guoyu (Michael), Khider, Mohammed, van Diggelen, Frank, "Android Raw GNSS Measurement Datasets for Precise Positioning," ION GNSS+ 2020, https://doi.org/10.33012/2020.17628

## Getting the data

A free Kaggle account and acceptance of the competition rules are required, and only you can do that. Join the competition, open the Data tab and download all files (about 3.5 GB zipped, 16 GB unpacked). Then extract only what the evaluation reads (the phone IMU and the reference track of the training trips, about 4 GB) into `data/raw/gsdc2023/`, keeping the layout `train/<drive>/<phone>/device_imu.csv` and `ground_truth.csv`. The test trips have no reference and are not used.

## Reproducing the evaluation

```bash
python tools/gsdc_survey.py                              # rates, durations, distances per trip -> reports/gsdc_survey.json
python tools/gsdc_to_recording.py --split development    # app-format recordings, one phone per drive -> data/processed/gsdc2023/
python tools/gsdc_to_recording.py --split held_out
python tools/gsdc_calibrator_probe.py --split development   # does the app's own mount calibrator lock? (it does not)
python tools/gsdc_outage_eval.py --split development --configs app,app_no_slip_no_ai,ekf_nhc
python tools/gsdc_outage_eval.py --split held_out --configs app,app_no_slip_no_ai,ekf_nhc
python tools/gsdc_compare.py --dir reports/gsdc_eval_held_out --baseline app --candidate app_no_slip_no_ai
python tools/build_claims.py                             # README, report and deck read the result files
```

## What was decided before looking, and what was chosen on the development drives

- The split is a hash of the drive name (`split_of` in `tools/gsdc_to_recording.py`): 27 of 65 drives held out, fixed before any result existed. A drive contributes one phone, the fastest one whose clock aligns, so phones riding in the same car are not counted as separate trips. One held-out drive has no usable phone and is excluded.
- The shipped configuration was changed (AI speed estimator and mount-slip detector off) on the evidence of the 38 development drives, then confirmed once on the 26 held-out drives. Intervals resample whole drives, not outages.

## What the conversion assumes

- The phone IMU is used as recorded (device frame). The gyroscope is the uncalibrated reading minus its logged bias.
- GNSS aiding is the 1 Hz reference track (NovAtel SPAN, 0.1 m) with a **declared** accuracy of 2.5 m and a **declared** satellite count of 12 (the reference has neither; the engine rejects fixes under 4 satellites). The dataset withholds the phone's own GNSS position, so this tests drift with good aiding, not the phone receiver's errors.
- Phone clocks are shifted onto the reference clock by a lag found from gyro norm against reference yaw rate; the median lag is 0 s and trips with a weak match (correlation under 0.5) are skipped.
- The mount is calibrated offline from the first 2 to 8 minutes of each trip (`tools/gsdc_batch_calibration.py`: gravity while stopped, forward from the speed derivative, gyro bias at rest), using only the IMU and the aiding fixes. The app's own in-drive calibrator reached VALID on 0 of 38 development trips under every threshold setting tried, so the app now also runs the same offline method itself (see the next section).

## The app's own calibration (in-engine)

The app now runs a batch mount calibrator (`BatchMountCalibrator.kt`, a Kotlin port of `tools/gsdc_batch_calibration.py`) beside the in-drive one, and uses it when the in-drive calibrator has not locked. Check it with the engine finding its own mount instead of being handed one:

```bash
python tools/gsdc_outage_eval.py --split development --tag inengine --in-engine-calibration --configs app_no_slip_no_ai
python tools/gsdc_outage_eval.py --split held_out --tag inengine --in-engine-calibration --configs app_no_slip_no_ai
```

`summary.json` then carries a parity block: how many trips the Kotlin calibrator calibrated, the largest angle between its mount and the Python one, and when it released (about 0.6 s after the window closes, by design). Result: 38 of 38 development and 26 of 26 held-out trips, largest difference 0.44 degrees, and outage drift in line with the offline-handed runs.

## Replaying real drives through the engine on a phone

`RealDriveEngineDeviceTest` runs the app's own calibrator and fusion configuration on a real drive ON THE DEVICE, forces a 30 s and a 60 s outage and writes a
report. The sensors are replayed, not live. It skips itself when no data is on the phone.

```bash
python tools/gsdc_trim_for_device.py                          # first 700 s of three trips -> data/processed/gsdc2023_device/
bash mobile/gradlew -p mobile installDebug installDebugAndroidTest
adb shell run-as com.intelligentdeadreckoning.app mkdir -p files/real_drives/<trip>
adb push data/processed/gsdc2023_device/<trip>/measurements.jsonl /data/local/tmp/m.jsonl
adb shell run-as com.intelligentdeadreckoning.app cp /data/local/tmp/m.jsonl files/real_drives/<trip>/measurements.jsonl
adb shell am instrument -w -e class com.intelligentdeadreckoning.app.RealDriveEngineDeviceTest \
    com.intelligentdeadreckoning.app.test/androidx.test.runner.AndroidJUnitRunner
adb exec-out run-as com.intelligentdeadreckoning.app cat files/real_drives/report_<trip>.json
```

Use `installDebug installDebugAndroidTest` and `am instrument`, not `connectedDebugAndroidTest`, which uninstalls the app and the pushed data with it. Files in the shared
storage folder are not readable by the app on every Android version; the app's own `files/` folder is.

## Limits

California roads, modern phones, a good reference as aiding, an offline calibration, no drive recorded with this app, and no two-wheelers. 60 s outages are only just under the 10 % target in median (about half are under 10 %).
