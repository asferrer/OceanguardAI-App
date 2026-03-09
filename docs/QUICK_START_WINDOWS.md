# 🚀 OceanGuard AI Mobile - Quick Start para Windows

**Actualizado**: Diciembre 2025 | **Versiones más recientes** | **Optimizado para Windows 11**

Ejecuta tu modelo de detección de residuos marinos en Android con las últimas tecnologías.

---

## 📋 Opciones de Desarrollo

Tienes **dos opciones** para desarrollar en Windows:

### Opción A: Instalación Nativa en Windows (Recomendada) ⭐
- ✅ **Mejor rendimiento**
- ✅ **Emulador Android funciona perfectamente**
- ✅ **Más fácil para debugging**
- ✅ **Integración completa con Android Studio**
- ❌ Requiere configurar Windows correctamente
- ⏱️ Setup: 30 minutos

### Opción B: Docker + WSL2 (Para CI/CD o desarrollo avanzado)
- ✅ **Entorno reproducible**
- ✅ **Ideal para builds automáticos**
- ✅ **Fácil compartir configuración con equipo**
- ❌ **No soporta emulador Android dentro del container**
- ❌ Más complejo de configurar
- ⏱️ Setup: 1 hora

**Recomendación**: Usa **Opción A** para desarrollo diario. Usa Docker solo para CI/CD.

---

## ⚡ OPCIÓN A: Instalación Nativa en Windows

### Phase 1: Preparar el Entorno (30 minutos)

#### 1.1 Instalar Prerrequisitos

**JDK 17 (Requerido):**
```powershell
# Opción 1: Chocolatey (recomendado)
choco install microsoft-openjdk17

# Opción 2: Descarga manual
# https://learn.microsoft.com/en-us/java/openjdk/download#openjdk-17
```

**Verificar instalación:**
```powershell
java -version
# Debe mostrar: openjdk version "17.x.x"
```

**Python 3.11 (para conversión de modelos):**
```powershell
# Opción 1: Chocolatey
choco install python311

# Opción 2: Microsoft Store
# Buscar "Python 3.11" en Microsoft Store

# Opción 3: python.org
# https://www.python.org/downloads/
```

**Verificar instalación:**
```powershell
python --version
# Debe mostrar: Python 3.11.x
```

**Android Studio (última versión):**
```powershell
# Opción 1: Chocolatey
choco install androidstudio

# Opción 2: Descarga manual
# https://developer.android.com/studio
```

#### 1.2 Configurar Variables de Entorno

Abre PowerShell como **Administrador** y ejecuta:

```powershell
# Configurar JAVA_HOME
[System.Environment]::SetEnvironmentVariable('JAVA_HOME', 'C:\Program Files\Microsoft\jdk-17.0.x-x', 'Machine')

# Configurar ANDROID_HOME (ajusta la ruta si es diferente)
[System.Environment]::SetEnvironmentVariable('ANDROID_HOME', "$env:LOCALAPPDATA\Android\Sdk", 'User')

# Agregar a PATH
$currentPath = [System.Environment]::GetEnvironmentVariable('Path', 'User')
$newPath = "$currentPath;$env:ANDROID_HOME\platform-tools;$env:ANDROID_HOME\tools;$env:ANDROID_HOME\tools\bin"
[System.Environment]::SetEnvironmentVariable('Path', $newPath, 'User')

# Reiniciar PowerShell para aplicar cambios
```

#### 1.3 Configurar Android Studio

1. **Primera vez abriendo Android Studio:**
   - Wizard inicial → Selecciona "Standard" installation
   - Descargará Android SDK, emulador, y herramientas (~5GB)
   - Acepta todas las licencias

2. **Instalar componentes adicionales:**
   ```
   Tools → SDK Manager → SDK Tools (pestaña)
   Marca:
   ✅ Android SDK Build-Tools (latest)
   ✅ Android Emulator
   ✅ Android SDK Platform-Tools
   ✅ Google Play services
   ✅ Intel x86 Emulator Accelerator (HAXM) o Android Emulator Hypervisor Driver

   Click "Apply"
   ```

3. **Optimizar Android Studio para Windows:**
   ```
   Help → Edit Custom VM Options

   Agrega estas líneas:
   -Xms2048m
   -Xmx8192m
   -XX:ReservedCodeCacheSize=512m
   -XX:+UseG1GC
   ```

