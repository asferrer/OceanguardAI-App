"""
download_reference_images.py — Descargador de imágenes de referencia para BioDex.

Para cada taxón de `taxon_list.yaml`, descarga N imágenes desde fuentes con
**API limpia y licencia permisiva** (sin scraping HTML frágil), filtrando por
licencia y registrando procedencia/atribución por imagen.

Fuentes:
  - FathomNet  (dominio submarino real — prioridad para calidad)
  - iNaturalist (research-grade, foto-licencias CC)
  - GBIF        (occurrence media, StillImage)

Estructura de salida (la que `build_reference_bank.py --images-dir` espera):
    images_dir/
      <species_key>/
        fathomnet_000.jpg
        inat_001.jpg
        gbif_002.jpg
      provenance.jsonl     # 1 línea/imagen: {species_key, source, url, license, creator, file}

Licencias: por defecto se aceptan CC0 / CC-BY / CC-BY-SA. Usa --allow-nc para
incluir CC-BY-NC (OJO: NC prohíbe uso comercial — verifícalo si la app se monetiza).

Uso:
    python download_reference_images.py --out images --per-species 40
    python download_reference_images.py --out images --per-species 40 --sources fathomnet,inat
    python download_reference_images.py --dry-run         # solo cuenta URLs, no descarga
"""

from __future__ import annotations

import argparse
import io
import json
import time
from dataclasses import dataclass
from pathlib import Path

import requests
import yaml
from PIL import Image

GBIF_MATCH = "https://api.gbif.org/v1/species/match"
GBIF_OCC = "https://api.gbif.org/v1/occurrence/search"
INAT_OBS = "https://api.inaturalist.org/v1/observations"
FATHOMNET_CONCEPT = "https://database.fathomnet.org/api/images/find/concept/{concept}"

USER_AGENT = "OceanGuardAI-BioDex/1.0 (research; marine species reference bank)"
TIMEOUT = 20

# Licencias permisivas normalizadas (substring match, case-insensitive).
PERMISSIVE = ("cc0", "publicdomain", "cc-by", "cc by", "by/4.0", "by/3.0", "by-sa", "by/2.0")
NC_TOKENS = ("nc", "noncommercial", "non-commercial")


@dataclass
class ImageRef:
    species_key: str
    source: str
    url: str
    license: str
    creator: str


def _license_ok(license_str: str, allow_nc: bool) -> bool:
    s = (license_str or "").lower()
    if not s:
        return False
    is_nc = any(t in s for t in NC_TOKENS)
    if is_nc and not allow_nc:
        return False
    return any(p in s for p in PERMISSIVE) or is_nc


def _session() -> requests.Session:
    s = requests.Session()
    s.headers.update({"User-Agent": USER_AGENT})
    return s


# ---------------------------------------------------------------------------
# Fuentes (cada una devuelve list[ImageRef], best-effort, nunca lanza)
# ---------------------------------------------------------------------------

def from_fathomnet(sess: requests.Session, key: str, sci: str, limit: int, allow_nc: bool) -> list[ImageRef]:
    try:
        r = sess.get(FATHOMNET_CONCEPT.format(concept=sci), timeout=TIMEOUT)
        if r.status_code != 200:
            return []
        out: list[ImageRef] = []
        for img in r.json()[: limit * 2]:
            url = img.get("url")
            if not url:
                continue
            # FathomNet es CC-BY por defecto (MBARI). Respetar si trae licencia explícita.
            lic = img.get("license") or "CC-BY"
            if not _license_ok(lic, allow_nc):
                continue
            out.append(ImageRef(key, "fathomnet", url, lic, img.get("contributorsEmail", "FathomNet/MBARI")))
            if len(out) >= limit:
                break
        return out
    except Exception:
        return []


def from_inaturalist(sess: requests.Session, key: str, sci: str, limit: int, allow_nc: bool) -> list[ImageRef]:
    try:
        params = {
            "taxon_name": sci, "quality_grade": "research", "photos": "true",
            "per_page": min(100, limit * 3), "order_by": "votes",
        }
        r = sess.get(INAT_OBS, params=params, timeout=TIMEOUT)
        if r.status_code != 200:
            return []
        out: list[ImageRef] = []
        for obs in r.json().get("results", []):
            for photo in obs.get("photos", []):
                lic = photo.get("license_code") or ""
                if not _license_ok(lic, allow_nc):
                    continue
                url = (photo.get("url") or "").replace("square", "large")
                if not url:
                    continue
                out.append(ImageRef(key, "inat", url, lic, photo.get("attribution", "iNaturalist")))
                if len(out) >= limit:
                    return out
        return out
    except Exception:
        return []


