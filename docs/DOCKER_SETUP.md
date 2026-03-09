# 🐳 OceanGuard AI - Docker Setup para Windows

**Actualizado**: Diciembre 2025 | **WSL2 + Docker Desktop**

Esta guía te permite construir la aplicación Android usando Docker en Windows. **Nota importante**: Docker es ideal para CI/CD y builds reproducibles, pero **NO** para desarrollo diario con Android Studio.

---

## ⚠️ Limitaciones de Docker en Windows

### ❌ **No Soportado en Docker**
- Android Emulator (requiere KVM/aceleración de hardware)
- Desarrollo interactivo con Android Studio
- Debugging visual de la UI
- Hot reload de Compose

### ✅ **Sí Soportado en Docker**
- Builds automatizados (CI/CD)
- Conversión de modelos
- Compilación de APKs
- Testing unitario
- Testing instrumentado (con dispositivos físicos conectados via ADB over network)

### 🎯 **Recomendación**
- **Desarrollo diario**: Usa instalación nativa (ver [QUICK_START_WINDOWS.md](QUICK_START_WINDOWS.md))
- **CI/CD y builds reproducibles**: Usa Docker (esta guía)

---

## 📋 Prerrequisitos

### Windows 11 (Recomendado)
- **Windows 11 Pro/Enterprise** con Hyper-V
- O **Windows 11 Home** con WSL2
- 16GB RAM mínimo (32GB recomendado)
- 100GB espacio libre en disco SSD

### Windows 10
- **Windows 10 Pro/Enterprise** (build 19044+)
- WSL2 habilitado
- 16GB RAM mínimo

### Software Requerido
- Docker Desktop (versión gratuita para uso personal)
- WSL2
- Git for Windows

---

## 🚀 Instalación y Configuración

### Paso 1: Instalar WSL2

```powershell
# Ejecutar PowerShell como Administrador

# Habilitar WSL
dism.exe /online /enable-feature /featurename:Microsoft-Windows-Subsystem-Linux /all /norestart

# Habilitar Virtual Machine Platform
dism.exe /online /enable-feature /featurename:VirtualMachinePlatform /all /norestart

# Reiniciar Windows
Restart-Computer
```

Después del reinicio:

```powershell
# Descargar e instalar el kernel de WSL2
# https://aka.ms/wsl2kernel

# Configurar WSL2 como versión predeterminada
wsl --set-default-version 2

# Instalar Ubuntu 22.04
wsl --install -d Ubuntu-22.04

# Configurar usuario y contraseña cuando se solicite
```

### Paso 2: Instalar Docker Desktop

1. **Descargar Docker Desktop para Windows:**
   ```
   https://www.docker.com/products/docker-desktop/
   ```

2. **Ejecutar instalador** y seguir wizard

3. **Durante instalación:**
   - ✅ Marcar "Use WSL 2 instead of Hyper-V"
   - ✅ Marcar "Add shortcut to desktop"

4. **Reiniciar Windows**

5. **Primera vez abriendo Docker Desktop:**
   - Aceptar términos de servicio
   - Opcionalmente crear cuenta Docker Hub (gratis)
   - En Settings:
     - Resources → WSL Integration → Activar Ubuntu-22.04
     - Resources → Advanced → Allocate 8GB RAM, 4 CPUs mínimo
     - Docker Engine → Aumentar disk image max-size a 100GB si es necesario

### Paso 3: Verificar Instalación

```powershell
# Verificar Docker
docker --version
# Salida esperada: Docker version 24.x.x, build xxxxxxx

# Verificar Docker Compose
docker compose version
# Salida esperada: Docker Compose version v2.x.x

# Verificar WSL
wsl --list --verbose
# Salida esperada:
#   NAME                   STATE           VERSION
# * Ubuntu-22.04           Running         2
#   docker-desktop         Running         2
#   docker-desktop-data    Running         2

# Test básico
docker run hello-world
# Salida esperada: "Hello from Docker!"
```

---

## 🏗️ Arquitectura Docker para OceanGuard

### Estructura de Containers

Usaremos **dos containers**:

1. **Python Container** (conversión de modelos)
   - Ubuntu 22.04
   - Python 3.11
   - PyTorch, Transformers, MediaPipe
   - AI Edge Torch

2. **Android Build Container** (compilación de APK)
   - Ubuntu 22.04
   - Android SDK
   - Android Command Line Tools
   - Gradle 8.7.3
   - JDK 17

