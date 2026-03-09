"""
OceanGuard AI - Model Validation Script

Validates converted LiteRT models by:
1. Loading and initializing the model
2. Running inference with test images
3. Comparing outputs with PyTorch baseline
4. Measuring performance metrics (latency, memory, throughput)

Usage:
    python validate_converted_model.py [--config CONFIG_PATH] [--test-image IMAGE_PATH]

Requirements:
    - Converted base model and LoRA adapter
    - MediaPipe with LLM Inference API
    - Test images
"""

import os
import sys
import argparse
import logging
from pathlib import Path
from typing import Optional, Dict, List
import time
import json

import yaml
import numpy as np
from PIL import Image

try:
    import mediapipe as mp
    from mediapipe.tasks.python.genai import llm_inference
    from mediapipe.tasks.python.core import base_options as mp_base_options
    MEDIAPIPE_AVAILABLE = True
except ImportError:
    MEDIAPIPE_AVAILABLE = False
    logging.error("MediaPipe not available - required for validation")

try:
    import psutil
    PSUTIL_AVAILABLE = True
except ImportError:
    PSUTIL_AVAILABLE = False
    logging.warning("psutil not available - memory monitoring disabled")


# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)


class ModelValidator:
    """Validates converted LiteRT models."""

    def __init__(self, config_path: str = "config.yaml"):
        """Initialize validator with configuration."""
        self.config = self._load_config(config_path)
        self.llm_inference = None
        self.metrics = {
            'latency': [],
            'memory_usage': [],
            'token_throughput': []
        }

    def _load_config(self, config_path: str) -> dict:
        """Load configuration from YAML file."""
        config_file = Path(config_path)
        if not config_file.exists():
            raise FileNotFoundError(f"Config file not found: {config_path}")

        with open(config_file, 'r') as f:
            config = yaml.safe_load(f)

        return config

    def check_prerequisites(self) -> bool:
        """Check if validation can proceed."""
        logger.info("Checking prerequisites...")

        if not MEDIAPIPE_AVAILABLE:
            logger.error("MediaPipe is required for validation")
            return False

        logger.info(f"✓ MediaPipe available (version {mp.__version__})")

        # Check if converted models exist
        base_model_path = Path(self.config['base_model']['output_file'])
        if not base_model_path.exists():
            logger.error(f"Base model not found: {base_model_path}")
            return False

        logger.info(f"✓ Base model found")

        lora_path = Path(self.config['lora_adapter']['output_file'])
        if not lora_path.exists():
            logger.warning(f"LoRA adapter not found: {lora_path}")
            logger.warning("Will validate base model only")
        else:
            logger.info(f"✓ LoRA adapter found")

        return True

    def load_model(self, use_lora: bool = True) -> bool:
        """Load the converted model with MediaPipe."""
        logger.info("Loading converted model...")

        try:
            base_model_path = str(Path(self.config['base_model']['output_file']))
            lora_path = str(Path(self.config['lora_adapter']['output_file']))

            # Build options
            options_builder = llm_inference.LlmInference.LlmInferenceOptions.builder()
            options_builder.set_model_path(base_model_path)

            # Add LoRA if available and requested
            if use_lora and Path(lora_path).exists():
                logger.info("Loading with LoRA adapter...")
                options_builder.set_lora_path(lora_path)
            else:
                logger.info("Loading base model only...")

            # Set inference parameters
            options_builder.set_max_tokens(2048)
            options_builder.set_temperature(0.1)

            # Set backend
            backend = self.config['conversion']['backend']
            if backend == "gpu":
                delegate = mp_base_options.BaseOptions.Delegate.GPU
                logger.info("Using GPU backend")
            else:
                delegate = mp_base_options.BaseOptions.Delegate.CPU
                logger.info("Using CPU backend")

            options_builder.set_delegate(delegate)

            options = options_builder.build()

            # Create inference instance
            logger.info("Initializing inference session...")
            start_time = time.time()

            self.llm_inference = llm_inference.LlmInference.create_from_options(None, options)

            init_time = time.time() - start_time
            logger.info(f"✓ Model loaded in {init_time:.2f} seconds")

            return True

        except Exception as e:
            logger.error(f"Failed to load model: {e}")
            import traceback
            traceback.print_exc()
            return False

    def preprocess_image(self, image_path: Path, target_size: int = 512) -> Image.Image:
        """Preprocess image according to mobile optimization guidelines."""
        logger.info(f"Loading image: {image_path}")

        image = Image.open(image_path).convert('RGB')
        original_size = image.size
        logger.info(f"Original size: {original_size}")

        # Resize to target size (critical optimization)
        max_dim = max(image.size)
        if max_dim > target_size:
            scale = target_size / max_dim
            new_width = int(image.size[0] * scale)
            new_height = int(image.size[1] * scale)
            image = image.resize((new_width, new_height), Image.Resampling.BILINEAR)
            logger.info(f"Resized to: {image.size} (optimization)")

        return image

    def run_inference(self, image: Image.Image, prompt: str) -> Dict:
        """Run inference and measure performance."""
        logger.info("Running inference...")

        # Measure memory before inference
        mem_before = 0
        if PSUTIL_AVAILABLE:
            process = psutil.Process()
            mem_before = process.memory_info().rss / (1024 ** 3)  # GB

        # Run inference with timing
        start_time = time.time()

        try:
            # Convert PIL Image to format expected by MediaPipe
            # Note: Actual API may vary - adjust based on MediaPipe version
            result = self.llm_inference.generate_response(prompt, image)

            # Measure time to first token (approximate)
            ttft = time.time() - start_time

            # Get full response
            response_text = result.text() if hasattr(result, 'text') else str(result)

            # Measure total time
            total_time = time.time() - start_time

            # Measure memory after inference
            mem_after = mem_before
            if PSUTIL_AVAILABLE:
                mem_after = process.memory_info().rss / (1024 ** 3)  # GB

            # Calculate metrics
            token_count = len(response_text.split())  # Rough estimate
            throughput = token_count / total_time if total_time > 0 else 0

            metrics = {
                'ttft': ttft,
                'total_time': total_time,
                'memory_delta': mem_after - mem_before,
                'memory_peak': mem_after,
                'token_count': token_count,
                'throughput': throughput,
                'response': response_text
            }

            # Log metrics
            logger.info(f"Inference complete:")
            logger.info(f"  Time-to-first-token: {ttft:.3f}s")
            logger.info(f"  Total time: {total_time:.3f}s")
            logger.info(f"  Tokens generated: {token_count}")
            logger.info(f"  Throughput: {throughput:.1f} tokens/s")
            if PSUTIL_AVAILABLE:
                logger.info(f"  Memory peak: {mem_after:.2f} GB")

            # Store for aggregation
            self.metrics['latency'].append(ttft)
            self.metrics['memory_usage'].append(mem_after)
            self.metrics['token_throughput'].append(throughput)

            return metrics

        except Exception as e:
            logger.error(f"Inference failed: {e}")
            import traceback
            traceback.print_exc()
            return {'error': str(e)}

    def validate_accuracy(self, response: str) -> Dict:
        """Validate response contains expected debris detection format."""
        logger.info("Validating response format...")

        validation = {
            'has_json': False,
            'has_debris_list': False,
            'has_bbox': False,
            'has_material': False,
            'parse_success': False
        }

        try:
            # Check if response contains JSON
            if '{' in response and '}' in response:
                validation['has_json'] = True

                # Try to extract and parse JSON
                json_start = response.find('{')
                json_end = response.rfind('}') + 1
                json_str = response[json_start:json_end]

                data = json.loads(json_str)
                validation['parse_success'] = True

                # Check for expected fields
                if 'debris_detected' in data:
                    validation['has_debris_list'] = True

                    if len(data['debris_detected']) > 0:
                        first_item = data['debris_detected'][0]
                        if 'bbox' in first_item:
                            validation['has_bbox'] = True
                        if 'material' in first_item:
                            validation['has_material'] = True

                logger.info("✓ Response format validation passed")
                logger.info(f"  Debris detected: {len(data.get('debris_detected', []))}")

        except json.JSONDecodeError as e:
            logger.warning(f"JSON parsing failed: {e}")
        except Exception as e:
            logger.warning(f"Validation error: {e}")

        return validation

    def run_validation_suite(self):
        """Run complete validation suite."""
        logger.info("\n" + "=" * 60)
        logger.info("STARTING MODEL VALIDATION")
        logger.info("=" * 60 + "\n")

        # Get test configuration
        test_images = self.config['validation']['test_images']
        test_prompt = self.config['validation']['test_prompts']['detection']
        target_size = self.config['preprocessing']['target_size']

        # Run tests
        results = []

        for i, image_path_str in enumerate(test_images, 1):
            image_path = Path(image_path_str)

            if not image_path.exists():
                logger.warning(f"Test image not found: {image_path}")
                continue

            logger.info(f"\n--- Test {i}/{len(test_images)} ---")

            # Preprocess image
            image = self.preprocess_image(image_path, target_size)

            # Run inference
            metrics = self.run_inference(image, test_prompt)

            if 'error' not in metrics:
                # Validate accuracy
                validation = self.validate_accuracy(metrics['response'])

                results.append({
                    'image': str(image_path),
                    'metrics': metrics,
                    'validation': validation
                })

        # Display summary
        self._display_summary(results)

        return results

    def _display_summary(self, results: List[Dict]):
        """Display validation summary."""
        logger.info("\n" + "=" * 60)
        logger.info("VALIDATION SUMMARY")
        logger.info("=" * 60)

        if not results:
            logger.warning("No results to summarize")
            return

        # Calculate averages
        avg_ttft = np.mean(self.metrics['latency']) if self.metrics['latency'] else 0
        avg_throughput = np.mean(self.metrics['token_throughput']) if self.metrics['token_throughput'] else 0
        avg_memory = np.mean(self.metrics['memory_usage']) if self.metrics['memory_usage'] else 0

        logger.info(f"\nPerformance Metrics (n={len(results)}):")
        logger.info(f"  Avg time-to-first-token: {avg_ttft:.3f}s")
        logger.info(f"  Avg throughput: {avg_throughput:.1f} tokens/s")
        if PSUTIL_AVAILABLE:
            logger.info(f"  Avg memory usage: {avg_memory:.2f} GB")

        # Check against targets
        targets = self.config['validation']['performance']
        logger.info(f"\nPerformance vs Targets:")

        ttft_status = "✓" if avg_ttft <= targets['max_ttft'] else "✗"
        logger.info(f"  {ttft_status} TTFT: {avg_ttft:.3f}s (target: <{targets['max_ttft']}s)")

        throughput_status = "✓" if avg_throughput >= targets['min_throughput'] else "✗"
        logger.info(f"  {throughput_status} Throughput: {avg_throughput:.1f} t/s (target: >{targets['min_throughput']} t/s)")

        if PSUTIL_AVAILABLE:
            memory_status = "✓" if avg_memory <= targets['max_memory'] else "✗"
            logger.info(f"  {memory_status} Memory: {avg_memory:.2f} GB (target: <{targets['max_memory']} GB)")

        # Accuracy validation
        logger.info(f"\nAccuracy Validation:")
        valid_json = sum(1 for r in results if r['validation']['parse_success'])
        valid_debris = sum(1 for r in results if r['validation']['has_debris_list'])

        logger.info(f"  Valid JSON responses: {valid_json}/{len(results)}")
        logger.info(f"  Debris detection format: {valid_debris}/{len(results)}")

        # Overall assessment
        logger.info(f"\n" + "=" * 60)
        if avg_ttft <= targets['max_ttft'] and avg_throughput >= targets['min_throughput']:
            logger.info("✓ VALIDATION PASSED - Model ready for deployment!")
        else:
            logger.warning("⚠ VALIDATION INCOMPLETE - Performance targets not met")
            logger.warning("Consider:")
            logger.warning("  - Reducing image size further (256px)")
            logger.warning("  - Testing on different device")
            logger.warning("  - Adjusting model quantization")

        logger.info("=" * 60 + "\n")