def from_gbif(sess: requests.Session, key: str, sci: str, limit: int, allow_nc: bool) -> list[ImageRef]:
    try:
        m = sess.get(GBIF_MATCH, params={"name": sci}, timeout=TIMEOUT).json()
        taxon_key = m.get("usageKey")
        if not taxon_key:
            return []
        params = {"taxonKey": taxon_key, "mediaType": "StillImage", "limit": min(300, limit * 5)}
        r = sess.get(GBIF_OCC, params=params, timeout=TIMEOUT)
        if r.status_code != 200:
            return []
        out: list[ImageRef] = []
        for occ in r.json().get("results", []):
            for media in occ.get("media", []):
                if media.get("type") != "StillImage":
                    continue
                lic = media.get("license") or ""
                if not _license_ok(lic, allow_nc):
                    continue
                url = media.get("identifier")
                if not url:
                    continue
                out.append(ImageRef(key, "gbif", url, lic, media.get("creator", "GBIF")))
                if len(out) >= limit:
                    return out
        return out
    except Exception:
        return []


SOURCE_FNS = {"fathomnet": from_fathomnet, "inat": from_inaturalist, "gbif": from_gbif}


# ---------------------------------------------------------------------------
# Descarga + validación
# ---------------------------------------------------------------------------

def _download_valid(sess: requests.Session, ref: ImageRef, dest: Path) -> bool:
    """Descarga y valida que sea una imagen real (>=64px). Devuelve True si OK."""
    try:
        r = sess.get(ref.url, timeout=TIMEOUT, stream=True)
        if r.status_code != 200:
            return False
        data = r.content
        img = Image.open(io.BytesIO(data)).convert("RGB")
        if min(img.size) < 64:
            return False
        img.save(dest, "JPEG", quality=92)
        return True
    except Exception:
        return False


def _load_taxa(path: Path) -> list[tuple[str, str]]:
    data = yaml.safe_load(path.read_text(encoding="utf-8"))
    return [(t["species_key"], t["scientific_name"]) for t in data["taxa"]]


def main() -> None:
    parser = argparse.ArgumentParser(description="Descarga imágenes de referencia BioDex (APIs permisivas).")
    parser.add_argument("--taxon-list", type=Path, default=Path(__file__).parent / "taxon_list.yaml")
    parser.add_argument("--out", type=Path, default=Path("images"))
    parser.add_argument("--per-species", type=int, default=40, help="Objetivo de imágenes por especie.")
    parser.add_argument("--sources", type=str, default="fathomnet,inat,gbif")
    parser.add_argument("--allow-nc", action="store_true", help="Incluir CC-BY-NC (no comercial).")
    parser.add_argument("--dry-run", action="store_true", help="Solo cuenta URLs candidatas, no descarga.")
    args = parser.parse_args()

    sources = [s.strip() for s in args.sources.split(",") if s.strip() in SOURCE_FNS]
    sess = _session()
    taxa = _load_taxa(args.taxon_list)
    args.out.mkdir(parents=True, exist_ok=True)
    prov_path = args.out / "provenance.jsonl"
    prov = prov_path.open("a", encoding="utf-8")

    grand_total = 0
    for key, sci in taxa:
        refs: list[ImageRef] = []
        seen: set[str] = set()
        per_source = max(5, args.per_species // max(1, len(sources)) + 5)
        for src in sources:
            for ref in SOURCE_FNS[src](sess, key, sci, per_source, args.allow_nc):
                if ref.url not in seen:
                    seen.add(ref.url)
                    refs.append(ref)
            time.sleep(0.3)  # respetar rate-limit
        refs = refs[: args.per_species]

        if args.dry_run:
            by_src = {s: sum(1 for r in refs if r.source == s) for s in sources}
            print(f"{key:<26} {sci:<28} candidatas={len(refs):>3}  {by_src}")
            grand_total += len(refs)
            continue

        sp_dir = args.out / key
        sp_dir.mkdir(parents=True, exist_ok=True)
        saved = 0
        for i, ref in enumerate(refs):
            dest = sp_dir / f"{ref.source}_{i:03d}.jpg"
            if _download_valid(sess, ref, dest):
                prov.write(json.dumps({
                    "species_key": key, "source": ref.source, "url": ref.url,
                    "license": ref.license, "creator": ref.creator, "file": dest.name,
                }, ensure_ascii=False) + "\n")
                saved += 1
        print(f"{key:<26} guardadas={saved}/{len(refs)}")
        grand_total += saved

    prov.close()
    print(f"\nTotal {'candidatas' if args.dry_run else 'guardadas'}: {grand_total}")
    if not args.dry_run:
        print(f"Procedencia/atribución: {prov_path}")


if __name__ == "__main__":
    main()