---

## 📁 Crear Dockerfiles

### Dockerfile para Conversión de Modelos

Crea `mobile/docker/Dockerfile.model-conversion`:

```dockerfile
# mobile/docker/Dockerfile.model-conversion
FROM python:3.11-slim

# Metadata
LABEL maintainer="OceanGuard AI Team"
LABEL description="Container for converting Gemma 3n models to LiteRT format"

# Configurar variables de entorno
ENV DEBIAN_FRONTEND=noninteractive
ENV PYTHONUNBUFFERED=1
ENV HF_HOME=/workspace/.cache/huggingface

# Instalar dependencias del sistema
RUN apt-get update && apt-get install -y \
    build-essential \
    git \
    wget \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Crear directorio de trabajo
WORKDIR /workspace

# Copiar requirements
COPY requirements-mobile.txt .

# Instalar dependencias Python
RUN pip install --no-cache-dir --upgrade pip && \
    pip install --no-cache-dir -r requirements-mobile.txt

# Crear directorios para caché y outputs
RUN mkdir -p /workspace/.cache/huggingface \
    /workspace/conversion/converted_models \
    /workspace/conversion_logs

# Volúmenes para persistencia
VOLUME ["/workspace/conversion/converted_models", "/workspace/.cache/huggingface"]

# Comando por defecto
CMD ["/bin/bash"]
```

### Dockerfile para Android Build

Crea `mobile/docker/Dockerfile.android-build`:

```dockerfile
# mobile/docker/Dockerfile.android-build
FROM ubuntu:22.04

# Metadata
LABEL maintainer="OceanGuard AI Team"
LABEL description="Container for building OceanGuard Android app"

# Configurar variables de entorno
ENV DEBIAN_FRONTEND=noninteractive
ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV PATH=${PATH}:${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${ANDROID_HOME}/build-tools/35.0.0

# Instalar dependencias del sistema
RUN apt-get update && apt-get install -y \
    openjdk-17-jdk \
    wget \
    unzip \
    git \
    && rm -rf /var/lib/apt/lists/*

# Crear directorio para Android SDK
RUN mkdir -p ${ANDROID_HOME}/cmdline-tools

# Descargar Android Command Line Tools
WORKDIR /tmp
RUN wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip && \
    unzip commandlinetools-linux-*_latest.zip -d ${ANDROID_HOME}/cmdline-tools && \
    mv ${ANDROID_HOME}/cmdline-tools/cmdline-tools ${ANDROID_HOME}/cmdline-tools/latest && \
    rm commandlinetools-linux-*_latest.zip

# Aceptar licencias de Android SDK
RUN yes | sdkmanager --licenses

# Instalar componentes del SDK
RUN sdkmanager --update && \
    sdkmanager \
    "platform-tools" \
    "platforms;android-35" \
    "build-tools;35.0.0" \
    "ndk;27.2.12479018" \
    "cmake;3.22.1"

# Crear directorio de trabajo
WORKDIR /workspace

# Copiar gradlew y configuración Gradle
COPY android/gradlew android/gradlew.bat android/gradle.properties ./
COPY android/gradle ./gradle

# Dar permisos de ejecución
RUN chmod +x ./gradlew

# Pre-descargar dependencias de Gradle (caché)
RUN ./gradlew --version || true

# Volúmenes
VOLUME ["/workspace", "/root/.gradle"]

# Comando por defecto
CMD ["/bin/bash"]
```

### Docker Compose

Crea `mobile/docker-compose.yml`:

```yaml
# mobile/docker-compose.yml
version: '3.8'

services:
  # Servicio para conversión de modelos
  model-conversion:
    build:
      context: .
      dockerfile: docker/Dockerfile.model-conversion
    container_name: oceanguard-model-conversion
    volumes:
      - ./conversion:/workspace/conversion
      - ./conversion/converted_models:/workspace/conversion/converted_models
      - model-cache:/workspace/.cache/huggingface
    environment:
      - HF_TOKEN=${HF_TOKEN}
    networks:
      - oceanguard-network
    profiles:
      - models

  # Servicio para builds de Android
  android-build:
    build:
      context: .
      dockerfile: docker/Dockerfile.android-build
    container_name: oceanguard-android-build
    volumes:
      - ./android:/workspace/android
      - gradle-cache:/root/.gradle
      - ./conversion/converted_models:/workspace/models
    working_dir: /workspace/android
    networks:
      - oceanguard-network
    profiles:
      - build

networks:
  oceanguard-network:
    driver: bridge

volumes:
  model-cache:
    driver: local
  gradle-cache:
    driver: local
```

