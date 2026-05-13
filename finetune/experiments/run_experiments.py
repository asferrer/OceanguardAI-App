"""run_experiments.py — pipeline streaming train+eval con watchdog y timeout.

Para cada exp del grid:
  1. Si outputs/lora_<exp>/adapter ya existe → skip train, lanza eval bg.
  2. Lanza train (subprocess python train_etapa1.py --config <yaml>).
  3. VRAM watchdog 30s: si > VRAM_LIMIT_GB sostenido 60s → kill, log FAIL.
  4. Timeout 45 min wall por exp.
  5. Al acabar adapter, lanza eval_grid.py --exp <exp> en background.
  6. Base eval (sin adapter) en bg DURANTE el train de exp01.
  7. 3 FAIL consecutivos → stop grid.
"""
from __future__ import annotations

import argparse
import json
import os
import signal
import subprocess
import sys
import threading
import time
from pathlib import Path
from typing import Optional

sys.path.insert(0, str(Path(__file__).resolve().parent))
from config import (  # noqa: E402
    CONFIGS_DIR,
    EXP_OUTPUTS_BASE,
    EXPERIMENTS,
    LOGS_DIR,
    REPO_ROOT,
    RESULTS_DIR,
)

VRAM_LIMIT_GB = float(os.environ.get("VRAM_LIMIT_GB", "30"))
VRAM_SUSTAINED_S = float(os.environ.get("VRAM_SUSTAINED_S", "60"))
EXP_TIMEOUT_S = float(os.environ.get("EXP_TIMEOUT_S", str(45 * 60)))
WATCHDOG_PERIOD_S = float(os.environ.get("WATCHDOG_PERIOD_S", "30"))
MAX_CONSECUTIVE_FAIL = 99  # disabled: keep grid running even after failures

LOGS_DIR.mkdir(parents=True, exist_ok=True)
RESULTS_DIR.mkdir(parents=True, exist_ok=True)
STATE_PATH = RESULTS_DIR / "_run_state.json"


def env_for_subproc() -> dict:
    e = os.environ.copy()
    e["TORCHDYNAMO_DISABLE"] = "1"
    e["UNSLOTH_COMPILE_DISABLE"] = "1"
    e["PYTORCH_CUDA_ALLOC_CONF"] = "expandable_segments:True,max_split_size_mb:512"
    e["TOKENIZERS_PARALLELISM"] = "false"
    return e


def query_vram_gb() -> Optional[float]:
    try:
        r = subprocess.run(
            ["nvidia-smi", "--query-gpu=memory.used", "--format=csv,noheader,nounits"],
            capture_output=True, text=True, timeout=10,
        )
        if r.returncode != 0:
            return None
        used_mb = float(r.stdout.strip().splitlines()[0])
        return used_mb / 1024.0
    except Exception:
        return None


def watchdog_loop(proc: subprocess.Popen, name: str, stop_evt: threading.Event, log_lines: list) -> None:
    over_since: float | None = None
    while not stop_evt.is_set():
        if proc.poll() is not None:
            return
        used = query_vram_gb()
        if used is not None:
            line = f"[watchdog:{name}] VRAM={used:.1f} GB"
            log_lines.append(line)
            if used > VRAM_LIMIT_GB:
                if over_since is None:
                    over_since = time.time()
                elif time.time() - over_since >= VRAM_SUSTAINED_S:
                    log_lines.append(f"[watchdog:{name}] VRAM>{VRAM_LIMIT_GB}GB sostenido {VRAM_SUSTAINED_S}s → KILL")
                    try:
                        proc.terminate()
                    except Exception:
                        pass
                    return
            else:
                over_since = None
        for _ in range(int(WATCHDOG_PERIOD_S)):
            if stop_evt.is_set() or proc.poll() is not None:
                return
            time.sleep(1)


