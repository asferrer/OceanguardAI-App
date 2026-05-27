"""
resolve_candidates.py — Resuelve candidatos Mediterráneos a entradas taxon_list.

Para cada nombre científico en _med_candidates.CANDIDATES:
  1. AphiaID vía WoRMS REST (AphiaIDByName, marine_only).
  2. Nombres comunes en/es/fr/de/it/pt (WoRMS vernaculars + GBIF fallback).
  3. Conteo de imágenes CC disponibles (GBIF occurrence media + iNat research).
     Solo se mantienen especies con >= MIN_IMAGES candidatas.

Escribe:
  _resolved_candidates.yaml   — entradas que PASAN el umbral (formato taxon_list)
  _resolved_report.json       — métricas: resueltas, descartadas, motivo

Uso:
    python resolve_candidates.py [--min-images 15] [--allow-nc]
"""

from __future__ import annotations

import argparse
import json
import re
import time
from pathlib import Path

import requests
import yaml

from _med_candidates import CANDIDATES

WORMS_ID = "https://www.marinespecies.org/rest/AphiaIDByName/{name}?marine_only=true"
WORMS_VERN = "https://www.marinespecies.org/rest/AphiaVernacularsByAphiaID/{aid}"
GBIF_MATCH = "https://api.gbif.org/v1/species/match"
GBIF_OCC = "https://api.gbif.org/v1/occurrence/search"
GBIF_VERN = "https://api.gbif.org/v1/species/{key}/vernacularNames"
INAT_OBS = "https://api.inaturalist.org/v1/observations"
UA = "OceanGuardAI-BioDex/1.0 (research; marine species reference bank)"
TIMEOUT = 25

# WoRMS usa ISO 639-3; GBIF usa 639-1/2. Mapeo destino → códigos fuente.
WORMS_LANG = {"en": "eng", "es": "spa", "fr": "fra", "de": "deu", "it": "ita", "pt": "por"}
GBIF_LANG = {"en": "eng", "es": "spa", "fr": "fra", "de": "deu", "it": "ita", "pt": "por"}

PERMISSIVE = ("cc0", "publicdomain", "cc-by", "cc by", "by/4.0", "by/3.0", "by-sa", "by/2.0")
NC_TOKENS = ("nc", "noncommercial", "non-commercial")


def _session() -> requests.Session:
    s = requests.Session()
    s.headers.update({"User-Agent": UA, "Accept": "application/json"})
    return s


def species_key(sci: str) -> str:
    return re.sub(r"[^a-z0-9]+", "_", sci.lower()).strip("_")


def license_ok(lic: str, allow_nc: bool) -> bool:
    s = (lic or "").lower()
    if not s:
        return False
    is_nc = any(t in s for t in NC_TOKENS)
    if is_nc and not allow_nc:
        return False
    return any(p in s for p in PERMISSIVE) or is_nc


def worms_aphia_id(sess: requests.Session, sci: str) -> int | None:
    try:
        r = sess.get(WORMS_ID.format(name=requests.utils.quote(sci)), timeout=TIMEOUT)
        if r.status_code == 200 and r.text.strip().isdigit():
            return int(r.text.strip())
    except Exception:
        pass
    return None


def worms_vernaculars(sess: requests.Session, aid: int) -> dict[str, str]:
    """Primer vernáculo por idioma destino desde WoRMS."""
    out: dict[str, str] = {}
    try:
        r = sess.get(WORMS_VERN.format(aid=aid), timeout=TIMEOUT)
        if r.status_code != 200:
            return out
        for d in r.json():
            code = d.get("language_code")
            for dst, src in WORMS_LANG.items():
                if code == src and dst not in out:
                    out[dst] = d["vernacular"]
    except Exception:
        pass
    return out


def gbif_key(sess: requests.Session, sci: str) -> int | None:
    try:
        j = sess.get(GBIF_MATCH, params={"name": sci}, timeout=TIMEOUT).json()
        return j.get("usageKey")
    except Exception:
        return None


def gbif_vernaculars(sess: requests.Session, key: int) -> dict[str, str]:
    out: dict[str, str] = {}
    try:
        r = sess.get(GBIF_VERN.format(key=key), timeout=TIMEOUT)
        if r.status_code != 200:
            return out
        for d in r.json().get("results", []):
            lang = d.get("language")
            for dst, src in GBIF_LANG.items():
                if lang == src and dst not in out:
                    out[dst] = d["vernacularName"].lower()
    except Exception:
        pass
    return out


