# core — shared navigation core (RESERVED, EMPTY)

Future home of the shared navigation core: calibration, coordinate frames,
mechanization, propagation and fusion, independent of UI and map code.

**Nothing is implemented here yet.** `NavigationEngine` in
`contracts/v1/kotlin/.../Models.kt` is the frozen interface any implementation
must satisfy (initialize / acceptImu / acceptGnss / drain / stop / reset).
Prerequisites before work starts here: the corrected INS baseline and the
frame/calibration resolution tracked in [CURRENT_STATE_AUDIT.md](../CURRENT_STATE_AUDIT.md)
(Phases D–F of the build order).

`training/common.py` is a data-analysis utility module, **not** this core.

See [ARCHITECTURE.md](../ARCHITECTURE.md) for ownership boundaries.
