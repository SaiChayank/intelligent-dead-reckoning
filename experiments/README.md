# experiments — collected drives (IGNORED CONTENT)

An experiment is the evaluation unit: a recording session plus the context that
makes the drive reusable. The schema is
[contracts/experiment/v1](../contracts/experiment/v1/README.md) and the collection
procedure is
[docs/PS26168_Experiment_Data_Collection_Protocol.md](../docs/PS26168_Experiment_Data_Collection_Protocol.md).

Experiment **content is gitignored**. Only this README is tracked, because
experiments contain recordings: real drives, real timestamps, real device
identifiers, and potentially larger files than a source repository should carry.
Recordings already have their own home under `data/` for the offline corpus; a
collected experiment is local evidence, not source.

## Layout

Corpus root. Experiments may sit here directly or grouped into campaigns:

```text
experiments/
  exp-2026-09-30-urban-01/          one experiment
    experiment.json
    annotations.jsonl
    integrity.sha256
    masks/<mask_id>.json
    reference/reference.jsonl        only when a reference is declared
    sessions/<session_id>/           recordings, exactly as the app exported them
  hyderabad-2026-09/                 optional campaign grouping
    exp-2026-09-22-tunnel-01/
```

Rules:

- The directory name **is** `experiment_id`. A manifest declaring another ID is
  refused (`ID_MISMATCH`), so moving a directory means re-authoring its manifest.
- Copy recordings in; never move or edit an original session directory, and never
  modify anything inside `sessions/` after sealing.
- Any file you add after sealing must be re-sealed. An unlisted file is a
  validation failure, not an extra.
- Never edit `integrity.sha256` by hand, and never edit `experiment.json` after
  sealing. Re-run `tools/seal_experiment.py`, which rewrites both consistently and
  then verifies the result through the reader.
- Two experiments anywhere in this tree must not share an `experiment_id`. The
  corpus check catches it; nothing inside one directory can.

## Commands

```powershell
python tools/seal_experiment.py experiments/<experiment_id>
python tools/validate_experiment.py --experiment experiments/<experiment_id>
python tools/validate_experiment.py --corpus experiments
python tools/experiment_report.py --experiment experiments/<experiment_id> --mask <mask_id>
```

Tests never read this directory. They build synthetic experiments, complete with
recordings produced by the real recording codec, so the suite runs with no
collected data and no device.