---

## 🔄 Uso de Docker para OceanGuard

### 1. Convertir Modelos con Docker

```powershell
# Navegar a la carpeta mobile
cd C:\Users\aleja\Desktop\gemma3n\mobile

# Configurar HuggingFace token
$env:HF_TOKEN = "hf_xxxxxxxxxxxxxxxxxxxxxxxxxxxxx"

# Construir imagen de conversión
docker compose build model-conversion

# Descargar modelo base
docker compose run --rm model-conversion python conversion/download_base_model.py

# Convertir modelo base
docker compose run --rm model-conversion python conversion/convert_base_model.py

# Convertir LoRA adapter
docker compose run --rm model-conversion python conversion/convert_lora_adapter.py

# Validar modelos
docker compose run --rm model-conversion python conversion/validate_converted_model.py
```

**Notas:**
- Los modelos convertidos se guardan en `conversion/converted_models/` (persistente)
- El caché de HuggingFace se guarda en un volumen Docker (persistente)
- Primera ejecución descarga ~5GB de imágenes base

### 2. Construir APK con Docker

```powershell
# Construir imagen de Android
docker compose build android-build

# Copiar modelos a assets (hacerlo ANTES del build)
docker compose run --rm android-build bash -c "
  mkdir -p android/app/src/main/assets/models && \
  cp /workspace/models/oceanguard_base.bin android/app/src/main/assets/models/ && \
  cp /workspace/models/oceanguard_adapter.bin android/app/src/main/assets/models/
"

# Ejecutar Gradle Sync (primera vez)
docker compose run --rm android-build ./gradlew --refresh-dependencies

# Build Debug APK
docker compose run --rm android-build ./gradlew assembleDebug

# Build Release APK (firmado)
docker compose run --rm android-build ./gradlew assembleRelease

# Limpiar build
docker compose run --rm android-build ./gradlew clean
```

**Output APKs:**
- Debug: `android/app/build/outputs/apk/debug/app-debug.apk`
- Release: `android/app/build/outputs/apk/release/app-release-unsigned.apk`

### 3. Ejecutar Tests con Docker

```powershell
# Tests unitarios
docker compose run --rm android-build ./gradlew test

# Generar reporte de tests
docker compose run --rm android-build ./gradlew testDebugUnitTest

# Ver reporte: android/app/build/reports/tests/testDebugUnitTest/index.html
```

### 4. Instalar APK en Dispositivo

```powershell
# Desde PowerShell en Windows (ADB debe estar instalado nativamente)

# Verificar dispositivo conectado
adb devices

# Instalar APK
adb install "android\app\build\outputs\apk\debug\app-debug.apk"

# Ver logs
adb logcat | Select-String "OceanGuard"
```

---

## 🛠️ Scripts de Automatización

### Script para Build Completo

Crea `mobile/docker/build-all.sh`:

```bash
#!/bin/bash
# mobile/docker/build-all.sh

set -e  # Exit on error

echo "🚀 OceanGuard AI - Docker Build Pipeline"
echo "========================================"

# Check HF_TOKEN
if [ -z "$HF_TOKEN" ]; then
    echo "❌ Error: HF_TOKEN not set"
    echo "Set it with: export HF_TOKEN=hf_xxxxx"
    exit 1
fi

# Step 1: Convert Models
echo ""
echo "📦 Step 1/4: Converting models..."
docker compose --profile models run --rm model-conversion python conversion/download_base_model.py
docker compose --profile models run --rm model-conversion python conversion/convert_base_model.py
docker compose --profile models run --rm model-conversion python conversion/convert_lora_adapter.py
docker compose --profile models run --rm model-conversion python conversion/validate_converted_model.py

# Step 2: Copy Models to Assets
echo ""
echo "📁 Step 2/4: Copying models to Android assets..."
docker compose --profile build run --rm android-build bash -c "
  mkdir -p android/app/src/main/assets/models && \
  cp /workspace/models/oceanguard_base.bin android/app/src/main/assets/models/ && \
  cp /workspace/models/oceanguard_adapter.bin android/app/src/main/assets/models/
"

# Step 3: Build APK
echo ""
echo "🔨 Step 3/4: Building Android APK..."
docker compose --profile build run --rm android-build ./gradlew assembleRelease

# Step 4: Run Tests
echo ""
echo "🧪 Step 4/4: Running tests..."
docker compose --profile build run --rm android-build ./gradlew test

echo ""
echo "✅ Build complete!"
echo "📱 APK location: android/app/build/outputs/apk/release/app-release-unsigned.apk"
```

