"""prepare_datasets.py — construye pools REAL/SYNTH y TEST hold-out 1000 (estratificado).

Outputs:
  experiments/splits/real_pool.json       — slim manifest train pool ≈ 13.6k
  experiments/splits/real_test_holdout.jsonl — chat format 1000 (para eval)
  experiments/splits/synth_pool.json      — slim manifest 10.9k
  experiments/splits/_stats.json          — distribución por split

Estratificación: tag por imagen = clase rara más prioritaria presente
  (Metal_Debris=5 > Can=1 > Mask=4 > Tire=7 > Glove=3 > Fishing_Net=2 > otros).
"""
from __future__ import annotations

import json
import os
import random
import sys
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))
from config import (  # noqa: E402
    CATEGORY_MAP,
    REAL_MANIFEST,
    REAL_IMAGE_ROOTS,
    SPLITS_DIR,
    SYNTH_IMAGES_ROOT,
    SYNTH_MANIFEST,
    TEST_HOLDOUT_SIZE,
    DETECTION_PROMPT,
    SYSTEM_PROMPT,
)

SEED = 42
PRIORITY_ORDER = [5, 1, 4, 7, 3, 2, 6, 0]  # rare → common


def clamp(v: int, lo: int = 0, hi: int = 1000) -> int:
    return max(lo, min(hi, v))


def bbox_to_gemma(bbox: list[float], w: int, h: int) -> list[int]:
    x, y, bw, bh = bbox
    return [
        clamp(round(y / h * 1000)),
        clamp(round(x / w * 1000)),
        clamp(round((y + bh) / h * 1000)),
        clamp(round((x + bw) / w * 1000)),
    ]


def resolve_real_image(file_name: str) -> str | None:
    """Intenta resolver file_name probando cada root en orden."""
    base = file_name.replace("\\", "/")
    candidates: list[Path] = []
    for root in REAL_IMAGE_ROOTS:
        candidates.append(root.parent / base)              # root.parent/JPEGImages/x.jpg
        candidates.append(root / Path(base).name)          # root/x.jpg
        candidates.append(root / base)                     # root/JPEGImages/x.jpg
    for cand in candidates:
        if cand.exists():
            return str(cand.resolve())
    return None


def resolve_synth_image(file_name: str) -> str | None:
    p = SYNTH_IMAGES_ROOT / Path(file_name).name
    if p.exists():
        return str(p.resolve())
    return None


def image_tag(classes: set[int]) -> int:
    for cat in PRIORITY_ORDER:
        if cat in classes:
            return cat
    return -1


def build_manifest(coco_json: Path, resolver, ignore_missing_cats: bool = False) -> list[dict]:
    """Lee un COCO json y devuelve lista slim de samples con dets resueltas."""
    with open(coco_json, encoding="utf-8") as f:
        data = json.load(f)

    images_by_id = {img["id"]: img for img in data["images"]}
    anns_by_img: dict[int, list[dict]] = defaultdict(list)
    for ann in data["annotations"]:
        anns_by_img[ann["image_id"]].append(ann)

    samples: list[dict] = []
    n_missing = 0
    n_no_anns = 0
    for img_id, img in images_by_id.items():
        path = resolver(img["file_name"])
        if path is None:
            n_missing += 1
            continue
        w, h = img["width"], img["height"]
        dets = []
        classes: set[int] = set()
        for ann in anns_by_img.get(img_id, []):
            cat_id = ann["category_id"]
            if cat_id not in CATEGORY_MAP:
                if ignore_missing_cats:
                    continue
                continue
            label, material = CATEGORY_MAP[cat_id]
            box = bbox_to_gemma(ann["bbox"], w, h)
            dets.append({
                "box_2d": box,
                "label": label,
                "material": material,
                "category_id": cat_id,
                "bbox_pixel": [float(x) for x in ann["bbox"]],
            })
            classes.add(cat_id)
        if not dets:
            n_no_anns += 1
            continue
        samples.append({
            "img_id": img_id,
            "image_path": path,
            "width": w,
            "height": h,
            "dets": dets,
            "classes": sorted(classes),
            "tag": image_tag(classes),
        })
    print(f"  manifest {coco_json.name}: {len(samples)} kept, missing={n_missing}, no_anns={n_no_anns}")
    return samples


