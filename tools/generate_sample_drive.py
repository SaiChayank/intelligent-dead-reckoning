"""Generate a realistic, contract-valid sample driving recording in Hyderabad.

This generates a 70-second calibrated driving run around central Hyderabad:
- 0 to 4 s: Stationary parked (gravity acquisition & gyro bias estimation).
- 4 to 16 s: Straight acceleration North to 12 m/s (~43 km/h).
  - Triggers MountEstimator Stage B to lock vehicle yaw (status -> VALID).
  - Next fix triggers headingFromMotion -> FusionNavigationEngine activates (FUSED mode)!
- 16 to 24 s: Straight cruise North at 12 m/s.
- 24 to 32 s: 92-degree smooth left turn West at 12 m/s.
- 32 to 52 s: GNSS OUTAGE (Underpass / Tunnel) for 20 seconds.
  - Zero GNSS fixes; fusion engine coasts on Dead Reckoning (DEAD_RECKONING mode).
- 52 to 62 s: GNSS RECOVERY. Returning fixes re-converge smoothly.
- 62 to 68 s: Deceleration to stop.
- 68 to 70 s: Parked. ZUPT active.
"""
from __future__ import annotations

import json
import math
from pathlib import Path

# Hyderabad Anchor (near Secretariat / Hussain Sagar)
ANCHOR_LAT = 17.4350
ANCHOR_LON = 78.4450
ANCHOR_ALT = 505.0
GRAVITY = 9.80665

# WGS-84 degree conversion at Hyderabad latitude
METRES_PER_DEG_LAT = 110660.0
METRES_PER_DEG_LON = 106200.0


