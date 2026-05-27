"""
publish_to_hf.py — Publica los assets BioDex en HuggingFace.

Sube el set descargable in-app (encoder ONNX + índice + catálogo + ráster +
jerarquía) al repo que `VlmModelManager.SPECIES_REPO` espera. Requiere `HF_TOKEN`
en el entorno con permiso de escritura sobre el namespace destino.

Uso:
    python publish_to_hf.py --repo asferrer/oceanguard-biodex --src output
    python publish_to_hf.py --repo asferrer/oceanguard-biodex --src output --private
"""

from __future__ import annotations

import argparse
import os
from pathlib import Path

from huggingface_hub import HfApi

ASSETS = [
    "clip_vitb32.onnx",
    "species_index_v1.bin",
    "species_catalog_v1.json",
    "meow_raster_v1.bin",
    "ecoregion_hierarchy_v1.json",
]

README = """\
---
license: mit
tags:
  - oceanguard
  - marine-species
  - visual-rag
  - clip
  - biodex
---

# OceanGuard AI — BioDex assets (visual species RAG)

Assets descargables on-demand por la app OceanGuard AI para la identificación
on-device de especies marinas (track **BioDex**), vía RAG visual.

## Contenido

| Fichero | Descripción |
|---------|-------------|
| `clip_vitb32.onnx` | Encoder visual OpenCLIP ViT-B/32 (LAION-2B), exportado a ONNX (fp32). Input `pixel_values`[b,3,224,224] (normalización CLIP), output `image_features`[b,512] L2-norm. |
| `species_index_v1.bin` | Índice de prototipos (162 vectores, 27 especies, k=6 por especie) sobre 547 imágenes reales. Formato autocontenido (header SPEX + float16 + trailer JSONL). |
| `species_catalog_v1.json` | Catálogo: nombres científicos + comunes (6 idiomas), AphiaID, ecorregiones MEOW, IUCN. |
| `meow_raster_v1.bin` | Ráster MEOW 0.25° (lat/lon → ecorregión). **Provisional (mock)** — pendiente del shapefile MEOW real. |
| `ecoregion_hierarchy_v1.json` | Jerarquía ecorregión → provincia → realm MEOW. |

## Calidad (retrieval, held-out)

OpenCLIP ViT-B/32 **zero-shot** (sin fine-tuning), split 70/30 (383 train / 164
held-out): **top-1 = 84.1 %, top-5 = 98.2 %** sobre 27 especies. Supera el gate
inicial del proyecto (top-1 ≥ 65 %, top-5 ≥ 85 %).

## Procedencia y licencias

- **Imágenes de referencia**: iNaturalist + GBIF (research-grade, CC-BY / CC0).
  Las imágenes NO se redistribuyen aquí; solo los **embeddings derivados**. La
  atribución por imagen está en `provenance.jsonl` del pipeline.
- **Encoder**: OpenCLIP ViT-B/32 LAION-2B — licencia MIT.
- **MEOW** (Spalding et al. 2007): TNC, libre con atribución.

## Notas

- El ráster/distribución son **provisionales (mock)** hasta integrar el shapefile
  MEOW real + ocurrencias OBIS/GBIF con lat/lon. El filtro geográfico degrada con
  elegancia (cosmopolitas y sin-datos nunca se excluyen).
- Encoder en **fp32 (352 MB)**; fp16 pendiente por incompatibilidad de fusión
  LayerNorm en ONNX Runtime.

Generado por `finetune/species/` del repo OceanguardAI-App.
"""


def main() -> None:
    p = argparse.ArgumentParser(description="Publica assets BioDex en HuggingFace.")
    p.add_argument("--repo", required=True, help="repo_id, p.ej. asferrer/oceanguard-biodex")
    p.add_argument("--src", type=Path, default=Path("output"))
    p.add_argument("--private", action="store_true")
    args = p.parse_args()

    token = os.environ.get("HF_TOKEN")
    if not token:
        raise SystemExit("Falta HF_TOKEN en el entorno.")

    api = HfApi(token=token)
    api.create_repo(args.repo, repo_type="model", private=args.private, exist_ok=True)
    print(f"Repo listo: {args.repo} (private={args.private})")

    # README
    readme = args.src / "_README.md"
    readme.write_text(README, encoding="utf-8")
    api.upload_file(path_or_fileobj=str(readme), path_in_repo="README.md",
                    repo_id=args.repo, repo_type="model")
    print("  README.md subido")

    for name in ASSETS:
        f = args.src / name
        if not f.exists():
            print(f"  SKIP (no existe): {name}")
            continue
        api.upload_file(path_or_fileobj=str(f), path_in_repo=name,
                        repo_id=args.repo, repo_type="model")
        print(f"  subido: {name} ({f.stat().st_size/1e6:.1f} MB)")

    base = f"https://huggingface.co/{args.repo}/resolve/main"
    print("\nURLs (las que espera VlmModelManager.SPECIES_REPO):")
    for name in ASSETS:
        print(f"  {base}/{name}")


if __name__ == "__main__":
    main()
