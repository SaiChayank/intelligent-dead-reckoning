2026-10-19 Part 1 done: Built factual inventory of 115 IO-VNBD sequences (72 synchronized pairs + 43 unsynchronized-only) and documented the blocking frame problem.
2026-10-19 Part 2 done: Labeled 72 IO-VNBD sequences into stopped/moving/accelerating/braking segments; flagged 31 drives with no stops.
2026-10-19 Part 3 done: Estimated IMU-to-vehicle rotations for all 72 sequences; confirmed all 72 are low confidence due to export-frame decoupling and latency.
2026-10-19 Part 4 done: Estimated cross-correlation time offsets for all 72 sequences; confirmed correlation improves after alignment; flagged 52 suspicious sequences.
2026-10-19 Part 5 done: Evaluated admission criteria for all 72 sequences; approved count is 0 (checkpoint hit) due to low frame confidence.
2026-10-19 Part 6 done: Created leakage-free drive-session split (70.4/15.3/14.4% by duration) across 12 candidate groups with zero overlap; verified LOGO fallback for <6 groups.
2026-10-20 Part 7 done: Reconciled 12 candidate durations (815.0 s confirmed); proved accel has gravity on +z and body-fixed x/y; generated 12-fold LOGO splits.
2026-10-20 Part 8 done: Built real-data dataset for Model A with 4 heading-invariant channels and 12-fold LOGO splits (7,947 windows); all tests passing.
2026-10-20 Part 9 done: Integrated IO-VNBD real and mixed data paths into Model A trainer with validation early stopping and fold isolation; smoke run passed with loss decreasing 47.18 -> 3.92.
2026-10-20 Part 10 done: Evaluated Model A variants across 12 LOGO folds (B real RMSE 6.11 m/s, C mixed 6.42 m/s, A syn 96.96 m/s, heading oracle gap +0.27 m/s).
2026-10-20 Part 11 done: Trained final Model A invariant variant on 12 candidate drives, exported versioned v2 weights, updated manifest, and documented Kotlin channel changes.
