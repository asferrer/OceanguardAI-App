"""
OceanGuard AI - Base Model Download Script

Downloads the Gemma 3n base model from Hugging Face for conversion to LiteRT format.
This script handles authentication, model downloading, and file organization.

Usage:
    python download_base_model.py [--config CONFIG_PATH] [--model-variant E2B|E4B]

Requirements:
    - Hugging Face account with Gemma access approval
    - HF_TOKEN environment variable or manual login
    - ~3-8GB free disk space (depending on variant)
"""

import os
import sys
import argparse
import logging
from pathlib import Path
from typing import Optional

import yaml
from huggingface_hub import login, snapshot_download, HfApi
from transformers import AutoTokenizer, AutoModelForCausalLM


# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)


class ModelDownloader:
    """Handles downloading Gemma 3n base models from Hugging Face."""

    def __init__(self, config_path: str = "config.yaml"):
        """Initialize downloader with configuration."""
        self.config = self._load_config(config_path)
        self.cache_dir = Path(self.config['base_model']['cache_dir'])
        self.cache_dir.mkdir(parents=True, exist_ok=True)

    def _load_config(self, config_path: str) -> dict:
        """Load configuration from YAML file."""
        config_file = Path(config_path)
        if not config_file.exists():
            raise FileNotFoundError(f"Config file not found: {config_path}")

        with open(config_file, 'r') as f:
            config = yaml.safe_load(f)

        return config

    def authenticate(self) -> bool:
        """Authenticate with Hugging Face."""
        # Try environment variable first
        hf_token = os.environ.get("HF_TOKEN")

        if hf_token:
            logger.info("Found HF_TOKEN in environment variables")
            try:
                login(token=hf_token)
                logger.info("Successfully authenticated with Hugging Face")
                return True
            except Exception as e:
                logger.error(f"Authentication failed: {e}")
                return False
        else:
            logger.info("HF_TOKEN not found in environment")
            logger.info("Attempting interactive login...")
            try:
                login()
                logger.info("Successfully authenticated with Hugging Face")
                return True
            except Exception as e:
                logger.error(f"Interactive login failed: {e}")
                logger.error("\nPlease follow these steps:")
                logger.error("1. Go to https://huggingface.co/settings/tokens")
                logger.error("2. Create a new token with 'read' permission")
                logger.error("3. Run: export HF_TOKEN='your_token_here'")
                logger.error("4. Or run: huggingface-cli login")
                return False

    def check_access(self, model_id: str) -> bool:
        """Check if user has access to the Gemma model."""
        try:
            api = HfApi()
            model_info = api.model_info(model_id)
            logger.info(f"✓ Access confirmed for {model_id}")
            logger.info(f"  Model: {model_info.modelId}")
            logger.info(f"  Author: {model_info.author}")
            logger.info(f"  Downloads: {model_info.downloads}")
            return True
        except Exception as e:
            logger.error(f"✗ Access denied for {model_id}")
            logger.error(f"  Error: {e}")
            logger.error("\nPlease request access:")
            logger.error(f"  1. Go to https://huggingface.co/{model_id}")
            logger.error("  2. Click 'Agree and access repository'")
            logger.error("  3. Wait for approval (usually instant for Gemma)")
            return False

    def download_model(self, model_id: Optional[str] = None) -> Path:
        """Download the Gemma model and tokenizer."""
        if model_id is None:
            model_id = self.config['base_model']['model_id']

        logger.info(f"Starting download: {model_id}")
        logger.info(f"Cache directory: {self.cache_dir}")

        try:
            # Download full model repository
            logger.info("Downloading model files...")
            model_path = snapshot_download(
                repo_id=model_id,
                cache_dir=str(self.cache_dir),
                local_dir=str(self.cache_dir / model_id.split('/')[-1]),
                local_dir_use_symlinks=False,
            )

            logger.info(f"✓ Model downloaded to: {model_path}")

            # Verify critical files exist
            model_dir = Path(model_path)
            required_files = [
                "model.safetensors.index.json",  # or model.safetensors
                "config.json",
                "tokenizer.json",
                "tokenizer_config.json",
            ]

            missing_files = []
            for file in required_files:
                file_path = model_dir / file
                if not file_path.exists():
                    # Try without .index for single-file models
                    if file == "model.safetensors.index.json":
                        alt_file = model_dir / "model.safetensors"
                        if not alt_file.exists():
                            missing_files.append(file)
                    else:
                        missing_files.append(file)

            if missing_files:
                logger.warning(f"Some files might be missing: {missing_files}")
                logger.warning("This may be normal for some model formats")

            # Download tokenizer separately to ensure we have it
            logger.info("Verifying tokenizer files...")
            tokenizer = AutoTokenizer.from_pretrained(
                model_id,
                cache_dir=str(self.cache_dir),
                trust_remote_code=True
            )

            tokenizer_path = self.cache_dir / "tokenizer"
            tokenizer_path.mkdir(exist_ok=True)
            tokenizer.save_pretrained(str(tokenizer_path))

            logger.info(f"✓ Tokenizer saved to: {tokenizer_path}")

            # Display download summary
            self._display_summary(model_dir)

            return model_dir

        except Exception as e:
            logger.error(f"Download failed: {e}")
            raise

    def _display_summary(self, model_path: Path):
        """Display download summary."""
        logger.info("\n" + "=" * 60)
        logger.info("DOWNLOAD SUMMARY")
        logger.info("=" * 60)

        # Calculate total size
        total_size = sum(f.stat().st_size for f in model_path.rglob('*') if f.is_file())
        total_size_gb = total_size / (1024 ** 3)

        logger.info(f"Model path: {model_path}")
        logger.info(f"Total size: {total_size_gb:.2f} GB")

        # List key files
        logger.info("\nKey files:")
        key_patterns = ["*.safetensors", "*.json", "*.model", "*.bin"]
        for pattern in key_patterns:
            files = list(model_path.glob(pattern))
            for file in files:
                size_mb = file.stat().st_size / (1024 ** 2)
                logger.info(f"  {file.name}: {size_mb:.1f} MB")

        logger.info("=" * 60 + "\n")

    def verify_download(self, model_path: Path) -> bool:
        """Verify the downloaded model can be loaded."""
        logger.info("Verifying model integrity...")

        try:
            # Try to load config
            from transformers import AutoConfig
            config = AutoConfig.from_pretrained(str(model_path))
            logger.info(f"✓ Model config loaded successfully")
            logger.info(f"  Architecture: {config.model_type}")
            logger.info(f"  Hidden size: {config.hidden_size}")
            logger.info(f"  Num layers: {config.num_hidden_layers}")

            # Try to load tokenizer
            tokenizer = AutoTokenizer.from_pretrained(str(model_path))
            logger.info(f"✓ Tokenizer loaded successfully")
            logger.info(f"  Vocab size: {len(tokenizer)}")

            # Test tokenization
            test_text = "Detect marine debris in this image"
            tokens = tokenizer.encode(test_text)
            decoded = tokenizer.decode(tokens)
            logger.info(f"✓ Tokenization test passed")
            logger.info(f"  Input: {test_text}")
            logger.info(f"  Tokens: {len(tokens)}")

            logger.info("\n✓ Verification complete - model is ready for conversion!")
            return True

        except Exception as e:
            logger.error(f"✗ Verification failed: {e}")
            return False


