"""train_detector.py — YOLO26-N marine 1-class detector training.

Recipe from research-analyst SOTA report (2026-05-29):
  - YOLO26-N (Ultralytics, Jan 2026): NMS-free + DFL-free → graph determinista
    sin Softmax-on-DFL fallback en NNAPI vendor drivers. 2.4 M params, 5.4 GFLOPs.
  - 416×416 input → target ~15-22 ms en Exynos 2200 NNAPI EP @ INT8.
  - 1 class "organism" (todos los animales colapsados; el RAG hace el fine-grained).
  - 80 epochs SGD lr 0.01 cos, batch 64, AMP, copy_paste 0.2, mixup 0.05.
  - flipud=0 (NUNCA flip vertical underwater — gravity prior).
  - close_mosaic=10 (apaga mosaic en las últimas 10 ép para boxes más tight).

TDR mitigations (RTX 5090 sm_120 + driver 596.36 + CUDA 13.2 + PyTorch nightly cu128):
  - TdrDelay=60 ya aplicado (regedit + reboot).
  - workers=4 (no 8/16 — el dataloader multiprocess de Ultralytics tiene leaks).
  - amp=True (acelera kernels → bajo riesgo de TDR durante backward).
  - sync_bn=False (single-GPU; sync_bn fuerza NCCL incluso en 1 GPU).
  - persistent_workers=False (deja que pytorch reset cada época).
  - PYTORCH_CUDA_ALLOC_CONF=expandable_segments:True (anti-fragmentation).

Usage:
  python train_detector.py --data marine_1class.yaml --epochs 80
  python train_detector.py --epochs 5 --smoke           # smoke test: 5 ep solo

Outputs:
  output/runs/train/marine_yolo26n/weights/{best,last}.pt
  output/runs/train/marine_yolo26n/{results.csv, results.png, args.yaml, ...}
"""
from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path


def set_blackwell_env() -> None:
    """Mitigaciones de fragmentación CUDA para Blackwell sm_120."""
    os.environ.setdefault(
        "PYTORCH_CUDA_ALLOC_CONF", "expandable_segments:True"
    )
    # Apaga CUDA graphs (Ultralytics no los usa, pero por si torch.compile entra).
    os.environ.setdefault("CUDA_LAUNCH_BLOCKING", "0")


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--data", type=Path, default=Path("marine_1class.yaml"))
    p.add_argument("--model", type=str, default="yolo26n.pt",
                   help="Pretrained COCO weights to warm-start from.")
    p.add_argument("--imgsz", type=int, default=416)
    p.add_argument("--epochs", type=int, default=80)
    p.add_argument("--batch", type=int, default=64)
    p.add_argument("--workers", type=int, default=4)
    p.add_argument("--lr0", type=float, default=0.01)
    p.add_argument("--device", type=str, default="0")
    p.add_argument("--name", type=str, default="marine_yolo26n")
    p.add_argument("--project", type=Path, default=Path("output/runs/train"))
    p.add_argument("--smoke", action="store_true",
                   help="5 epochs + 1 batch + reduced workers for sanity.")
    p.add_argument("--resume", action="store_true",
                   help="Resume from last checkpoint of same name.")
    return p.parse_args()


def main() -> None:
    set_blackwell_env()
    args = parse_args()

    import torch
    print(f"torch {torch.__version__}  cuda={torch.cuda.is_available()}  "
          f"cap={torch.cuda.get_device_capability() if torch.cuda.is_available() else None}",
          flush=True)
    if not torch.cuda.is_available():
        print("ERROR: CUDA no disponible. Verifica install torch nightly cu128.",
              file=sys.stderr)
        sys.exit(1)

    from ultralytics import YOLO

    model = YOLO(args.model)
    print(f"Model: {args.model}  params="
          f"{sum(p.numel() for p in model.model.parameters()) / 1e6:.2f}M",
          flush=True)

    train_kwargs: dict = dict(
        data=str(args.data),
        imgsz=args.imgsz,
        epochs=5 if args.smoke else args.epochs,
        batch=args.batch if not args.smoke else min(args.batch, 16),
        workers=args.workers,
        device=args.device,
        project=str(args.project),
        name=args.name + ("_smoke" if args.smoke else ""),
        exist_ok=True,
        # Optimizer + schedule (research recipe).
        optimizer="SGD",
        lr0=args.lr0,
        lrf=0.01,
        momentum=0.937,
        weight_decay=0.0005,
        warmup_epochs=3,
        cos_lr=True,
        # Mixed precision — main TDR mitigation, faster kernels.
        amp=True,
        # Augmentations tuned for underwater 1-class.
        hsv_h=0.015,
        hsv_s=0.5,   # underwater color cast already strong — no need to push more.
        hsv_v=0.3,
        mosaic=1.0,
        close_mosaic=10,
        mixup=0.05,
        copy_paste=0.2,
        degrees=5,
        translate=0.1,
        scale=0.5,
        fliplr=0.5,
        flipud=0.0,   # NEVER vertical flip underwater (gravity prior).
        # Stability + TDR knobs (sync_bn removed in Ultralytics 8.4.56 args list;
        # not relevant for single-GPU anyway).
        deterministic=False,
        # Early stop conservador para no entrenar de más.
        patience=15,
        plots=True,
        save=True,
        save_period=10,
        verbose=True,
    )
    if args.resume:
        train_kwargs["resume"] = True

    print("Training kwargs:", flush=True)
    for k, v in sorted(train_kwargs.items()):
        print(f"  {k}: {v}", flush=True)

    results = model.train(**train_kwargs)
    print(f"\nDONE. results.save_dir={results.save_dir}", flush=True)


if __name__ == "__main__":
    main()
