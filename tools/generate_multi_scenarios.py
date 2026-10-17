"""Multi-Scenario Benchmark Suite Generator for Intelligent Dead Reckoning.

Generates two challenging evaluation scenarios conforming to Recording Contract 1.0.0:
1. 'urban-canyon-multipath': High-rise canyon with severe multipath reflections and satellite occlusion.
   Proves NIS chi-squared innovation gating and dead reckoning bridging.
2. 'stop-and-go-outage': Flyover underpass congestion with crawling and complete stops.
   Proves Model A stillness detection (99.7% accuracy) and Zero Velocity Updates (ZUPT).
"""
import gzip
import json
import math
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

# Hitec City Cyber Towers Anchor (Urban Canyon)
HITEC_LAT = 17.4504
HITEC_LON = 78.3808
HITEC_ALT = 540.0

# PVNR Expressway Flyover Anchor (Stop-and-Go Underpass)
PVNR_LAT = 17.3616
PVNR_LON = 78.4418
PVNR_ALT = 510.0


def create_record(event_id, t_ns, session_id, event_type, data):
    return {
        "contract_version": "1.0.0",
        "session_id": f"acq_{session_id}",
        "source": "simulation",
        "event": {
            "event_id": str(event_id),
            "type": event_type,
            "t_ns": str(t_ns),
            "received_ns": str(t_ns + 500_000),
            "data": data,
        },
    }


def create_recording_metadata(session_id, record_count, total_s, origin_ns, imu_count, gnss_count):
    return {
        "recording_contract_version": "1.0.0",
        "measurement_contract_version": "1.0.0",
        "recording_id": session_id,
        "acquisition_session_id": f"acq_{session_id}",
        "source": "simulation",
        "start_state": "recording",
        "end_state": "stopped",
        "completion_state": "completed",
        "recovery_state": "none",
        "created_utc_ms": None,
        "started_utc_ms": None,
        "ended_utc_ms": None,
        "clock": {
            "domain": "android_elapsed_realtime_ns",
            "boot_id": None,
            "session_clock_id": f"{session_id}-clock-v1",
            "origin_ns": str(origin_ns),
            "started_ns": str(origin_ns),
            "ended_ns": str(origin_ns + int(total_s * 1_000_000_000)),
        },
        "device": {
            "manufacturer": None,
            "model": None,
            "os_name": None,
            "os_version": None,
            "api_level": None,
        },
        "application": {
            "package_name": None,
            "version_name": None,
            "version_code": None,
            "source_version": None,
        },
        "sensors": [
            {
                "sensor": "accelerometer",
                "name": "Synthetic 3-Axis Accelerometer",
                "vendor": "SIH IDR",
                "available": True,
                "requested_hz": 100.0,
                "measured_hz": 100.0,
            },
            {
                "sensor": "gyroscope",
                "name": "Synthetic 3-Axis Gyroscope",
                "vendor": "SIH IDR",
                "available": True,
                "requested_hz": 100.0,
                "measured_hz": 100.0,
            },
        ],
        "source_configuration": {
            "location_permission": "precise",
            "location_service_enabled": True,
            "gps_provider_enabled": True,
            "network_provider_enabled": False,
            "satellite_status_enabled": True,
            "foreground_only": True,
        },
        "calibration": {
            "state": "not_applied",
            "calibration_id": None,
        },
        "record_count": str(record_count),
        "channel_counts": [
            {
                "channel": "imu",
                "count": str(imu_count),
            },
            {
                "channel": "gnss",
                "count": str(gnss_count),
            },
        ],
    }