def main():
    """Main execution function."""
    parser = argparse.ArgumentParser(
        description="Download Gemma 3n base model for OceanGuard AI mobile deployment"
    )
    parser.add_argument(
        "--config",
        type=str,
        default="config.yaml",
        help="Path to configuration file"
    )
    parser.add_argument(
        "--model-variant",
        type=str,
        choices=["E2B", "E4B"],
        help="Model variant to download (overrides config)"
    )
    parser.add_argument(
        "--skip-verification",
        action="store_true",
        help="Skip model verification after download"
    )

    args = parser.parse_args()

    try:
        # Initialize downloader
        downloader = ModelDownloader(args.config)

        # Override model variant if specified
        if args.model_variant:
            if args.model_variant == "E2B":
                model_id = "unsloth/gemma-3n-e2b-it"
            else:  # E4B
                model_id = "unsloth/gemma-3n-e4b-it"
        else:
            model_id = None  # Use config default

        # Step 1: Authenticate
        logger.info("Step 1: Authenticating with Hugging Face")
        if not downloader.authenticate():
            logger.error("Authentication failed. Exiting.")
            sys.exit(1)

        # Step 2: Check access
        logger.info("\nStep 2: Verifying model access")
        check_model_id = model_id or downloader.config['base_model']['model_id']
        if not downloader.check_access(check_model_id):
            logger.error("Access check failed. Exiting.")
            sys.exit(1)

        # Step 3: Download
        logger.info("\nStep 3: Downloading model")
        model_path = downloader.download_model(model_id)

        # Step 4: Verify
        if not args.skip_verification:
            logger.info("\nStep 4: Verifying download")
            if not downloader.verify_download(model_path):
                logger.error("Verification failed.")
                sys.exit(1)

        logger.info("\n✓ All steps completed successfully!")
        logger.info(f"\nNext steps:")
        logger.info(f"1. Review conversion config: config.yaml")
        logger.info(f"2. Run base model conversion: python convert_base_model.py")
        logger.info(f"3. Run LoRA conversion: python convert_lora_adapter.py")

        return 0

    except KeyboardInterrupt:
        logger.info("\nDownload interrupted by user")
        return 1
    except Exception as e:
        logger.error(f"\nFatal error: {e}")
        import traceback
        traceback.print_exc()
        return 1


if __name__ == "__main__":
    sys.exit(main())
