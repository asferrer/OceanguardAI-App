"""pipeline.py — orquestador end-to-end. UN solo comando:

    cd finetune
    python experiments/pipeline.py

Pasos (con hard timeout 6h):
  T+0:00  prepare_datasets.py
  T+0:03  generate_configs.py
  T+0:04  run_experiments.py streaming
  T+3:30  collect_results.py
  T+3:35  auto_promote_best.py
  T+4:30  update_docs.py
  T+5:00  smoke_inference.py
  T+5:15  resumen final
  T+6:00  HARD: abort pendientes, best-so-far
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from config import EXP_OUTPUTS_BASE, REPO_ROOT, RESULTS_DIR  # noqa: E402

PIPELINE_DEADLINE_S = float(os.environ.get("PIPELINE_DEADLINE_S", str(6 * 3600)))
EXPERIMENTS_BUDGET_S = float(os.environ.get("EXPERIMENTS_BUDGET_S", str(3.5 * 3600)))


def env_for_subproc() -> dict:
    e = os.environ.copy()
    e["TORCHDYNAMO_DISABLE"] = "1"
    e["UNSLOTH_COMPILE_DISABLE"] = "1"
    e["PYTORCH_CUDA_ALLOC_CONF"] = "expandable_segments:True,max_split_size_mb:512"
    e["TOKENIZERS_PARALLELISM"] = "false"
    return e


def stage(name: str, cmd: list[str], cwd: Path = REPO_ROOT, timeout: float | None = None,
          allow_fail: bool = False) -> tuple[int, float]:
    print(f"\n{'='*60}\n[stage] {name}\n[stage] cmd: {' '.join(cmd)}\n{'='*60}")
    t0 = time.time()
    try:
        rc = subprocess.call(cmd, cwd=str(cwd), env=env_for_subproc(), timeout=timeout)
    except subprocess.TimeoutExpired:
        print(f"[stage:{name}] TIMEOUT")
        rc = 124
    dt = time.time() - t0
    print(f"[stage:{name}] rc={rc} elapsed={dt:.0f}s")
    if rc != 0 and not allow_fail:
        print(f"[stage:{name}] FAIL (allow_fail=False); continuamos con cuidado")
    return rc, dt


def remaining(t0: float) -> float:
    return PIPELINE_DEADLINE_S - (time.time() - t0)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--skip-prepare", action="store_true")
    parser.add_argument("--skip-configs", action="store_true")
    parser.add_argument("--skip-train", action="store_true")
    parser.add_argument("--skip-promote", action="store_true")
    parser.add_argument("--skip-docs", action="store_true")
    parser.add_argument("--skip-smoke", action="store_true")
    args = parser.parse_args()

    started = time.time()
    log: list[dict] = []

    def log_stage(name, rc, dt):
        log.append({"stage": name, "rc": rc, "elapsed_s": dt, "ts": time.time()})

    if not args.skip_prepare:
        rc, dt = stage("prepare_datasets", [sys.executable, "experiments/prepare_datasets.py"])
        log_stage("prepare_datasets", rc, dt)

    if not args.skip_configs:
        rc, dt = stage("generate_configs", [sys.executable, "experiments/generate_configs.py"])
        log_stage("generate_configs", rc, dt)

    # Train + eval streaming con budget
    if not args.skip_train:
        budget = min(EXPERIMENTS_BUDGET_S, max(60.0, remaining(started) - 30 * 60))
        cmd = [sys.executable, "experiments/run_experiments.py", "--global-deadline-s", str(budget)]
        rc, dt = stage("run_experiments", cmd, allow_fail=True)
        log_stage("run_experiments", rc, dt)

    # Collect
    rc, dt = stage("collect_results", [sys.executable, "experiments/collect_results.py"], allow_fail=True)
    log_stage("collect_results", rc, dt)

    # Promote
    if not args.skip_promote:
        rc, dt = stage("auto_promote_best", [sys.executable, "experiments/auto_promote_best.py"], allow_fail=True)
        log_stage("auto_promote_best", rc, dt)

    # Update docs
    if not args.skip_docs:
        rc, dt = stage("update_docs", [sys.executable, "experiments/update_docs.py"], allow_fail=True)
        log_stage("update_docs", rc, dt)

    # Smoke
    if not args.skip_smoke and remaining(started) > 5 * 60:
        rc, dt = stage("smoke_inference", [sys.executable, "experiments/smoke_inference.py"], allow_fail=True)
        log_stage("smoke_inference", rc, dt)

    # Resumen
    elapsed = time.time() - started
    summary = {
        "elapsed_total_s": elapsed,
        "pipeline_deadline_s": PIPELINE_DEADLINE_S,
        "stages": log,
        "results_csv": str(RESULTS_DIR / "results.csv"),
        "results_md": str(RESULTS_DIR / "results.md"),
        "grid_best": str(EXP_OUTPUTS_BASE / "grid_best"),
    }
    out = RESULTS_DIR / "_pipeline_summary.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(summary, indent=2, ensure_ascii=False), encoding="utf-8")

    print("\n" + "=" * 60)
    print(f"PIPELINE DONE — elapsed={elapsed/60:.1f} min")
    print(f"  results.csv : {summary['results_csv']}")
    print(f"  results.md  : {summary['results_md']}")
    print(f"  grid_best   : {summary['grid_best']}")
    print(f"  summary     : {out}")
    print("=" * 60)
    return 0


if __name__ == "__main__":
    sys.exit(main())
