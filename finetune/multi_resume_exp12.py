"""multi_resume_exp12.py — Opcion A: training en 6 chunks de 200 steps.

Estrategia para evitar el OOM virtual mem que mato exp12 dos veces:
  Cada chunk arranca un proceso fresco de train_etapa1.py via --resume + --max-steps.
  Al final del chunk, train_etapa1.py escribe el adapter completo.
  Eval, comparacion vs mejor anterior, decide si seguir.

Estado inicial: best mAP@0.5 = 0.3236 (checkpoint-1050 partial). Goal: superar 0.3253 (exp10).

Idempotencia:
  - Si metrics_exp12_chunk_<end>.json existe -> salta entrenamiento Y eval del chunk
  - Si crashea mid-chunk, intenta construir adapter desde el ultimo checkpoint disponible y evaluarlo
  - Detecta regresion: si nuevo mAP < anterior - 0.01, para
"""
from __future__ import annotations

import sys
import os
import json
import shutil
import subprocess
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import wmi_bypass  # noqa: E402, F401

REPO_ROOT = Path(__file__).resolve().parent
EXP_DIR = REPO_ROOT / "experiments"
RESULTS_DIR = EXP_DIR / "results"
LOGS_DIR = REPO_ROOT / "outputs" / "logs"
LOGS_DIR.mkdir(parents=True, exist_ok=True)

EXP12_DIR = REPO_ROOT / "outputs" / "lora_exp12_vision_lora"
EXP12_ADAPTER = EXP12_DIR / "adapter"
EXP11_ADAPTER = REPO_ROOT / "outputs" / "lora_exp11_real_synth_full" / "adapter"

CONFIG_YAML = EXP_DIR / "configs" / "exp12_vision_lora.yaml"
SUMMARY_LOG = LOGS_DIR / "multi_resume_summary.log"

# Chunks of training (start_at, end_at). We always resume from the latest checkpoint >= start_at.
CHUNKS: list[tuple[int, int]] = [
    (1100, 1300),
    (1300, 1500),
    (1500, 1700),
    (1700, 1900),
    (1900, 2100),
    (2100, 2292),
]

BASELINE_BEST_MAP = 0.3236  # exp12 partial step 1050
WINNER_TARGET = 0.3253      # exp10_real_full — to surpass and become new global winner
REGRESSION_THRESHOLD = 0.01 # if mAP drops more than this vs previous chunk, stop


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
    log(f"  cmd: {' '.join(cmd[:4])} ... (truncated)")
    with log_path.open("a", encoding="utf-8") as logf:
        logf.write(f"\n{'='*70}\n[{time.strftime('%Y-%m-%d %H:%M:%S')}] CMD: {' '.join(cmd)}\n{'='*70}\n")
        logf.flush()
        proc = subprocess.Popen(cmd, cwd=str(REPO_ROOT), env=env_for_subproc(),
                                stdout=logf, stderr=subprocess.STDOUT, bufsize=0)
        rc = proc.wait()
        logf.write(f"\n[exit code: {rc}]\n")
    log(f"END   {log_name} rc={rc}")
    return rc


def find_latest_checkpoint() -> Path | None:
    ckpts = sorted(EXP12_DIR.glob("checkpoint-*"), key=lambda p: int(p.name.split("-")[1]))
    return ckpts[-1] if ckpts else None


def build_adapter_from_checkpoint(ckpt_dir: Path) -> int:
    """Copy adapter_* files from checkpoint + processor files from exp11/adapter."""
    log(f"  Building adapter/ from {ckpt_dir.name}")
    if EXP12_ADAPTER.exists():
        shutil.rmtree(EXP12_ADAPTER)
    EXP12_ADAPTER.mkdir(parents=True, exist_ok=True)

    for fname in ["adapter_config.json", "adapter_model.safetensors"]:
        src = ckpt_dir / fname
        if not src.exists():
            log(f"  ABORT: missing {fname} in {ckpt_dir}")
            return 1
        shutil.copy2(src, EXP12_ADAPTER / fname)

    for fname in ["processor_config.json", "chat_template.jinja", "tokenizer.json",
                  "tokenizer_config.json", "README.md"]:
        src = EXP11_ADAPTER / fname
        if src.exists():
            shutil.copy2(src, EXP12_ADAPTER / fname)

    return 0


