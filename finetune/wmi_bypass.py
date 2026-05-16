"""wmi_bypass.py — workaround for stuck Windows WMI service.

Import this BEFORE importing torch to skip platform.uname()'s WMI query.
The deadlocked WmiPrvSE process cannot be killed without admin, so we
monkey-patch platform.uname to return a pre-populated tuple instead.

Usage:
    python -c "import wmi_bypass; import torch; ..."

or via sitecustomize.py / sys.path manipulation in launcher scripts.
"""
from __future__ import annotations

import platform as _platform
import sys

# Pre-populated uname using values we already know about this machine
# (from PROCESSOR_IDENTIFIER env var, sys.platform, and hostname)
# Python 3.12's uname_result no longer accepts `processor` kwarg in __new__
# (it's a lazy property that queries WMI). We bypass by setting processor on
# the instance directly after construction.
_FAKE_UNAME = _platform.uname_result(
    "Windows",
    "DESKTOP-SAFLEX",
    "10",
    "10.0.26200",
    "AMD64",
)
# Force the .processor attribute so the lazy WMI call never happens
try:
    object.__setattr__(_FAKE_UNAME, "processor", "Intel64 Family 6 Model 198 Stepping 2, GenuineIntel")
except (AttributeError, TypeError):
    pass

# Populate the cache so Python's own caching kicks in
_platform._uname_cache = _FAKE_UNAME


def _patched_uname():
    return _FAKE_UNAME


def _patched_processor():
    return _FAKE_UNAME.processor


def _patched_version():
    return _FAKE_UNAME.version


def _patched_node():
    return _FAKE_UNAME.node


def _patched_release():
    return _FAKE_UNAME.release


# Replace all platform functions that internally call _wmi
_platform.uname = _patched_uname
_platform.processor = _patched_processor
_platform.version = _patched_version
_platform.node = _patched_node
_platform.release = _patched_release

# Also block direct _wmi access if some library tries it
try:
    import _wmi  # noqa: E402

    def _blocked_query(*args, **kwargs):
        raise RuntimeError("wmi_bypass: _wmi.exec_query intentionally blocked")

    _wmi.exec_query = _blocked_query
except ImportError:
    pass

print("[wmi_bypass] platform.* WMI calls disabled — using cached uname", file=sys.stderr)
