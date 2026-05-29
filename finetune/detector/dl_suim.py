"""
SUIM downloader — Semantic Underwater Image dataset, CC-BY 4.0 (IROS 2020).

Source: Google Drive, file ID 1uEnlqKrlt6lITc_i80NTtb7iHGcO47sU
  - File: SUIM.zip, ~4.9 GB (GDrive reports 4.9G; Content-Length = 5,245,090,436 bytes)
  - Confirmed working 2026-05-29 via UUID-based download bypass of virus-scan interstitial.

Original page:  https://irvlab.cs.umn.edu/resources/suim-dataset
GitHub:         https://github.com/xahidbuffon/SUIM

Splits: train/val = 1525 annotated images, test = 110 images
Semantic classes (8-class RGB mask, one bit per channel in a 3-channel mask):
  BW = Background/waterbody
  HD = Human divers
  PF = Aquatic plants / sea-grass
  WR = Wrecks / ruins
  RO = Robots / instruments
  RI = Reefs / invertebrates
  FV = Fish and vertebrates  <-- we use this to generate bboxes
  SR = Sea-floor / rocks

Usage for negatives:
  All images where no "FV" (fish) pixels are present become background frames for
  negative training (empty YOLO label file).
  Images with FV pixels: we derive bboxes from connected components of the FV channel.

YOLO classes produced:
  0 = fish (FV class from SUIM mask)
  BACKGROUND frames → empty label (useful as hard negatives in detector training)

Estimated download: 5.2 GB. Time at 100 Mbps: ~420 seconds (~7 minutes).

License: CC-BY 4.0 (confirmed in GitHub repo)
Citation: Islam et al. (2020), IROS 2020 — "Semantic Segmentation of Underwater Imagery"

Dependencies: pip install requests tqdm numpy Pillow scipy
"""

from __future__ import annotations

import io
import re
import sys
import zipfile
from pathlib import Path

import numpy as np
import requests
from PIL import Image
from tqdm import tqdm

SLUG = "suim"
DATASET_DIR = Path(__file__).parent / "datasets" / SLUG
IMAGES_DIR = DATASET_DIR / "images"
LABELS_DIR = DATASET_DIR / "labels"

GDRIVE_FILE_ID = "1uEnlqKrlt6lITc_i80NTtb7iHGcO47sU"
ZIP_CACHE = DATASET_DIR / "SUIM.zip"
DONE_SENTINEL = DATASET_DIR / ".done"

# Minimum bounding box pixel side length (before normalisation)
MIN_BBOX_PX = 16

# SUIM mask RGB encoding — each class occupies one bit in a specific colour channel.
# FV (fish + vertebrates): R=0, G=1, B=1  → but in practice the masks are stored as
# 8-class indexed or paletted PNGs where the FV class pixel value is 32 (0b00100000)
# when packed as a single channel, OR as an RGB where FV = (0, 128, 128).
# The SUIM code reads masks as RGB and checks the following pattern per the paper:
#   BW=0, HD=1, PF=2, WR=3, RO=4, RI=5, FV=6, SR=7
# In RGB paletted mode, class index 6 corresponds to FV.
# In the actual released masks (3-channel PNG), FV pixels have G≥128 AND B≥128 AND R<128.
FV_RGB_LOWER = (0, 100, 100)
FV_RGB_UPPER = (80, 255, 255)


def _get_gdrive_download_url() -> str:
    """
    Get a valid Google Drive download URL by extracting the UUID from the
    virus-scan warning page. The UUID is per-session and changes each time.
    """
    headers = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"}
    session = requests.Session()
    session.headers.update(headers)

    # Step 1: trigger the virus scan warning page to get a UUID
    warn_url = f"https://drive.google.com/uc?id={GDRIVE_FILE_ID}&export=download"
    r = session.get(warn_url, timeout=30)
    r.raise_for_status()

    uuid_match = re.search(r'name="uuid"\s+value="([^"]+)"', r.text)
    if not uuid_match:
        # Fallback: sometimes the download is direct
        if r.headers.get("Content-Type", "").startswith("application/"):
            return warn_url
        raise RuntimeError(
            "Could not extract UUID from GDrive warning page. "
            "The file may have been moved or restricted. "
            f"Try accessing manually: https://drive.google.com/file/d/{GDRIVE_FILE_ID}/view"
        )

    uuid = uuid_match.group(1)
    download_url = (
        f"https://drive.usercontent.google.com/download"
        f"?id={GDRIVE_FILE_ID}&export=download&confirm=t&uuid={uuid}"
    )
    return download_url


def _download_zip() -> None:
    """Download SUIM.zip with progress bar, using the UUID-based GDrive URL."""
    print("Fetching Google Drive download URL (UUID extraction)...")
    dl_url = _get_gdrive_download_url()

    print(f"Downloading SUIM.zip (~5.2 GB) ...")
    headers = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"}
    session = requests.Session()
    session.headers.update(headers)

    # Support resume if partial file exists
    resume_byte = ZIP_CACHE.stat().st_size if ZIP_CACHE.exists() else 0
    if resume_byte > 0:
        print(f"  Resuming from byte {resume_byte:,}")
        session.headers["Range"] = f"bytes={resume_byte}-"

    r = session.get(dl_url, stream=True, timeout=120)
    if r.status_code == 416:
        print("  File already fully downloaded.")
        return
    r.raise_for_status()

    total = int(r.headers.get("content-length", 0)) + resume_byte
    mode = "ab" if resume_byte > 0 else "wb"

    with open(ZIP_CACHE, mode) as f, tqdm(
        initial=resume_byte, total=total, unit="B", unit_scale=True,
        desc="SUIM.zip", unit_divisor=1024
    ) as bar:
        for chunk in r.iter_content(chunk_size=1 << 20):
            f.write(chunk)
            bar.update(len(chunk))


