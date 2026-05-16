"""run_chain_exp11_eval_then_exp12.py — cadena automatica post-exp11.

Ejecuta en serie SIN timeout del orchestrator (eval bloqueante directo via eval_grid.py):

  1. Re-eval exp11 sobre el adapter ya guardado (recupera el metrics_*.json que falto por timeout 30min).
  2. Train exp12_vision_lora via train_etapa1.py --config experiments/configs/exp12_vision_lora.yaml.
  3. Eval exp12 sobre su adapter recien guardado.
  4. collect_results.py para refrescar results.md / best.json / results.csv.
  5. auto_promote_best.py para empujar a HuggingFace si el mejor cambio.

Cada step escribe stdout/stderr a outputs/logs/chain_<step>.log y deja una fila
en outputs/logs/chain_summary.log con timestamp + rc.

Idempotencia:
  - Si metrics_exp11_real_synth_full.json YA existe (no es {} ni invalido), salta step 1.
  - Si outputs/lora_exp12_vision_lora/adapter/ ya existe, salta step 2.
  - Si metrics_exp12_vision_lora.json ya existe, salta step 3.
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
import time
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
EXP_DIR = REPO_ROOT / "experiments"
RESULTS_DIR = EXP_DIR / "results"
LOGS_DIR = REPO_ROOT / "outputs" / "logs"
LOGS_DIR.mkdir(parents=True, exist_ok=True)

PYTHON = sys.executable

EXP11_ADAPTER = REPO_ROOT / "outputs" / "lora_exp11_real_synth_full" / "adapter"
EXP12_ADAPTER = REPO_ROOT / "outputs" / "lora_exp12_vision_lora" / "adapter"
EXP11_METRICS = RESULTS_DIR / "metrics_exp11_real_synth_full.json"
EXP12_METRICS = RESULTS_DIR / "metrics_exp12_vision_lora.json"

SUMMARY_LOG = LOGS_DIR / "chain_summary.log"


def log_summary(line: str) -> None:
    ts = time.strftime("%Y-%m-%d %H:%M:%S")
    msg = f"[{ts}] {line}\n"
    print(msg, end="")
    with SUMMARY_LOG.open("a", encoding="utf-8") as f:
        f.write(msg)


def env_for_subproc() -> dict:
    e = os.environ.copy()
    e["TORCHDYNAMO_DISABLE"] = "1"
    e["UNSLOTH_COMPILE_DISABLE"] = "1"
    e["PYTORCH_CUDA_ALLOC_CONF"] = "expandable_segments:True,max_split_size_mb:512"
    e["TOKENIZERS_PARALLELISM"] = "false"
    e["PYTHONUNBUFFERED"] = "1"
    e["HF_HUB_OFFLINE"] = "0"
    return e


def run(cmd: list[str], log_name: str) -> int:
    log_path = LOGS_DIR / log_name
    log_summary(f"START {log_name}: {' '.join(cmd)}")
    with log_path.open("a", encoding="utf-8") as logf:
        logf.write(f"\n{'='*70}\n[{time.strftime('%Y-%m-%d %H:%M:%S')}] CMD: {' '.join(cmd)}\n{'='*70}\n")
        logf.flush()
        proc = subprocess.Popen(cmd, cwd=str(REPO_ROOT), env=env_for_subproc(),
                                stdout=logf, stderr=subprocess.STDOUT,
                                bufsize=0)
        rc = proc.wait()
        logf.write(f"\n[exit code: {rc}]\n")
    log_summary(f"END   {log_name} rc={rc}")
    return rc


def metrics_valid(path: Path) -> bool:
    if not path.exists():
        return False
    try:
        with path.open(encoding="utf-8") as f:
            data = json.load(f)
        return isinstance(data, dict) and "mAP_50" in data
    except Exception:
        return False


def main() -> int:
    log_summary("=" * 60)
    log_summary("CHAIN START: exp11 eval -> exp12 train -> exp12 eval -> collect -> promote")
    log_summary("=" * 60)

    # Step 1: re-eval exp11 (no timeout)
    if metrics_valid(EXP11_METRICS):
        log_summary(f"SKIP step1: metrics already valid at {EXP11_METRICS}")
    elif not EXP11_ADAPTER.exists():
        log_summary(f"ABORT step1: adapter missing at {EXP11_ADAPTER}")
        return 1
    else:
        rc = run([
            PYTHON, str(EXP_DIR / "eval_grid.py"),
            "--key", "exp11_real_synth_full",
            "--adapter", str(EXP11_ADAPTER),
        ], "chain_step1_eval_exp11.log")
        if rc != 0:
            log_summary(f"ABORT step1: eval_exp11 rc={rc}")
            return rc

    # Step 2: train exp12_vision_lora directly via train_etapa1.py (no orchestrator timeout)
    if EXP12_ADAPTER.exists() and any(EXP12_ADAPTER.iterdir()):
        log_summary(f"SKIP step2: adapter already exists at {EXP12_ADAPTER}")
    else:
        rc = run([
            PYTHON, str(REPO_ROOT / "train_etapa1.py"),
            "--config", str(EXP_DIR / "configs" / "exp12_vision_lora.yaml"),
        ], "chain_step2_train_exp12.log")
        if rc != 0:
            log_summary(f"ABORT step2: train_exp12 rc={rc}")
            return rc

    # Step 3: eval exp12 (no timeout)
    if metrics_valid(EXP12_METRICS):
        log_summary(f"SKIP step3: metrics already valid at {EXP12_METRICS}")
    elif not EXP12_ADAPTER.exists():
        log_summary(f"ABORT step3: adapter missing at {EXP12_ADAPTER}")
        return 1
    else:
        rc = run([
            PYTHON, str(EXP_DIR / "eval_grid.py"),
            "--key", "exp12_vision_lora",
            "--adapter", str(EXP12_ADAPTER),
        ], "chain_step3_eval_exp12.log")
        if rc != 0:
            log_summary(f"ABORT step3: eval_exp12 rc={rc}")
            return rc

    # Step 4: collect results
    rc = run([
        PYTHON, str(EXP_DIR / "collect_results.py"),
    ], "chain_step4_collect.log")
    if rc != 0:
        log_summary(f"WARN step4: collect rc={rc} (continuando)")

    # Step 5: auto-promote best
    rc = run([
        PYTHON, str(EXP_DIR / "auto_promote_best.py"),
    ], "chain_step5_promote.log")
    if rc != 0:
        log_summary(f"WARN step5: promote rc={rc} (no critico)")

    log_summary("=" * 60)
    log_summary("CHAIN DONE")
    log_summary("=" * 60)
    return 0


if __name__ == "__main__":
    sys.exit(main())
