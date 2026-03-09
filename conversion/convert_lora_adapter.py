"""
OceanGuard AI - LoRA Adapter Conversion Script

Converts the fine-tuned LoRA adapter from Safetensors format to LiteRT format
for Android deployment with the base Gemma 3n model.

Usage:
    python convert_lora_adapter.py [--config CONFIG_PATH] [--adapter-path ADAPTER_PATH]

Requirements:
    - Fine-tuned LoRA adapter (adapter_model.safetensors)
    - Converted base model (run convert_base_model.py first)
    - MediaPipe with LoRA support (GPU backend required)
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
from safetensors import safe_open

try:
    import mediapipe as mp
    from mediapipe.tasks.python.genai import converter
    MEDIAPIPE_AVAILABLE = True
except ImportError:
    MEDIAPIPE_AVAILABLE = False
    logging.error("MediaPipe not available - required for LoRA conversion")


# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)


class LoRAConverter:
    """Converts LoRA adapter to LiteRT format."""

    def __init__(self, config_path: str = "config.yaml"):
        """Initialize converter with configuration."""
        self.config = self._load_config(config_path)
        self.output_dir = Path(self.config['lora_adapter']['output_file']).parent
        self.output_dir.mkdir(parents=True, exist_ok=True)

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

        # Check MediaPipe availability
        if not MEDIAPIPE_AVAILABLE:
            logger.error("MediaPipe is required for LoRA conversion")
            logger.error("Install with: pip install mediapipe")
            return False

        logger.info(f"✓ MediaPipe available (version {mp.__version__})")

        # Check if adapter file exists
        adapter_path = Path(self.config['lora_adapter']['adapter_path'])
        if not adapter_path.exists():
            logger.error(f"LoRA adapter not found at {adapter_path}")
            logger.error("Please check the path in config.yaml")
            logger.error("Expected file: adapter_model.safetensors")
            return False

        adapter_size_mb = adapter_path.stat().st_size / (1024 ** 2)
        logger.info(f"✓ LoRA adapter found: {adapter_size_mb:.1f} MB")

        # Check if base model was converted
        base_model_path = Path(self.config['base_model']['output_file'])
        if not base_model_path.exists():
            logger.error(f"Base model not found at {base_model_path}")
            logger.error("Please run convert_base_model.py first")
            return False

        logger.info(f"✓ Base model found: {base_model_path}")

        # Check backend (GPU required for LoRA)
        backend = self.config['conversion']['backend']
        if backend != "gpu":
            logger.warning(f"Backend is set to '{backend}'")
            logger.warning("LoRA inference requires GPU backend")
            logger.warning("Forcing backend to 'gpu'")
            self.config['conversion']['backend'] = "gpu"

        return True

    def inspect_lora_adapter(self, adapter_path: Path) -> dict:
        """Inspect LoRA adapter to extract metadata."""
        logger.info("Inspecting LoRA adapter...")

        try:
            metadata = {
                'tensors': [],
                'total_params': 0,
                'rank': None,
                'target_modules': set()
            }

            with safe_open(adapter_path, framework="pt", device="cpu") as f:
                for key in f.keys():
                    tensor = f.get_tensor(key)
                    params = tensor.numel()
                    metadata['tensors'].append({
                        'name': key,
                        'shape': list(tensor.shape),
                        'params': params,
                        'dtype': str(tensor.dtype)
                    })
                    metadata['total_params'] += params

                    # Extract rank from lora_A tensors
                    if 'lora_A' in key and metadata['rank'] is None:
                        metadata['rank'] = tensor.shape[0]

                    # Extract target module names
                    if 'lora_' in key:
                        module_name = key.split('.')[-2]  # e.g., 'q_proj', 'v_proj'
                        metadata['target_modules'].add(module_name)

            metadata['target_modules'] = list(metadata['target_modules'])

            # Display summary
            logger.info(f"LoRA Adapter Summary:")
            logger.info(f"  Total parameters: {metadata['total_params']:,}")
            logger.info(f"  Rank: {metadata['rank']}")
            logger.info(f"  Target modules: {', '.join(metadata['target_modules'])}")
            logger.info(f"  Number of tensors: {len(metadata['tensors'])}")

            return metadata

        except Exception as e:
            logger.error(f"Failed to inspect adapter: {e}")
            return {}

    def convert(self, adapter_path: Optional[Path] = None) -> bool:
        """Convert LoRA adapter to LiteRT format."""
        # Get adapter path
        if adapter_path is None:
            adapter_path = Path(self.config['lora_adapter']['adapter_path'])

        # Get output path
        output_path = Path(self.config['lora_adapter']['output_file'])

        # Get base model info
        base_model_id = self.config['base_model']['model_id']
        base_model_name = base_model_id.split('/')[-1]
        base_model_cache = Path(self.config['base_model']['cache_dir']) / base_model_name

        logger.info("\n" + "=" * 60)
        logger.info("STARTING LORA ADAPTER CONVERSION")
        logger.info("=" * 60)
        logger.info(f"Base model: {base_model_cache}")
        logger.info(f"LoRA adapter: {adapter_path}")
        logger.info(f"Output file: {output_path}")
        logger.info("=" * 60 + "\n")

        # Inspect adapter
        metadata = self.inspect_lora_adapter(adapter_path)

        # Get LoRA configuration
        lora_rank = self.config['lora_adapter'].get('rank', metadata.get('rank', 64))
        lora_alpha = self.config['lora_adapter'].get('alpha', 64)

        try:
            # Determine model type
            variant = self.config['base_model']['variant']
            if variant == "E2B":
                model_type = "GEMMA_2B"
            elif variant == "E4B":
                model_type = "GEMMA_4B"
            else:
                model_type = "GEMMA_2B"

            # Prepare conversion config with LoRA
            logger.info("Preparing MediaPipe conversion config...")
            config = converter.ConversionConfig(
                # Base model parameters
                input_ckpt=str(base_model_cache),
                ckpt_format='safetensors',
                model_type=model_type,
                backend='gpu',  # GPU required for LoRA
                output_tflite_file=str(output_path.parent / "temp_base.bin"),

                # LoRA specific parameters
                lora_ckpt=str(adapter_path),
                lora_rank=lora_rank,
                lora_output_tflite_file=str(output_path)
            )

            # Add vocab/tokenizer if specified
            vocab_file = self.config['conversion'].get('vocab_model_file')
            if vocab_file and Path(vocab_file).exists():
                config.vocab_model_file = vocab_file

            logger.info("Conversion configuration:")
            logger.info(f"  Model type: {model_type}")
            logger.info(f"  LoRA rank: {lora_rank}")
            logger.info(f"  LoRA alpha: {lora_alpha}")
            logger.info(f"  Backend: gpu (required for LoRA)")

            # Run conversion
            logger.info("\nStarting conversion (this may take 15-45 minutes)...")
            logger.info("Note: This process requires GPU and substantial memory")
            start_time = time.time()

            converter.convert_checkpoint(config)

            elapsed_time = time.time() - start_time
            logger.info(f"✓ Conversion completed in {elapsed_time / 60:.1f} minutes")

            # Check output file
            if output_path.exists():
                size_mb = output_path.stat().st_size / (1024 ** 2)
                logger.info(f"✓ LoRA adapter file created: {size_mb:.1f} MB")

                # Clean up temp base file if it exists
                temp_base = output_path.parent / "temp_base.bin"
                if temp_base.exists():
                    temp_base.unlink()
                    logger.info("✓ Cleaned up temporary files")

                self._display_success(output_path, metadata)
                return True
            else:
                logger.error("Output file not created")
                return False

        except Exception as e:
            logger.error(f"LoRA conversion failed: {e}")
            import traceback
            traceback.print_exc()

            # Display troubleshooting info
            logger.error("\nTroubleshooting:")
            logger.error("1. Ensure GPU is available and CUDA is properly installed")
            logger.error("2. Check that adapter file is valid Safetensors format")
            logger.error("3. Verify base model was converted successfully")
            logger.error("4. Try with a smaller LoRA rank if memory issues occur")

            # Check if this is a known limitation
            logger.error("\nNOTE: If conversion fails with vision-related errors:")
            logger.error("MediaPipe may have limited support for vision LoRA layers")
            logger.error("Alternative: Use merged model + INT4 quantization approach")
            logger.error("  1. Merge LoRA with base model using PEFT")
            logger.error("  2. Convert merged model with INT4 quantization")

            return False

    def _display_success(self, output_path: Path, metadata: dict):
        """Display success message and next steps."""
        logger.info("\n" + "=" * 60)
        logger.info("LORA CONVERSION SUCCESSFUL!")
        logger.info("=" * 60)

        size_mb = output_path.stat().st_size / (1024 ** 2)
        logger.info(f"Converted adapter: {output_path}")
        logger.info(f"File size: {size_mb:.1f} MB")

        if metadata:
            compression_ratio = (metadata['total_params'] * 4) / (size_mb * 1024 * 1024)
            logger.info(f"Compression: {compression_ratio:.1f}x")

        logger.info("\nModel deployment files:")
        base_path = Path(self.config['base_model']['output_file'])
        logger.info(f"  1. Base model: {base_path}")
        logger.info(f"  2. LoRA adapter: {output_path}")

        base_size = base_path.stat().st_size / (1024 ** 2) if base_path.exists() else 0
        total_size = base_size + size_mb
        logger.info(f"\nTotal deployment size: {total_size:.1f} MB ({total_size/1024:.2f} GB)")

        logger.info("\nNext steps:")
        logger.info("1. Validate converted models: python validate_converted_model.py")
        logger.info("2. Copy both files to Android project:")
        android_dir = self.config['android']['output_directory']
        logger.info(f"   Target directory: {android_dir}")
        logger.info("3. Configure Android app to load base + LoRA")
        logger.info("4. Test on Android device/emulator")

        logger.info("=" * 60 + "\n")


def main():
    """Main execution function."""
    parser = argparse.ArgumentParser(
        description="Convert LoRA adapter to LiteRT format for Android deployment"
    )
    parser.add_argument(
        "--config",
        type=str,
        default="config.yaml",
        help="Path to configuration file"
    )
    parser.add_argument(
        "--adapter-path",
        type=str,
        help="Path to LoRA adapter file (overrides config)"
    )
    parser.add_argument(
        "--output",
        type=str,
        help="Output file path (overrides config)"
    )

    args = parser.parse_args()

    try:
        # Initialize converter
        converter_obj = LoRAConverter(args.config)

        # Override paths if specified
        if args.adapter_path:
            converter_obj.config['lora_adapter']['adapter_path'] = args.adapter_path
        if args.output:
            converter_obj.config['lora_adapter']['output_file'] = args.output

        # Check prerequisites
        if not converter_obj.check_prerequisites():
            logger.error("Prerequisites check failed")
            sys.exit(1)

        # Run conversion
        adapter_path = Path(converter_obj.config['lora_adapter']['adapter_path'])
        success = converter_obj.convert(adapter_path)

        if success:
            logger.info("✓ LoRA adapter conversion complete!")
            return 0
        else:
            logger.error("✗ LoRA adapter conversion failed")
            logger.error("\nIf conversion continues to fail:")
            logger.error("Consider the alternative approach: merged model + quantization")
            logger.error("See docs/CONVERSION_GUIDE.md for details")
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
