"""recover_exp12.py — recuperacion exp12_vision_lora tras crash CUBLAS.

1. Copia archivos desde checkpoint-1050 + processor de exp11 -> adapter/
2. Eval exp12 (con wmi_bypass aplicado antes de torch)
3. collect_results.py
4. auto_promote_best.py

Diseñado para correr con el WMI deadlocked: importa wmi_bypass al inicio.
"""
from __future__ import annotations

# Critical: import wmi_bypass BEFORE any torch/transformers import
import sys
import os
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
import wmi_bypass  # noqa: E402, F401

import shutil
import subprocess
import time

REPO_ROOT = Path(__file__).resolve().parent  # finetune/
EXP_DIR = REPO_ROOT / "experiments"
RESULTS_DIR = EXP_DIR / "results"
LOGS_DIR = REPO_ROOT / "outputs" / "logs"
LOGS_DIR.mkdir(parents=True, exist_ok=True)

EXP12_DIR = REPO_ROOT / "outputs" / "lora_exp12_vision_lora"
CKPT_1050 = EXP12_DIR / "checkpoint-1050"
EXP12_ADAPTER = EXP12_DIR / "adapter"
EXP11_ADAPTER = REPO_ROOT / "outputs" / "lora_exp11_real_synth_full" / "adapter"

SUMMARY_LOG = LOGS_DIR / "recover_exp12_summary.log"


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
    # Make sure subprocess also picks up wmi_bypass
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


def step1_build_adapter() -> int:
    """Copia archivos de checkpoint-1050 + processor de exp11 -> adapter/"""
    log("=" * 60)
    log("STEP 1: build adapter/ from checkpoint-1050 + exp11 processor files")

    if EXP12_ADAPTER.exists() and any(EXP12_ADAPTER.iterdir()):
        log("  adapter/ already exists with content -> skip step 1")
        return 0

    if not CKPT_1050.exists():
        log(f"  ABORT: checkpoint-1050 not found at {CKPT_1050}")
        return 1
    if not EXP11_ADAPTER.exists():
        log(f"  ABORT: exp11 adapter not found at {EXP11_ADAPTER}")
        return 1

    EXP12_ADAPTER.mkdir(parents=True, exist_ok=True)

    # adapter_* files come from checkpoint-1050 (TRL trainer output)
    for fname in ["adapter_config.json", "adapter_model.safetensors"]:
        src = CKPT_1050 / fname
        if src.exists():
            shutil.copy2(src, EXP12_ADAPTER / fname)
            log(f"  copied {fname} from checkpoint-1050 ({src.stat().st_size} bytes)")
        else:
            log(f"  MISSING in checkpoint-1050: {fname}")

    # Processor files come from exp11 (saved at end of train via save_pretrained)
    for fname in ["processor_config.json", "chat_template.jinja", "tokenizer.json",
                  "tokenizer_config.json", "README.md"]:
        src = EXP11_ADAPTER / fname
        if src.exists():
            shutil.copy2(src, EXP12_ADAPTER / fname)
            log(f"  copied {fname} from exp11/adapter ({src.stat().st_size} bytes)")
        else:
            log(f"  MISSING in exp11/adapter: {fname}")

    files = sorted(EXP12_ADAPTER.iterdir())
    log(f"  adapter/ now has {len(files)} files:")
    for f in files:
        log(f"    {f.name}  ({f.stat().st_size} bytes)")

    return 0


def step2_eval_exp12() -> int:
    """Eval exp12 sobre el adapter recien construido."""
    log("=" * 60)
    log("STEP 2: eval exp12_vision_lora")

    metrics_path = RESULTS_DIR / "metrics_exp12_vision_lora.json"
    if metrics_path.exists() and metrics_path.stat().st_size > 100:
        log(f"  metrics already exist at {metrics_path} -> skip step 2")
        return 0

    return run_subproc([
        sys.executable, "-c",
        f"import sys, runpy; sys.path.insert(0, r'{REPO_ROOT}'); import wmi_bypass; "
        f"sys.argv = [r'{EXP_DIR / 'eval_grid.py'}', '--key', 'exp12_vision_lora', '--adapter', r'{EXP12_ADAPTER}']; "
        f"runpy.run_path(r'{EXP_DIR / 'eval_grid.py'}', run_name='__main__')"
    ], "recover_step2_eval_exp12.log")


def step3_collect() -> int:
    """collect_results.py — refresca results.md y best.json."""
    log("=" * 60)
    log("STEP 3: collect_results")
    return run_subproc([
        sys.executable, str(EXP_DIR / "collect_results.py"),
    ], "recover_step3_collect.log")


def step4_promote() -> int:
    """auto_promote_best.py — push HF si el winner cambio."""
    log("=" * 60)
    log("STEP 4: auto_promote_best")
    return run_subproc([
        sys.executable, str(EXP_DIR / "auto_promote_best.py"),
    ], "recover_step4_promote.log")


def main() -> int:
    log("=" * 60)
    log("RECOVERY CHAIN START: exp12 from checkpoint-1050")
    log("=" * 60)

    rc = step1_build_adapter()
    if rc != 0:
        log(f"ABORT step1 rc={rc}")
        return rc

    rc = step2_eval_exp12()
    if rc != 0:
        log(f"ABORT step2 rc={rc}")
        return rc

    rc = step3_collect()
    if rc != 0:
        log(f"WARN step3 rc={rc} (continuing)")

    rc = step4_promote()
    if rc != 0:
        log(f"WARN step4 rc={rc} (non-critical)")

    log("=" * 60)
    log("RECOVERY DONE")
    log("=" * 60)
    return 0


if __name__ == "__main__":
    sys.exit(main())
