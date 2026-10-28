# core — shared navigation core (RESERVED, EMPTY)

> **Documentation note:** the detailed companion documents this file used to link (acquisition, recording, export, replay, calibration, fusion, map, UI design, security and release notes) were removed in the repository cleanup. Their content is not reproduced here; the current verification record is [reports/gap_closure_log.md](../reports/gap_closure_log.md).

Future home of the shared navigation core: calibration, coordinate frames,
mechanization, propagation and fusion, independent of UI and map code.

**Nothing is implemented here yet.** `NavigationEngine` in
`contracts/v1/kotlin/.../Models.kt` is the frozen interface any implementation
must satisfy (initialize / acceptImu / acceptGnss / drain / stop / reset).
Prerequisites before work starts here: the corrected INS baseline and the
frame/calibration resolution tracked in ARCHITECTURE.md
(Phases D–F of the build order).

`training/common.py` is a data-analysis utility module, **not** this core.

See ARCHITECTURE.md for ownership boundaries.
