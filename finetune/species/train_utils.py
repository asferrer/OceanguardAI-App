"""
train_utils.py — Utilidades para train_encoder.py (fine-tune del visual tower).

Contiene: dataset por especie, sampler P-K, SupCon loss, partial-unfreeze del
visual tower, eval top-k interno (prototipo = media de embeddings train por
especie) y export ONNX del modelo afinado.

Separado de train_encoder.py para mantener ambos ficheros < 500 líneas y poder
reutilizar las piezas desde tests / otros scripts.
"""

from __future__ import annotations

import random
from pathlib import Path

import numpy as np
import torch
from PIL import Image
from torchvision import transforms

# Normalización CLIP estándar (debe coincidir con embedder.py / Kotlin).
_CLIP_MEAN = (0.48145466, 0.4578275, 0.40821073)
_CLIP_STD = (0.26862954, 0.26130258, 0.27577711)
_CLIP_MODEL = "ViT-B-32"
_CLIP_PRETRAINED = "laion2b_s34b_b79k"
EMBED_DIM = 512


# ---------------------------------------------------------------------------
# Transforms
# ---------------------------------------------------------------------------

def build_train_transform() -> transforms.Compose:
    """Augmentación ligera para train: RandomResizedCrop + flip horizontal."""
    return transforms.Compose([
        transforms.RandomResizedCrop(224, scale=(0.7, 1.0),
                                     interpolation=transforms.InterpolationMode.BICUBIC),
        transforms.RandomHorizontalFlip(0.5),
        transforms.ToTensor(),
        transforms.Normalize(_CLIP_MEAN, _CLIP_STD),
    ])


def build_eval_transform() -> transforms.Compose:
    """Preprocess determinista (espejo del open_clip preprocess) para val/index."""
    return transforms.Compose([
        transforms.Resize(224, interpolation=transforms.InterpolationMode.BICUBIC),
        transforms.CenterCrop(224),
        transforms.ToTensor(),
        transforms.Normalize(_CLIP_MEAN, _CLIP_STD),
    ])


# ---------------------------------------------------------------------------
# Descubrimiento de datos y split interno
# ---------------------------------------------------------------------------

def discover_train_images(
    images_dir: Path, exts: set[str], cap: int = 0, seed: int = 42
) -> dict[str, list[Path]]:
    """
    Escanea images_dir/{species_key}/**/*.{ext} → {species_key: [paths]}.
    Solo conserva especies con >=2 imágenes (SupCon necesita positivos).
    `cap` > 0 limita el nº de imgs por especie (muestreo determinista).
    """
    rng = random.Random(seed)
    result: dict[str, list[Path]] = {}
    for sp_dir in sorted(images_dir.iterdir()):
        if not sp_dir.is_dir():
            continue
        imgs = sorted(p for p in sp_dir.rglob("*") if p.suffix.lower() in exts)
        if len(imgs) < 2:
            continue
        if cap and len(imgs) > cap:
            imgs = sorted(rng.sample(imgs, cap))
        result[sp_dir.name] = imgs
    return result


def stratified_train_val(
    by_species: dict[str, list[Path]], val_frac: float, seed: int
) -> tuple[list[tuple[Path, str]], list[tuple[Path, str]]]:
    """
    Split estratificado por especie en (train, val). Garantiza >=1 img en train
    por especie. val solo recibe imgs si la especie tiene >=3 (deja >=2 en train
    para que SupCon tenga positivos).
    """
    rng = random.Random(seed)
    train: list[tuple[Path, str]] = []
    val: list[tuple[Path, str]] = []
    for key, imgs in sorted(by_species.items()):
        imgs = list(imgs)
        rng.shuffle(imgs)
        n_val = int(round(len(imgs) * val_frac)) if len(imgs) >= 3 else 0
        n_val = min(n_val, len(imgs) - 2)
        n_val = max(0, n_val)
        for p in imgs[:n_val]:
            val.append((p, key))
        for p in imgs[n_val:]:
            train.append((p, key))
    return train, val


# ---------------------------------------------------------------------------
# Dataset y sampler P-K
# ---------------------------------------------------------------------------

