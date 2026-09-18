# IDR Phase 1B — Body-Frame Diagnostic

Window: 60.000 s
Rows: 601

## Best joint candidate

- Mapping: `Vx(forward)=-X | Vy(lateral)=-Z | Vz(up)=+Y`
- Joint score: `0.294200`
- det(M): `-1`
- Gravity alignment: `-0.001869`
- Gyro yaw correlation: `+0.970504`
- Gyro yaw slope: `+0.989186`
- Gyro yaw normalized RMSE: `0.248503`
- Best diagnostic lag: `-5` samples
- Best horizontal yaw: `-99.157963` deg
- Longitudinal correlation: `+0.283250`
- Lateral correlation: `+0.325179`
- Acceleration normalized RMSE: `2.890908`

## Independent searches

- Best gyro-only: `Vx(forward)=-X | Vy(lateral)=-Z | Vz(up)=+Y`
- Best acceleration-only: `Vx(forward)=+X | Vy(lateral)=+Z | Vz(up)=-Y`

The independent results must be reviewed before forcing one shared
mapping into the INS.

## Top joint candidates

| Rank | Joint | det | Gravity | Gyro corr | Long corr | Lat corr | Yaw* (deg) | Mapping |
|---:|---:|---:|---:|---:|---:|---:|---:|---|
| 1 | 0.29420 | -1 | -0.0019 | +0.9705 | +0.2833 | +0.3252 | -99.16 | Vx(forward)=-X | Vy(lateral)=-Z | Vz(up)=+Y |
| 2 | 0.29420 | -1 | -0.0019 | +0.9705 | +0.2833 | +0.3252 | +80.84 | Vx(forward)=+X | Vy(lateral)=+Z | Vz(up)=+Y |
| 3 | 0.29420 | -1 | -0.0019 | +0.9705 | +0.2833 | +0.3252 | -9.16 | Vx(forward)=-Z | Vy(lateral)=+X | Vz(up)=+Y |
| 4 | 0.29420 | -1 | -0.0019 | +0.9705 | +0.2833 | +0.3252 | +170.84 | Vx(forward)=+Z | Vy(lateral)=-X | Vz(up)=+Y |
| 5 | 0.29063 | +1 | -0.0019 | +0.9705 | +0.2736 | +0.3243 | -101.29 | Vx(forward)=-X | Vy(lateral)=+Z | Vz(up)=+Y |
| 6 | 0.29063 | +1 | -0.0019 | +0.9705 | +0.2736 | +0.3243 | +78.71 | Vx(forward)=+X | Vy(lateral)=-Z | Vz(up)=+Y |
| 7 | 0.29063 | +1 | -0.0019 | +0.9705 | +0.2736 | +0.3243 | -11.29 | Vx(forward)=+Z | Vy(lateral)=+X | Vz(up)=+Y |
| 8 | 0.29063 | +1 | -0.0019 | +0.9705 | +0.2736 | +0.3243 | +168.71 | Vx(forward)=-Z | Vy(lateral)=-X | Vz(up)=+Y |
| 9 | 0.14256 | +1 | +0.0019 | -0.9705 | +0.2833 | +0.3252 | -99.16 | Vx(forward)=-X | Vy(lateral)=-Z | Vz(up)=-Y |
| 10 | 0.14256 | +1 | +0.0019 | -0.9705 | +0.2833 | +0.3252 | +80.84 | Vx(forward)=+X | Vy(lateral)=+Z | Vz(up)=-Y |

## Interpretation rules

- A strong candidate should score well on gyro yaw agreement AND
  horizontal acceleration agreement, not just one signal.
- det(M)=+1 is a proper rotation; det(M)=-1 is a reflection.
- Gravity alignment near +1 means transformed physical gravity points
  approximately downward under the vehicle +Z=Up convention.
- The reported lag is diagnostic only. It is NOT applied to the data.
- The reported yaw* is a mounting-orientation estimate, not a command
  to modify the synchronization timeline.

## Important caution

The numerical winner is evidence, not automatic physical truth.
The final mapping must also be consistent with the dataset's physical
mounting geometry and with the subsequent attitude-propagation test.