def stratified_holdout(samples: list[dict], holdout_n: int, rng: random.Random) -> tuple[list[dict], list[dict]]:
    by_tag: dict[int, list[dict]] = defaultdict(list)
    for s in samples:
        by_tag[s["tag"]].append(s)
    for tag in by_tag:
        rng.shuffle(by_tag[tag])

    total = sum(len(v) for v in by_tag.values())
    holdout: list[dict] = []
    for tag, lst in by_tag.items():
        quota = max(1, round(len(lst) * holdout_n / total)) if lst else 0
        quota = min(quota, len(lst))
        holdout.extend(lst[:quota])

    # ajustar tamaño exacto
    rng.shuffle(holdout)
    while len(holdout) > holdout_n:
        holdout.pop()
    chosen_ids = {s["img_id"] for s in holdout}
    pool = [s for s in samples if s["img_id"] not in chosen_ids]
    return pool, holdout


def sample_to_chat(sample: dict, include_assistant: bool = True) -> dict:
    """Convierte slim sample a chat format multimodal."""
    dets = [
        {"box_2d": d["box_2d"], "label": d["label"], "material": d["material"]}
        for d in sample["dets"]
    ]
    msgs = [
        {"role": "system", "content": SYSTEM_PROMPT},
        {"role": "user", "content": [
            {"type": "image", "image": sample["image_path"]},
            {"type": "text", "text": DETECTION_PROMPT},
        ]},
    ]
    if include_assistant:
        msgs.append({"role": "assistant", "content": json.dumps(dets, ensure_ascii=False, separators=(",", ":"))})
    return {
        "img_id": sample["img_id"],
        "image_path": sample["image_path"],
        "width": sample["width"],
        "height": sample["height"],
        "gt_dets": sample["dets"],
        "messages": msgs,
    }


def write_json(path: Path, obj) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False, indent=2)


def write_jsonl(path: Path, records) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as f:
        for r in records:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")


def distribution(samples: list[dict]) -> dict:
    cnt = Counter()
    for s in samples:
        for d in s["dets"]:
            cnt[d["label"]] += 1
    return dict(cnt)


def main() -> int:
    rng = random.Random(SEED)
    SPLITS_DIR.mkdir(parents=True, exist_ok=True)

    print(f"[prepare] REAL manifest: {REAL_MANIFEST}")
    real_samples = build_manifest(REAL_MANIFEST, resolve_real_image)
    print(f"[prepare] SYNTH manifest: {SYNTH_MANIFEST}")
    synth_samples = build_manifest(SYNTH_MANIFEST, resolve_synth_image)

    print(f"[prepare] REAL kept: {len(real_samples)} (target hold-out: {TEST_HOLDOUT_SIZE})")
    real_pool, holdout = stratified_holdout(real_samples, TEST_HOLDOUT_SIZE, rng)
    print(f"[prepare] REAL pool: {len(real_pool)}  HOLD-OUT: {len(holdout)}")

    # write slim pools
    pool_real_path = SPLITS_DIR / "real_pool.json"
    pool_synth_path = SPLITS_DIR / "synth_pool.json"
    holdout_jsonl = SPLITS_DIR / "real_test_holdout.jsonl"

    write_json(pool_real_path, real_pool)
    write_json(pool_synth_path, synth_samples)

    # hold-out en chat format (incluye assistant para tener GT inline)
    write_jsonl(holdout_jsonl, [sample_to_chat(s, include_assistant=True) for s in holdout])

    stats = {
        "seed": SEED,
        "real_pool_n": len(real_pool),
        "real_holdout_n": len(holdout),
        "synth_pool_n": len(synth_samples),
        "real_pool_label_dist": distribution(real_pool),
        "real_holdout_label_dist": distribution(holdout),
        "synth_pool_label_dist": distribution(synth_samples),
        "real_pool_tag_dist": dict(Counter(s["tag"] for s in real_pool)),
        "real_holdout_tag_dist": dict(Counter(s["tag"] for s in holdout)),
    }
    write_json(SPLITS_DIR / "_stats.json", stats)
    print("[prepare] stats:")
    print(json.dumps(stats, indent=2))
    print("[prepare] DONE")
    return 0


if __name__ == "__main__":
    sys.exit(main())