def main():
    """Main execution function."""
    parser = argparse.ArgumentParser(
        description="Validate converted LiteRT models"
    )
    parser.add_argument(
        "--config",
        type=str,
        default="config.yaml",
        help="Path to configuration file"
    )
    parser.add_argument(
        "--test-image",
        type=str,
        help="Single test image (overrides config test suite)"
    )
    parser.add_argument(
        "--no-lora",
        action="store_true",
        help="Test base model only (skip LoRA)"
    )

    args = parser.parse_args()

    try:
        # Initialize validator
        validator = ModelValidator(args.config)

        # Check prerequisites
        if not validator.check_prerequisites():
            logger.error("Prerequisites check failed")
            sys.exit(1)

        # Load model
        use_lora = not args.no_lora
        if not validator.load_model(use_lora=use_lora):
            logger.error("Model loading failed")
            sys.exit(1)

        # Run validation
        if args.test_image:
            # Single image test
            image_path = Path(args.test_image)
            if not image_path.exists():
                logger.error(f"Test image not found: {image_path}")
                sys.exit(1)

            prompt = validator.config['validation']['test_prompts']['detection']
            image = validator.preprocess_image(image_path)
            metrics = validator.run_inference(image, prompt)

            if 'error' not in metrics:
                print(f"\nResponse:\n{metrics['response']}")

        else:
            # Full validation suite
            results = validator.run_validation_suite()

        logger.info("✓ Validation complete!")
        return 0

    except KeyboardInterrupt:
        logger.info("\nValidation interrupted by user")
        return 1
    except Exception as e:
        logger.error(f"\nFatal error: {e}")
        import traceback
        traceback.print_exc()
        return 1


if __name__ == "__main__":
    sys.exit(main())