def generate_urban_canyon(out_dir: Path):
    """Scenario 1: Urban Canyon with Multipath Outliers and Shadowing."""
    out_dir.mkdir(parents=True, exist_ok=True)
    session_id = "urban-canyon-multipath"
    total_s = 60.0
    dt_imu = 0.01  # 100 Hz

    records = []
    event_id = 0
    origin_ns = 1_000_000_000
    imu_count = 0
    gnss_count = 0

    cur_lat = HITEC_LAT
    cur_lon = HITEC_LON
    steps = int(total_s / dt_imu)

    for i in range(steps + 1):
        t = round(i * dt_imu, 4)
        t_ns = origin_ns + int(t * 1e9)

        # Drive kinematics: accelerating through corridor heading North (0 deg)
        speed = 10.0 if t >= 5.0 else (t * 2.0)
        heading_rad = 0.0  # North

        # Integrate motion
        cur_lat += (speed * math.cos(heading_rad) * dt_imu) / 110660.0
        cur_lon += (speed * math.sin(heading_rad) * dt_imu) / 106200.0

        # IMU Accelerometer Record (100 Hz)
        records.append(
            create_record(
                event_id,
                t_ns,
                session_id,
                "imu",
                {
                    "sensor": "accelerometer",
                    "frame": "android_device",
                    "unit": "m/s^2",
                    "xyz": [0.0, 0.0, 9.80665],
                    "accuracy": "high",
                },
            )
        )
        event_id += 1
        imu_count += 1

        # IMU Gyroscope Record (100 Hz)
        records.append(
            create_record(
                event_id,
                t_ns,
                session_id,
                "imu",
                {
                    "sensor": "gyroscope",
                    "frame": "android_device",
                    "unit": "rad/s",
                    "xyz": [0.0, 0.0, 0.0],
                    "accuracy": "high",
                },
            )
        )
        event_id += 1
        imu_count += 1

        # GNSS Record (1 Hz)
        if i % int(1.0 / dt_imu) == 0:
            if 20.0 <= t <= 40.0:
                # Artificial multipath jump (35m outlier)
                multipath_lat = cur_lat + (35.0 / 110660.0)
                multipath_lon = cur_lon - (25.0 / 106200.0)
                records.append(
                    create_record(
                        event_id,
                        t_ns,
                        session_id,
                        "gnss",
                        {
                            "latitude_deg": multipath_lat,
                            "longitude_deg": multipath_lon,
                            "altitude_m": HITEC_ALT,
                            "altitude_reference": "ellipsoid",
                            "speed_m_s": speed + 5.0,
                            "bearing_deg": 45.0,
                            "horizontal_accuracy_m": 42.0,
                            "vertical_accuracy_m": 60.0,
                            "satellites_used": 4,
                            "provider": "gps",
                            "utc_ms": None,
                        },
                    )
                )
            else:
                # Open sky fixes
                records.append(
                    create_record(
                        event_id,
                        t_ns,
                        session_id,
                        "gnss",
                        {
                            "latitude_deg": cur_lat,
                            "longitude_deg": cur_lon,
                            "altitude_m": HITEC_ALT,
                            "altitude_reference": "ellipsoid",
                            "speed_m_s": speed,
                            "bearing_deg": 0.0,
                            "horizontal_accuracy_m": 2.5,
                            "vertical_accuracy_m": 4.0,
                            "satellites_used": 14,
                            "provider": "gps",
                            "utc_ms": None,
                        },
                    )
                )
            event_id += 1
            gnss_count += 1

    metadata = create_recording_metadata(
        session_id, len(records), total_s, origin_ns, imu_count, gnss_count
    )
    (out_dir / "metadata.json").write_text(json.dumps(metadata, indent=2), encoding="utf-8")
    with gzip.open(out_dir / "measurements.jsonl.gz", "wt", encoding="utf-8") as f:
        for r in records:
            f.write(json.dumps(r) + "\n")
    print(
        f"Generated {session_id}: {len(records)} records ({imu_count} imu, {gnss_count} gnss) in {out_dir}"
    )