def count_gbif_images(sess: requests.Session, key: int, allow_nc: bool) -> int:
    try:
        params = {"taxonKey": key, "mediaType": "StillImage", "limit": 100}
        r = sess.get(GBIF_OCC, params=params, timeout=TIMEOUT)
        if r.status_code != 200:
            return 0
        n = 0
        for occ in r.json().get("results", []):
            for m in occ.get("media", []):
                if m.get("type") == "StillImage" and license_ok(m.get("license", ""), allow_nc):
                    if m.get("identifier"):
                        n += 1
        return n
    except Exception:
        return 0


def count_inat_images(sess: requests.Session, sci: str, allow_nc: bool) -> int:
    try:
        params = {"taxon_name": sci, "quality_grade": "research", "photos": "true",
                  "per_page": 100, "order_by": "votes"}
        r = sess.get(INAT_OBS, params=params, timeout=TIMEOUT)
        if r.status_code != 200:
            return 0
        n = 0
        for obs in r.json().get("results", []):
            for p in obs.get("photos", []):
                if license_ok(p.get("license_code", ""), allow_nc) and p.get("url"):
                    n += 1
        return n
    except Exception:
        return 0


def build_common_names(worms: dict, gbif: dict) -> dict[str, str]:
    """Combina WoRMS (preferido) + GBIF, fallback de es/fr/de/it/pt a en."""
    cn = {}
    for lang in ("en", "es", "fr", "de", "it", "pt"):
        cn[lang] = worms.get(lang) or gbif.get(lang) or ""
    if cn["en"]:
        for lang in ("es", "fr", "de", "it", "pt"):
            if not cn[lang]:
                cn[lang] = cn["en"]
    return cn


def resolve_one(sess: requests.Session, sci: str, cosmo: bool, min_imgs: int,
                allow_nc: bool) -> tuple[dict | None, str, int]:
    aid = worms_aphia_id(sess, sci)
    time.sleep(0.25)
    gkey = gbif_key(sess, sci)
    time.sleep(0.2)
    n_gbif = count_gbif_images(sess, gkey, allow_nc) if gkey else 0
    time.sleep(0.2)
    n_inat = count_inat_images(sess, sci, allow_nc)
    time.sleep(0.3)
    n_imgs = n_gbif + n_inat
    if n_imgs < min_imgs:
        return None, "too_few_images", n_imgs
    worms_cn = worms_vernaculars(sess, aid) if aid else {}
    time.sleep(0.2)
    gbif_cn = gbif_vernaculars(sess, gkey) if gkey else {}
    time.sleep(0.2)
    entry = {
        "species_key": species_key(sci),
        "aphia_id": aid,
        "scientific_name": sci,
        "common_names": build_common_names(worms_cn, gbif_cn),
        "cosmopolitan": cosmo,
    }
    return entry, "ok", n_imgs


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--min-images", type=int, default=15)
    ap.add_argument("--allow-nc", action="store_true")
    ap.add_argument("--out-yaml", type=Path, default=Path("_resolved_candidates.yaml"))
    ap.add_argument("--out-report", type=Path, default=Path("_resolved_report.json"))
    args = ap.parse_args()

    sess = _session()
    kept: list[dict] = []
    report = {"total": len(CANDIDATES), "kept": 0, "dropped": [], "no_aphia": []}

    for i, (sci, group, cosmo) in enumerate(CANDIDATES, 1):
        entry, status, n = resolve_one(sess, sci, cosmo, args.min_images, args.allow_nc)
        tag = "KEEP" if entry else "DROP"
        print(f"[{i:>3}/{len(CANDIDATES)}] {tag} {sci:<32} imgs={n:<3} {group}", flush=True)
        if entry:
            entry["_group"] = group
            kept.append(entry)
            report["kept"] += 1
            if entry["aphia_id"] is None:
                report["no_aphia"].append(sci)
        else:
            report["dropped"].append({"sci": sci, "group": group, "reason": status, "imgs": n})

    args.out_yaml.write_text(yaml.safe_dump({"taxa": kept}, allow_unicode=True,
                                            sort_keys=False), encoding="utf-8")
    args.out_report.write_text(json.dumps(report, indent=2, ensure_ascii=False),
                               encoding="utf-8")
    print(f"\nKEPT {report['kept']}/{report['total']}  "
          f"DROPPED {len(report['dropped'])}  NO_APHIA {len(report['no_aphia'])}")


if __name__ == "__main__":
    main()