def run_train(exp_name: str, yaml_path: Path) -> tuple[bool, str]:
    out_dir = EXP_OUTPUTS_BASE / f"lora_{exp_name}"
    adapter_dir = out_dir / "adapter"
    log_path = LOGS_DIR / f"train_{exp_name}.log"

    if adapter_dir.exists() and any(adapter_dir.iterdir()):
        return True, f"already exists at {adapter_dir}"

    # auto-resume
    resume_arg = []
    if out_dir.exists():
        ckpts = sorted(out_dir.glob("checkpoint-*"))
        if ckpts:
            resume_arg = ["--resume", str(ckpts[-1])]
            print(f"[train:{exp_name}] resume from {ckpts[-1]}")

    cmd = [sys.executable, "train_etapa1.py", "--config", str(yaml_path)] + resume_arg
    print(f"[train:{exp_name}] cmd: {' '.join(cmd)}")

    stop_evt = threading.Event()
    log_lines: list[str] = []
    t0 = time.time()
    with log_path.open("w", encoding="utf-8") as logf:
        proc = subprocess.Popen(
            cmd, cwd=str(REPO_ROOT), env=env_for_subproc(),
            stdout=logf, stderr=subprocess.STDOUT,
        )
        wd = threading.Thread(target=watchdog_loop, args=(proc, exp_name, stop_evt, log_lines), daemon=True)
        wd.start()

        try:
            while True:
                rc = proc.poll()
                if rc is not None:
                    break
                if time.time() - t0 > EXP_TIMEOUT_S:
                    log_lines.append(f"[train:{exp_name}] TIMEOUT after {EXP_TIMEOUT_S}s → KILL")
                    proc.terminate()
                    try:
                        proc.wait(timeout=20)
                    except subprocess.TimeoutExpired:
                        proc.kill()
                    break
                time.sleep(5)
        finally:
            stop_evt.set()
            wd.join(timeout=10)

        for line in log_lines:
            logf.write(line + "\n")
        logf.flush()

    rc = proc.returncode
    elapsed = time.time() - t0
    success = rc == 0 and adapter_dir.exists() and any(adapter_dir.iterdir())
    return success, f"rc={rc} elapsed={elapsed:.0f}s adapter_dir_present={adapter_dir.exists()}"


def launch_eval_bg(key: str, adapter_path: str | None) -> subprocess.Popen:
    log_path = LOGS_DIR / f"eval_{key}.log"
    cmd = [sys.executable, "experiments/eval_grid.py", "--key", key]
    if adapter_path is None:
        cmd.append("--base")
    else:
        cmd += ["--adapter", adapter_path]
    print(f"[eval-bg:{key}] launch: {' '.join(cmd)}")
    logf = open(log_path, "w", encoding="utf-8")
    proc = subprocess.Popen(cmd, cwd=str(REPO_ROOT), env=env_for_subproc(),
                            stdout=logf, stderr=subprocess.STDOUT)
    return proc