def generate_drive(out_dir: Path) -> dict:
    out_dir.mkdir(parents=True, exist_ok=True)
    session_id = "hyderabad-demo-drive"
    acq_id = "hyderabad-demo-acquisition"

    origin_ns = 1_000_000_000  # 1.0 s
    total_s = 70.0
    dt_imu = 0.01  # 100 Hz
    dt_gnss = 1.0  # 1 Hz

    records = []
    event_id = 0

    cur_lat = ANCHOR_LAT
    cur_lon = ANCHOR_LON
    cur_speed = 0.0
    cur_heading_deg = 0.0  # North

    imu_count = 0
    gnss_count = 0

    step_count = int(total_s / dt_imu)
    next_gnss_t = 0.0

    for step in range(step_count + 1):
        t = round(step * dt_imu, 4)
        t_ns = origin_ns + int(t * 1_000_000_000)

        # Profile definition
        if t < 4.0:
            # Parked
            cur_speed = 0.0
            accel_fwd = 0.0
            yaw_rate = 0.0
        elif t < 16.0:
            # Straight acceleration North
            accel_fwd = 1.0
            cur_speed = min(12.0, (t - 4.0) * 1.0)
            yaw_rate = 0.0
            cur_heading_deg = 0.0
        elif t < 24.0:
            # Straight cruise North
            accel_fwd = 0.0
            cur_speed = 12.0
            yaw_rate = 0.0
            cur_heading_deg = 0.0
        elif t < 32.0:
            # 8 s Left Turn West: turns from 0 deg (North) to 270 deg (West)
            # CCW turn in ENU: dHeading/dt = +92 deg / 8 s = +11.5 deg/s = +0.2007 rad/s
            accel_fwd = 0.0
            cur_speed = 12.0
            yaw_rate = 0.2007  # rad/s
            turn_fraction = (t - 24.0) / 8.0
            cur_heading_deg = (360.0 - turn_fraction * 90.0) % 360.0
        elif t < 52.0:
            # GNSS Outage: 20 s cruise West at 12 m/s
            accel_fwd = 0.0
            cur_speed = 12.0
            yaw_rate = 0.0
            cur_heading_deg = 270.0
        elif t < 62.0:
            # Recovery cruise West at 12 m/s
            accel_fwd = 0.0
            cur_speed = 12.0
            yaw_rate = 0.0
            cur_heading_deg = 270.0
        elif t < 68.0:
            # Decelerate to 0 m/s
            accel_fwd = -2.0
            cur_speed = max(0.0, 12.0 - (t - 62.0) * 2.0)
            yaw_rate = 0.0
            cur_heading_deg = 270.0
        else:
            # Parked at finish
            accel_fwd = 0.0
            cur_speed = 0.0
            yaw_rate = 0.0
            cur_heading_deg = 270.0

        # Update position
        dist = cur_speed * dt_imu
        heading_rad = math.radians(cur_heading_deg)
        d_north = dist * math.cos(heading_rad)
        d_east = dist * math.sin(heading_rad)
        cur_lat += d_north / METRES_PER_DEG_LAT
        cur_lon += d_east / METRES_PER_DEG_LON

        # Device IMU (assuming device frame = vehicle frame: X fwd, Y left, Z up)
        # Specific force: f = a - g = [accel_fwd, centripetal_left, +9.80665]
        centripetal_left = cur_speed * yaw_rate
        accel_xyz = [round(accel_fwd, 6), round(centripetal_left, 6), round(GRAVITY, 6)]
        gyro_xyz = [0.0, 0.0, round(yaw_rate, 6)]

        # Emit Accelerometer
        records.append({
            "contract_version": "1.0.0",
            "session_id": acq_id,
            "source": "simulation",
            "event": {
                "event_id": str(event_id),
                "type": "imu",
                "t_ns": str(t_ns),
                "received_ns": str(t_ns + 500_000),
                "data": {
                    "sensor": "accelerometer",
                    "frame": "android_device",
                    "unit": "m/s^2",
                    "xyz": accel_xyz,
                    "accuracy": "high",
                },
            },
        })
        event_id += 1
        imu_count += 1

        # Emit Gyroscope
        records.append({
            "contract_version": "1.0.0",
            "session_id": acq_id,
            "source": "simulation",
            "event": {
                "event_id": str(event_id),
                "type": "imu",
                "t_ns": str(t_ns),
                "received_ns": str(t_ns + 600_000),
                "data": {
                    "sensor": "gyroscope",
                    "frame": "android_device",
                    "unit": "rad/s",
                    "xyz": gyro_xyz,
                    "accuracy": "high",
                },
            },
        })
        event_id += 1
        imu_count += 1

        # GNSS fix at 1 Hz, suppressed during outage (32.0 s to 51.99 s)
        in_outage = (32.0 <= t < 52.0)
        if t >= next_gnss_t:
            next_gnss_t += dt_gnss
            if not in_outage:
                records.append({
                    "contract_version": "1.0.0",
                    "session_id": acq_id,
                    "source": "simulation",
                    "event": {
                        "event_id": str(event_id),
                        "type": "gnss",
                        "t_ns": str(t_ns),
                        "received_ns": str(t_ns + 5_000_000),
                        "data": {
                            "latitude_deg": round(cur_lat, 8),
                            "longitude_deg": round(cur_lon, 8),
                            "altitude_m": ANCHOR_ALT,
                            "altitude_reference": "ellipsoid",
                            "speed_m_s": round(cur_speed, 3),
                            "bearing_deg": round(cur_heading_deg, 2),
                            "horizontal_accuracy_m": 2.5,
                            "vertical_accuracy_m": 5.0,
                            "satellites_used": 18,
                            "provider": "gps",
                            "utc_ms": None,
                        },
                    },
                })
                event_id += 1
                gnss_count += 1

    # Write measurements.jsonl and gzipped asset version
    measurements_file = out_dir / "measurements.jsonl"
    with open(measurements_file, "w", encoding="utf-8") as f:
        for r in records:
            f.write(json.dumps(r, separators=(",", ":")) + "\n")

    import gzip
    gz_file = out_dir / "measurements.jsonl.gz"
    with open(measurements_file, "rb") as f_in, gzip.open(gz_file, "wb", compresslevel=9) as f_out:
        f_out.write(f_in.read())
    # Keep only the gzipped asset in source control / apk assets
    measurements_file.unlink()

    ended_ns = max(int(r["event"]["received_ns"]) for r in records)

    # Write metadata.json
    metadata = {
        "recording_contract_version": "1.0.0",
        "measurement_contract_version": "1.0.0",
        "recording_id": session_id,
        "acquisition_session_id": acq_id,
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
            "session_clock_id": "hyderabad-demo-clock-v1",
            "origin_ns": str(origin_ns),
            "started_ns": str(origin_ns),
            "ended_ns": str(ended_ns),
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
        "record_count": str(len(records)),
        "channel_counts": [
            {"channel": "imu", "count": str(imu_count)},
            {"channel": "gnss", "count": str(gnss_count)},
        ],
    }

    metadata_file = out_dir / "metadata.json"
    with open(metadata_file, "w", encoding="utf-8") as f:
        json.dump(metadata, f, indent=2)

    return {
        "records": len(records),
        "imu": imu_count,
        "gnss": gnss_count,
        "measurements_file": str(measurements_file),
        "metadata_file": str(metadata_file),
    }


if __name__ == "__main__":
    target = Path("mobile/app/src/main/assets/sample_drive/hyderabad-demo-drive")
    result = generate_drive(target)
    print(f"Generated sample drive: {result}")
