"""
train_encoder.py — Fine-tune marino del visual tower de OpenCLIP ViT-B/32.

OBJETIVO
--------
Mejorar el retrieval de especies del RAG visual BioDex afinando SOLO el encoder
de imágenes (visual tower) sobre el banco de referencia marino (203 especies).
El resultado es un drop-in ONNX con el MISMO contrato que el zero-shot:
input `pixel_values`[b,3,224,224] CLIP-normalizado, output `image_features`[b,512]
L2-normalizado, opset 14 (ver embedder.OpenCLIPEmbedder.export_onnx).

ENFOQUE
-------
- **Partial-unfreeze** (no LoRA): se descongelan los últimos N bloques del
  transformer + `ln_post` + la proyección final `proj`; el resto queda congelado.
  Motivo: el attention de open_clip usa `nn.MultiheadAttention` con `in_proj_weight`
  empaquetado, que PEFT/LoRA no inyecta limpiamente (mismo gotcha que el merge
  manual de SigLIP2 en el track Gemma). Partial-unfreeze mantiene la arquitectura
  intacta → `export_onnx` funciona sin merge ni cirugía de tensores.
- **Supervised Contrastive (SupCon)** sobre embeddings L2-norm dentro del batch.
  Acerca embeddings de la misma especie y aleja los de especies distintas → es
  exactamente la geometría que mejora el match nearest-prototype (coseno).
- Sampler P-K: cada batch lleva P especies × K imágenes → SupCon tiene positivos.
- Split: usa SOLO images_train (mismo split determinista que make_holdout_split.py
  seed 42). NUNCA toca el held-out. Validación interna = split train→val 90/10
  estratificado por especie (semilla fija), NO el held-out (ese es para eval_retrieval).

USO (full run recomendado)
--------------------------
  python train_encoder.py --images-dir images_train --out-ckpt output/clip_vitb32_ft.pt \
      --export-onnx output/clip_vitb32_ft.onnx \
      --unfreeze-blocks 4 --epochs 12 --batch-p 16 --batch-k 4 \
      --lr 1e-5 --temp 0.07 --early-stop-patience 3

  # Pasada reducida (smoke / budget bajo):
  python train_encoder.py --images-dir images_train --out-ckpt output/clip_vitb32_ft.pt \
      --export-onnx output/clip_vitb32_ft.onnx --epochs 3 --unfreeze-blocks 2 \
      --max-imgs-per-species 24

El ckpt guardado contiene state_dict del visual tower afinado (compatible con
embedder.OpenCLIPEmbedder vía --load-ckpt en build_reference_bank / eval).
"""

from __future__ import annotations

import argparse
import random
from pathlib import Path

import numpy as np
import torch

from train_utils import (
    SpeciesImageDataset,
    PKSampler,
    build_train_transform,
    build_eval_transform,
    discover_train_images,
    stratified_train_val,
    supcon_loss,
    set_visual_trainable,
    load_openclip_visual,
    eval_topk_internal,
    export_finetuned_onnx,
)

IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".webp"}


def set_seed(seed: int) -> None:
    random.seed(seed)
    np.random.seed(seed)
    torch.manual_seed(seed)
    torch.cuda.manual_seed_all(seed)


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Fine-tune marino del visual tower OpenCLIP ViT-B/32.")
    p.add_argument("--images-dir", type=Path, default=Path("images_train"))
    p.add_argument("--out-ckpt", type=Path, default=Path("output/clip_vitb32_ft.pt"))
    p.add_argument("--export-onnx", type=Path, default=None,
                   help="Si se indica, exporta el visual tower afinado a este .onnx.")
    p.add_argument("--unfreeze-blocks", type=int, default=4,
                   help="Nº de bloques finales del transformer a descongelar (+ln_post+proj).")
    p.add_argument("--epochs", type=int, default=12)
    p.add_argument("--batch-p", type=int, default=16, help="Especies por batch (P).")
    p.add_argument("--batch-k", type=int, default=4, help="Imágenes por especie (K).")
    p.add_argument("--lr", type=float, default=1e-5)
    p.add_argument("--weight-decay", type=float, default=1e-4)
    p.add_argument("--temp", type=float, default=0.07, help="Temperatura SupCon.")
    p.add_argument("--early-stop-patience", type=int, default=3)
    p.add_argument("--max-imgs-per-species", type=int, default=0,
                   help="Cap de imgs/especie (0=sin cap). Útil para smoke runs.")
    p.add_argument("--val-frac", type=float, default=0.1)
    p.add_argument("--steps-per-epoch", type=int, default=0,
                   help="0 = auto (cubre ~todo el train una vez por época).")
    p.add_argument("--seed", type=int, default=42)
    p.add_argument("--device", type=str, default=None)
    return p.parse_args()


