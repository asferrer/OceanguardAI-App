# 🚀 Setup Híbrido: Android Studio Nativo + Docker para Modelos

**Mejor de ambos mundos**: Desarrollo rápido en Windows + Conversión de modelos reproducible en Docker

---

## 🎯 Estrategia

### Qué va en Windows Nativo
- ✅ Android Studio (desarrollo, debugging, emulador)
- ✅ JDK 17
- ✅ Build de la aplicación
- ✅ Testing

### Qué va en Docker
- ✅ Python + dependencias de conversión
- ✅ Conversión de modelos (PyTorch → LiteRT)
- ✅ Validación de modelos
- ✅ (Opcional) CI/CD

---

## 📋 PARTE 1: Setup de Android Studio (Windows Nativo)

### Paso 1.1: Instalar JDK 17

**Opción A: Chocolatey (Recomendado)**

```powershell
# Abrir PowerShell como Administrador

# Si no tienes Chocolatey, instalarlo:
Set-ExecutionPolicy Bypass -Scope Process -Force
[System.Net.ServicePointManager]::SecurityProtocol = [System.Net.ServicePointManager]::SecurityProtocol -bor 3072
iex ((New-Object System.Net.WebClient).DownloadString('https://community.chocolatey.org/install.ps1'))

# Instalar JDK 17
choco install microsoft-openjdk17 -y

# Cerrar y reabrir PowerShell
```

**Opción B: Descarga Manual**

1. Ve a: https://learn.microsoft.com/en-us/java/openjdk/download#openjdk-17
2. Descarga "Microsoft Build of OpenJDK 17.x.x - Windows x64 MSI"
3. Ejecuta el instalador
4. Sigue el wizard (todo por defecto está bien)

**Verificar instalación:**

```powershell
java -version
# Debe mostrar: openjdk version "17.0.x"

javac -version
# Debe mostrar: javac 17.0.x
```

### Paso 1.2: Instalar Android Studio

**Opción A: Chocolatey**

```powershell
# PowerShell como Administrador
choco install androidstudio -y
```

**Opción B: Descarga Manual**

1. Ve a: https://developer.android.com/studio
2. Click "Download Android Studio"
3. Acepta términos
4. Descarga "android-studio-2024.x.x.x-windows.exe"
5. Ejecuta el instalador

**Wizard de Android Studio (Primera vez):**

1. Welcome → "Next"
2. Install Type → **"Standard"** → Next
3. Verify Settings → "Next"
4. License Agreement → "Accept" todas → "Finish"
5. **Espera 15-30 minutos** mientras descarga:
   - Android SDK (~5GB)
   - Android Emulator
   - Platform Tools
   - Build Tools

### Paso 1.3: Configurar Variables de Entorno

```powershell
# Abrir PowerShell como Administrador

# 1. JAVA_HOME
$jdkPath = "C:\Program Files\Microsoft\jdk-17.0.13.11-hotspot"  # Ajusta versión si es diferente
[System.Environment]::SetEnvironmentVariable('JAVA_HOME', $jdkPath, 'Machine')

# 2. ANDROID_HOME
$androidHome = "$env:LOCALAPPDATA\Android\Sdk"
[System.Environment]::SetEnvironmentVariable('ANDROID_HOME', $androidHome, 'User')
[System.Environment]::SetEnvironmentVariable('ANDROID_SDK_ROOT', $androidHome, 'User')

# 3. Actualizar PATH
$currentPath = [System.Environment]::GetEnvironmentVariable('Path', 'User')
$newPaths = @(
    "$env:ANDROID_HOME\platform-tools",
    "$env:ANDROID_HOME\tools",
    "$env:ANDROID_HOME\tools\bin",
    "$env:ANDROID_HOME\emulator"
)

foreach ($path in $newPaths) {
    if ($currentPath -notlike "*$path*") {
        $currentPath = "$currentPath;$path"
    }
}

[System.Environment]::SetEnvironmentVariable('Path', $currentPath, 'User')

# 4. CERRAR Y REABRIR PowerShell para que tome efecto

# 5. Verificar
echo $env:JAVA_HOME
echo $env:ANDROID_HOME
adb version  # Debe mostrar Android Debug Bridge version
```