4. **Excluir de Windows Defender** (Mejora velocidad 30-50%):
   ```powershell
   # Ejecutar como Administrador
   Add-MpPreference -ExclusionPath "$env:LOCALAPPDATA\Android\Sdk"
   Add-MpPreference -ExclusionPath "$env:USERPROFILE\.gradle"
   Add-MpPreference -ExclusionPath "$env:USERPROFILE\.android"
   Add-MpPreference -ExclusionPath "C:\Users\aleja\Desktop\gemma3n"
   ```

---

### Phase 2: Convertir el Modelo (1-2 horas)

#### 2.1 Preparar Entorno Python

```powershell
# Navegar a la carpeta del proyecto
cd C:\Users\aleja\Desktop\gemma3n\mobile

# Crear entorno virtual
python -m venv venv

# Activar entorno virtual (PowerShell)
.\venv\Scripts\Activate.ps1

# Si da error de ejecución de scripts:
Set-ExecutionPolicy -ExecutionPolicy RemoteSigned -Scope CurrentUser

# Luego intenta activar de nuevo
.\venv\Scripts\Activate.ps1

# Actualizar pip
python -m pip install --upgrade pip

# Instalar dependencias (toma 5-10 minutos)
pip install -r requirements-mobile.txt
```

#### 2.2 Configurar Hugging Face Token

```powershell
# Obtén tu token en: https://huggingface.co/settings/tokens
# Necesitas LEER permisos para modelos de Google

# Configurar token (PowerShell)
$env:HF_TOKEN = "hf_xxxxxxxxxxxxxxxxxxxxxxxxxxxxx"

# Verificar
echo $env:HF_TOKEN
```

#### 2.3 Convertir Modelos

```powershell
cd conversion

# Paso 1: Descargar modelo base Gemma 3n (10-30 min | 3GB)
python download_base_model.py
# Salida esperada: "✓ Model downloaded successfully to ..."

# Paso 2: Convertir modelo base a LiteRT (10-30 min)
python convert_base_model.py
# Salida esperada: "✓ Base model converted successfully: oceanguard_base.bin (2.5GB)"

# Paso 3: Convertir adaptador LoRA (15-45 min)
python convert_lora_adapter.py
# Salida esperada: "✓ LoRA adapter converted successfully: oceanguard_adapter.bin (600MB)"

# Paso 4: Validar modelos (5 min)
python validate_converted_model.py
# Salida esperada:
# ✓ Model loaded in 12.34 seconds
# ✓ TTFT: 0.687s (target: <1.5s)
# ✓ Throughput: 41.5 t/s (target: >30 t/s)
# ✓ VALIDATION PASSED - Model ready for deployment!
```

**Nota:** Si tienes GPU NVIDIA, la conversión será más rápida.

---

### Phase 3: Construir Aplicación Android (30-45 minutos)

#### 3.1 Abrir Proyecto en Android Studio

1. **Abrir Android Studio**

2. **File → Open**

3. **Navegar a:**
   ```
   C:\Users\aleja\Desktop\gemma3n\mobile\android
   ```

4. **Click "OK"**

5. **Esperar Gradle Sync** (10-20 minutos primera vez)
   - Descargará ~3-4GB de dependencias
   - Verás progreso en la parte inferior
   - **Esto es NORMAL** - tomará tiempo

6. **Si aparece error de Gradle**, sigue las instrucciones en:
   ```
   mobile\android\FIXED_GRADLE_ERROR.md
   ```

#### 3.2 Copiar Modelos Convertidos

```powershell
# Crear directorio de assets
New-Item -Path "C:\Users\aleja\Desktop\gemma3n\mobile\android\app\src\main\assets\models" -ItemType Directory -Force

# Copiar modelos
Copy-Item "C:\Users\aleja\Desktop\gemma3n\mobile\conversion\converted_models\oceanguard_base.bin" `
  -Destination "C:\Users\aleja\Desktop\gemma3n\mobile\android\app\src\main\assets\models\"

Copy-Item "C:\Users\aleja\Desktop\gemma3n\mobile\conversion\converted_models\oceanguard_adapter.bin" `
  -Destination "C:\Users\aleja\Desktop\gemma3n\mobile\android\app\src\main\assets\models\"

# Verificar
Get-ChildItem "C:\Users\aleja\Desktop\gemma3n\mobile\android\app\src\main\assets\models\"
```

**Salida esperada:**
```
Mode                 LastWriteTime         Length Name
----                 -------------         ------ ----
-a---          12/1/2025   10:30 AM     2500000000 oceanguard_base.bin
-a---          12/1/2025   10:35 AM      600000000 oceanguard_adapter.bin
```

