"""download_datasets.py — Descarga e inventario de los 4 datasets marinos.

Datasets (todos CC-BY 4.0, real underwater):
  - OzFish (AIMS, BRUVS, ~80k crops) — GitHub release
  - Brackish (Aalborg, 12.4k imgs, turbid) — ZIP directo
  - Aquarium Combined (Roboflow public, 638 imgs, 7 taxa) — Roboflow export
  - DeepFish-localization (4.5k imgs, masks→bbox) — paper supplements

Todos quedan en finetune/detector/datasets/<name>/ con su license + provenance.
Idempotente: si la carpeta ya existe y trae el marker `.done`, salta.
"""
from __future__ import annotations

import hashlib
import os
import shutil
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent
DATA = ROOT / "datasets"

# Cada entrada: (slug, url, kind) — kind: 'zip' | 'tar' | 'git' | 'manual'.
DATASETS: list[tuple[str, str, str]] = [
    # OzFish — fish-only BRUVS, AIMS. GitHub release zip.
    # NOTE: full annotated set is hosted via AIMS; the GitHub mirror has the
    # YOLO-ready subset and links to the full AIMS archive.
    ("ozfish",
     "https://github.com/open-AIMS/ozfish/archive/refs/heads/master.zip",
     "zip"),
    # Brackish dataset (Aalborg University) — fish + invertebrates in turbid water.
    ("brackish",
     "https://www.kaggle.com/api/v1/datasets/download/aalborguniversity/brackish-dataset",
     "manual"),  # requires kaggle CLI; we surface instructions
    # DeepFish localization split — masks; we'll derive bbox via OpenCV.
    ("deepfish",
     "https://alzayats.github.io/DeepFish/dataset/DeepFish.tar",
     "tar"),
    # SUIM (for negatives only) — semantic segmentation, we filter background-only.
    ("suim",
     "https://drive.usercontent.google.com/download?id=1YWjUODQWwQ3_vKSytqVdF4recqBOEe72&export=download",
     "manual"),  # GDrive interstitial — surface manual url
    # Aquarium Combined — small but adds sharks/turtles/jellies/rays/stingrays.
    # Public Roboflow: requires API key + export. Surface manual.
    ("aquarium_combined",
     "https://public.roboflow.com/object-detection/aquarium",
     "manual"),
]


def _download(url: str, dst: Path) -> None:
    """Descarga con barra de progreso minimalista (cada 8 MB)."""
    print(f"  -> {url}", flush=True)
    dst.parent.mkdir(parents=True, exist_ok=True)
    tmp = dst.with_suffix(dst.suffix + ".tmp")
    with urllib.request.urlopen(url, timeout=60) as r, open(tmp, "wb") as f:
        chunk = 1 << 20  # 1 MB
        size = int(r.headers.get("Content-Length", 0))
        got = 0
        ticks = 0
        while True:
            data = r.read(chunk)
            if not data:
                break
            f.write(data)
            got += len(data)
            ticks += 1
            if ticks % 8 == 0:
                mb = got / (1 << 20)
                pct = f" ({100 * got / size:.1f}%)" if size else ""
                print(f"     {mb:7.1f} MB{pct}", flush=True)
    tmp.replace(dst)
    print(f"  OK {dst.name} ({dst.stat().st_size / (1 << 20):.1f} MB)", flush=True)


def _extract_zip(archive: Path, out_dir: Path) -> None:
    import zipfile
    with zipfile.ZipFile(archive) as z:
        z.extractall(out_dir)


def _extract_tar(archive: Path, out_dir: Path) -> None:
    import tarfile
    with tarfile.open(archive) as t:
        t.extractall(out_dir)


def handle(slug: str, url: str, kind: str) -> None:
    target = DATA / slug
    marker = target / ".done"
    if marker.exists():
        print(f"[{slug}] ya descargado — skip")
        return
    target.mkdir(parents=True, exist_ok=True)
    print(f"\n[{slug}] kind={kind}")

    if kind == "manual":
        # Documentamos URL para el usuario.
        (target / "MANUAL_DOWNLOAD.md").write_text(
            f"# {slug}\n\nFuente: {url}\n\n"
            "Este dataset requiere interaccion manual (Kaggle CLI, GDrive\n"
            "interstitial, o registro Roboflow). Descargalo y descomprime\n"
            f"el contenido directamente en `{target}` (sin subcarpeta extra).\n",
            encoding="utf-8",
        )
        print(f"  MANUAL — instrucciones en {target}/MANUAL_DOWNLOAD.md")
        return

    archive = target / f"_{slug}.{kind}"
    _download(url, archive)

    if kind == "zip":
        _extract_zip(archive, target)
    elif kind == "tar":
        _extract_tar(archive, target)
    archive.unlink()
    marker.touch()
    print(f"[{slug}] OK")


def main() -> None:
    DATA.mkdir(parents=True, exist_ok=True)
    t0 = time.time()
    for slug, url, kind in DATASETS:
        try:
            handle(slug, url, kind)
        except Exception as e:
            print(f"[{slug}] FAILED: {e}", flush=True)
    print(f"\nTotal {time.time() - t0:.1f}s")
    # Inventario final
    print("\n=== INVENTARIO ===")
    for slug, _, _ in DATASETS:
        d = DATA / slug
        if d.exists():
            n_files = sum(1 for _ in d.rglob("*") if _.is_file())
            size_mb = sum(
                p.stat().st_size for p in d.rglob("*") if p.is_file()
            ) / (1 << 20)
            print(f"  {slug:<22} {n_files:>6} files  {size_mb:>9.1f} MB")


if __name__ == "__main__":
    main()
