# data — datasets (IGNORED CONTENT)

`raw/` and `processed/` are gitignored. The IO-VNBD dataset is obtained and
extracted separately; placement is `data/raw/iovnbd/` with the original
directory structure (see the [root README](../README.md) for the exact layout
and `--data-root` overrides).

Rules:

- Never modify, rename, delete or regenerate anything under `data/raw/`.
  `reports/phase0_raw_manifest.json` records SHA-256/size/mtime for integrity
  comparison.
- Tests must not require this dataset; they use synthetic fixtures.
- Any `data/raw/**/.git` directory belongs to the dataset's own distribution —
  do not nest commits there.
