"""
OceanGuard AI - Base Model Conversion Script

Converts Gemma 3n base model from PyTorch/Safetensors format to LiteRT format
for Android deployment using MediaPipe converter.

Usage:
    python convert_base_model.py [--config CONFIG_PATH] [--output OUTPUT_PATH]

Requirements:
    - Downloaded Gemma 3n model (run download_base_model.py first)
    - ai-edge-torch, mediapipe, ai-edge-litert installed
    - 16GB+ RAM recommended
"""

import os
import sys
import argparse
import logging
from pathlib import Path
from typing import Optional
import time

import yaml
import torch
import numpy as np
from transformers import AutoTokenizer, AutoModelForCausalLM

try:
    import mediapipe as mp
    from mediapipe.tasks.python.genai import converter
    MEDIAPIPE_AVAILABLE = True
except ImportError:
    MEDIAPIPE_AVAILABLE = False
    logging.warning("MediaPipe not available, will use ai-edge-torch instead")

try:
    from ai_edge_torch.generative.quantize import quant_recipes
    from ai_edge_torch import convert as aiedge_convert
    AI_EDGE_AVAILABLE = True
except ImportError:
    AI_EDGE_AVAILABLE = False


# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)


class BaseModelConverter:
    """Converts Gemma 3n base model to LiteRT format."""

    def __init__(self, config_path: str = "config.yaml"):
        """Initialize converter with configuration."""
        self.config = self._load_config(config_path)
        self.cache_dir = Path(self.config['base_model']['cache_dir'])
        self.output_dir = Path(self.config['base_model']['output_file']).parent
        self.output_dir.mkdir(parents=True, exist_ok=True)

        # Create logs directory
        log_dir = Path(self.config['logging']['log_file']).parent
        log_dir.mkdir(parents=True, exist_ok=True)

    def _load_config(self, config_path: str) -> dict:
        """Load configuration from YAML file."""
        config_file = Path(config_path)
        if not config_file.exists():
            raise FileNotFoundError(f"Config file not found: {config_path}")

        with open(config_file, 'r') as f:
            config = yaml.safe_load(f)

        return config

    def check_prerequisites(self) -> bool:
        """Check if all prerequisites are met."""
        logger.info("Checking prerequisites...")

        # Check if model is downloaded
        model_id = self.config['base_model']['model_id']
        model_name = model_id.split('/')[-1]
        model_path = self.cache_dir / model_name

        if not model_path.exists():
            logger.error(f"Model not found at {model_path}")
            logger.error("Please run download_base_model.py first")
            return False

        logger.info(f"✓ Model found at {model_path}")

        # Check conversion libraries
        if not MEDIAPIPE_AVAILABLE and not AI_EDGE_AVAILABLE:
            logger.error("Neither MediaPipe nor AI Edge Torch is available")
            logger.error("Please install: pip install mediapipe ai-edge-torch")
            return False

        if MEDIAPIPE_AVAILABLE:
            logger.info(f"✓ MediaPipe available (version {mp.__version__})")
        if AI_EDGE_AVAILABLE:
            logger.info("✓ AI Edge Torch available")

        # Check disk space (need ~5GB free for conversion)
        import shutil
        stat = shutil.disk_usage(self.output_dir)
        free_gb = stat.free / (1024 ** 3)
        if free_gb < 5:
            logger.warning(f"Low disk space: {free_gb:.1f} GB free")
            logger.warning("Conversion may fail - recommend at least 5GB free")

        logger.info(f"✓ Disk space: {free_gb:.1f} GB available")

        return True

    def convert_with_mediapipe(self, model_path: Path, output_path: Path) -> bool:
        """Convert model using MediaPipe converter."""
        logger.info("Converting with MediaPipe converter...")

        try:
            # Determine model type based on variant
            variant = self.config['base_model']['variant']
            if variant == "E2B":
                model_type = "GEMMA_2B"
            elif variant == "E4B":
                model_type = "GEMMA_4B"  # May need adjustment based on MediaPipe version
            else:
                logger.warning(f"Unknown variant {variant}, defaulting to GEMMA_2B")
                model_type = "GEMMA_2B"

            # Get backend
            backend = self.config['conversion']['backend']

            # Prepare conversion config
            config = converter.ConversionConfig(
                input_ckpt=str(model_path),
                ckpt_format='safetensors',
                model_type=model_type,
                backend=backend,
                output_tflite_file=str(output_path),
            )

            # Add vocab/tokenizer if specified
            vocab_file = self.config['conversion'].get('vocab_model_file')
            if vocab_file and Path(vocab_file).exists():
                config.vocab_model_file = vocab_file

            logger.info(f"Conversion configuration:")
            logger.info(f"  Input: {model_path}")
            logger.info(f"  Output: {output_path}")
            logger.info(f"  Model type: {model_type}")
            logger.info(f"  Backend: {backend}")

            # Run conversion
            logger.info("Starting conversion (this may take 10-30 minutes)...")
            start_time = time.time()

            converter.convert_checkpoint(config)

            elapsed_time = time.time() - start_time
            logger.info(f"✓ Conversion completed in {elapsed_time / 60:.1f} minutes")

            # Check output file
            if output_path.exists():
                size_mb = output_path.stat().st_size / (1024 ** 2)
                logger.info(f"✓ Output file created: {size_mb:.1f} MB")
                return True
            else:
                logger.error("Output file not created")
                return False

        except Exception as e:
            logger.error(f"MediaPipe conversion failed: {e}")
            import traceback
            traceback.print_exc()
            return False

    def convert_with_aiedge(self, model_path: Path, output_path: Path) -> bool:
        """Convert model using AI Edge Torch."""
        logger.info("Converting with AI Edge Torch...")

        try:
            # Load model
            logger.info("Loading PyTorch model...")
            model = AutoModelForCausalLM.from_pretrained(
                str(model_path),
                torch_dtype=torch.float16,
                device_map="cpu",
                trust_remote_code=True
            )

            logger.info("Model loaded successfully")

            # Get quantization config
            quant_config = None
            if self.config['conversion']['quantization']['enabled']:
                scheme = self.config['conversion']['quantization']['scheme']
                logger.info(f"Applying quantization: {scheme}")

                if scheme == "dynamic_int4_weight_only":
                    quant_config = quant_recipes.dynamic_int4_weight_only()
                elif scheme == "dynamic_int8":
                    quant_config = quant_recipes.dynamic_int8()
                # Add other schemes as needed

            # Convert
            logger.info("Starting conversion...")
            start_time = time.time()

            edge_model = aiedge_convert(
                model,
                quant_config=quant_config
            )

            # Export
            edge_model.export(str(output_path))

            elapsed_time = time.time() - start_time
            logger.info(f"✓ Conversion completed in {elapsed_time / 60:.1f} minutes")

            # Check output
            if output_path.exists():
                size_mb = output_path.stat().st_size / (1024 ** 2)
                logger.info(f"✓ Output file created: {size_mb:.1f} MB")
                return True
            else:
                logger.error("Output file not created")
                return False

        except Exception as e:
            logger.error(f"AI Edge Torch conversion failed: {e}")
            import traceback
            traceback.print_exc()
            return False

    def convert(self) -> bool:
        """Main conversion method."""
        # Get model path
        model_id = self.config['base_model']['model_id']
        model_name = model_id.split('/')[-1]
        model_path = self.cache_dir / model_name

        # Get output path
        output_path = Path(self.config['base_model']['output_file'])

        logger.info("\n" + "=" * 60)
        logger.info("STARTING BASE MODEL CONVERSION")
        logger.info("=" * 60)
        logger.info(f"Input model: {model_path}")
        logger.info(f"Output file: {output_path}")
        logger.info("=" * 60 + "\n")

        # Try MediaPipe first, fallback to AI Edge Torch
        success = False

        if MEDIAPIPE_AVAILABLE:
            success = self.convert_with_mediapipe(model_path, output_path)

        if not success and AI_EDGE_AVAILABLE:
            logger.info("Trying AI Edge Torch as fallback...")
            success = self.convert_with_aiedge(model_path, output_path)

        if success:
            self._display_success(output_path)
        else:
            logger.error("Conversion failed with all available methods")

        return success

    def _display_success(self, output_path: Path):
        """Display success message and next steps."""
        logger.info("\n" + "=" * 60)
        logger.info("CONVERSION SUCCESSFUL!")
        logger.info("=" * 60)
        logger.info(f"Converted model: {output_path}")

        size_mb = output_path.stat().st_size / (1024 ** 2)
        size_gb = size_mb / 1024
        logger.info(f"File size: {size_mb:.1f} MB ({size_gb:.2f} GB)")

        logger.info("\nNext steps:")
        logger.info("1. Convert LoRA adapter: python convert_lora_adapter.py")
        logger.info("2. Validate models: python validate_converted_model.py")
        logger.info("3. Copy to Android project assets")

        logger.info("=" * 60 + "\n")


def main():
    """Main execution function."""
    parser = argparse.ArgumentParser(
        description="Convert Gemma 3n base model to LiteRT format"
    )
    parser.add_argument(
        "--config",
        type=str,
        default="config.yaml",
        help="Path to configuration file"
    )
    parser.add_argument(
        "--output",
        type=str,
        help="Output file path (overrides config)"
    )

    args = parser.parse_args()

    try:
        # Initialize converter
        converter_obj = BaseModelConverter(args.config)

        # Override output if specified
        if args.output:
            converter_obj.config['base_model']['output_file'] = args.output

        # Check prerequisites
        if not converter_obj.check_prerequisites():
            logger.error("Prerequisites check failed")
            sys.exit(1)

        # Run conversion
        success = converter_obj.convert()

        if success:
            logger.info("✓ Base model conversion complete!")
            return 0
        else:
            logger.error("✗ Base model conversion failed")
            return 1

    except KeyboardInterrupt:
        logger.info("\nConversion interrupted by user")
        return 1
    except Exception as e:
        logger.error(f"\nFatal error: {e}")
        import traceback
        traceback.print_exc()
        return 1


if __name__ == "__main__":
    sys.exit(main())