def eval_chunk(chunk_end: int) -> tuple[int, dict | None]:
    """Eval the current adapter, save as metrics_exp12_chunk_<end>.json. Returns (rc, metrics)."""
    key = f"exp12_chunk_{chunk_end}"
    rc = run_subproc([
        sys.executable, "-c",
        f"import sys, runpy; sys.path.insert(0, r'{REPO_ROOT}'); import wmi_bypass; "
        f"sys.argv = [r'{EXP_DIR / 'eval_grid.py'}', '--key', '{key}', "
        f"'--adapter', r'{EXP12_ADAPTER}']; "
        f"runpy.run_path(r'{EXP_DIR / 'eval_grid.py'}', run_name='__main__')"
    ], f"multi_resume_eval_chunk_{chunk_end}.log")

    if rc != 0:
        return rc, None

    metrics_path = RESULTS_DIR / f"metrics_{key}.json"
    if not metrics_path.exists():
        log(f"  ERROR: metrics file not created at {metrics_path}")
        return 1, None

    with metrics_path.open(encoding="utf-8") as f:
        m = json.load(f)
    return 0, m


def train_chunk(resume_from: Path, max_steps: int) -> int:
    """Run train_etapa1.py with --resume + --max-steps. Process exits cleanly when reaching max_steps."""
    return run_subproc([
        sys.executable, "-c",
        f"import sys, runpy; sys.path.insert(0, r'{REPO_ROOT}'); import wmi_bypass; "
        f"sys.argv = [r'{REPO_ROOT / 'train_etapa1.py'}', "
        f"'--config', r'{CONFIG_YAML}', '--resume', r'{resume_from}', '--max-steps', '{max_steps}']; "
        f"runpy.run_path(r'{REPO_ROOT / 'train_etapa1.py'}', run_name='__main__')"
    ], f"multi_resume_train_to_{max_steps}.log")