### Paso 1.4: Configurar Android Studio

**Componentes adicionales:**

1. Abre Android Studio
2. **More Actions** → **SDK Manager**
3. Pestaña **"SDK Platforms"**:
   - ✅ Android 14.0 ("UpsideDownCake") - API Level 34
   - ✅ Android 13.0 ("Tiramisu") - API Level 33
   - Click "Apply"

4. Pestaña **"SDK Tools"**:
   - ✅ Android SDK Build-Tools 35.0.0
   - ✅ Android Emulator
   - ✅ Android SDK Platform-Tools
   - ✅ Google Play services
   - ✅ Android Emulator Hypervisor Driver for AMD Processors (si tienes AMD)
   - ✅ Intel x86 Emulator Accelerator (HAXM) (si tienes Intel)
   - Click "Apply"

5. **Espera la descarga** (~2-3GB adicionales)

### Paso 1.5: Optimizar Android Studio para Windows

**Aumentar memoria asignada:**

1. En Android Studio: **Help → Edit Custom VM Options**
2. Si pregunta "Create a file?", click "Yes"
3. Modifica o agrega estas líneas:

```properties
-Xms2048m
-Xmx8192m
-XX:ReservedCodeCacheSize=512m
-XX:+UseG1GC
-XX:SoftRefLRUPolicyMSPerMB=50
-XX:CICompilerCount=2
-Dsun.io.useCanonPrefixCache=false
-Djdk.http.auth.tunneling.disabledSchemes=""
-Djdk.attach.allowAttachSelf=true
-Djdk.module.illegalAccess.silent=true
```

4. Restart Android Studio

**Excluir de Windows Defender (IMPORTANTE - mejora 30-50% velocidad):**

```powershell
# Ejecutar PowerShell como Administrador

Add-MpPreference -ExclusionPath "$env:LOCALAPPDATA\Android\Sdk"
Add-MpPreference -ExclusionPath "$env:USERPROFILE\.gradle"
Add-MpPreference -ExclusionPath "$env:USERPROFILE\.android"
Add-MpPreference -ExclusionPath "$env:USERPROFILE\.AndroidStudio*"
Add-MpPreference -ExclusionPath "C:\Users\aleja\Desktop\gemma3n"

# Verificar
Get-MpPreference | Select-Object -ExpandProperty ExclusionPath
```

### Paso 1.6: Crear Gradle Properties Global

```powershell
# Crear archivo gradle.properties en tu home
$gradleProps = @"
# Gradle Performance Optimizations
org.gradle.daemon=true
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.configureondemand=true
org.gradle.jvmargs=-Xmx4096m -XX:MaxMetaspaceSize=1024m -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8

# Kotlin
kotlin.code.style=official
kotlin.incremental=true

# Android
android.useAndroidX=true
android.enableJetifier=false
"@

New-Item -Path "$env:USERPROFILE\.gradle" -ItemType Directory -Force
Set-Content -Path "$env:USERPROFILE\.gradle\gradle.properties" -Value $gradleProps

echo "✅ Gradle properties configurado"
```

### ✅ Verificación Final - Parte 1

```powershell
# Ejecutar en PowerShell

echo "=== Java ==="
java -version

echo "`n=== Android SDK ==="
echo $env:ANDROID_HOME
adb version

echo "`n=== Gradle ==="
Get-Content "$env:USERPROFILE\.gradle\gradle.properties" -Head 5

echo "`n=== Windows Defender Exclusions ==="
Get-MpPreference | Select-Object -ExpandProperty ExclusionPath | Select-String "gradle|Android|gemma3n"
```

**Todo debe mostrar valores correctos sin errores.**

---

## 🐳 PARTE 2: Setup de Docker para Gestión de Modelos

### Paso 2.1: Habilitar WSL2

```powershell
# Abrir PowerShell como Administrador