#### 3.3 Build del Proyecto

En Android Studio:

1. **Build → Make Project** (o `Ctrl+F9`)

2. **Esperar compilación** (10-15 minutos primera vez)

3. **Verificar que no haya errores** en el panel "Build" (abajo)

---

### Phase 4: Ejecutar en Dispositivo (15 minutos)

#### 4.1 Opción A: Dispositivo Físico (Recomendado para LLMs)

**En tu teléfono/tablet Android:**

1. **Habilitar Modo Desarrollador:**
   ```
   Configuración → Acerca del teléfono → Tocar "Número de compilación" 7 veces
   ```

2. **Habilitar USB Debugging:**
   ```
   Configuración → Opciones de desarrollador → Depuración USB → Activar
   ```

3. **Conectar vía USB al PC**

4. **Aceptar el diálogo** "¿Permitir depuración USB?"

**En Android Studio:**

5. Verás tu dispositivo en el dropdown (toolbar superior)

6. Click **Run** ▶️ (o `Shift+F10`)

7. La app se instalará y abrirá automáticamente

#### 4.2 Opción B: Emulador Android

**Crear emulador:**

1. **Tools → Device Manager**

2. **Create Device**

3. **Seleccionar:**
   - Hardware: **Pixel 7** o **Pixel 7 Pro**
   - System Image: **UpsideDownCake (API 34, Android 14)**
   - ABI: **x86_64** (más rápido en PCs Intel/AMD)
   - Download si es necesario (~1GB)

4. **Click "Next"**

5. **Configurar AVD:**
   ```
   AVD Name: OceanGuard_Test

   Show Advanced Settings:
   RAM: 4096 MB (mínimo para el modelo de IA)
   VM heap: 512 MB
   Internal Storage: 8192 MB
   SD Card: 512 MB
   ```

6. **Click "Finish"**

**Ejecutar app:**

7. Selecciona el emulador en el dropdown

8. Click **Run** ▶️

9. Espera a que el emulador arranque (2-5 min primera vez)

10. La app se instalará automáticamente

---

### Phase 5: Probar la Aplicación

#### 5.1 Primera Ejecución

1. **Permisos**: La app pedirá permisos
   - Cámara: Permite
   - Almacenamiento: Permite
   - Ubicación: Permite (opcional)

2. **Carga del modelo**: Primera vez toma 15-30 segundos
   - Verás un indicador de carga
   - El modelo queda en memoria para uso futuro

3. **Pantalla principal**: Deberías ver:
   ```
   🌊 OceanGuard AI

   Welcome to OceanGuard AI
   Offline Marine Debris Detection

   [📷 Take Photo]
   [🖼️ Select from Gallery]
   [📊 View History]

   Model Information
   Model: Gemma 3n E2B
   Size: ~3.5 GB
   Mode: 100% Offline
   Status: Ready ✅
   ```

#### 5.2 Verificar Logs

En Android Studio:

1. **View → Tool Windows → Logcat**

2. **Filtrar por "OceanGuard"** en el buscador

3. **Deberías ver:**
   ```
   I/OceanGuardApp: OceanGuard AI starting...
   I/OceanGuardInference: Initializing on-device marine debris detection model
   I/OceanGuardInference: Loading Gemma 3n E2B model...
   I/OceanGuardInference: Loading LoRA adapter...
   I/OceanGuardInference: Model loaded successfully in 12.4s
   I/OceanGuardInference: GPU backend initialized
   I/OceanGuardInference: Ready for inference
   ```

4. **Si ves errores rojos**, copia el error y consulta la sección de Troubleshooting

---

## 📊 Rendimiento Esperado

| Métrica | Valor Esperado | Notas |
|---------|----------------|-------|
| **Carga inicial del modelo** | 10-30s | Solo primera vez |
| **TTFT (Time to First Token)** | 0.5-1.5s | Depende del dispositivo |
| **Throughput** | 30-60 tokens/s | GPU > CPU |
| **Inferencia completa** | 2-5s | Para detección de imagen |
| **Uso de memoria** | 3.5-4.5GB | Modelo + runtime |
| **Uso de batería** | ~1-2% por sesión | Depende de uso |
| **Tamaño de la app** | ~3.8GB | Con modelos incluidos |

**Dispositivos recomendados:**
- Google Pixel 7/8 o superior
- Samsung Galaxy S23 o superior
- Cualquier dispositivo con 6GB+ RAM y Android 8.0+

---

## 🔧 Troubleshooting