def main() -> int:
    log("=" * 60)
    log("MULTI-RESUME CHAIN START — 6 chunks de 200 steps con eval intermedio")
    log(f"Baseline best: {BASELINE_BEST_MAP:.4f} (partial step 1050)")
    log(f"Target winner: {WINNER_TARGET:.4f} (exp10_real_full)")
    log("=" * 60)

    best_map = BASELINE_BEST_MAP
    best_chunk_end = 1050  # corresponds to adapter_partial_step1050
    prev_chunk_map = BASELINE_BEST_MAP

    for chunk_idx, (start_step, end_step) in enumerate(CHUNKS):
        log("")
        log("-" * 60)
        log(f"CHUNK {chunk_idx + 1}/{len(CHUNKS)}: train step {start_step} -> {end_step}")
        log("-" * 60)

        # Idempotency: skip if metrics for this chunk already exist
        existing_metrics = RESULTS_DIR / f"metrics_exp12_chunk_{end_step}.json"
        if existing_metrics.exists() and existing_metrics.stat().st_size > 100:
            with existing_metrics.open() as f:
                m = json.load(f)
            chunk_map = m.get("mAP_50", 0.0)
            log(f"  SKIP: metrics already at {existing_metrics.name}, mAP={chunk_map:.4f}")
            if chunk_map > best_map:
                best_map = chunk_map
                best_chunk_end = end_step
            prev_chunk_map = chunk_map
            continue

        # 1. Find latest checkpoint to resume from
        latest_ckpt = find_latest_checkpoint()
        if latest_ckpt is None:
            log("  ABORT: no checkpoint found in lora_exp12_vision_lora/")
            return 1
        latest_step = int(latest_ckpt.name.split("-")[1])
        log(f"  Resuming from {latest_ckpt.name} (step {latest_step})")

        if latest_step >= end_step:
            log(f"  WARN: latest_step ({latest_step}) >= chunk end ({end_step}). Will likely no-op.")

        # 2. Train this chunk
        chunk_t0 = time.time()
        train_rc = train_chunk(latest_ckpt, end_step)
        chunk_dt = time.time() - chunk_t0
        log(f"  train chunk dt = {chunk_dt:.0f}s ({chunk_dt/60:.1f} min)")

        # 3. Verify what we got — even on crash, latest checkpoint may have advanced
        latest_ckpt_after = find_latest_checkpoint()
        if latest_ckpt_after is None:
            log("  ABORT: no checkpoint after training (severe)")
            return 1
        latest_step_after = int(latest_ckpt_after.name.split("-")[1])
        log(f"  latest checkpoint after: {latest_ckpt_after.name} (step {latest_step_after})")

        # 4. If train crashed (rc != 0), build adapter manually from latest checkpoint
        if train_rc != 0:
            log(f"  TRAIN CRASHED with rc={train_rc} (likely OOM again)")
            if latest_step_after > latest_step:
                log(f"  But we advanced from step {latest_step} -> {latest_step_after}, eval anyway")
                if build_adapter_from_checkpoint(latest_ckpt_after) != 0:
                    log("  ABORT: cannot build adapter from latest checkpoint")
                    return 1
            else:
                log("  No new checkpoint, no progress this chunk -> STOP chain")
                break
        else:
            # train_etapa1.py wrote adapter/ at end automatically
            if not EXP12_ADAPTER.exists() or not any(EXP12_ADAPTER.iterdir()):
                log("  WARN: train succeeded but adapter/ missing. Building from latest checkpoint.")
                if build_adapter_from_checkpoint(latest_ckpt_after) != 0:
                    return 1

        # 5. Backup the current adapter under a chunk-specific name
        chunk_backup = EXP12_DIR / f"adapter_chunk_{latest_step_after}"
        if chunk_backup.exists():
            shutil.rmtree(chunk_backup)
        shutil.copytree(EXP12_ADAPTER, chunk_backup)
        log(f"  Backed up adapter -> {chunk_backup.name}")

        # 6. Eval
        eval_rc, metrics = eval_chunk(latest_step_after)
        if eval_rc != 0 or metrics is None:
            log(f"  eval rc={eval_rc} -> stopping chain")
            break

        chunk_map = metrics.get("mAP_50", 0.0)
        per_class = metrics.get("per_class_mAP_50", {})
        log(f"  CHUNK {chunk_idx + 1} RESULT: mAP@0.5 = {chunk_map:.4f}")
        log(f"    Can={per_class.get('Can', 0):.3f} Metal_Debris={per_class.get('Metal_Debris', 0):.3f} "
            f"Fishing_Net={per_class.get('Fishing_Net', 0):.3f} Glove={per_class.get('Glove', 0):.3f}")

        # 7. Track best
        if chunk_map > best_map:
            best_map = chunk_map
            best_chunk_end = latest_step_after
            log(f"  NEW BEST! {chunk_map:.4f} > previous {best_map:.4f}")
        else:
            log(f"  no improvement (best still {best_map:.4f} at step {best_chunk_end})")

        # 8. Regression check
        if chunk_map < prev_chunk_map - REGRESSION_THRESHOLD:
            log(f"  REGRESSION DETECTED: {chunk_map:.4f} < {prev_chunk_map:.4f} - {REGRESSION_THRESHOLD}")
            log("  STOPPING chain — restoring best adapter")
            break
        prev_chunk_map = chunk_map

        # 9. Crash without progress -> stop
        if train_rc != 0:
            log(f"  Train crashed mid-chunk (rc={train_rc}). Stopping chain after eval.")
            break

    # 10. Finalize: restore BEST adapter and write metrics_exp12_vision_lora.json
    log("")
    log("=" * 60)
    log(f"FINALIZING — best mAP@0.5 = {best_map:.4f} at step {best_chunk_end}")
    log("=" * 60)

    if best_chunk_end == 1050:
        # Best was the baseline (partial step 1050)
        best_source = EXP12_DIR / "adapter_partial_step1050"
        src_metrics = RESULTS_DIR / "metrics_exp12_vision_lora.json"  # already 0.3236
    else:
        best_source = EXP12_DIR / f"adapter_chunk_{best_chunk_end}"
        src_metrics = RESULTS_DIR / f"metrics_exp12_chunk_{best_chunk_end}.json"

    if best_source.exists():
        if EXP12_ADAPTER.exists():
            shutil.rmtree(EXP12_ADAPTER)
        shutil.copytree(best_source, EXP12_ADAPTER)
        log(f"  Restored adapter from {best_source.name}")

    if src_metrics.exists() and best_chunk_end != 1050:
        final_metrics_path = RESULTS_DIR / "metrics_exp12_vision_lora.json"
        shutil.copy2(src_metrics, final_metrics_path)
        log(f"  Wrote {final_metrics_path.name}")

    # 11. Run collect_results + auto_promote_best
    run_subproc([sys.executable, str(EXP_DIR / "collect_results.py")], "multi_resume_collect.log")
    run_subproc([sys.executable, str(EXP_DIR / "auto_promote_best.py")], "multi_resume_promote.log")

    log("=" * 60)
    log(f"MULTI-RESUME DONE. Best mAP@0.5 = {best_map:.4f}")
    if best_map > WINNER_TARGET:
        log(f"  *** NEW GLOBAL WINNER! Beat exp10 ({WINNER_TARGET:.4f}) ***")
    else:
        log(f"  exp10 still winner ({WINNER_TARGET:.4f} vs {best_map:.4f})")
    log("=" * 60)
    return 0


if __name__ == "__main__":
    sys.exit(main())
