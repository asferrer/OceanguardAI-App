"""generate_configs.py — emite 9 YAMLs + 9 train.jsonl derivados del template etapa1.yaml.

Por cada exp:
  - subsamplea synth_pool y real_pool según synth_n/real_n/strategies
  - concatena + shuffle
  - escribe experiments/splits/<exp>/train.jsonl (chat format)
  - escribe experiments/configs/<exp>.yaml con overrides (dataset, output_dir,
    num_train_epochs=1, save_steps=50, save_total_limit=1, eval_strategy=no)
"""
from __future__ import annotations

import json
import random
import sys
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any

import yaml

sys.path.insert(0, str(Path(__file__).resolve().parent))
from config import (  # noqa: E402
    CATEGORY_MAP,
    CONFIGS_DIR,
    EXP_OUTPUTS_BASE,
    EXPERIMENTS,
    ETAPA1_TEMPLATE,
    MINORITY_CAT_IDS,
    SAVE_STEPS,
    SAVE_TOTAL_LIMIT,
    SPLITS_DIR,
    TRAIN_EPOCHS,
    DETECTION_PROMPT,
    SYSTEM_PROMPT,
)
from prepare_datasets import sample_to_chat  # noqa: E402

SEED = 42


def load_pool(path: Path) -> list[dict]:
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def stratified_sample(pool: list[dict], n: int, rng: random.Random) -> list[dict]:
    if n <= 0:
        return []
    if n >= len(pool):
        return list(pool)
    by_tag: dict[int, list[dict]] = defaultdict(list)
    for s in pool:
        by_tag[s["tag"]].append(s)
    for tag in by_tag:
        rng.shuffle(by_tag[tag])
    total = len(pool)
    chosen: list[dict] = []
    for tag, lst in by_tag.items():
        quota = max(1, round(len(lst) * n / total)) if lst else 0
        quota = min(quota, len(lst))
        chosen.extend(lst[:quota])
    rng.shuffle(chosen)
    if len(chosen) > n:
        chosen = chosen[:n]
    elif len(chosen) < n:
        # rellenar con el resto en orden aleatorio (sin duplicar)
        taken = {s["img_id"] for s in chosen}
        rest = [s for s in pool if s["img_id"] not in taken]
        rng.shuffle(rest)
        chosen.extend(rest[: n - len(chosen)])
    return chosen


def sample_synth(pool: list[dict], n: int, strategy: str | None, rng: random.Random) -> list[dict]:
    if n <= 0 or strategy is None:
        return []
    if strategy == "random":
        rng.shuffle(pool := list(pool))
        return pool[:n]
    if strategy == "minority_only":
        filtered = [s for s in pool if any(c in MINORITY_CAT_IDS for c in s["classes"])]
        rng.shuffle(filtered)
        if not filtered:
            return []
        # si no llega a n, ciclar (oversample dentro del filtro)
        out: list[dict] = []
        i = 0
        while len(out) < n:
            out.append(filtered[i % len(filtered)])
            i += 1
        return out[:n]
    if strategy == "inverse_proportional":
        cnt: Counter = Counter()
        for s in pool:
            for c in s["classes"]:
                cnt[c] += 1
        max_c = max(cnt.values()) if cnt else 1
        weights = []
        for s in pool:
            w = 0.0
            for c in s["classes"]:
                w += max_c / max(1, cnt[c])
            weights.append(max(w, 1e-6))
        # weighted sampling sin reemplazo simulado: orden por w*random
        indexed = list(range(len(pool)))
        # Efraimidis-Spirakis weighted reservoir
        keys = [rng.random() ** (1.0 / w) for w in weights]
        order = sorted(indexed, key=lambda i: keys[i], reverse=True)
        return [pool[i] for i in order[:n]]
    raise ValueError(f"unknown synth strategy: {strategy}")


def sample_real(pool: list[dict], n: int, strategy: str | None, rng: random.Random) -> list[dict]:
    if n <= 0 or strategy is None:
        return []
    if strategy == "stratified":
        return stratified_sample(pool, n, rng)
    raise ValueError(f"unknown real strategy: {strategy}")


def load_template() -> dict:
    with open(ETAPA1_TEMPLATE, encoding="utf-8") as f:
        return yaml.safe_load(f)


def write_yaml(path: Path, data: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as f:
        yaml.safe_dump(data, f, sort_keys=False)


def write_jsonl(path: Path, records) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as f:
        for r in records:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")


def main() -> int:
    real_pool = load_pool(SPLITS_DIR / "real_pool.json")
    synth_pool = load_pool(SPLITS_DIR / "synth_pool.json")
    print(f"[gen] real_pool={len(real_pool)}  synth_pool={len(synth_pool)}")

    template = load_template()
    CONFIGS_DIR.mkdir(parents=True, exist_ok=True)

    summary: list[dict] = []
    for exp in EXPERIMENTS:
        name = exp["name"]
        rng = random.Random(SEED + abs(hash(name)) % (2**31))
        synth_pick = sample_synth(synth_pool, exp["synth_n"], exp.get("synth_strategy"), rng)
        real_pick = sample_real(real_pool, exp["real_n"], exp.get("real_strategy"), rng)

        all_samples = synth_pick + real_pick
        rng.shuffle(all_samples)

        exp_split_dir = SPLITS_DIR / name
        train_jsonl_path = exp_split_dir / "train.jsonl"
        chat_records = [sample_to_chat(s, include_assistant=True) for s in all_samples]
        # SFTTrainer ignora claves extra siempre que "messages" exista; conservamos messages-only
        # para no inflar memoria.
        write_jsonl(train_jsonl_path, [{"messages": r["messages"]} for r in chat_records])

        out_dir = EXP_OUTPUTS_BASE / f"lora_{name}"
        cfg = dict(template)
        exp_epochs = int(exp.get("epochs", TRAIN_EPOCHS))
        cfg.update({
            "dataset": str(train_jsonl_path).replace("\\", "/"),
            "eval_dataset": None,
            "output_dir": str(out_dir).replace("\\", "/"),
            "num_train_epochs": exp_epochs,
            "save_steps": SAVE_STEPS,
            "save_total_limit": SAVE_TOTAL_LIMIT,
            "eval_strategy": "no",
            "eval_steps": 999999,
            "run_name": f"gemma4-{name}",
            "report_to": "none",
            "seed": SEED,
        })
        # remove None values that pyyaml would render as nulls if not desired
        if cfg.get("eval_dataset") is None:
            cfg.pop("eval_dataset", None)

        cfg_path = CONFIGS_DIR / f"{name}.yaml"
        write_yaml(cfg_path, cfg)

        # distribución de clases en train
        cnt: Counter = Counter()
        for s in all_samples:
            for d in s["dets"]:
                cnt[d["label"]] += 1
        info = {
            "name": name,
            "synth_n": len(synth_pick),
            "real_n": len(real_pick),
            "total_train_samples": len(all_samples),
            "yaml": str(cfg_path).replace("\\", "/"),
            "train_jsonl": str(train_jsonl_path).replace("\\", "/"),
            "output_dir": cfg["output_dir"],
            "class_dist": dict(cnt),
        }
        summary.append(info)
        print(f"[gen] {name}: synth={len(synth_pick)} real={len(real_pick)} total={len(all_samples)}")

    with (SPLITS_DIR / "_configs_summary.json").open("w", encoding="utf-8") as f:
        json.dump(summary, f, indent=2, ensure_ascii=False)
    print("[gen] DONE")
    return 0


if __name__ == "__main__":
    sys.exit(main())
