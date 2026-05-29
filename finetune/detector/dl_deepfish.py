"""
DeepFish downloader — JCU/AIMS fish localization dataset, CC-BY 4.0.

Source: http://data.qld.edu.au/public/Q5842/2020-AlzayatSaleh-00e364223a600e83bd9c3f5bcd91045-DeepFish/DeepFish.tar
Verified working as of 2026-05-29 (HTTP 200, Content-Length: 7,621,627,392 bytes, Accept-Ranges: bytes).

Structure inside the tar:
  DeepFish/Classification/<habitat_id>/{fish/empty}/*.jpg
  DeepFish/Localization/images/<habitat_id>/{fish/empty}/*.jpg
  DeepFish/Localization/masks/<habitat_id>/{fish/empty}/*.png  <- point annotation masks
  DeepFish/Localization/train.csv, val.csv, test.csv
  DeepFish/Segmentation/...  (pixel-level masks)

Strategy:
  - Extract ONLY the Localization split (images + masks + CSVs) to save disk.
    The full tar is 7.1 GB; the Localization split is ~1.1 GB.
  - Convert point-annotation masks (PNG with non-zero pixels = fish locations) into
    YOLO bboxes using scipy.ndimage connected-component labelling. Each connected
    component (cluster of annotated points) becomes one bounding box.
  - Images with zero non-zero pixels in the mask are written as empty label files
    (background frames — useful for negative training).

Total download (selective extraction): ~1.1 GB Localization images + masks.
Full tar: 7.1 GB — only extracted if EXTRACT_FULL=True below.
Estimated time for Localization at 100 Mbps: ~90 seconds.

Classes: 0 = fish (single class; point annotations do not carry species ID)

License: CC-BY 4.0 — confirmed at https://data.qld.edu.au
Citation: Saleh et al. (2020) Scientific Reports, https://doi.org/10.1038/s41598-020-71639-x

Dependencies: pip install requests tqdm numpy Pillow scipy
"""

from __future__ import annotations

import io
import os
import sys
import tarfile
import time
from pathlib import Path

import numpy as np
import requests
from PIL import Image
from tqdm import tqdm

SLUG = "deepfish"
DATASET_DIR = Path(__file__).parent / "datasets" / SLUG
IMAGES_DIR = DATASET_DIR / "images"
LABELS_DIR = DATASET_DIR / "labels"

TAR_URL = (
    "http://data.qld.edu.au/public/Q5842/"
    "2020-AlzayatSaleh-00e364223a600e83bd9c3f5bcd91045-DeepFish/DeepFish.tar"
)
TAR_SIZE_BYTES = 7_621_627_392

# Prefixes we want to extract (Localization only)
LOC_PREFIXES = (
    "DeepFish/Localization/",
)

DONE_SENTINEL = DATASET_DIR / ".done"

# Minimum side length for a bbox derived from a point cluster (in pixels)
MIN_BBOX_PX = 32


def _mask_to_yolo_boxes(mask_array: np.ndarray, img_w: int, img_h: int) -> list[str]:
    """
    Convert a point-annotation mask (H x W, uint8) to YOLO bbox strings.
    Each connected component of non-zero pixels becomes one bbox.
    Falls back to a minimum-size box when a component is a single pixel.
    """
    try:
        from scipy.ndimage import label as nd_label
    except ImportError:
        print("WARNING: scipy not installed — using single-box fallback.")
        ys, xs = np.nonzero(mask_array)
        if len(ys) == 0:
            return []
        x_min, x_max = int(xs.min()), int(xs.max())
        y_min, y_max = int(ys.min()), int(ys.max())
        cx = (x_min + x_max) / 2.0 / img_w
        cy = (y_min + y_max) / 2.0 / img_h
        w = max(x_max - x_min, MIN_BBOX_PX) / img_w
        h = max(y_max - y_min, MIN_BBOX_PX) / img_h
        return [f"0 {cx:.6f} {cy:.6f} {w:.6f} {h:.6f}"]

    labeled, n_components = nd_label(mask_array > 0)
    lines = []
    for comp_id in range(1, n_components + 1):
        ys, xs = np.where(labeled == comp_id)
        x_min, x_max = int(xs.min()), int(xs.max())
        y_min, y_max = int(ys.min()), int(ys.max())
        # Expand single-pixel or tiny annotations to a minimum box size
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
        w = (x_max - x_min) / img_w
        h = (y_max - y_min) / img_h
        lines.append(f"0 {cx:.6f} {cy:.6f} {w:.6f} {h:.6f}")
    return lines


