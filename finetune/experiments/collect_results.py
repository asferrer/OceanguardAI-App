"""collect_results.py — agrega metrics_*.json en results.csv + results.md.

Identifica best por mAP@0.5 hold-out e imprime ΔmAP vs base.
"""
from __future__ import annotations

import csv
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from config import (  # noqa: E402
    CLASS_NAMES,
    EXPERIMENTS,
    RESULTS_DIR,
)


def load_metrics(key: str) -> dict | None:
    p = RESULTS_DIR / f"metrics_{key}.json"
    if not p.exists():
        return None
    with p.open(encoding="utf-8") as f:
        return json.load(f)


def main() -> int:
    keys = ["base"] + [e["name"] for e in EXPERIMENTS]
    rows: list[dict] = []
    for k in keys:
        m = load_metrics(k)
        if m is None:
            rows.append({"key": k, "status": "fail"})
            continue
        per_class = m.get("per_class_mAP_50", {})
        row = {
            "key": k,
            "status": "ok",
            "mAP_50": m.get("mAP_50", 0.0),
            "mAP_50_95": m.get("mAP_50_95", 0.0),
            "mAP_75": m.get("mAP_75", 0.0),
            "json_validity": m.get("json_validity", 0.0),
            "n_predictions": m.get("n_predictions", 0),
            "n_samples": m.get("n_samples", 0),
            "mean_latency_s": m.get("mean_latency_s", 0.0),
            "elapsed_s": m.get("elapsed_s", 0.0),
        }
        for name in CLASS_NAMES:
            row[f"AP50_{name}"] = per_class.get(name, 0.0)
        rows.append(row)

    csv_path = RESULTS_DIR / "results.csv"
    md_path = RESULTS_DIR / "results.md"

    fieldnames = list(rows[0].keys()) if rows else []
    # asegúrate de incluir todas las columnas posibles
    all_cols: list[str] = []
    for r in rows:
        for k in r.keys():
            if k not in all_cols:
                all_cols.append(k)
    with csv_path.open("w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=all_cols)
        w.writeheader()
        for r in rows:
            w.writerow(r)

    # Identifica best entre exp_* (no base)
    base_map50 = next((r.get("mAP_50", 0.0) for r in rows if r["key"] == "base" and r.get("status") == "ok"), 0.0)
    candidate_rows = [r for r in rows if r["key"] != "base" and r.get("status") == "ok"]
    best = max(candidate_rows, key=lambda r: r.get("mAP_50", 0.0), default=None)

    with md_path.open("w", encoding="utf-8") as f:
        f.write("# OceanGuard AI — Grid de 9 Experimentos (Gemma 4 E2B LoRA)\n\n")
        f.write(f"Base mAP@0.5: **{base_map50:.4f}**\n\n")
        if best is not None:
            delta = best.get("mAP_50", 0.0) - base_map50
            f.write(f"Best: **{best['key']}** mAP@0.5={best['mAP_50']:.4f}  Δ={delta:+.4f}\n\n")
        f.write("## Resumen\n\n")
        f.write("| key | mAP@0.5 | mAP@0.5:0.95 | JSON-validity | n_preds | latency(s) | status |\n")
        f.write("|---|---|---|---|---|---|---|\n")
        for r in rows:
            if r.get("status") != "ok":
                f.write(f"| {r['key']} | — | — | — | — | — | {r.get('status')} |\n")
                continue
            f.write(
                f"| {r['key']} | {r['mAP_50']:.4f} | {r['mAP_50_95']:.4f} | "
                f"{r['json_validity']:.3f} | {r['n_predictions']} | {r['mean_latency_s']:.2f} | ok |\n"
            )

        f.write("\n## Per-class mAP@0.5\n\n")
        f.write("| key | " + " | ".join(CLASS_NAMES) + " |\n")
        f.write("|---" + "|---" * len(CLASS_NAMES) + "|\n")
        for r in rows:
            if r.get("status") != "ok":
                continue
            cells = [f"{r.get(f'AP50_{n}', 0.0):.3f}" for n in CLASS_NAMES]
            f.write(f"| {r['key']} | " + " | ".join(cells) + " |\n")

    # best.json para ser leído por auto_promote_best
    best_meta = {"best_key": best["key"] if best else None,
                 "best_mAP_50": best["mAP_50"] if best else 0.0,
                 "base_mAP_50": base_map50,
                 "delta": (best["mAP_50"] - base_map50) if best else 0.0}
    (RESULTS_DIR / "best.json").write_text(json.dumps(best_meta, indent=2, ensure_ascii=False), encoding="utf-8")

    print(f"[collect] results.csv: {csv_path}")
    print(f"[collect] results.md:  {md_path}")
    print(f"[collect] best:        {best_meta}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