def run_epoch(model, sampler, dataset, optimizer, temp, device) -> float:
    """Una época de entrenamiento SupCon. Devuelve la loss media."""
    model.train()
    losses: list[float] = []
    for labels, paths in sampler:
        imgs = dataset.load_batch(paths).to(device)
        labels_t = torch.tensor(labels, device=device)
        feats = model(imgs)
        feats = torch.nn.functional.normalize(feats, dim=-1)
        loss = supcon_loss(feats, labels_t, temperature=temp)
        optimizer.zero_grad()
        loss.backward()
        torch.nn.utils.clip_grad_norm_(
            [p for p in model.parameters() if p.requires_grad], 1.0
        )
        optimizer.step()
        losses.append(float(loss.detach().cpu()))
    return float(np.mean(losses)) if losses else 0.0


def main() -> None:
    args = parse_args()
    set_seed(args.seed)
    device = args.device or ("cuda" if torch.cuda.is_available() else "cpu")
    print(f"Device: {device} | unfreeze_blocks={args.unfreeze_blocks} "
          f"epochs={args.epochs} P={args.batch_p} K={args.batch_k} lr={args.lr}")

    # --- datos: SOLO images_train (split determinista seed 42) ---
    by_species = discover_train_images(args.images_dir, IMAGE_EXTS,
                                        cap=args.max_imgs_per_species, seed=args.seed)
    species_keys = sorted(by_species.keys())
    print(f"Especies: {len(species_keys)} | imgs train totales: "
          f"{sum(len(v) for v in by_species.values())}")

    train_items, val_items = stratified_train_val(by_species, args.val_frac, args.seed)
    print(f"Train interno: {len(train_items)} | Val interno: {len(val_items)}")

    train_tf = build_train_transform()
    eval_tf = build_eval_transform()
    train_ds = SpeciesImageDataset(train_items, species_keys, train_tf)
    val_ds = SpeciesImageDataset(val_items, species_keys, eval_tf)

    # --- modelo ---
    model = load_openclip_visual(device)
    n_trainable = set_visual_trainable(model, args.unfreeze_blocks)
    print(f"Params entrenables: {n_trainable/1e6:.2f}M de {sum(p.numel() for p in model.parameters())/1e6:.2f}M")

    optimizer = torch.optim.AdamW(
        [p for p in model.parameters() if p.requires_grad],
        lr=args.lr, weight_decay=args.weight_decay,
    )

    sampler = PKSampler(train_ds, p=args.batch_p, k=args.batch_k,
                        steps=args.steps_per_epoch, seed=args.seed)

    # --- baseline val antes de entrenar ---
    base_top1, base_top5 = eval_topk_internal(model, val_ds, train_ds, device)
    print(f"[época 0 / pre-train] val top-1={base_top1:.3f} top-5={base_top5:.3f}")

    best_top1 = train_loop(model, sampler, train_ds, val_ds, optimizer, args,
                           base_top1, device)
    print(f"Checkpoint guardado: {args.out_ckpt} (mejor val top-1={best_top1:.3f})")

    if args.export_onnx:
        export_finetuned_onnx(model, args.export_onnx, device)
        print(f"ONNX afinado exportado: {args.export_onnx}")


def _save_ckpt(out_ckpt, state: dict, top1: float, unfreeze: int) -> None:
    """Guarda el ckpt en cada mejora → el mejor sobrevive a interrupciones."""
    out_ckpt.parent.mkdir(parents=True, exist_ok=True)
    torch.save({"visual_state_dict": state, "best_val_top1": top1,
                "unfreeze_blocks": unfreeze}, out_ckpt)


def train_loop(model, sampler, train_ds, val_ds, optimizer, args,
               base_top1: float, device: str) -> float:
    """Bucle de entrenamiento con early-stop + save-on-best. Devuelve best val top-1."""
    best_top1 = base_top1
    best_state = {k: v.detach().cpu().clone() for k, v in model.state_dict().items()}
    patience = 0
    for epoch in range(1, args.epochs + 1):
        sampler.reshuffle(epoch)
        loss = run_epoch(model, sampler, train_ds, optimizer, args.temp, device)
        top1, top5 = eval_topk_internal(model, val_ds, train_ds, device)
        flag = ""
        if top1 > best_top1 + 1e-4:
            best_top1 = top1
            best_state = {k: v.detach().cpu().clone() for k, v in model.state_dict().items()}
            _save_ckpt(args.out_ckpt, best_state, best_top1, args.unfreeze_blocks)
            patience = 0
            flag = "  <- best (guardado)"
        else:
            patience += 1
        print(f"[época {epoch}/{args.epochs}] loss={loss:.4f} "
              f"val top-1={top1:.3f} top-5={top5:.3f}{flag}", flush=True)
        if patience >= args.early_stop_patience:
            print(f"Early-stop: sin mejora en {patience} épocas.")
            break
    model.load_state_dict(best_state)
    _save_ckpt(args.out_ckpt, best_state, best_top1, args.unfreeze_blocks)
    return best_top1


if __name__ == "__main__":
    main()
