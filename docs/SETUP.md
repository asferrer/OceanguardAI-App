# OceanGuard AI Mobile - Complete Setup Guide

This guide walks you through the complete setup process for deploying OceanGuard AI on Android devices.

## Table of Contents

1. [Prerequisites](#prerequisites)
2. [Phase 1: Model Conversion](#phase-1-model-conversion)
3. [Phase 2: Android Project Setup](#phase-2-android-project-setup)
4. [Phase 3: Testing](#phase-3-testing)
5. [Troubleshooting](#troubleshooting)

---

## Prerequisites

### Development Machine Requirements

- **Operating System**: Windows 10/11, macOS 10.14+, or Linux
- **RAM**: 16GB minimum (32GB recommended for model conversion)
- **Storage**: 50GB free space
- **GPU**: NVIDIA GPU with CUDA support (recommended for conversion)
- **Python**: 3.10 or 3.11

### Software Requirements

- **Python 3.10/3.11** - [Download](https://www.python.org/downloads/)
- **Android Studio** - Latest version [Download](https://developer.android.com/studio)
- **Java Development Kit (JDK)** 17 or higher
- **Git** - For cloning the repository

### Android Device/Emulator Requirements

- **Android Version**: 8.0 (API 26) or higher
- **RAM**: 4GB minimum (6GB+ recommended)
- **Storage**: 4GB free space
- **GPU**: OpenGL ES 3.2+ support

### Hugging Face Account

You need a Hugging Face account with access to Gemma models:

1. Create account at [huggingface.co](https://huggingface.co/join)
2. Request access to Gemma models:
   - Go to [unsloth/gemma-3n-e2b-it](https://huggingface.co/unsloth/gemma-3n-e2b-it)
   - Click "Agree and access repository"
   - Approval is usually instant

3. Create an access token:
   - Go to [Settings → Access Tokens](https://huggingface.co/settings/tokens)
   - Click "New token"
   - Name: "OceanGuard Mobile"
   - Type: "Read"
   - Click "Generate"
   - **SAVE THIS TOKEN** - you'll need it later

---

## Phase 1: Model Conversion

### Step 1.1: Clone Repository

```bash
cd ~/Desktop  # or your preferred directory
git clone <your-repo-url>
cd gemma3n/mobile
```

### Step 1.2: Install Python Dependencies

```bash
# Create virtual environment (recommended)
python -m venv venv

# Activate virtual environment
# On Windows:
venv\Scripts\activate
# On macOS/Linux:
source venv/bin/activate

# Install dependencies
pip install -r requirements-mobile.txt
```

**Expected installation time**: 5-15 minutes

### Step 1.3: Configure Hugging Face Authentication

```bash
# Method 1: Environment variable (recommended)
# On Windows (PowerShell):
$env:HF_TOKEN="your_token_here"

# On Windows (Command Prompt):
set HF_TOKEN=your_token_here

# On macOS/Linux:
export HF_TOKEN="your_token_here"

# Method 2: Interactive login
huggingface-cli login
# Enter your token when prompted
```

### Step 1.4: Download Base Model

```bash
cd conversion
python download_base_model.py
```

**Expected output:**
```
Step 1: Authenticating with Hugging Face
✓ Successfully authenticated with Hugging Face

Step 2: Verifying model access
✓ Access confirmed for unsloth/gemma-3n-e2b-it

Step 3: Downloading model
Downloading model files...
✓ Model downloaded to: ./models_cache/gemma-3n-e2b-it

Step 4: Verifying download
✓ Model config loaded successfully
✓ Tokenizer loaded successfully
✓ Verification complete - model is ready for conversion!
```

**Expected time**: 10-30 minutes (depending on internet speed)
**Download size**: ~3GB

### Step 1.5: Convert Base Model

```bash
python convert_base_model.py
```

**Expected output:**
```
Checking prerequisites...
✓ Model found at ./models_cache/gemma-3n-e2b-it
✓ MediaPipe available (version 0.10.24)
✓ Disk space: 45.3 GB available

Converting with MediaPipe converter...
Conversion configuration:
  Input: ./models_cache/gemma-3n-e2b-it
  Output: ./converted_models/oceanguard_base.bin
  Model type: GEMMA_2B
  Backend: gpu

Starting conversion (this may take 10-30 minutes)...
✓ Conversion completed in 18.5 minutes
✓ Output file created: 2847.3 MB

CONVERSION SUCCESSFUL!
```

**Expected time**: 10-30 minutes
**Output size**: ~2.5-3GB

**⚠️ Common Issues:**
- **Out of memory**: Close other applications, or use a machine with more RAM
- **CUDA not found**: CPU fallback will be used (slower but works)
- **Conversion fails**: Check logs in `conversion_logs/conversion.log`

### Step 1.6: Convert LoRA Adapter

```bash
python convert_lora_adapter.py
```

**Expected output:**
```
Checking prerequisites...
✓ MediaPipe available (version 0.10.24)
✓ LoRA adapter found: 587.4 MB
✓ Base model found

Inspecting LoRA adapter...
LoRA Adapter Summary:
  Total parameters: 151,126,016
  Rank: 64
  Target modules: q_proj, k_proj, v_proj, o_proj, gate_proj, up_proj, down_proj

Starting conversion (this may take 15-45 minutes)...
✓ Conversion completed in 24.7 minutes
✓ LoRA adapter file created: 612.8 MB

LORA CONVERSION SUCCESSFUL!
```

**Expected time**: 15-45 minutes
**Output size**: ~600MB

**⚠️ Important Notes:**
- GPU backend is **required** for LoRA conversion
- If conversion fails with vision layer errors, see [CONVERSION_GUIDE.md](CONVERSION_GUIDE.md) for alternative approach (merged model + INT4 quantization)

### Step 1.7: Validate Converted Models

```bash
python validate_converted_model.py
```

**Expected output:**
```
Loading converted model...
Loading with LoRA adapter...
✓ Model loaded in 12.34 seconds

Running inference...
Image preprocessed: 512x512
Inference complete:
  Time-to-first-token: 0.687s
  Total time: 3.421s
  Tokens generated: 142
  Throughput: 41.5 tokens/s
  Memory peak: 4.12 GB

✓ Response format validation passed
  Debris detected: 3

Performance vs Targets:
  ✓ TTFT: 0.687s (target: <1.5s)
  ✓ Throughput: 41.5 t/s (target: >30 t/s)
  ✓ Memory: 4.12 GB (target: <5.0 GB)

✓ VALIDATION PASSED - Model ready for deployment!
```

**If validation passes**: Proceed to Phase 2
**If validation fails**: See [Troubleshooting](#troubleshooting)

---

## Phase 2: Android Project Setup

### Step 2.1: Open Project in Android Studio

1. Launch **Android Studio**
2. Click **Open** (or **File → Open**)
3. Navigate to `gemma3n/mobile/android/`
4. Click **OK**
5. Wait for Gradle sync to complete (5-10 minutes first time)

**Expected**: Gradle sync successful with no errors

### Step 2.2: Copy Model Files to Android Project

```bash
# From the conversion directory
cd conversion

# Create assets directory if it doesn't exist
mkdir -p ../android/app/src/main/assets/models

# Copy converted models
cp converted_models/oceanguard_base.bin ../android/app/src/main/assets/models/
cp converted_models/oceanguard_adapter.bin ../android/app/src/main/assets/models/
```

**Verify files are copied:**
```bash
ls -lh ../android/app/src/main/assets/models/
# Should show:
# oceanguard_base.bin (~2.8GB)
# oceanguard_adapter.bin (~600MB)
```

**⚠️ Important**: These large files will increase APK build time significantly. For production, you should use Asset Packs (see [DEPLOYMENT_GUIDE.md](DEPLOYMENT_GUIDE.md))

### Step 2.3: Configure Android SDK

In Android Studio:

1. Go to **File → Project Structure → SDK Location**
2. Ensure **Android SDK location** is set
3. Go to **SDK Manager** (Tools → SDK Manager)
4. Install required components:
   - ✅ Android SDK Platform 35
   - ✅ Android SDK Build-Tools 35.0.0
   - ✅ Android SDK Platform-Tools
   - ✅ Android Emulator (if using emulator)
   - ✅ NDK (Side by side) - latest version

### Step 2.4: Create Android Resources

Create the required XML resource files:

**1. Create `res/values/strings.xml`:**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">OceanGuard AI</string>
    <string name="app_description">Marine Debris Detection</string>
</resources>
```

**2. Create `res/values/themes.xml`:**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.OceanGuard" parent="android:Theme.Material.Light.NoActionBar">
        <item name="android:statusBarColor">@android:color/transparent</item>
    </style>
</resources>
```

**3. Create `res/xml/file_paths.xml`:**

```xml
<?xml version="1.0" encoding="utf-8"?>
<paths xmlns:android="http://schemas.android.com/apk/res/android">
    <files-path name="images" path="images/" />
    <cache-path name="cache" path="/" />
    <external-files-path name="external_files" path="." />
</paths>
```

**4. Create `res/xml/backup_rules.xml`:**

```xml
<?xml version="1.0" encoding="utf-8"?>
<full-backup-content>
    <exclude domain="sharedpref" path="device_prefs.xml" />
</full-backup-content>
```

**5. Create `res/xml/data_extraction_rules.xml`:**

```xml
<?xml version="1.0" encoding="utf-8"?>
<data-extraction-rules>
    <cloud-backup />
    <device-transfer />
</data-extraction-rules>
```

### Step 2.5: Build Project

1. In Android Studio, click **Build → Make Project** (or press Ctrl+F9 / Cmd+F9)
2. Wait for build to complete (first build may take 10-20 minutes due to large model files)

**Expected**: Build successful with no errors

**⚠️ If build fails:**
- Check [Troubleshooting](#troubleshooting) section
- Verify all dependencies are downloaded
- Ensure JDK 17 is being used
- Check `build.gradle.kts` for syntax errors

---

## Phase 3: Testing

### Step 3.1: Prepare Test Device

**Option A: Physical Device (Recommended)**

1. Enable **Developer Options** on your Android device:
   - Go to **Settings → About Phone**
   - Tap **Build Number** 7 times
   - Go back to **Settings → Developer Options**

2. Enable **USB Debugging**

3. Connect device to computer via USB

4. Accept USB debugging prompt on device

5. Verify connection in Android Studio:
   - Look for device in top toolbar dropdown

**Option B: Emulator**

1. In Android Studio, go to **Tools → Device Manager**

2. Click **Create Device**

3. Select a device with these specs:
   - **Phone**: Pixel 7 or newer
   - **Android**: 13 (API 33) or higher
   - **RAM**: 4GB minimum (6GB+ recommended)

4. Click **Next**, then **Finish**

5. Launch emulator

**⚠️ Note**: Emulator performance may be slower than physical device, especially for model inference

### Step 3.2: Run Application

1. In Android Studio, select your device from the dropdown

2. Click **Run** (green play button) or press Shift+F10 / Ctrl+R

3. Wait for app to install and launch (first launch may take 1-2 minutes to load model)

4. Grant required permissions when prompted:
   - ✅ Camera access
   - ✅ Storage access
   - ✅ Location access (optional)

### Step 3.3: Test Debris Detection

1. **Take/Select a Test Image**:
   - Use camera to capture underwater image, OR
   - Select test image from gallery

2. **Expected Behavior**:
   - Image preprocessing: <1 second
   - Model inference: 0.5-1.5 seconds (depending on device)
   - Results displayed with bounding boxes
   - Debris count and material breakdown shown
   - Ecosystem health score calculated

3. **Verify Output**:
   - Check logcat for inference metrics
   - Confirm debris objects are detected
   - Verify health score is calculated (0-100)

### Step 3.4: Performance Monitoring

Monitor performance in Android Studio:

1. Go to **View → Tool Windows → Profiler**

2. Select your running app

3. Monitor:
   - **CPU**: Should spike during inference, then drop
   - **Memory**: Peak around 3.5-4.5GB during inference
   - **Network**: Should be 0 (fully offline)

**Expected Performance** (Snapdragon 778G / 6GB RAM):
- Time-to-first-token: 0.5-1.0s
- Throughput: 40-60 tokens/s
- Memory: 3.5-4.5GB
- Battery: ~1-2% per session

---

## Troubleshooting

### Model Conversion Issues

#### Issue: "Model not found"
**Solution**: Run `download_base_model.py` first, check internet connection

#### Issue: "Out of memory during conversion"
**Solutions**:
- Close all other applications
- Use a machine with 16GB+ RAM
- Enable swap file (Linux/macOS)
- Use `--quantization int4` flag for smaller output

#### Issue: "LoRA conversion fails with vision layer error"
**Solution**: Use alternative approach (merged model + INT4 quantization)
- See `docs/CONVERSION_GUIDE.md` for detailed instructions
- This is a known limitation with some MediaPipe versions

#### Issue: "GPU not available"
**Solutions**:
- Install CUDA toolkit
- Update GPU drivers
- Use CPU backend (slower but works): edit `config.yaml`, set `backend: cpu`

### Android Build Issues

#### Issue: "Gradle sync failed"
**Solutions**:
- Update Android Studio to latest version
- File → Invalidate Caches / Restart
- Check internet connection (downloads dependencies)
- Manually install SDK components

#### Issue: "Build fails with 'Duplicate class' error"
**Solution**: Add to `app/build.gradle.kts`:
```kotlin
configurations.all {
    exclude(group = "com.google.protobuf", module = "protobuf-javalite")
}
```

#### Issue: "APK too large (>2GB)"
**Solution**: This is expected with embedded models. For production:
- Use Android App Bundles with Asset Packs
- See `docs/DEPLOYMENT_GUIDE.md`

### Runtime Issues

#### Issue: "Model initialization failed"
**Checks**:
1. Verify model files are in `assets/models/`
2. Check file sizes match expected values
3. Review logcat for detailed error
4. Ensure device has 4GB+ RAM
5. Check GPU compatibility (OpenGL ES 3.2+)

#### Issue: "OutOfMemoryError during inference"
**Solutions**:
- Restart app
- Clear app cache/data
- Reduce image size further (edit `TARGET_IMAGE_SIZE` to 256)
- Close other apps on device

#### Issue: "Inference very slow (>5s)"
**Checks**:
1. Verify GPU acceleration is enabled (check logs)
2. Confirm image is being resized to 512px
3. Check device isn't in battery saver mode
4. Monitor CPU/GPU usage in Profiler

#### Issue: "JSON parsing error"
**Checks**:
1. Review model response in logcat
2. Model may be returning unexpected format
3. Try adjusting temperature (lower = more consistent)
4. Check prompt formatting

### Device Compatibility

#### Issue: "App crashes on launch"
**Checks**:
1. Android version >= 8.0 (API 26)
2. Device has 4GB+ RAM
3. Check logcat for specific error
4. Try on different device/emulator

#### Issue: "GPU delegate initialization fails"
**Solutions**:
- Fall back to CPU mode (slower but compatible)
- Update device drivers if possible
- Use newer device with OpenGL ES 3.2+

---

## Next Steps

Once setup is complete:

1. **Customize UI**: Implement Jetpack Compose screens (see `ui/` directory)
2. **Add Database**: Implement Room for offline storage (see `data/` directory)
3. **Implement Camera**: Build CameraX integration for live capture
4. **Add Maps**: Integrate Google Maps for geolocation visualization
5. **Optimize**: Follow [PERFORMANCE_TUNING.md](PERFORMANCE_TUNING.md) for production optimizations

---

## Getting Help

- **Documentation**: See `docs/` directory for detailed guides
- **Logs**: Check `conversion_logs/` for conversion issues
- **Logcat**: Use Android Studio logcat for runtime debugging
- **Issues**: Open issue on GitHub repository with:
  - Device specs (model, RAM, Android version)
  - Complete error logs
  - Steps to reproduce

---

## Summary Checklist

### Model Conversion
- [ ] Python environment set up
- [ ] Hugging Face authenticated
- [ ] Base model downloaded (~3GB)
- [ ] Base model converted (~2.5GB output)
- [ ] LoRA adapter converted (~600MB output)
- [ ] Validation passed (TTFT <1.5s, throughput >30 t/s)

### Android Setup
- [ ] Android Studio installed and configured
- [ ] Project opened and Gradle synced
- [ ] Model files copied to `assets/models/`
- [ ] Required XML resources created
- [ ] Project builds successfully

### Testing
- [ ] Device/emulator prepared
- [ ] App installed and launched
- [ ] Permissions granted
- [ ] Debris detection works
- [ ] Performance metrics acceptable

**Ready for deployment!** 🎉

See [DEPLOYMENT_GUIDE.md](DEPLOYMENT_GUIDE.md) for production release instructions.