# 1. Habilitar Windows Subsystem for Linux
dism.exe /online /enable-feature /featurename:Microsoft-Windows-Subsystem-Linux /all /norestart

# 2. Habilitar Virtual Machine Platform
dism.exe /online /enable-feature /featurename:VirtualMachinePlatform /all /norestart

# 3. REINICIAR WINDOWS
Restart-Computer -Force

# Después del reinicio, continuar...
```

Después del reinicio:

```powershell
# PowerShell como Administrador

# 4. Descargar e instalar kernel WSL2
# Ir a: https://aka.ms/wsl2kernel
# Descargar "WSL2 Linux kernel update package for x64 machines"
# Ejecutar el instalador

# 5. Configurar WSL2 como predeterminado
wsl --set-default-version 2

# 6. Instalar Ubuntu 22.04
wsl --install -d Ubuntu-22.04

# 7. Configurar usuario Ubuntu cuando se abra
# Username: aleja (o el que prefieras)
# Password: (elige uno seguro)

# 8. Verificar
wsl --list --verbose
# Debe mostrar Ubuntu-22.04 con VERSION 2
```

### Paso 2.2: Instalar Docker Desktop

**Descarga e instalación:**

1. Ve a: https://www.docker.com/products/docker-desktop/
2. Click "Download for Windows"
3. Ejecuta "Docker Desktop Installer.exe"
4. Durante instalación:
   - ✅ **Marcar**: "Use WSL 2 instead of Hyper-V"
   - ✅ **Marcar**: "Add shortcut to desktop"
5. Click "Ok" → Espera instalación
6. **Restart Windows** cuando lo pida

**Primera configuración:**

1. Abre **Docker Desktop**
2. **Skip tutorial** (o hazlo si quieres)
3. Ve a **Settings** (⚙️ arriba a la derecha)

4. **General**:
   - ✅ Use the WSL 2 based engine
   - ✅ Start Docker Desktop when you log in

5. **Resources → WSL Integration**:
   - ✅ Enable integration with my default WSL distro
   - ✅ **Ubuntu-22.04** (activar el toggle)
   - Click "Apply & Restart"

6. **Resources → Advanced**:
   - **CPUs**: 4 (o mitad de tus cores)
   - **Memory**: 8 GB (mínimo para conversión de modelos)
   - **Swap**: 2 GB
   - **Disk image size**: 100 GB
   - Click "Apply & Restart"

7. **Docker Engine** (dejar por defecto)

### Paso 2.3: Verificar Docker

```powershell
# PowerShell normal (no admin necesario)

# Verificar Docker
docker --version
# Output esperado: Docker version 24.x.x, build xxxxxx

docker compose version
# Output esperado: Docker Compose version v2.x.x

# Test básico
docker run hello-world
# Debe descargar imagen y mostrar "Hello from Docker!"

# Verificar WSL
wsl --list --verbose
# Debe mostrar:
# * Ubuntu-22.04        Running    2
#   docker-desktop      Running    2
#   docker-desktop-data Running    2
```

### Paso 2.4: Crear Estructura Docker

```powershell
cd C:\Users\aleja\Desktop\gemma3n\mobile

# Crear carpeta docker
New-Item -Path "docker" -ItemType Directory -Force

# Crear Dockerfile para conversión de modelos
```

**Crear `mobile/docker/Dockerfile.model-conversion`:**

```dockerfile
FROM python:3.11-slim

LABEL maintainer="OceanGuard AI"
LABEL description="Container for Gemma 3n model conversion"

ENV DEBIAN_FRONTEND=noninteractive
ENV PYTHONUNBUFFERED=1
ENV HF_HOME=/workspace/.cache/huggingface

# Install system dependencies
RUN apt-get update && apt-get install -y \
    build-essential \
    git \
    wget \
    curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace

# Copy requirements
COPY requirements-mobile.txt .

# Install Python dependencies
RUN pip install --no-cache-dir --upgrade pip && \
    pip install --no-cache-dir -r requirements-mobile.txt

# Create directories
RUN mkdir -p \
    /workspace/.cache/huggingface \
    /workspace/conversion/converted_models \
    /workspace/conversion_logs

VOLUME ["/workspace/conversion", "/workspace/.cache/huggingface"]

CMD ["/bin/bash"]
```

**Crear `mobile/docker-compose.yml`:**

```yaml
version: '3.8'

services:
  model-conversion:
    build:
      context: .
      dockerfile: docker/Dockerfile.model-conversion
    container_name: oceanguard-model-conversion
    volumes:
      - ./conversion:/workspace/conversion
      - model-cache:/workspace/.cache/huggingface
    environment:
      - HF_TOKEN=${HF_TOKEN}
    stdin_open: true
    tty: true

volumes:
  model-cache:
    driver: local
```

### Paso 2.5: Construir Imagen Docker

```powershell
cd C:\Users\aleja\Desktop\gemma3n\mobile

# Construir imagen (primera vez toma 5-10 minutos)
docker compose build model-conversion

# Verificar imagen creada
docker images | Select-String "oceanguard"
```

---

## 🔄 PARTE 3: Workflow Completo

### 3.1: Convertir Modelos con Docker

**Configurar HuggingFace Token:**

```powershell
# En PowerShell
$env:HF_TOKEN = "hf_xxxxxxxxxxxxxxxxxxxxxxxxxxxxx"

# Verificar
echo $env:HF_TOKEN
```

**Ejecutar conversión de modelos:**

```powershell
cd C:\Users\aleja\Desktop\gemma3n\mobile

# 1. Descargar modelo base (10-30 min)
docker compose run --rm model-conversion python conversion/download_base_model.py

# 2. Convertir modelo base (10-30 min)
docker compose run --rm model-conversion python conversion/convert_base_model.py

# 3. Convertir LoRA adapter (15-45 min)
docker compose run --rm model-conversion python conversion/convert_lora_adapter.py

# 4. Validar modelos (5 min)
docker compose run --rm model-conversion python conversion/validate_converted_model.py
```

**Output esperado:**

```
✓ Model downloaded successfully
✓ Base model converted: oceanguard_base.bin (2.5GB)
✓ LoRA adapter converted: oceanguard_adapter.bin (600MB)
✓ TTFT: 0.687s (target: <1.5s) ✅
✓ Throughput: 41.5 t/s (target: >30 t/s) ✅
✓ VALIDATION PASSED
```

**Los modelos quedan en:**
```
C:\Users\aleja\Desktop\gemma3n\mobile\conversion\converted_models\
├── oceanguard_base.bin (2.5GB)
└── oceanguard_adapter.bin (600MB)
```

### 3.2: Copiar Modelos a Android App (Windows)

```powershell
# Crear directorio assets
New-Item -Path "android\app\src\main\assets\models" -ItemType Directory -Force

# Copiar modelos
Copy-Item "conversion\converted_models\oceanguard_base.bin" `
  -Destination "android\app\src\main\assets\models\"

Copy-Item "conversion\converted_models\oceanguard_adapter.bin" `
  -Destination "android\app\src\main\assets\models\"

# Verificar
Get-ChildItem "android\app\src\main\assets\models" | Format-Table Name, Length
```

### 3.3: Abrir Proyecto en Android Studio

```powershell
# Desde PowerShell, abrir Android Studio con el proyecto
start "" "C:\Program Files\Android\Android Studio\bin\studio64.exe" "C:\Users\aleja\Desktop\gemma3n\mobile\android"
```

O manualmente:

1. Abre **Android Studio**
2. **File → Open**
3. Navega a: `C:\Users\aleja\Desktop\gemma3n\mobile\android`
4. Click **OK**
5. **Espera Gradle Sync** (10-20 min primera vez)

### 3.4: Limpiar Caché de Gradle (Si hay errores)

```powershell
cd C:\Users\aleja\Desktop\gemma3n\mobile\android