class SpeciesImageDataset:
    """Lista de (path, species_key) → tensores preprocesados. Label = índice de especie."""

    def __init__(self, items: list[tuple[Path, str]], species_keys: list[str], transform):
        self.items = items
        self.transform = transform
        self.key_to_label = {k: i for i, k in enumerate(species_keys)}
        self.label_to_paths: dict[int, list[Path]] = {}
        for path, key in items:
            lbl = self.key_to_label[key]
            self.label_to_paths.setdefault(lbl, []).append(path)

    def __len__(self) -> int:
        return len(self.items)

    def load_one(self, path: Path) -> torch.Tensor:
        with Image.open(path) as im:
            return self.transform(im.convert("RGB"))

    def load_batch(self, paths: list[Path]) -> torch.Tensor:
        return torch.stack([self.load_one(p) for p in paths])


class PKSampler:
    """
    Sampler P especies × K imágenes por batch (necesario para SupCon).
    Cada step elige P labels al azar y K imgs de cada uno (con reemplazo si <K).
    """

    def __init__(self, dataset: SpeciesImageDataset, p: int, k: int,
                 steps: int = 0, seed: int = 42):
        self.ds = dataset
        self.p = min(p, len(dataset.label_to_paths))
        self.k = k
        self.labels = list(dataset.label_to_paths.keys())
        if steps > 0:
            self.steps = steps
        else:
            self.steps = max(1, len(dataset) // (self.p * self.k))
        self._rng = random.Random(seed)

    def reshuffle(self, epoch: int) -> None:
        self._rng = random.Random(1000 + epoch)

    def __iter__(self):
        for _ in range(self.steps):
            chosen = self._rng.sample(self.labels, self.p)
            batch_labels: list[int] = []
            batch_paths: list[Path] = []
            for lbl in chosen:
                pool = self.ds.label_to_paths[lbl]
                picks = (self._rng.sample(pool, self.k) if len(pool) >= self.k
                         else [self._rng.choice(pool) for _ in range(self.k)])
                batch_labels.extend([lbl] * self.k)
                batch_paths.extend(picks)
            yield batch_labels, batch_paths


# ---------------------------------------------------------------------------
# SupCon loss
# ---------------------------------------------------------------------------

def supcon_loss(features: torch.Tensor, labels: torch.Tensor,
                temperature: float = 0.07) -> torch.Tensor:
    """
    Supervised Contrastive Loss (Khosla et al. 2020), variante SupCon-out.
    features: (B, dim) L2-normalizados. labels: (B,) índices de especie.
    """
    device = features.device
    sim = features @ features.t() / temperature
    # estabilidad numérica
    sim = sim - sim.max(dim=1, keepdim=True)[0].detach()
    exp_sim = torch.exp(sim)

    self_mask = torch.eye(len(labels), dtype=torch.bool, device=device)
    pos_mask = (labels.unsqueeze(0) == labels.unsqueeze(1)) & ~self_mask

    # denominador: todos menos uno mismo
    denom = exp_sim.masked_fill(self_mask, 0.0).sum(dim=1, keepdim=True)
    log_prob = sim - torch.log(denom + 1e-12)

    pos_counts = pos_mask.sum(dim=1)
    valid = pos_counts > 0
    if not valid.any():
        return torch.zeros((), device=device, requires_grad=True)
    mean_log_prob_pos = (pos_mask * log_prob).sum(dim=1)[valid] / pos_counts[valid]
    return -mean_log_prob_pos.mean()


# ---------------------------------------------------------------------------
# Modelo: carga + partial-unfreeze + export
# ---------------------------------------------------------------------------

def load_openclip_visual(device: str) -> torch.nn.Module:
    """Carga OpenCLIP ViT-B/32 y devuelve un wrapper que da embeddings L2-normalizables."""
    import open_clip
    model, _, _ = open_clip.create_model_and_transforms(
        _CLIP_MODEL, pretrained=_CLIP_PRETRAINED
    )
    visual = model.visual
    return _VisualEncoder(visual).to(device)


class _VisualEncoder(torch.nn.Module):
    """Envuelve el visual tower; forward → embedding crudo (B,512), sin normalizar."""

    def __init__(self, visual: torch.nn.Module):
        super().__init__()
        self.visual = visual

    def forward(self, pixel_values: torch.Tensor) -> torch.Tensor:
        return self.visual(pixel_values)


def set_visual_trainable(model: _VisualEncoder, unfreeze_blocks: int) -> int:
    """
    Congela todo y descongela: últimos `unfreeze_blocks` resblocks + ln_post + proj.
    Devuelve el nº de parámetros entrenables.
    """
    for p in model.parameters():
        p.requires_grad = False

    visual = model.visual
    resblocks = visual.transformer.resblocks
    n = len(resblocks)
    start = max(0, n - unfreeze_blocks)
    for i in range(start, n):
        for p in resblocks[i].parameters():
            p.requires_grad = True
    for p in visual.ln_post.parameters():
        p.requires_grad = True
    if hasattr(visual, "proj") and isinstance(visual.proj, torch.nn.Parameter):
        visual.proj.requires_grad = True
    return sum(p.numel() for p in model.parameters() if p.requires_grad)


@torch.no_grad()
def _embed_dataset(model: _VisualEncoder, ds: SpeciesImageDataset,
                   device: str, batch: int = 64) -> tuple[np.ndarray, np.ndarray]:
    """Embebe todo el dataset → (feats L2-norm (N,512), labels (N,))."""
    model.eval()
    feats: list[np.ndarray] = []
    labels: list[int] = []
    items = ds.items
    for start in range(0, len(items), batch):
        chunk = items[start:start + batch]
        imgs = torch.stack([ds.load_one(p) for p, _ in chunk]).to(device)
        out = torch.nn.functional.normalize(model(imgs), dim=-1)
        feats.append(out.cpu().float().numpy())
        labels.extend(ds.key_to_label[k] for _, k in chunk)
    return np.concatenate(feats, 0), np.asarray(labels)


@torch.no_grad()
def eval_topk_internal(model: _VisualEncoder, val_ds: SpeciesImageDataset,
                       train_ds: SpeciesImageDataset, device: str) -> tuple[float, float]:
    """
    Eval top-1/top-5 interno: prototipo = media L2-norm de embeddings train por
    especie; cada query de val se asigna a la especie del prototipo más cercano
    (coseno). Espejo simplificado de eval_retrieval (sin geo), barato y rápido.
    """
    if len(val_ds) == 0:
        return 0.0, 0.0
    tr_feats, tr_labels = _embed_dataset(model, train_ds, device)
    proto_labels = sorted(set(tr_labels.tolist()))
    protos = np.stack([
        _l2(tr_feats[tr_labels == lbl].mean(axis=0)) for lbl in proto_labels
    ])
    proto_labels_arr = np.asarray(proto_labels)

    q_feats, q_labels = _embed_dataset(model, val_ds, device)
    sims = q_feats @ protos.T  # (Nq, Nproto)
    order = np.argsort(-sims, axis=1)
    top1 = (proto_labels_arr[order[:, 0]] == q_labels).mean()
    top5_keys = proto_labels_arr[order[:, :5]]
    top5 = np.any(top5_keys == q_labels[:, None], axis=1).mean()
    return float(top1), float(top5)


def _l2(v: np.ndarray) -> np.ndarray:
    n = np.linalg.norm(v)
    return v / (n if n > 1e-8 else 1.0)


def export_finetuned_onnx(model: _VisualEncoder, output_path: Path,
                          device: str, opset: int = 14) -> Path:
    """
    Exporta el visual tower afinado a ONNX con el contrato de OnnxSpeciesEmbedder.kt:
    input `pixel_values`[b,3,224,224], output `image_features`[b,512] L2-norm, opset 14.
    """
    output_path = Path(output_path)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    model.eval()
    dummy = torch.zeros(1, 3, 224, 224, device=device)

    class _ExportWrapper(torch.nn.Module):
        def __init__(self, enc: _VisualEncoder):
            super().__init__()
            self.enc = enc

        def forward(self, pixel_values: torch.Tensor) -> torch.Tensor:
            feats = self.enc(pixel_values)
            norm = feats.norm(dim=-1, keepdim=True).clamp(min=1e-8)
            return feats / norm

    wrapper = _ExportWrapper(model).eval().to(device)
    torch.onnx.export(
        wrapper, dummy, str(output_path),
        input_names=["pixel_values"], output_names=["image_features"],
        dynamic_axes={"pixel_values": {0: "batch"}, "image_features": {0: "batch"}},
        opset_version=opset,
    )
    return output_path