def _extract_and_convert() -> tuple[int, int]:
    """
    Stream-extract the Localization split from the remote tar and convert masks to YOLO.
    Returns (n_images, n_boxes).
    """
    IMAGES_DIR.mkdir(parents=True, exist_ok=True)
    LABELS_DIR.mkdir(parents=True, exist_ok=True)

    print(f"Streaming DeepFish tar from {TAR_URL}")
    print("Extracting Localization split only (~1.1 GB of 7.1 GB total tar)")

    resp = requests.get(TAR_URL, stream=True, timeout=120,
                        headers={"User-Agent": "Mozilla/5.0"})
    resp.raise_for_status()

    # We need to buffer enough to support tarfile streaming
    # tarfile.open with fileobj requires seekable stream — use chunked approach
    # by reading the full tar via streaming and processing on-the-fly
    class StreamWrapper(io.RawIOBase):
        """Wraps a requests response iter_content into a readable stream for tarfile."""
        def __init__(self, response: requests.Response) -> None:
            self._iter = response.iter_content(chunk_size=1 << 20)
            self._buf = b""
            self._bytes_read = 0
            self._bar = tqdm(
                total=TAR_SIZE_BYTES, unit="B", unit_scale=True,
                desc="DeepFish tar", unit_divisor=1024
            )

        def read(self, n: int = -1) -> bytes:
            while len(self._buf) < n or n < 0:
                try:
                    chunk = next(self._iter)
                    self._buf += chunk
                    self._bar.update(len(chunk))
                except StopIteration:
                    break
            if n < 0:
                data, self._buf = self._buf, b""
            else:
                data, self._buf = self._buf[:n], self._buf[n:]
            self._bytes_read += len(data)
            return data

        def readable(self) -> bool:
            return True

        def close(self) -> None:
            self._bar.close()
            super().close()

    wrapper = StreamWrapper(resp)
    tf = tarfile.open(fileobj=wrapper, mode="r|")  # streaming mode (no seek)

    # Store masks temporarily so we can match image <-> mask by stem
    mask_cache: dict[str, np.ndarray] = {}
    img_cache: dict[str, tuple[int, int]] = {}  # stem -> (w, h)

    # YOLO labels written directly
    n_images = 0
    n_boxes = 0

    for member in tf:
        name = member.name
        if not any(name.startswith(pfx) for pfx in LOC_PREFIXES):
            tf.members = []  # clear internal queue
            continue

        # Determine what kind of file this is
        if not member.isfile():
            continue

        parts = Path(name).parts
        # parts: DeepFish, Localization, (images|masks), habitat, (fish|empty), filename
        if len(parts) < 6:
            continue

        subtype = parts[2]  # "images" or "masks"
        habitat = parts[3]
        presence = parts[4]  # "fish" or "empty"
        fname = parts[5]
        stem = Path(fname).stem

        key = f"{habitat}_{presence}_{stem}"
        split_dir = "train"  # default; CSVs provide proper split but we unify here

        if subtype == "images" and name.endswith(".jpg"):
            raw = tf.extractfile(member)
            if raw is None:
                continue
            img_bytes = raw.read()
            try:
                pil = Image.open(io.BytesIO(img_bytes))
                img_w, img_h = pil.size
            except Exception:
                continue
            img_cache[key] = (img_w, img_h)
            out_path = IMAGES_DIR / f"{key}.jpg"
            if not out_path.exists():
                with open(out_path, "wb") as f:
                    f.write(img_bytes)
            n_images += 1

        elif subtype == "masks" and name.endswith(".png"):
            raw = tf.extractfile(member)
            if raw is None:
                continue
            mask_bytes = raw.read()
            try:
                mask_arr = np.array(Image.open(io.BytesIO(mask_bytes)))
            except Exception:
                continue
            mask_cache[key] = mask_arr

    tf.close()

    # Generate YOLO label files
    print("Generating YOLO labels from masks...")
    for key, mask_arr in tqdm(mask_cache.items(), desc="Converting masks"):
        lbl_path = LABELS_DIR / f"{key}.txt"
        if lbl_path.exists():
            continue
        if key in img_cache:
            img_w, img_h = img_cache[key]
        else:
            img_w, img_h = mask_arr.shape[1], mask_arr.shape[0]

        boxes = _mask_to_yolo_boxes(mask_arr, img_w, img_h)
        with open(lbl_path, "w") as lf:
            lf.write("\n".join(boxes))
        n_boxes += len(boxes)

    # Also create empty labels for images without masks (all-negative frames)
    for key in img_cache:
        lbl_path = LABELS_DIR / f"{key}.txt"
        if not lbl_path.exists():
            lbl_path.touch()

    return n_images, n_boxes


def main() -> None:
    DATASET_DIR.mkdir(parents=True, exist_ok=True)

    if DONE_SENTINEL.exists():
        n_img = sum(1 for _ in IMAGES_DIR.glob("*.jpg"))
        n_lbl = sum(1 for _ in LABELS_DIR.glob("*.txt"))
        print(f"deepfish: {n_img} images, {n_lbl} labels  (already complete)")
        return

    # Write class name
    with open(DATASET_DIR / "classes.txt", "w") as f:
        f.write("fish\n")

    try:
        n_img, n_box = _extract_and_convert()
    except requests.RequestException as e:
        print(f"ERROR downloading DeepFish: {e}")
        sys.exit(1)

    DONE_SENTINEL.touch()
    print(f"deepfish: {n_img} images, {n_box} labels")


if __name__ == "__main__":
    main()
