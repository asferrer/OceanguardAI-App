"""Robust DeepFish downloader: bajar tar completo a disco con resume
(`Range` header), reintentar en caídas, luego extract local del subset
Localization.

Sustituye al stream-tar del agente: el HTTP `iter_content` cortó silenciosamente
al 67% en dos intentos. Bajar a disco desacopla download de extract y permite
resume.
"""
from __future__ import annotations

import sys
import tarfile
import time
from pathlib import Path

import numpy as np
import requests
from PIL import Image
from tqdm import tqdm

ROOT = Path(__file__).resolve().parent
DATA = ROOT / "datasets" / "deepfish"
TAR_PATH = DATA / "DeepFish.tar"
IMAGES_DIR = DATA / "images"
LABELS_DIR = DATA / "labels"
DONE = DATA / ".done"

URL = (
    "http://data.qld.edu.au/public/Q5842/"
    "2020-AlzayatSaleh-00e364223a600e83bd9c3f5bcd91045-DeepFish/DeepFish.tar"
)
TARGET_BYTES = 7_621_627_392
LOC_PREFIX = "DeepFish/Localization/"
MIN_BBOX_PX = 32
MAX_ATTEMPTS = 6
CHUNK = 1 << 20  # 1 MB


def download_with_resume() -> None:
    DATA.mkdir(parents=True, exist_ok=True)
    have = TAR_PATH.stat().st_size if TAR_PATH.exists() else 0
    if have >= TARGET_BYTES:
        print(f"tar already complete: {have} bytes", flush=True)
        return
    for attempt in range(1, MAX_ATTEMPTS + 1):
        have = TAR_PATH.stat().st_size if TAR_PATH.exists() else 0
        if have >= TARGET_BYTES:
            return
        headers = {"User-Agent": "Mozilla/5.0", "Range": f"bytes={have}-"}
        print(f"\n[attempt {attempt}] resuming at {have/(1<<20):.1f} MB", flush=True)
        try:
            with requests.get(URL, headers=headers, stream=True, timeout=120) as r:
                if r.status_code not in (200, 206):
                    print(f"  bad status {r.status_code}", flush=True)
                    continue
                bar = tqdm(total=TARGET_BYTES, initial=have, unit="B",
                           unit_scale=True, desc="DeepFish.tar", unit_divisor=1024)
                with open(TAR_PATH, "ab") as f:
                    for chunk in r.iter_content(chunk_size=CHUNK):
                        if not chunk:
                            break
                        f.write(chunk)
                        bar.update(len(chunk))
                bar.close()
        except (requests.RequestException, OSError) as e:
            print(f"  network error: {e}; sleep 5s + retry", flush=True)
            time.sleep(5)
            continue
    have = TAR_PATH.stat().st_size if TAR_PATH.exists() else 0
    if have < TARGET_BYTES:
        raise RuntimeError(
            f"failed to fully download after {MAX_ATTEMPTS} attempts "
            f"({have}/{TARGET_BYTES} bytes)"
        )


def _mask_to_yolo_boxes(mask: np.ndarray, w: int, h: int) -> list[str]:
    from scipy.ndimage import label as nd_label
    labeled, n = nd_label(mask > 0)
    out: list[str] = []
    for k in range(1, n + 1):
        ys, xs = np.where(labeled == k)
        x0, x1 = int(xs.min()), int(xs.max())
        y0, y1 = int(ys.min()), int(ys.max())
        half = MIN_BBOX_PX // 2
        if x1 - x0 < MIN_BBOX_PX:
            cx = (x0 + x1) // 2
            x0, x1 = max(0, cx - half), min(w - 1, cx + half)
        if y1 - y0 < MIN_BBOX_PX:
            cy = (y0 + y1) // 2
            y0, y1 = max(0, cy - half), min(h - 1, cy + half)
        cx = (x0 + x1) / 2.0 / w
        cy = (y0 + y1) / 2.0 / h
        bw = (x1 - x0) / w
        bh = (y1 - y0) / h
        out.append(f"0 {cx:.6f} {cy:.6f} {bw:.6f} {bh:.6f}")
    return out


def extract_and_convert() -> tuple[int, int]:
    IMAGES_DIR.mkdir(parents=True, exist_ok=True)
    LABELS_DIR.mkdir(parents=True, exist_ok=True)
    print(f"\nopening {TAR_PATH} ({TAR_PATH.stat().st_size/(1<<20):.1f} MB)",
          flush=True)
    img_cache: dict[str, tuple[int, int]] = {}
    mask_cache: dict[str, np.ndarray] = {}
    n_imgs = 0
    with tarfile.open(TAR_PATH, "r") as tf:
        members = [m for m in tf.getmembers() if m.name.startswith(LOC_PREFIX)
                   and m.isfile()]
        print(f"Localization members: {len(members)}", flush=True)
        for m in tqdm(members, desc="extract"):
            # Real layout: DeepFish/Localization/{images|masks}/{empty|valid}/<file>
            parts = Path(m.name).parts
            if len(parts) < 5:
                continue
            subtype = parts[2]   # "images" or "masks"
            presence = parts[3]  # "empty" or "valid"
            stem = Path(parts[4]).stem
            # filenames embed habitat + fish_idx + frame e.g. 7117_no_fish_2_f000000
            key = f"{presence}_{stem}"
            f = tf.extractfile(m)
            if f is None:
                continue
            data = f.read()
            if subtype == "images" and m.name.endswith(".jpg"):
                try:
                    img = Image.open(__import__("io").BytesIO(data))
                    w, h = img.size
                except Exception:
                    continue
                img_cache[key] = (w, h)
                out = IMAGES_DIR / f"{key}.jpg"
                if not out.exists():
                    out.write_bytes(data)
                n_imgs += 1
            elif subtype == "masks" and m.name.endswith(".png"):
                try:
                    arr = np.array(Image.open(__import__("io").BytesIO(data)))
                except Exception:
                    continue
                mask_cache[key] = arr
    n_boxes = 0
    print("generating YOLO labels...", flush=True)
    for key, mask in tqdm(mask_cache.items()):
        out = LABELS_DIR / f"{key}.txt"
        if out.exists():
            continue
        w, h = img_cache.get(key, (mask.shape[1], mask.shape[0]))
        lines = _mask_to_yolo_boxes(mask, w, h)
        out.write_text("\n".join(lines), encoding="utf-8")
        n_boxes += len(lines)
    # empty labels for images without masks (negatives)
    for key in img_cache:
        out = LABELS_DIR / f"{key}.txt"
        if not out.exists():
            out.touch()
    return n_imgs, n_boxes


def main() -> None:
    if DONE.exists():
        n_img = sum(1 for _ in IMAGES_DIR.glob("*.jpg"))
        n_lbl = sum(1 for _ in LABELS_DIR.glob("*.txt"))
        print(f"deepfish: {n_img} images, {n_lbl} labels  (already complete)")
        return
    try:
        download_with_resume()
    except Exception as e:
        print(f"DOWNLOAD FAILED: {e}", file=sys.stderr)
        sys.exit(1)
    n_img, n_box = extract_and_convert()
    DONE.touch()
    print(f"\ndeepfish: {n_img} images, {n_box} labels")


if __name__ == "__main__":
    main()
