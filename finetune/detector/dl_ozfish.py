"""
OzFish downloader — AIMS BRUVS fish dataset.

IMPORTANT — USER ACTION REQUIRED:
    OzFish images are hosted on the Pawsey Data Portal (storage.pawsey.org.au),
    which is a React SPA that does not expose direct HTTP file download URLs.
    Every path under /public/m/FDFML/ returns the SPA HTML regardless of Accept headers.
    The Pawsey Swift/Acacia object-store endpoints are not publicly routable from outside
    Pawsey's network.

    TO DOWNLOAD MANUALLY:
        1. Open a browser and go to https://storage.pawsey.org.au/public/m/FDFML/
        2. Navigate to the "labelled/frames" directory and download all images.
        3. Navigate to "labelled/speciesboxes" for the species-level bounding box CSV/JSON.
        4. Extract everything to:
               finetune/detector/datasets/ozfish/images/
               finetune/detector/datasets/ozfish/annotations/
        5. Re-run this script to convert annotations to YOLO format.

    ALTERNATIVE (automatic but NOT original frames — masked 512x512 crops, no species labels):
        Zenodo record 13121950 contains oz_masked_512.zip (~4 GB), which are fish crops
        derived from OzFish frames. These have NO bounding box annotations (crops ARE the
        boxes), so they can be used as classification crops but NOT for bbox training directly.
        The Zenodo files are: https://zenodo.org/api/records/13121950/files/oz_masked_512.zip/content
        We do NOT download this automatically because it does not match the bbox contract.

License: CC BY 3.0 AU (NOT CC-BY 4.0 — the README says 3.0 Australian, not 4.0 International).
Citation: AIMS, UWA, Curtin University (2019), https://doi.org/10.25845/5e28f062c5097

Dependencies: pip install requests tqdm
"""

from __future__ import annotations

import json
import os
import sys
from pathlib import Path

SLUG = "ozfish"
DATASET_DIR = Path(__file__).parent / "datasets" / SLUG
IMAGES_DIR = DATASET_DIR / "images"
LABELS_DIR = DATASET_DIR / "labels"


def _convert_sagemaker_manifest_to_yolo(annotations_dir: Path, images_dir: Path, labels_dir: Path) -> tuple[int, int]:
    """
    Convert OzFish SageMaker Ground Truth JSON manifests to YOLO txt format.

    Manifest line format (from README):
        {"source-ref": "E000501_R.MP4.31568.png",
         "20191014": {"annotations": [
             {"class_id": 0, "width": 139, "top": 306, "height": 84, "left": 588.5}
         ], "image_size": [{"width": 1920, "depth": 3, "height": 1080}]}}

    The date key (e.g. "20191014") is the labelling job ID — pick the first non-metadata key.
    """
    labels_dir.mkdir(parents=True, exist_ok=True)
    n_images = 0
    n_labels = 0

    manifest_files = list(annotations_dir.glob("*.manifest")) + list(annotations_dir.glob("output.manifest"))
    if not manifest_files:
        print(f"  No manifest files found in {annotations_dir}")
        return 0, 0

    for manifest_path in manifest_files:
        with open(manifest_path, "r") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    record = json.loads(line)
                except json.JSONDecodeError:
                    continue

                image_name = os.path.basename(record.get("source-ref", ""))
                if not image_name:
                    continue

                # Find the annotation key (first key that is not source-ref and not *-metadata)
                ann_key = None
                for k in record:
                    if k == "source-ref" or k.endswith("-metadata"):
                        continue
                    if isinstance(record[k], dict) and "annotations" in record[k]:
                        ann_key = k
                        break
                if ann_key is None:
                    continue

                ann_block = record[ann_key]
                size_list = ann_block.get("image_size", [{}])
                img_w = float(size_list[0].get("width", 1920))
                img_h = float(size_list[0].get("height", 1080))
                annotations = ann_block.get("annotations", [])

                stem = Path(image_name).stem
                label_path = labels_dir / f"{stem}.txt"
                with open(label_path, "w") as lf:
                    for ann in annotations:
                        x_min = float(ann["left"])
                        y_min = float(ann["top"])
                        w = float(ann["width"])
                        h = float(ann["height"])
                        # YOLO: class cx cy w h (normalised)
                        cx = (x_min + w / 2) / img_w
                        cy = (y_min + h / 2) / img_h
                        nw = w / img_w
                        nh = h / img_h
                        lf.write(f"0 {cx:.6f} {cy:.6f} {nw:.6f} {nh:.6f}\n")
                    n_labels += len(annotations)
                n_images += 1

    return n_images, n_labels


def main() -> None:
    DATASET_DIR.mkdir(parents=True, exist_ok=True)

    # Check if images already present
    existing_images = list(IMAGES_DIR.glob("*.png")) + list(IMAGES_DIR.glob("*.jpg"))
    if existing_images:
        # Try to convert existing annotations if present
        annotations_dir = DATASET_DIR / "annotations"
        n_img, n_lbl = _convert_sagemaker_manifest_to_yolo(annotations_dir, IMAGES_DIR, LABELS_DIR)
        if n_img > 0:
            print(f"ozfish: {n_img} images, {n_lbl} labels")
        else:
            total_imgs = len(existing_images)
            existing_labels = list(LABELS_DIR.glob("*.txt"))
            print(f"ozfish: {total_imgs} images, {len(existing_labels)} labels")
        return

    print("=" * 60)
    print("OzFish: USER ACTION REQUIRED")
    print("=" * 60)
    print()
    print("OzFish images are NOT downloadable automatically.")
    print("The Pawsey Data Portal (storage.pawsey.org.au) is a React")
    print("SPA that does not serve files via direct HTTP URLs.")
    print()
    print("MANUAL STEPS:")
    print("  1. Open in a browser:")
    print("       https://storage.pawsey.org.au/public/m/FDFML/")
    print("  2. Download 'labelled/frames/' (all .png images)")
    print("  3. Download 'labelled/speciesboxes/' (JSON manifests)")
    print("  4. Extract images to:")
    print(f"       {IMAGES_DIR}")
    print("  5. Extract annotations to:")
    print(f"       {DATASET_DIR / 'annotations'}")
    print("  6. Re-run this script to convert manifests to YOLO format.")
    print()
    print("LICENSE NOTE: CC BY 3.0 AU (Australian), not CC-BY 4.0 International.")
    print("  Cite: AIMS/UWA/Curtin (2019), https://doi.org/10.25845/5e28f062c5097")
    print()
    print("Approx size: ~45k frames (labelled subset), ~2-5 GB")
    sys.exit(1)


if __name__ == "__main__":
    main()