### Error: "Gradle sync failed"

**Solución:**
```powershell
# En la carpeta mobile\android
.\gradlew clean
.\gradlew --stop

# Luego en Android Studio:
File → Invalidate Caches → Invalidate and Restart
```

### Error: "Could not find com.google.mediapipe:tasks-genai"

**Causa:** Sin conexión a Maven Central

**Solución:**
1. Verifica tu conexión a internet
2. File → Settings → Build, Execution, Deployment → Gradle
3. Gradle user home: Verifica que sea `C:\Users\aleja\.gradle`
4. Click OK
5. File → Sync Project with Gradle Files

### Error: "SDK not found"

**Solución:**
```
File → Project Structure
SDK Location → Android SDK location: C:\Users\aleja\AppData\Local\Android\Sdk
JDK location: C:\Program Files\Microsoft\jdk-17.x.x
Click OK
```

### Error: "OutOfMemoryError" en la app

**Causa:** Dispositivo sin suficiente RAM

**Soluciones:**
1. Cerrar todas las apps en segundo plano
2. Reducir tamaño de imagen de entrada:
   ```kotlin
   // En ImagePreprocessor.kt, cambiar:
   const val TARGET_SIZE = 256  // En lugar de 512
   ```
3. Usar dispositivo con más RAM (6GB+)

### Error: "Model file not found"

**Causa:** Modelos no están en assets

**Solución:**
```powershell
# Verificar que existan:
Get-ChildItem "C:\Users\aleja\Desktop\gemma3n\mobile\android\app\src\main\assets\models\"

# Si no existen, copiarlos de nuevo (ver Phase 3.2)
```

### Gradle muy lento en Windows

**Soluciones:**

1. **Excluir de Windows Defender** (ver Phase 1.3)

2. **Usar SSD** para proyecto y caché de Gradle

3. **Agregar configuraciones de Gradle:**
   ```
   File → Settings → Build, Execution, Deployment → Gradle

   Build and run using: Gradle (not IntelliJ IDEA)
   Run tests using: Gradle

   Gradle JVM: jbr-17 (JetBrains Runtime)
   ```

4. **Crear `gradle.properties` en `%USERPROFILE%\.gradle\`:**
   ```properties
   org.gradle.daemon=true
   org.gradle.parallel=true
   org.gradle.caching=true
   org.gradle.configureondemand=true
   org.gradle.jvmargs=-Xmx4096m -XX:MaxMetaspaceSize=512m -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8
   ```

### Emulador no arranca

**Solución 1: Verificar virtualización**
```powershell
# Ejecutar como Administrador
systeminfo | findstr /I "Hyper-V"

# Si dice "Hyper-V Requirements: A hypervisor has been detected..."
# Entonces Hyper-V está activo (correcto)

# Si no está activo:
bcdedit /set hypervisorlaunchtype auto
# Reiniciar PC
```

**Solución 2: Reinstalar Android Emulator Hypervisor Driver**
```
Tools → SDK Manager → SDK Tools
Desmarcar "Android Emulator Hypervisor Driver"
Apply
Marcar de nuevo
Apply
```

---

## 🚀 Versiones Utilizadas (Diciembre 2025 - Latest)

### Build Tools
- **Android Gradle Plugin**: 8.7.3 (estable, compatible con Kotlin 2.1.0)
- **Gradle**: 8.7.3
- **Kotlin**: 2.1.0
- **Java**: OpenJDK 17

### AndroidX Core
- **core-ktx**: 1.15.0
- **lifecycle-runtime-ktx**: 2.8.7
- **activity-compose**: 1.9.3

### Jetpack Compose
- **Compose BOM**: 2024.12.01
- **Compose Compiler**: Automático con Kotlin 2.1.0

### MediaPipe
- **tasks-genai**: 0.10.24 (soporta Gemma 3n + LoRA)

### Otras Bibliotecas
- **Room**: 2.6.1
- **Navigation Compose**: 2.8.5
- **CameraX**: 1.4.1
- **Coroutines**: 1.10.1

**Nota:** Usamos versiones estables probadas para evitar conflictos. Las versiones más recientes (2025.08 Compose BOM, AGP 8.13, etc.) están disponibles pero pueden tener bugs no resueltos.

---

## 📚 Siguientes Pasos

### Implementar Funcionalidades Completas

1. **Captura de cámara** (Semana 3)
   - Integrar CameraX
   - Preview en tiempo real
   - Captura y procesamiento

2. **Visualización de resultados** (Semana 3-4)
   - Dibujar bounding boxes
   - Mostrar clasificación y confianza
   - Calcular health score

3. **Base de datos** (Semana 4)
   - Implementar Room
   - Guardar detecciones
   - Historial de sesiones

4. **Mapas** (Semana 5)
   - Google Maps API
   - Marcar ubicaciones
   - Heatmap de contaminación

5. **Optimización** (Semana 6-8)
   - Asset Packs para distribución
   - Optimizar rendimiento
   - Reducir uso de batería

### Recursos de Aprendizaje

- **Kotlin**: [mobile/LEARNING_PATH.md](LEARNING_PATH.md)
- **Jetpack Compose**: [developer.android.com/compose](https://developer.android.com/compose)
- **MediaPipe**: [ai.google.dev/edge/mediapipe](https://ai.google.dev/edge/mediapipe)
- **CameraX**: [developer.android.com/camerax](https://developer.android.com/camerax)

---

## 💡 Mejores Prácticas para Windows

### PowerShell vs CMD

**Usa PowerShell** para desarrollo Android:
- Mejor manejo de paths con espacios
- Soporte UNC paths (para WSL2)
- Scripting más poderoso
- Auto-completado mejorado

**Ejecutar Gradle:**
```powershell
# PowerShell
./gradlew build