# Limpiar build
.\gradlew clean

# Detener daemon de Gradle
.\gradlew --stop

# Cerrar Android Studio

# Opcional: Borrar caché completo
Remove-Item -Path "$env:USERPROFILE\.gradle\caches" -Recurse -Force

# Reabrir Android Studio
```

### 3.5: Build de la App (Android Studio)

En Android Studio:

1. **Build → Make Project** (Ctrl+F9)
2. Espera compilación (5-10 min primera vez)
3. Verifica panel "Build" abajo: debe decir "BUILD SUCCESSFUL"

### 3.6: Crear Emulador

1. **Tools → Device Manager**
2. **Create Device**
3. **Hardware**: Pixel 7
4. **System Image**: UpsideDownCake (API 34, Android 14)
   - Si dice "Download", click para descargar (~1GB)
5. **Next**
6. **AVD Name**: OceanGuard_Test
7. **Show Advanced Settings**:
   - **RAM**: 4096 MB
   - **Internal Storage**: 8192 MB
   - **Graphics**: Hardware - GLES 2.0
8. **Finish**

### 3.7: Ejecutar App

1. Selecciona **OceanGuard_Test** en dropdown de dispositivos
2. Click **▶️ Run** (Shift+F10)
3. Espera que arranque emulador (2-5 min primera vez)
4. App se instalará automáticamente

**Primera ejecución:**
- Acepta permisos (cámara, almacenamiento)
- Espera carga del modelo (15-30s)
- Verás la pantalla principal

---

## 📊 Resumen de Comandos Rápidos

### Conversión de Modelos (Docker)

```powershell
cd C:\Users\aleja\Desktop\gemma3n\mobile
$env:HF_TOKEN = "hf_xxxxx"
docker compose run --rm model-conversion python conversion/convert_base_model.py
```

### Build Android (Nativo)

```powershell
cd C:\Users\aleja\Desktop\gemma3n\mobile\android
.\gradlew clean
.\gradlew assembleDebug
```

### Limpiar Todo

```powershell
# Limpiar Gradle
cd C:\Users\aleja\Desktop\gemma3n\mobile\android
.\gradlew clean
.\gradlew --stop

# Limpiar Docker
cd C:\Users\aleja\Desktop\gemma3n\mobile
docker compose down --volumes
docker system prune -a
```

---

## 🐛 Troubleshooting

### Docker: "Cannot connect to Docker daemon"

**Solución:**
```powershell
# Verificar que Docker Desktop está corriendo
Get-Process "Docker Desktop" -ErrorAction SilentlyContinue

# Si no está corriendo, abrirlo
Start-Process "C:\Program Files\Docker\Docker\Docker Desktop.exe"

# Esperar 1-2 minutos
Start-Sleep -Seconds 60

# Verificar
docker ps
```

### Android Studio: "Gradle sync failed"

**Solución:**
```powershell
cd C:\Users\aleja\Desktop\gemma3n\mobile\android
.\gradlew clean --refresh-dependencies
.\gradlew --stop
```

Luego en Android Studio:
```
File → Invalidate Caches → Invalidate and Restart
```

### Modelo no encontrado en la app

**Verificar:**
```powershell
Get-ChildItem "C:\Users\aleja\Desktop\gemma3n\mobile\android\app\src\main\assets\models"

# Debe mostrar:
# oceanguard_base.bin     (2,500,000,000 bytes aprox)
# oceanguard_adapter.bin  (600,000,000 bytes aprox)
```

Si no están, copiarlos de nuevo:
```powershell
Copy-Item "conversion\converted_models\*.bin" `
  -Destination "android\app\src\main\assets\models\" -Force
```

### WSL2 no arranca