### Ejecutar desde Windows:

```powershell
# Dar permisos (solo primera vez)
wsl chmod +x mobile/docker/build-all.sh

# Ejecutar
wsl bash mobile/docker/build-all.sh
```

---

## 🔍 Debugging con Docker

### Ver Logs de Build

```powershell
# Logs en tiempo real
docker compose run --rm android-build ./gradlew assembleDebug --info

# Logs detallados
docker compose run --rm android-build ./gradlew assembleDebug --debug > build.log

# Abrir build.log en VS Code o Notepad++
```

### Entrar al Container Interactivamente

```powershell
# Bash interactivo en container de Android
docker compose run --rm android-build /bin/bash

# Dentro del container puedes:
# - Explorar archivos: ls -la
# - Ejecutar Gradle: ./gradlew tasks
# - Ver configuración: ./gradlew properties
# - Salir: exit
```

### Limpiar Caché y Rebuilds

```powershell
# Limpiar builds de Gradle
docker compose run --rm android-build ./gradlew clean

# Eliminar caché de Gradle (forzar re-descarga)
docker volume rm oceanguard_gradle-cache

# Reconstruir imágenes desde cero
docker compose build --no-cache

# Eliminar todo (imágenes, containers, volúmenes)
docker compose down --volumes --rmi all
```

---

## 📊 Optimización de Performance

### Configuración de Gradle en Docker

Crea `mobile/android/gradle.properties`:

```properties
# Gradle Daemon (más rápido)
org.gradle.daemon=true
org.gradle.parallel=true
org.gradle.caching=true

# Memory para JVM (ajustar según RAM del host)
org.gradle.jvmargs=-Xmx6g -XX:MaxMetaspaceSize=1g -XX:+HeapDumpOnOutOfMemoryError

# Configuración específica para Docker
org.gradle.vfs.watch=false
org.gradle.unsafe.watch-fs=false

# Kotlin
kotlin.code.style=official
kotlin.incremental=true
```

### Usar Caché Local de Maven

Modificar `docker-compose.yml` para usar caché local:

```yaml
services:
  android-build:
    volumes:
      - ./android:/workspace/android
      - ~/.m2:/root/.m2  # Caché Maven del host
      - gradle-cache:/root/.gradle
```

---

## 🤖 CI/CD con Docker

### GitHub Actions Workflow

Crea `.github/workflows/android-build.yml`:

```yaml
name: Android CI

on:
  push:
    branches: [ main, develop ]
  pull_request:
    branches: [ main ]

jobs:
  build:
    runs-on: ubuntu-latest

    steps:
    - name: Checkout code
      uses: actions/checkout@v4

    - name: Set up Docker Buildx
      uses: docker/setup-buildx-action@v3

    - name: Build Model Conversion Image
      run: |
        cd mobile
        docker compose build model-conversion

    - name: Convert Models
      env:
        HF_TOKEN: ${{ secrets.HF_TOKEN }}
      run: |
        cd mobile
        docker compose run --rm model-conversion python conversion/download_base_model.py
        docker compose run --rm model-conversion python conversion/convert_base_model.py
        docker compose run --rm model-conversion python conversion/convert_lora_adapter.py

    - name: Build Android Image
      run: |
        cd mobile
        docker compose build android-build

    - name: Copy Models to Assets
      run: |
        cd mobile
        docker compose run --rm android-build bash -c "
          mkdir -p android/app/src/main/assets/models &&
          cp /workspace/models/*.bin android/app/src/main/assets/models/
        "

    - name: Build APK
      run: |
        cd mobile
        docker compose run --rm android-build ./gradlew assembleRelease

    - name: Run Tests
      run: |
        cd mobile
        docker compose run --rm android-build ./gradlew test

    - name: Upload APK
      uses: actions/upload-artifact@v4
      with:
        name: app-release
        path: mobile/android/app/build/outputs/apk/release/*.apk
```

---

## ⚠️ Limitaciones y Consideraciones

