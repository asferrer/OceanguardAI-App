"""resume_exp12.py — reanudar training de exp12_vision_lora desde checkpoint-1050.

Diseñado para correr con el WMI deadlocked: usa wmi_bypass + runpy para lanzar
train_etapa1.py con --resume.

Pasos:
  1. Resume train_etapa1.py desde checkpoint-1050 hasta step 2292 (final)
     - Aplica wmi_bypass antes de cualquier import torch
     - Si train termina OK, train_etapa1 escribe el adapter final en outputs/.../adapter/
       (sobrescribe el adapter parcial — pero ya tenemos backup en adapter_partial_step1050/)
  2. Eval exp12 sobre el adapter completo (clave 'exp12_vision_lora_full')
  3. collect_results.py + auto_promote_best.py
"""
from __future__ import annotations

import sys
import os
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
import wmi_bypass  # noqa: E402, F401

import subprocess
import time

REPO_ROOT = Path(__file__).resolve().parent
EXP_DIR = REPO_ROOT / "experiments"
RESULTS_DIR = EXP_DIR / "results"
LOGS_DIR = REPO_ROOT / "outputs" / "logs"
LOGS_DIR.mkdir(parents=True, exist_ok=True)

EXP12_DIR = REPO_ROOT / "outputs" / "lora_exp12_vision_lora"
CKPT_1050 = EXP12_DIR / "checkpoint-1050"
EXP12_ADAPTER = EXP12_DIR / "adapter"

CONFIG_YAML = EXP_DIR / "configs" / "exp12_vision_lora.yaml"
SUMMARY_LOG = LOGS_DIR / "resume_exp12_summary.log"


def log(msg: str) -> None:
    ts = time.strftime("%Y-%m-%d %H:%M:%S")
    line = f"[{ts}] {msg}\n"
    print(line, end="", flush=True)
    with SUMMARY_LOG.open("a", encoding="utf-8") as f:
        f.write(line)


def env_for_subproc() -> dict:
    e = os.environ.copy()
    e["TORCHDYNAMO_DISABLE"] = "1"
    e["UNSLOTH_COMPILE_DISABLE"] = "1"
    e["PYTORCH_CUDA_ALLOC_CONF"] = "expandable_segments:True,max_split_size_mb:512"
    e["TOKENIZERS_PARALLELISM"] = "false"
    e["PYTHONUNBUFFERED"] = "1"
    e["PYTHONPATH"] = str(REPO_ROOT) + (";" + e.get("PYTHONPATH", "") if e.get("PYTHONPATH") else "")
    return e


def run_subproc(cmd: list[str], log_name: str) -> int:
    log_path = LOGS_DIR / log_name
    log(f"START {log_name}")
    log(f"  cmd: {' '.join(cmd)}")
    with log_path.open("a", encoding="utf-8") as logf:
        logf.write(f"\n{'='*70}\n[{time.strftime('%Y-%m-%d %H:%M:%S')}] CMD: {' '.join(cmd)}\n{'='*70}\n")
        logf.flush()
        proc = subprocess.Popen(cmd, cwd=str(REPO_ROOT), env=env_for_subproc(),
                                stdout=logf, stderr=subprocess.STDOUT, bufsize=0)
        rc = proc.wait()
        logf.write(f"\n[exit code: {rc}]\n")
    log(f"END   {log_name} rc={rc}")
    return rc


def step1_resume_train() -> int:
    log("=" * 60)
    log("STEP 1: resume train_etapa1.py from checkpoint-1050")
    if not CKPT_1050.exists():
        log(f"  ABORT: checkpoint-1050 not found at {CKPT_1050}")
        return 1
    return run_subproc([
        sys.executable, "-c",
        f"import sys, runpy; sys.path.insert(0, r'{REPO_ROOT}'); import wmi_bypass; "
        f"sys.argv = [r'{REPO_ROOT / 'train_etapa1.py'}', "
        f"'--config', r'{CONFIG_YAML}', '--resume', r'{CKPT_1050}']; "
        f"runpy.run_path(r'{REPO_ROOT / 'train_etapa1.py'}', run_name='__main__')"
    ], "resume_step1_train.log")


def step2_eval_full() -> int:
    log("=" * 60)
    log("STEP 2: eval exp12 (full training)")
    if not EXP12_ADAPTER.exists():
        log(f"  ABORT: adapter not found at {EXP12_ADAPTER}")
        return 1
    # We OVERWRITE the previous metrics_exp12_vision_lora.json since the train completed
    return run_subproc([
        sys.executable, "-c",
        f"import sys, runpy; sys.path.insert(0, r'{REPO_ROOT}'); import wmi_bypass; "
        f"sys.argv = [r'{EXP_DIR / 'eval_grid.py'}', '--key', 'exp12_vision_lora', "
        f"'--adapter', r'{EXP12_ADAPTER}']; "
        f"runpy.run_path(r'{EXP_DIR / 'eval_grid.py'}', run_name='__main__')"
    ], "resume_step2_eval.log")


def step3_collect() -> int:
    log("=" * 60)
    log("STEP 3: collect_results")
    return run_subproc([
        sys.executable, str(EXP_DIR / "collect_results.py"),
    ], "resume_step3_collect.log")


def step4_promote() -> int:
    log("=" * 60)
    log("STEP 4: auto_promote_best")
    return run_subproc([
        sys.executable, str(EXP_DIR / "auto_promote_best.py"),
    ], "resume_step4_promote.log")


def main() -> int:
    log("=" * 60)
    log("RESUME CHAIN START: exp12 from checkpoint-1050 -> step 2292")
    log("=" * 60)

    rc = step1_resume_train()
    if rc != 0:
        log(f"ABORT step1 train rc={rc}")
        log("(adapter_partial_step1050/ remains intact as fallback)")
        return rc

    rc = step2_eval_full()
    if rc != 0:
        log(f"ABORT step2 eval rc={rc}")
        return rc

    rc = step3_collect()
    if rc != 0:
        log(f"WARN step3 rc={rc} (continuing)")

    rc = step4_promote()
    if rc != 0:
        log(f"WARN step4 rc={rc} (non-critical)")

    log("=" * 60)
    log("RESUME DONE")
    log("=" * 60)
    return 0


if __name__ == "__main__":
    sys.exit(main())
