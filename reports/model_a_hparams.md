# Model A Hyperparameter Selection & Validation Report

## 1. Protocol & Guardrails
In strict accordance with Step 1 of the LOGO evaluation task:
- Hyperparameters were chosen **ONCE** using solely the validation drive of Fold 0 (`vta12`), trained on the remaining 10 candidate drives (`vta13`, `vta19`, `vta23`, `vta24`, `vta7`, `vtb10`, `vtb12`, `vtb7`, `vw16`, `vw9`).
- The held-out drive for Fold 0 (`vta1`) was **NEVER** accessed, evaluated, or examined during this tuning process.
- No other folds or held-out test drives were inspected prior to fixing these parameters.
- These hyperparameters remain strictly fixed for all 12 leave-one-drive-out folds and all variants.

---

## 2. Validation Search Grid on Fold 0 Validation Drive (`vta12`)

Grid evaluated over learning rate $\in \{0.001, 0.002\}$ and batch size $\in \{32, 64\}$ across 12 epochs:

| Learning Rate | Batch Size | Validation Loss (`vta12`) | Validation RMSE (`vta12`) | Validation MAE (`vta12`) | Best Epoch | Stillness Accuracy |
|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| 0.001 | 32 | 5.1375 | 5.2379 m/s | 4.5124 m/s | 7 | 100.0% |
| 0.001 | 64 | 5.9391 | 6.1061 m/s | 5.2003 m/s | 7 | 100.0% |
| **0.002** | **32** | **5.1012** | **5.2128 m/s** | **4.4864 m/s** | **7** | **100.0%** |
| 0.002 | 64 | 5.3103 | 5.4667 m/s | 4.6525 m/s | 7 | 100.0% |

---

## 3. Fixed Hyperparameters for 12-Fold LOGO Evaluation

| Hyperparameter | Value | Rationale |
|:---|:---:|:---|
| **Learning Rate** | `0.002` | Lowest validation loss (5.1012) and RMSE (5.21 m/s) on validation drive |
| **Batch Size** | `32` | Consistently outperforms batch size 64 across both learning rates |
| **Epochs Cap** | `10` | Loss plateaus and reaches global minimum at epoch 7; 10 provides full convergence margin |
| **Early Stopping** | Validation Loss on `--val-drive` | Best checkpoint selected based strictly on validation drive loss |
| **Optimizer** | `AdamW` | Weight decay `1e-4`, CosineAnnealingLR scheduling over `T_max=10` |
| **Mixed Ratio** | `0.5` | Equal 1:1 real-to-synthetic window mixture in training batches |
| **Loss Formulation** | Huber + 0.5 NLL + 0.5 BCE | Identical to Model A production spec ($\delta=1.0$) |
| **Random Seed** | `42` | Strictly deterministic seed across all folds and runs |
| **Window Length / Stride** | 100 samples / 10 samples | 1.0 s window at 100 Hz, 0.1 s causal inference step |