### Tamaño de Imágenes Docker

- **Python container**: ~2GB
- **Android container**: ~5GB
- **Caché de Gradle**: ~2-3GB
- **Caché de HuggingFace**: ~5GB
- **Total**: ~15GB de espacio en disco

### Performance

**Builds en Docker vs Nativo:**
- Docker: ~20-30 minutos para build completo
- Nativo: ~10-15 minutos para build completo

**Razón**: Overhead de virtualización y I/O de volúmenes

### Limitaciones en Windows

1. **No GPU acceleration** en containers WSL2 (solo CPU)
   - Conversión de modelos será más lenta
   - Inferencia no soportada en container

2. **No emulador Android** en container
   - Debes usar dispositivo físico
   - O emulador en Windows host

3. **Networking**: ADB over network funciona, pero es complejo

---

## 🎯 Cuándo Usar Docker

### ✅ Usa Docker Para:

- **CI/CD pipelines** (GitHub Actions, GitLab CI, Jenkins)
- **Builds reproducibles** para releases
- **Testing automatizado** (unit tests)
- **Equipos grandes** (mismo entorno para todos)
- **Servidores de build** dedicados

### ❌ NO Uses Docker Para:

- **Desarrollo diario** con Android Studio
- **Debugging visual** de UI
- **Testing con emulador**
- **Hot reload** de Jetpack Compose
- **Iteración rápida** de código

---

## 🆚 Docker vs Nativo: Comparación

| Aspecto | Docker | Nativo (Windows) |
|---------|--------|------------------|
| **Setup inicial** | 1 hora | 30 minutos |
| **Build speed** | Lento (20-30 min) | Rápido (10-15 min) |
| **Emulator** | ❌ No soportado | ✅ Funciona perfectamente |
| **Android Studio** | ❌ No integrado | ✅ Integración completa |
| **Debugging** | ⚠️ Limitado | ✅ Completo |
| **CI/CD** | ✅ Excelente | ⚠️ Complejo |
| **Reproducibilidad** | ✅ Perfecta | ⚠️ Variable |
| **Uso de disco** | ~15GB | ~60GB |
| **Curva de aprendizaje** | Media | Baja |

---

## 📚 Recursos Adicionales

### Documentación Oficial

- [Docker Desktop for Windows](https://docs.docker.com/desktop/windows/)
- [WSL2 Documentation](https://learn.microsoft.com/en-us/windows/wsl/)
- [Android Command Line Tools](https://developer.android.com/tools)

### Proyectos de Referencia

- [budtmo/docker-android](https://github.com/budtmo/docker-android)
- [thyrlian/AndroidSDK](https://github.com/thyrlian/AndroidSDK)

### Troubleshooting

- [Docker + Android Common Issues](https://github.com/thyrlian/AndroidSDK/wiki/Troubleshooting)
- [WSL2 + Docker Issues](https://docs.docker.com/desktop/troubleshoot/topics/)

---

## ✅ Checklist para Docker Setup

### Prerrequisitos
- [ ] Windows 11 Pro/Enterprise o Windows 10 Pro (build 19044+)
- [ ] 16GB+ RAM
- [ ] 100GB+ espacio libre
- [ ] WSL2 instalado y configurado
- [ ] Docker Desktop instalado
- [ ] Ubuntu-22.04 WSL distribution instalada

### Configuración
- [ ] Dockerfiles creados
- [ ] docker-compose.yml creado
- [ ] Variables de entorno configuradas (HF_TOKEN)
- [ ] Volúmenes Docker verificados
- [ ] Gradle properties optimizados

### Testing
- [ ] `docker --version` funciona
- [ ] `docker compose version` funciona
- [ ] Model conversion completada
- [ ] APK build exitoso
- [ ] Tests pasan

---

## 🎉 Conclusión

Docker en Windows es una herramienta poderosa para:
- ✅ **CI/CD** automatizado
- ✅ **Builds reproducibles** para releases
- ✅ **Environments consistentes** entre desarrolladores

Pero **NO** reemplaza Android Studio para desarrollo diario.

**Recomendación final**:
- **Desarrollo**: Usa instalación nativa ([QUICK_START_WINDOWS.md](QUICK_START_WINDOWS.md))
- **Producción/CI**: Usa Docker (esta guía)

---

**¿Preguntas?** Consulta la documentación principal en [README.md](README.md)