# CMD
gradlew.bat build
```

### Organización de Carpetas

```
C:\
├── Users\
│   └── aleja\
│       ├── AppData\
│       │   └── Local\
│       │       └── Android\
│       │           └── Sdk\        # Android SDK (auto)
│       ├── .gradle\                # Caché de Gradle (auto)
│       ├── .android\               # Configuración AVD (auto)
│       └── Desktop\
│           └── gemma3n\            # Tu proyecto
│               └── mobile\
│                   ├── android\    # Proyecto Android Studio
│                   └── conversion\ # Scripts Python
```

### Copias de Seguridad

**Qué respaldar:**
- ✅ `mobile/android/app/src/` (tu código)
- ✅ `mobile/conversion/converted_models/` (modelos convertidos)
- ✅ `outputs/` (LoRA adapter entrenado)

**Qué NO respaldar:**
- ❌ `.gradle/` (se regenera)
- ❌ `build/` (se regenera)
- ❌ `venv/` (se regenera con pip install)
- ❌ Android SDK (se descarga)

---

## ✅ Checklist Completo

### Entorno Windows
- [ ] JDK 17 instalado y verificado
- [ ] Python 3.11 instalado y verificado
- [ ] Android Studio instalado
- [ ] Variables de entorno configuradas
- [ ] Android SDK descargado
- [ ] Windows Defender exclusiones agregadas
- [ ] Gradle configurado

### Conversión de Modelos
- [ ] Entorno virtual Python creado
- [ ] Dependencias instaladas
- [ ] HuggingFace token configurado
- [ ] Modelo base descargado (3GB)
- [ ] Modelo base convertido (2.5GB)
- [ ] LoRA adapter convertido (600MB)
- [ ] Validación pasada

### Proyecto Android
- [ ] Proyecto abierto en Android Studio
- [ ] Gradle sync completado sin errores
- [ ] Modelos copiados a assets/models/
- [ ] Proyecto compilado exitosamente
- [ ] Emulador creado o dispositivo conectado

### Primera Ejecución
- [ ] App instalada en dispositivo/emulador
- [ ] Permisos otorgados
- [ ] Modelo cargado correctamente
- [ ] Logs muestran "Ready for inference"
- [ ] UI funciona correctamente

---

## 🎉 ¡Listo!

Tu aplicación OceanGuard AI está corriendo en Android con:
- ✅ Gemma 3n E2B (2B parámetros efectivos)
- ✅ LoRA adapter personalizado para residuos marinos
- ✅ 100% offline (sin internet requerido)
- ✅ Inferencia en dispositivo (<2s por imagen)
- ✅ Últimas versiones estables de todas las dependencias

**Tiempo total estimado**: 2-4 horas (incluyendo descargas)

**Espacio en disco usado**: ~60GB

**Próximo paso**: Implementar captura de cámara y visualización de resultados.

---

**¿Tienes problemas?** Consulta [FIXED_GRADLE_ERROR.md](android/FIXED_GRADLE_ERROR.md) y [NEXT_STEPS.md](NEXT_STEPS.md)

**¿Listo para aprender?** Lee [LEARNING_PATH.md](LEARNING_PATH.md) para guías paso a paso