```powershell
# Reiniciar WSL
wsl --shutdown
wsl

# Si falla, reinstalar distribución
wsl --unregister Ubuntu-22.04
wsl --install -d Ubuntu-22.04
```

---

## ✅ Checklist Completo

### Parte 1: Android Studio (Windows Nativo)
- [ ] JDK 17 instalado (`java -version`)
- [ ] Android Studio instalado
- [ ] Variables de entorno configuradas (JAVA_HOME, ANDROID_HOME)
- [ ] SDK Components descargados (API 34, Build Tools)
- [ ] Windows Defender exclusiones agregadas
- [ ] Gradle properties configurado
- [ ] Android Studio optimizado (VM options)

### Parte 2: Docker
- [ ] WSL2 habilitado y funcionando
- [ ] Ubuntu 22.04 instalado en WSL
- [ ] Docker Desktop instalado
- [ ] Docker Desktop integrado con WSL2
- [ ] `docker --version` funciona
- [ ] Dockerfile creado
- [ ] docker-compose.yml creado
- [ ] Imagen Docker construida

### Parte 3: Modelos
- [ ] HuggingFace token configurado
- [ ] Modelo base descargado (3GB)
- [ ] Modelo base convertido (2.5GB)
- [ ] LoRA adapter convertido (600MB)
- [ ] Validación pasada
- [ ] Modelos copiados a assets/

### Parte 4: Android App
- [ ] Proyecto abierto en Android Studio
- [ ] Gradle sync exitoso
- [ ] Build exitoso
- [ ] Emulador creado
- [ ] App ejecutada en emulador
- [ ] Modelo carga correctamente
- [ ] UI funciona

---

## 🎯 Próximos Pasos

Una vez completado el setup:

1. **Lee**: [LEARNING_PATH.md](LEARNING_PATH.md) para aprender Kotlin y Compose
2. **Implementa**: Camera capture (CameraX)
3. **Implementa**: Image selection from gallery
4. **Implementa**: Bounding box visualization
5. **Implementa**: Database (Room) for history

---

## 💡 Tips Pro

### Alias de PowerShell para Comandos Comunes

Crea `$PROFILE` con aliases útiles:

```powershell
# Abrir perfil de PowerShell
notepad $PROFILE

# Agregar estos alias:
function oceanguard-docker {
    cd C:\Users\aleja\Desktop\gemma3n\mobile
    docker compose run --rm model-conversion bash
}

function oceanguard-build {
    cd C:\Users\aleja\Desktop\gemma3n\mobile\android
    .\gradlew clean
    .\gradlew assembleDebug
}

function oceanguard-studio {
    start "" "C:\Program Files\Android\Android Studio\bin\studio64.exe" "C:\Users\aleja\Desktop\gemma3n\mobile\android"
}

# Guardar y cerrar
# Recargar perfil
. $PROFILE
```

Ahora puedes usar:
```powershell
oceanguard-docker   # Abre bash en container Docker
oceanguard-build    # Build de la app
oceanguard-studio   # Abre Android Studio
```

### Monitorear Uso de Recursos

```powershell
# Ver uso de Docker
docker stats

# Ver procesos de Gradle
Get-Process | Where-Object {$_.ProcessName -like "*java*"} | Format-Table ProcessName, CPU, WS

# Ver espacio en disco
Get-PSDrive C | Select-Object Used, Free
```

---

## 📞 Soporte

Si tienes problemas:

1. **Gradle**: Ver [android/FIXED_GRADLE_ERROR.md](android/FIXED_GRADLE_ERROR.md)
2. **Docker**: Ver [DOCKER_SETUP.md](DOCKER_SETUP.md)
3. **General**: Ver [QUICK_START_WINDOWS.md](QUICK_START_WINDOWS.md)

---

**🎉 ¡Con esto tienes el mejor setup posible para desarrollo Android con modelos de IA!**

- ⚡ Android Studio nativo = velocidad y debugging
- 🐳 Docker para modelos = reproducibilidad y portabilidad
- 🚀 Lo mejor de ambos mundos