def save_state(state: dict) -> None:
    STATE_PATH.write_text(json.dumps(state, indent=2, ensure_ascii=False), encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--skip-base", action="store_true", help="No correr eval base")
    parser.add_argument("--only", nargs="*", default=None, help="Sólo estos exps")
    parser.add_argument("--global-deadline-s", type=float, default=None,
                        help="Tiempo wall máximo absoluto (segundos) antes de abortar")
    args = parser.parse_args()

    started = time.time()
    state: dict = {
        "started_at": started,
        "exps": {},
        "base_eval": "pending",
    }

    consecutive_fail = 0
    eval_procs: dict[str, subprocess.Popen] = {}

    # Base eval lo dejamos para después de todos los trains (evita VRAM clash al inicio)
    save_state(state)

    # 2) Train + eval por exp
    target_exps = EXPERIMENTS
    if args.only:
        keep = set(args.only)
        target_exps = [e for e in EXPERIMENTS if e["name"] in keep]

    for exp in target_exps:
        name = exp["name"]
        yaml_path = CONFIGS_DIR / f"{name}.yaml"
        if not yaml_path.exists():
            print(f"[run:{name}] YAML missing {yaml_path}, skip")
            state["exps"][name] = {"status": "skip_no_yaml"}
            save_state(state)
            continue

        if args.global_deadline_s is not None:
            remaining = args.global_deadline_s - (time.time() - started)
            if remaining < 5 * 60:
                print(f"[run:{name}] global deadline cerca ({remaining:.0f}s) → abort")
                state["exps"][name] = {"status": "abort_global_deadline"}
                save_state(state)
                break

        # join evals previos para liberar GPU si pesan
        # (eval bg consume vram; pero la idea es que train ocupa principal y eval pueda
        #  esperar en cola). Estrategia simple: si hay >2 evals corriendo, esperar al primero.
        running = [k for k, p in eval_procs.items() if p.poll() is None]
        if len(running) >= 2:
            first = running[0]
            print(f"[run:{name}] esperando eval {first} (>=2 evals concurrentes)")
            eval_procs[first].wait()

        print(f"\n[run:{name}] === TRAIN START ===")
        state["exps"][name] = {"status": "training", "started": time.time()}
        save_state(state)
        success, info = run_train(name, yaml_path)
        state["exps"][name].update({"train_success": success, "info": info, "ended": time.time()})
        save_state(state)
        if not success:
            consecutive_fail += 1
            state["exps"][name]["status"] = "fail"
            save_state(state)
            print(f"[run:{name}] FAIL ({info}). consecutive_fail={consecutive_fail}")
            if consecutive_fail >= MAX_CONSECUTIVE_FAIL:
                print(f"[run] {MAX_CONSECUTIVE_FAIL} FAIL consecutivos -> STOP grid")
                state["stopped_reason"] = "consecutive_fail"
                save_state(state)
                break
            continue
        consecutive_fail = 0
        state["exps"][name]["status"] = "train_done"
        save_state(state)

        adapter_dir = EXP_OUTPUTS_BASE / f"lora_{name}" / "adapter"
        # SERIALIZED eval (blocking) — evita VRAM clash con siguiente train
        try:
            print(f"[run:{name}] eval blocking start")
            p = launch_eval_bg(name, str(adapter_dir))
            try:
                p.wait(timeout=30 * 60)
                state["exps"][name]["eval"] = "done"
            except subprocess.TimeoutExpired:
                print(f"[run:{name}] eval timeout 30m -> terminate")
                p.terminate()
                try:
                    p.wait(timeout=30)
                except Exception:
                    p.kill()
                state["exps"][name]["eval"] = "timeout"
        except Exception as e:
            state["exps"][name]["eval"] = f"launch_error: {e}"
            print(f"[run:{name}] eval launch falló: {e}")
        save_state(state)

    # 3) Esperar evals exps pendientes ANTES de base eval (libera memoria)
    print("\n[run] esperando evals de exps colgados…")
    for key, p in list(eval_procs.items()):
        if p.poll() is None:
            try:
                p.wait(timeout=30 * 60)
            except subprocess.TimeoutExpired:
                print(f"[run:{key}] eval timeout → terminate")
                try:
                    p.terminate(); p.wait(timeout=30)
                except Exception:
                    p.kill()

    # 4) Base eval al final (sin conflicto VRAM)
    if not args.skip_base:
        print("\n[run] base eval start")
        try:
            p = launch_eval_bg("base", None)
            eval_procs["base"] = p
            state["base_eval"] = "running"
            save_state(state)
        except Exception as e:
            print(f"[run] base eval launch falló: {e}")
            state["base_eval"] = f"launch_error: {e}"
            save_state(state)

    # 5) Esperar lo que quede
    print("\n[run] esperando evals pendientes…")
    deadline = (args.global_deadline_s - (time.time() - started)) if args.global_deadline_s else None
    for key, p in list(eval_procs.items()):
        if p.poll() is not None:
            continue
        wait_s = deadline if deadline is None else max(60, deadline)
        try:
            p.wait(timeout=wait_s)
        except subprocess.TimeoutExpired:
            print(f"[run:{key}] eval timeout → terminate")
            try:
                p.terminate()
                p.wait(timeout=30)
            except Exception:
                p.kill()
    state["finished_at"] = time.time()
    save_state(state)
    print("[run] DONE")
    return 0


if __name__ == "__main__":
    sys.exit(main())
