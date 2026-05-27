"""
embedder.py — Wrapper del encoder de imágenes para BioDex RAG visual.

Interfaz pluggable: ImageEmbedder.embed(image_paths) -> np.ndarray L2-normalizado.

Implementaciones:
  - OpenCLIPEmbedder: ViT-B/32 LAION-2B (dim 512, licencia MIT).
      Requiere: open_clip_torch, torch, Pillow
      Uso producción: python embedder.py --export-onnx clip_vitb32.onnx
  - FakeEmbedder: hash deterministico → vector, para tests sin descargar pesos.
      Sin dependencias pesadas; siempre disponible.

Export ONNX:
  El método export_onnx() exporta SOLO el visual encoder (image tower) de CLIP,
  que es el componente usado en Android (ONNX Runtime Mobile / NNAPI EP).
  El encoder de texto no se exporta (no se usa en el RAG on-device).

Contrato de dimensiones:
  - Salida siempre dim=512, float32, L2-normalizado (norma = 1.0).
  - Compatible con brute-force coseno via producto punto en Kotlin.
"""

from __future__ import annotations

import hashlib
from abc import ABC, abstractmethod
from pathlib import Path
from typing import TYPE_CHECKING

import numpy as np

if TYPE_CHECKING:
    pass

# Dimensión fijada globalmente para desacoplar encoder de índice.
EMBED_DIM = 512
# Modelo primario OpenCLIP
_CLIP_MODEL = "ViT-B-32"
_CLIP_PRETRAINED = "laion2b_s34b_b79k"


class ImageEmbedder(ABC):
    """Interfaz base. Toda implementación devuelve embeddings L2-normalizados."""

    @property
    def dim(self) -> int:
        return EMBED_DIM

    @abstractmethod
    def embed(self, image_paths: list[str]) -> np.ndarray:
        """
        Procesa una lista de paths de imagen y devuelve embeddings.

        Args:
            image_paths: Rutas absolutas o relativas a archivos de imagen.

        Returns:
            np.ndarray shape (N, 512), dtype float32, cada fila L2-normalizada.
        """

    @staticmethod
    def _l2_normalize(vectors: np.ndarray) -> np.ndarray:
        """Normaliza cada fila a norma unitaria (in-place safe)."""
        norms = np.linalg.norm(vectors, axis=1, keepdims=True)
        norms = np.where(norms < 1e-8, 1.0, norms)
        return vectors / norms


class FakeEmbedder(ImageEmbedder):
    """
    Embeddder determinista basado en hash SHA256 del path.
    No requiere pesos ni GPU. Útil para tests y modo --mock.

    Los vectores son reproducibles: mismo path -> mismo vector siempre.
    """

    def embed(self, image_paths: list[str]) -> np.ndarray:
        vectors = np.zeros((len(image_paths), EMBED_DIM), dtype=np.float32)
        for i, path in enumerate(image_paths):
            seed = int(hashlib.sha256(path.encode()).hexdigest()[:8], 16)
            rng = np.random.default_rng(seed)
            vectors[i] = rng.standard_normal(EMBED_DIM).astype(np.float32)
        return self._l2_normalize(vectors)


