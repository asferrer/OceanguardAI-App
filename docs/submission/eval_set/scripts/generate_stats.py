#!/usr/bin/env python
"""Print descriptive statistics over an evaluation-set COCO JSON.

Output: per-class object count, image count, per-image debris density
histogram, and bbox area distribution (small / medium / large per
COCO convention: small <= 32^2, medium <= 96^2, large > 96^2).
"""

from __future__ import annotations

import argparse
import json
from collections import Counter, defaultdict
from pathlib import Path

from tabulate import tabulate

CANONICAL_NAMES = {
    0: "Bottle", 1: "Can", 2: "Fishing_Net", 3: "Glove", 4: "Mask",
    5: "Metal_Debris", 6: "Plastic_Debris", 7: "Tire",
}

SMALL_THRESH = 32 ** 2
MEDIUM_THRESH = 96 ** 2


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("coco_json", type=Path)
    args = p.parse_args()

    with args.coco_json.open(encoding="utf-8") as f:
        coco = json.load(f)

    images = coco.get("images", [])
    anns = coco.get("annotations", [])

    print(f"Images:      {len(images)}")
    print(f"Annotations: {len(anns)}")
    print()

    # Per-class counts
    class_counts = Counter(a["category_id"] for a in anns)
    rows = []
    for cid in sorted(CANONICAL_NAMES):
        rows.append([
            cid,
            CANONICAL_NAMES[cid],
            class_counts.get(cid, 0),
            f"{(class_counts.get(cid, 0) / max(len(anns), 1) * 100):.1f}%",
        ])
    print("Per-class object counts:")
    print(tabulate(rows, headers=["id", "name", "count", "pct"],
                   tablefmt="github"))
    print()

    # Density per image
    per_image = defaultdict(int)
    for a in anns:
        per_image[a["image_id"]] += 1
    densities = sorted(per_image.values())
    if densities:
        print("Debris per image:")
        print(f"  min:  {densities[0]}")
        print(f"  p50:  {densities[len(densities) // 2]}")
        print(f"  p95:  {densities[int(len(densities) * 0.95)]}")
        print(f"  max:  {densities[-1]}")
        print(f"  mean: {sum(densities) / len(densities):.2f}")
        print()

    # Bbox area distribution (COCO size conventions)
    sizes = Counter()
    for a in anns:
        x, y, w, h = a["bbox"]
        area = w * h
        if area <= SMALL_THRESH:
            sizes["small"] += 1
        elif area <= MEDIUM_THRESH:
            sizes["medium"] += 1
        else:
            sizes["large"] += 1

    total = sum(sizes.values()) or 1
    rows = [[k, sizes[k], f"{sizes[k] / total * 100:.1f}%"]
            for k in ("small", "medium", "large")]
    print("Bbox area distribution (COCO convention):")
    print(tabulate(rows, headers=["size", "count", "pct"], tablefmt="github"))

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
