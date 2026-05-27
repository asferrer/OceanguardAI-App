"""
make_holdout_split.py — Split train/held-out para evaluar el RAG visual.

Para cada especie en `images/`, aparta una fracción held-out (resto = train),
copia el train a `images_train/{species_key}/` y escribe `held_out.jsonl`
({image_path, species_key}) con el held-out. Determinista por semilla.

El held-out NO lleva lat/lon (el scraper actual no captura coords) → la
evaluación corre en modo OFF efectivo (top-1/top-5 de retrieval puro), que es
el gate go/no-go del plan (Fase 0). La ablación geográfica real requiere coords
+ ráster MEOW real.

Uso:
    python make_holdout_split.py --images images --train-out images_train \
        --holdout-jsonl held_out.jsonl --frac 0.3 --seed 42
"""

from __future__ import annotations

import argparse
import json
import random
import shutil
from pathlib import Path

IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".webp"}


def main() -> None:
    parser = argparse.ArgumentParser(description="Split train/held-out para eval del RAG.")
    parser.add_argument("--images", type=Path, default=Path("images"))
    parser.add_argument("--train-out", type=Path, default=Path("images_train"))
    parser.add_argument("--holdout-jsonl", type=Path, default=Path("held_out.jsonl"))
    parser.add_argument("--frac", type=float, default=0.3, help="Fracción held-out por especie.")
    parser.add_argument("--min-holdout", type=int, default=2)
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()

    rng = random.Random(args.seed)
    args.train_out.mkdir(parents=True, exist_ok=True)
    held_lines: list[str] = []
    n_train = n_held = 0

    for sp_dir in sorted(args.images.iterdir()):
        if not sp_dir.is_dir():
            continue
        key = sp_dir.name
        imgs = sorted(p for p in sp_dir.iterdir() if p.suffix.lower() in IMAGE_EXTS)
        if len(imgs) < 4:  # muy pocas → todas a train (no se pueden evaluar fiable)
            train, held = imgs, []
        else:
            rng.shuffle(imgs)
            n_h = max(args.min_holdout, int(round(len(imgs) * args.frac)))
            n_h = min(n_h, len(imgs) - 2)  # dejar >=2 en train
            held, train = imgs[:n_h], imgs[n_h:]

        dst = args.train_out / key
        dst.mkdir(parents=True, exist_ok=True)
        for p in train:
            shutil.copy2(p, dst / p.name)
            n_train += 1
        for p in held:
            held_lines.append(json.dumps({
                "image_path": str(p.resolve()), "species_key": key,
            }))
            n_held += 1

    args.holdout_jsonl.write_text("\n".join(held_lines) + "\n", encoding="utf-8")
    print(f"Train: {n_train} imgs en {args.train_out}/")
    print(f"Held-out: {n_held} queries en {args.holdout_jsonl}")


if __name__ == "__main__":
    main()