class OpenCLIPEmbedder(ImageEmbedder):
    """
    Encoder visual OpenCLIP ViT-B/32 (LAION-2B). Licencia MIT.

    Dependencias requeridas: open_clip_torch, torch, Pillow.
    Pesos descargados automáticamente por open_clip desde cache local.

    Args:
        device: 'cpu' | 'cuda'. Se auto-detecta si None.
        batch_size: Número de imágenes por forward pass.
        ckpt_path: Si se indica, carga el state_dict del visual tower afinado
            (ckpt de train_encoder.py, clave 'visual_state_dict') sobre los pesos
            base. El resto del contrato (preprocess, dim 512, L2-norm) no cambia.
    """

    def __init__(self, device: str | None = None, batch_size: int = 32,
                 ckpt_path: str | Path | None = None) -> None:
        import torch
        import open_clip  # type: ignore[import]

        self._torch = torch
        self._device = device or ("cuda" if torch.cuda.is_available() else "cpu")
        self._batch_size = batch_size

        model, _, preprocess = open_clip.create_model_and_transforms(
            _CLIP_MODEL, pretrained=_CLIP_PRETRAINED
        )
        if ckpt_path is not None:
            self._load_finetuned(model.visual, Path(ckpt_path), torch)
        model.eval().to(self._device)
        self._model = model
        self._preprocess = preprocess

    @staticmethod
    def _load_finetuned(visual, ckpt_path: Path, torch) -> None:
        """Carga 'visual_state_dict' del ckpt afinado sobre el visual tower base."""
        ckpt = torch.load(str(ckpt_path), map_location="cpu", weights_only=False)
        state = ckpt["visual_state_dict"] if "visual_state_dict" in ckpt else ckpt
        # El ckpt guarda con prefijo 'visual.' (wrapper _VisualEncoder); lo quitamos.
        cleaned = {
            (k[len("visual."):] if k.startswith("visual.") else k): v
            for k, v in state.items()
        }
        missing, unexpected = visual.load_state_dict(cleaned, strict=False)
        if unexpected:
            print(f"WARN ckpt: claves inesperadas ignoradas: {len(unexpected)}")
        print(f"Fine-tuned visual cargado desde {ckpt_path} "
              f"(missing={len(missing)}, unexpected={len(unexpected)})")

    def embed(self, image_paths: list[str]) -> np.ndarray:
        from PIL import Image  # type: ignore[import]

        all_vectors: list[np.ndarray] = []
        for start in range(0, len(image_paths), self._batch_size):
            batch_paths = image_paths[start : start + self._batch_size]
            imgs = [
                self._preprocess(Image.open(p).convert("RGB"))
                for p in batch_paths
            ]
            tensor = self._torch.stack(imgs).to(self._device)
            with self._torch.no_grad():
                features = self._model.encode_image(tensor)
            vecs = features.cpu().float().numpy()
            all_vectors.append(vecs)

        combined = np.concatenate(all_vectors, axis=0)
        return self._l2_normalize(combined)

    def export_onnx(self, output_path: str | Path, opset: int = 14) -> Path:
        """
        Exporta el visual encoder a ONNX para uso en Android (ONNX Runtime Mobile).

        Input:  pixel_values   shape (batch, 3, 224, 224) float32  rango [0,1] normalizado CLIP
        Output: image_features shape (batch, 512)          float32  L2-normalizado

        NOTA: los nombres de tensores DEBEN coincidir con OnnxSpeciesEmbedder.kt
        (INPUT_NAME="pixel_values", OUTPUT_NAME="image_features"). Verificado E2E
        en device con el encoder sintético; un mismatch rompe la inferencia ORT.

        El tensor de entrada sigue la normalización CLIP estándar:
          mean = [0.48145466, 0.4578275, 0.40821073]
          std  = [0.26862954, 0.26130258, 0.27577711]
        Esto debe replicarse en Kotlin antes de pasar al ORT session.

        Args:
            output_path: Ruta destino del archivo .onnx.
            opset: Versión ONNX opset (14 = compatible con ORT Mobile 1.16+).

        Returns:
            Path al archivo exportado.
        """
        import torch

        output_path = Path(output_path)
        output_path.parent.mkdir(parents=True, exist_ok=True)

        dummy = torch.zeros(1, 3, 224, 224, device=self._device)

        class _VisualWrapper(torch.nn.Module):
            def __init__(self, visual):
                super().__init__()
                self.visual = visual

            def forward(self, pixel_values: torch.Tensor) -> torch.Tensor:
                feats = self.visual(pixel_values)
                norm = feats.norm(dim=-1, keepdim=True).clamp(min=1e-8)
                return feats / norm

        wrapper = _VisualWrapper(self._model.visual).eval().to(self._device)

        torch.onnx.export(
            wrapper,
            dummy,
            str(output_path),
            input_names=["pixel_values"],
            output_names=["image_features"],
            dynamic_axes={"pixel_values": {0: "batch"}, "image_features": {0: "batch"}},
            opset_version=opset,
        )
        print(f"ONNX exportado: {output_path}")
        return output_path


def _build_embedder(fake: bool, ckpt_path: str | None = None) -> ImageEmbedder:
    if fake:
        return FakeEmbedder()
    return OpenCLIPEmbedder(ckpt_path=ckpt_path)


def main() -> None:
    import argparse

    parser = argparse.ArgumentParser(
        description="Embed imágenes con OpenCLIP ViT-B/32 o FakeEmbedder (--mock)."
    )
    parser.add_argument("images", nargs="*", help="Paths de imagen a embeber.")
    parser.add_argument(
        "--export-onnx",
        metavar="PATH",
        help="Exportar visual encoder a ONNX en el path indicado y salir.",
    )
    parser.add_argument(
        "--mock",
        action="store_true",
        help="Usar FakeEmbedder determinista (sin descargar pesos).",
    )
    parser.add_argument(
        "--load-ckpt",
        metavar="PATH",
        help="Cargar visual tower afinado (ckpt de train_encoder.py) antes de embed/export.",
    )
    args = parser.parse_args()

    embedder = _build_embedder(fake=args.mock, ckpt_path=args.load_ckpt)
    print(f"Embedder: {embedder.__class__.__name__}, dim={embedder.dim}")

    if args.export_onnx:
        if isinstance(embedder, OpenCLIPEmbedder):
            embedder.export_onnx(args.export_onnx)
        else:
            print("ERROR: --export-onnx requiere OpenCLIPEmbedder (no --mock).")
        return

    if not args.images:
        print("Modo test: generando 3 embeddings sintéticos...")
        fake = FakeEmbedder()
        vecs = fake.embed(["img_a.jpg", "img_b.jpg", "img_c.jpg"])
        print(f"  Shape: {vecs.shape}, dtype: {vecs.dtype}")
        norms = np.linalg.norm(vecs, axis=1)
        print(f"  Normas (deben ser ~1.0): {norms}")
        return

    vecs = embedder.embed(args.images)
    print(f"Shape: {vecs.shape}, dtype: {vecs.dtype}")
    norms = np.linalg.norm(vecs, axis=1)
    print(f"Normas: {norms}")


if __name__ == "__main__":
    main()