def _fv_mask_to_yolo_boxes(mask_rgb: np.ndarray, img_w: int, img_h: int) -> list[str]:
    """
    Extract Fish+Vertebrate (FV) pixels from an RGB SUIM mask and return YOLO bbox lines.
    """
    r_ch = mask_rgb[:, :, 0].astype(np.int32)
    g_ch = mask_rgb[:, :, 1].astype(np.int32)
    b_ch = mask_rgb[:, :, 2].astype(np.int32)

    fv_mask = (
        (r_ch <= FV_RGB_UPPER[0]) & (r_ch >= FV_RGB_LOWER[0]) &
        (g_ch >= FV_RGB_LOWER[1]) & (g_ch <= FV_RGB_UPPER[1]) &
        (b_ch >= FV_RGB_LOWER[2]) & (b_ch <= FV_RGB_UPPER[2])
    ).astype(np.uint8)

    if fv_mask.sum() == 0:
        return []

    try:
        from scipy.ndimage import label as nd_label
        labeled, n_comp = nd_label(fv_mask)
    except ImportError:
        # Fallback: single bounding box over all FV pixels
        ys, xs = np.nonzero(fv_mask)
        labeled = fv_mask
        n_comp = 1

    lines = []
    for cid in range(1, n_comp + 1):
        ys, xs = np.where(labeled == cid)
        x_min, x_max = int(xs.min()), int(xs.max())
        y_min, y_max = int(ys.min()), int(ys.max())
        half = MIN_BBOX_PX // 2
        if x_max - x_min < MIN_BBOX_PX:
            cx_px = (x_min + x_max) / 2
            x_min = max(0, int(cx_px - half))
            x_max = min(img_w - 1, int(cx_px + half))
        if y_max - y_min < MIN_BBOX_PX:
            cy_px = (y_min + y_max) / 2
            y_min = max(0, int(cy_px - half))
            y_max = min(img_h - 1, int(cy_px + half))
        cx = (x_min + x_max) / 2.0 / img_w
        cy = (y_min + y_max) / 2.0 / img_h
        w = (x_max - x_min) / float(img_w)
        h = (y_max - y_min) / float(img_h)
        lines.append(f"0 {cx:.6f} {cy:.6f} {w:.6f} {h:.6f}")
    return lines


def _extract_and_convert() -> tuple[int, int]:
    """Extract SUIM.zip and convert masks to YOLO labels."""
    IMAGES_DIR.mkdir(parents=True, exist_ok=True)
    LABELS_DIR.mkdir(parents=True, exist_ok=True)

    print("Extracting and converting SUIM.zip ...")
    n_images = 0
    n_boxes = 0

    with zipfile.ZipFile(ZIP_CACHE, "r") as zf:
        all_names = zf.namelist()
        # Find image files (train_val/images/ or TEST/images/)
        img_names = [n for n in all_names if "/images/" in n and n.lower().endswith((".jpg", ".png", ".bmp"))]
        mask_by_stem: dict[str, str] = {}
        for n in all_names:
            if "/masks/" in n and n.lower().endswith(".png"):
                stem = Path(n).stem
                mask_by_stem[stem] = n

        for img_name in tqdm(img_names, desc="SUIM"):
            stem = Path(img_name).stem
            out_img = IMAGES_DIR / f"{stem}.jpg"
            out_lbl = LABELS_DIR / f"{stem}.txt"

            if out_img.exists() and out_lbl.exists():
                n_images += 1
                n_boxes += sum(1 for line in open(out_lbl) if line.strip())
                continue

            # Save image
            if not out_img.exists():
                img_bytes = zf.read(img_name)
                try:
                    pil = Image.open(io.BytesIO(img_bytes)).convert("RGB")
                    pil.save(out_img, "JPEG", quality=95)
                    img_w, img_h = pil.size
                except Exception:
                    continue
            else:
                pil = Image.open(out_img)
                img_w, img_h = pil.size

            # Generate YOLO label from mask
            if not out_lbl.exists():
                mask_name = mask_by_stem.get(stem)
                if mask_name:
                    mask_bytes = zf.read(mask_name)
                    mask_pil = Image.open(io.BytesIO(mask_bytes)).convert("RGB")
                    mask_arr = np.array(mask_pil)
                    boxes = _fv_mask_to_yolo_boxes(mask_arr, img_w, img_h)
                    with open(out_lbl, "w") as lf:
                        lf.write("\n".join(boxes))
                    n_boxes += len(boxes)
                else:
                    out_lbl.touch()  # No mask — background only

            n_images += 1

    return n_images, n_boxes


def main() -> None:
    DATASET_DIR.mkdir(parents=True, exist_ok=True)

    if DONE_SENTINEL.exists():
        n_img = sum(1 for _ in IMAGES_DIR.glob("*.jpg"))
        n_lbl = sum(1 for _ in LABELS_DIR.glob("*.txt"))
        print(f"suim: {n_img} images, {n_lbl} labels  (already complete)")
        return

    with open(DATASET_DIR / "classes.txt", "w") as f:
        f.write("fish\n")

    try:
        if not ZIP_CACHE.exists() or ZIP_CACHE.stat().st_size < 5_000_000_000:
            _download_zip()
        else:
            print("SUIM.zip already present and complete.")
    except Exception as e:
        print(f"ERROR downloading SUIM: {e}")
        sys.exit(1)

    try:
        n_img, n_box = _extract_and_convert()
    except Exception as e:
        print(f"ERROR extracting SUIM: {e}")
        sys.exit(1)

    DONE_SENTINEL.touch()
    print(f"suim: {n_img} images, {n_box} labels")


if __name__ == "__main__":
    main()
