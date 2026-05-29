"""
Master download runner for all 5 marine detector datasets.

Calls each downloader sequentially and prints a final summary.
OzFish will exit with a non-zero code (user action required) — the runner
catches that and continues with the remaining datasets.

Usage:
    python run_downloads.py [--skip-ozfish] [--skip-deepfish] [--skip-suim]

Flags:
    --skip-ozfish    Skip OzFish (it requires manual download; use this flag if you
                     haven't placed the images yet and want to run the rest)
    --skip-deepfish  Skip DeepFish (7.1 GB full tar — slow on metered connections)
    --skip-suim      Skip SUIM (5.2 GB GDrive download)

Estimated total download (without OzFish):
    Brackish:  ~537 MB
    DeepFish:  ~7.1 GB (Localization split ~1.1 GB extracted)
    SUIM:      ~5.2 GB
    Aquarium:  ~39 MB
    TOTAL:     ~6.9 GB  (Localization-only DeepFish) or ~12.9 GB (full tar)

Time at 100 Mbps: ~12 minutes (Localization-only) or ~17 minutes (full tar).

Dependencies: pip install requests tqdm pyarrow pandas Pillow scipy
"""

from __future__ import annotations

import importlib
import subprocess
import sys
import time
from pathlib import Path

SCRIPTS = [
    ("ozfish",   "dl_ozfish"),
    ("brackish", "dl_brackish"),
    ("deepfish", "dl_deepfish"),
    ("suim",     "dl_suim"),
    ("aquarium", "dl_aquarium"),
]

SKIP_FLAGS = {
    "ozfish":   "--skip-ozfish",
    "deepfish": "--skip-deepfish",
    "suim":     "--skip-suim",
}

# OzFish is always skippable (requires user action)
ALWAYS_WARN = {"ozfish"}


def main() -> None:
    skipped = {slug for slug, flag in SKIP_FLAGS.items() if flag in sys.argv}
    # OzFish cannot be fully automated — always skip unless user explicitly opted in
    # by having images already placed (the script itself handles that check)

    results: list[tuple[str, str]] = []
    scripts_dir = Path(__file__).parent

    for slug, module_name in SCRIPTS:
        if slug in skipped:
            results.append((slug, "SKIPPED (--skip flag)"))
            continue

        print(f"\n{'='*60}")
        print(f"  {slug.upper()}")
        print(f"{'='*60}")
        t0 = time.monotonic()

        # Run each downloader as a subprocess to isolate failures
        script_path = scripts_dir / f"{module_name}.py"
        result = subprocess.run(
            [sys.executable, str(script_path)],
            capture_output=False,
        )
        elapsed = time.monotonic() - t0

        if result.returncode == 0:
            results.append((slug, f"OK ({elapsed:.0f}s)"))
        elif slug in ALWAYS_WARN:
            results.append((slug, "NEEDS MANUAL ACTION (see dl_ozfish.py)"))
        else:
            results.append((slug, f"FAILED (exit {result.returncode}, {elapsed:.0f}s)"))

    print(f"\n{'='*60}")
    print("  FINAL SUMMARY")
    print(f"{'='*60}")
    for slug, status in results:
        print(f"  {slug:<12}  {status}")
    print()

    # Overall success check (ignore OzFish)
    failures = [s for s, status in results if "FAILED" in status]
    if failures:
        print(f"WARNING: {len(failures)} dataset(s) failed: {', '.join(failures)}")
        sys.exit(1)


if __name__ == "__main__":
    main()