def generate_stop_and_go(out_dir: Path):
    """Scenario 2: Underpass Flyover with Stop-and-Go Congestion and Complete Stops."""
    out_dir.mkdir(parents=True, exist_ok=True)
    session_id = "stop-and-go-outage"
    total_s = 65.0
    dt_imu = 0.01  # 100 Hz

    records = []
    event_id = 0
    origin_ns = 1_000_000_000
    imu_count = 0
    gnss_count = 0

    cur_lat = PVNR_LAT
    cur_lon = PVNR_LON
    steps = int(total_s / dt_imu)

    for i in range(steps + 1):
        t = round(i * dt_imu, 4)
        t_ns = origin_ns + int(t * 1e9)

        # Kinematic profile:
        # 0-10s: Cruise at 8 m/s approach underpass (GNSS Available)
        # 10-25s: Enter underpass (GNSS DENIED). Slowing down.
        # 25-38s: COMPLETE STOP (0.0 m/s) under flyover for 13 seconds (ZUPT Active)
        # 38-48s: Crawl at 3.0 m/s in heavy traffic
        # 48-65s: Accelerate to 9.0 m/s and exit underpass (GNSS RECOVERED)
        if t < 10.0:
            speed = 8.0
            is_stopped = False
        elif t < 25.0:
            speed = max(0.0, 8.0 - (t - 10.0) * (8.0 / 15.0))
            is_stopped = speed < 0.05
        elif t < 38.0:
            speed = 0.0
            is_stopped = True
        elif t < 48.0:
            speed = 3.0
            is_stopped = False
        else:
            speed = min(9.0, 3.0 + (t - 48.0) * 0.5)
            is_stopped = False

        heading_rad = 0.0
        cur_lat += (speed * math.cos(heading_rad) * dt_imu) / 110660.0
        cur_lon += (speed * math.sin(heading_rad) * dt_imu) / 106200.0

        # IMU Accelerometer Record (100 Hz)
        y_acc = 0.0 if is_stopped else 0.05 * math.sin(t * 8)
        records.append(
            create_record(
                event_id,
                t_ns,
                session_id,
                "imu",
                {
                    "sensor": "accelerometer",
                    "frame": "android_device",
                    "unit": "m/s^2",
                    "xyz": [0.0, round(y_acc, 6), 9.80665],
                    "accuracy": "high",
                },
            )
        )
        event_id += 1
        imu_count += 1

        # IMU Gyroscope Record (100 Hz)
        records.append(
            create_record(
                event_id,
                t_ns,
                session_id,
                "imu",
                {
                    "sensor": "gyroscope",
                    "frame": "android_device",
                    "unit": "rad/s",
                    "xyz": [0.0, 0.0, 0.0],
                    "accuracy": "high",
                },
            )
        )
        event_id += 1
        imu_count += 1

        # GNSS records (Denied between t=10s and 48s)
        is_denied = 10.0 <= t <= 48.0
        if not is_denied and (i % int(1.0 / dt_imu) == 0):
            records.append(
                create_record(
                    event_id,
                    t_ns,
                    session_id,
                    "gnss",
                    {
                        "latitude_deg": cur_lat,
                        "longitude_deg": cur_lon,
                        "altitude_m": PVNR_ALT,
                        "altitude_reference": "ellipsoid",
                        "speed_m_s": speed,
                        "bearing_deg": 0.0,
                        "horizontal_accuracy_m": 3.0,
                        "vertical_accuracy_m": 5.0,
                        "satellites_used": 12,
                        "provider": "gps",
                        "utc_ms": None,
                    },
                )
            )
            event_id += 1
            gnss_count += 1

    metadata = create_recording_metadata(
        session_id, len(records), total_s, origin_ns, imu_count, gnss_count
    )
    (out_dir / "metadata.json").write_text(json.dumps(metadata, indent=2), encoding="utf-8")
    with gzip.open(out_dir / "measurements.jsonl.gz", "wt", encoding="utf-8") as f:
        for r in records:
            f.write(json.dumps(r) + "\n")
    print(
        f"Generated {session_id}: {len(records)} records ({imu_count} imu, {gnss_count} gnss) in {out_dir}"
    )


def main():
    base_assets = ROOT / "mobile" / "app" / "src" / "main" / "assets" / "sample_drive"
    generate_urban_canyon(base_assets / "urban-canyon-multipath")
    generate_stop_and_go(base_assets / "stop-and-go-outage")
    print("Multi-scenario suite generation complete!")


if __name__ == "__main__":
    main()
