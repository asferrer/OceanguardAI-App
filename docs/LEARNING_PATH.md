# 🎓 OceanGuard AI - Android Development Learning Path

Esta guía te llevará desde cero hasta una aplicación móvil completa, aprendiendo Android Studio y Kotlin mientras construyes OceanGuard AI.

---

## 📚 Sesión 1: Instalación y Configuración Inicial (30-60 minutos)

### 1.1 Instalar Java Development Kit (JDK) 17

Android Studio requiere JDK para compilar aplicaciones Android.

**Pasos:**

1. **Descargar JDK 17**:
   - Ve a: https://www.oracle.com/java/technologies/javase/jdk17-archive-downloads.html
   - O usa OpenJDK: https://adoptium.net/temurin/releases/?version=17

2. **Elegir la versión correcta**:
   - Para Windows x64: `jdk-17_windows-x64_bin.exe`
   - Descarga (~150 MB)

3. **Instalar**:
   - Ejecuta el instalador
   - Usa la ubicación por defecto: `C:\Program Files\Java\jdk-17`
   - Click "Next" → "Next" → "Close"

4. **Configurar Variable de Entorno** (IMPORTANTE):
   ```
   Win + R → escribe "sysdm.cpl" → Enter
   → Pestaña "Opciones Avanzadas"
   → "Variables de Entorno"
   → En "Variables del Sistema" → Click "Nueva"

   Nombre de variable: JAVA_HOME
   Valor de variable: C:\Program Files\Java\jdk-17

   → Click "Aceptar"

   → Editar la variable "Path"
   → Click "Nuevo"
   → Agregar: %JAVA_HOME%\bin
   → Click "Aceptar" en todo
   ```

5. **Verificar Instalación**:
   ```powershell
   # Abre PowerShell NUEVA (importante, cierra la anterior)
   java -version
   # Debería mostrar: java version "17.x.x"

   javac -version
   # Debería mostrar: javac 17.x.x
   ```

✅ **Checkpoint**: Si ves las versiones, ¡Java está instalado correctamente!

---

### 1.2 Instalar Android Studio

**Pasos:**

1. **Descargar Android Studio**:
   - Ve a: https://developer.android.com/studio
   - Click en "Download Android Studio"
   - Acepta los términos
   - Descarga (~1 GB)

2. **Instalar**:
   - Ejecuta `android-studio-xxxx-windows.exe`
   - Click "Next"
   - Selecciona "Standard" installation
   - Click "Next" → "Next" → "Finish"

3. **Primera Ejecución** (Setup Wizard):
   ```
   → "Next"
   → Install Type: "Standard"
   → UI Theme: Elige el que prefieras (yo uso Darcula - tema oscuro)
   → "Next"
   → Verify Settings → "Finish"

   → Comenzará a descargar componentes SDK (~3-5 GB)
   → Esto toma 10-30 minutos dependiendo de tu internet ☕
   ```

4. **Componentes Necesarios**:
   Después de la instalación inicial:
   ```
   → Click "More Actions" → "SDK Manager"
   → En "SDK Platforms" tab, asegúrate de tener:
      ✅ Android 15.0 (API 35)
      ✅ Android 13.0 (API 33)
      ✅ Android 8.0 (API 26)

   → En "SDK Tools" tab, asegúrate de tener:
      ✅ Android SDK Build-Tools
      ✅ Android SDK Platform-Tools
      ✅ Android Emulator
      ✅ NDK (Side by side)
      ✅ Intel x86 Emulator Accelerator (HAXM installer)

   → Click "Apply" → espera a que descargue todo
   → Click "OK"
   ```

✅ **Checkpoint**: Android Studio abierto con SDK instalado.

---

### 1.3 Crear un Dispositivo Virtual (Emulador)

Antes de trabajar con nuestro proyecto real, vamos a crear un emulador para testing.

**Pasos:**

1. En Android Studio:
   ```
   → Click "More Actions" → "Virtual Device Manager"
   → Click "Create Device"
   ```

2. **Seleccionar Hardware**:
   ```
   → Category: "Phone"
   → Dispositivo recomendado: "Pixel 7" o "Pixel 7 Pro"
   → Click "Next"
   ```

3. **Seleccionar System Image**:
   ```
   → Release Name: "UpsideDownCake" (Android 14 - API 34)
   → ABI: "x86_64"
   → Si dice "Download" → Click para descargar (~1 GB)
   → Click "Next"
   ```

4. **Configurar AVD**:
   ```
   → AVD Name: "OceanGuard Test Device"
   → Startup orientation: "Portrait"
   → Click "Show Advanced Settings"

   → RAM: 4096 MB (mínimo para nuestro modelo)
   → VM Heap: 512 MB
   → Internal Storage: 8192 MB

   → Click "Finish"
   ```

5. **Probar el Emulador**:
   ```
   → En Device Manager, click el ▶️ (Play) junto a tu dispositivo
   → Espera 1-2 minutos a que arranque
   → Deberías ver un teléfono Android en tu pantalla
   ```

✅ **Checkpoint**: Emulador Android funcionando.

---

## 📚 Sesión 2: Abrir y Entender el Proyecto OceanGuard (30 minutos)

### 2.1 Abrir el Proyecto

1. **Abrir Android Studio**

2. **Abrir Proyecto**:
   ```
   → Click "Open"
   → Navega a: C:\Users\aleja\Desktop\gemma3n\mobile\android\
   → Click "OK"
   ```

3. **Esperar Gradle Sync**:
   ```
   → Verás una barra de progreso abajo: "Gradle Sync"
   → Primera vez toma 5-15 minutos
   → Descarga todas las dependencias (MediaPipe, Compose, etc.)

   ☕ Buen momento para un café
   ```

4. **Posibles Errores y Soluciones**:

   **Error: "Gradle version X required"**
   ```
   → File → Project Structure
   → En "Project", cambia Gradle Version a la requerida
   → Click "OK" → Sync again
   ```

   **Error: "SDK not found"**
   ```
   → File → Project Structure → SDK Location
   → Asegúrate que apunta a tu Android SDK
   → Usualmente: C:\Users\aleja\AppData\Local\Android\Sdk
   ```

✅ **Checkpoint**: Proyecto abierto sin errores de compilación.

---

### 2.2 Tour por Android Studio

Voy a explicarte la interfaz:

```
┌─────────────────────────────────────────────────────────────┐
│  Android Studio                                    [ - □ X ]│
├─────────────────────────────────────────────────────────────┤
│  File  Edit  View  Navigate  Code  Analyze  Build  Run ...  │
├──────┬──────────────────────────────────────────────────────┤
│      │  1. PROJECT PANEL (Izquierda)                        │
│  📁  │  ┌──────────────────────────────────────────┐       │
│ app  │  │  Muestra estructura de archivos          │       │
│  └─  │  │                                           │       │
│  src │  │  2. EDITOR (Centro)                       │       │
│  ├─  │  │  Aquí escribes código                     │       │
│ main │  │                                           │       │
│  └─  │  │                                           │       │
│ java │  │                                           │       │
│      │  └──────────────────────────────────────────┘       │
│      │                                                       │
├──────┴───────────────────────────────────────────────────────┤
│  3. BUILD PANEL (Abajo)                                      │
│  Muestra errores, advertencias, logs                         │
│                                                               │
│  [Build] [Logcat] [Terminal] [TODO] [Problems]              │
└───────────────────────────────────────────────────────────────┘
```

**Paneles Importantes:**

1. **Project Panel (Izquierda)**:
   - Vista de carpetas y archivos
   - Cambia vista con el dropdown arriba (usa "Android" o "Project")

2. **Editor (Centro)**:
   - Donde escribes código
   - Tabs arriba para múltiples archivos abiertos

3. **Build/Logcat Panel (Abajo)**:
   - **Build**: Errores de compilación
   - **Logcat**: Logs de tu app en ejecución (MUY IMPORTANTE)
   - **Terminal**: Terminal integrada
   - **TODO**: Tareas pendientes en código

4. **Toolbar (Arriba)**:
   - 🔨 Build (Martillo): Compila el proyecto
   - ▶️ Run (Play): Ejecuta la app
   - 🐞 Debug: Ejecuta con debugger
   - Dropdown: Selecciona dispositivo (emulador o físico)

---

### 2.3 Entender la Estructura del Proyecto

En el Project Panel, verás esta estructura:

```
OceanGuard/
│
├── app/                          ← Módulo principal de la app
│   │
│   ├── manifests/
│   │   └── AndroidManifest.xml  ← Configuración de la app
│   │                               (permisos, activities, etc.)
│   │
│   ├── java/                     ← Código Kotlin/Java
│   │   └── com.oceanguard.ai/
│   │       ├── OceanGuardApp.kt       ← Clase Application
│   │       ├── inference/
│   │       │   └── OceanGuardInference.kt  ← Motor IA
│   │       ├── utils/
│   │       │   ├── ImagePreprocessor.kt
│   │       │   └── DebrisJsonParser.kt
│   │       ├── data/
│   │       │   └── Models.kt
│   │       └── ui/               ← AQUÍ crearemos las pantallas
│   │           └── MainActivity.kt
│   │
│   ├── res/                      ← Recursos (imágenes, strings, XML)
│   │   ├── values/
│   │   │   ├── strings.xml      ← Textos de la app
│   │   │   ├── colors.xml       ← Colores
│   │   │   └── themes.xml       ← Temas visuales
│   │   ├── drawable/            ← Imágenes, íconos
│   │   └── xml/                 ← Configuraciones XML
│   │
│   └── assets/                   ← Archivos que se incluyen en la app
│       └── models/               ← AQUÍ van los modelos convertidos
│           ├── oceanguard_base.bin      (2-3 GB)
│           └── oceanguard_adapter.bin   (600 MB)
│
├── gradle/                       ← Sistema de build
│   └── libs.versions.toml       ← Versiones de dependencias
│
├── build.gradle.kts             ← Configuración del proyecto
└── settings.gradle.kts          ← Módulos del proyecto
```

**Conceptos Clave:**

- **AndroidManifest.xml**: "Certificado de nacimiento" de tu app
  - Define permisos (cámara, storage, etc.)
  - Lista las "Activities" (pantallas)
  - Configuración general

- **Activities**: Pantallas de tu app
  - `MainActivity.kt` es la pantalla principal
  - Cada pantalla es una Activity

- **res/**: Todos los recursos visuales
  - Separados del código (buena práctica)
  - Android los optimiza automáticamente

- **assets/**: Archivos que se copian tal cual
  - Nuestros modelos de IA van aquí
  - No se optimizan ni comprimen

---

### 2.4 Abrir y Leer un Archivo Kotlin

Vamos a ver el código que ya creé para ti:

1. **Abrir el archivo**:
   ```
   → En Project Panel, navega a:
   app → java → com.oceanguard.ai → OceanGuardApp
   → Doble click en OceanGuardApp.kt
   ```

2. **Leer el código** - Te explico línea por línea:

```kotlin
package com.oceanguard.ai  // ← Organización del código (como carpetas)

import android.app.Application  // ← Importar clase de Android

// Comentarios de documentación (explican qué hace la clase)
/**
 * OceanGuard AI Application Class
 *
 * Inicializa el modelo al arrancar la app
 */
class OceanGuardApp : Application() {  // ← Nuestra clase hereda de Application

    // Companion object = variables/funciones "estáticas" (compartidas)
    companion object {
        private const val TAG = "OceanGuardApp"  // ← Para logs
    }

    // Variable para el motor de inferencia
    lateinit var oceanGuardInference: OceanGuardInference

    // onCreate() se ejecuta cuando la app arranca (UNA VEZ)
    override fun onCreate() {
        super.onCreate()  // ← Siempre llamar a super primero

        // Log.i() = Log de Información (verás esto en Logcat)
        Log.i(TAG, "OceanGuard AI starting...")

        // Crear instancia del motor de IA
        oceanGuardInference = OceanGuardInference(this)

        // Inicializar modelo en background (no bloquea la app)
        applicationScope.launch {
            oceanGuardInference.initialize()
        }
    }
}
```

**Conceptos Kotlin Importantes:**

- **`class`**: Define una clase (plantilla para objetos)
- **`:` (herencia)**: `class Hijo : Padre()` = Hijo hereda de Padre
- **`override`**: Sobreescribir función del padre
- **`lateinit var`**: Variable que se inicializa después (pero garantizamos que antes de usarla)
- **`companion object`**: Como `static` en Java - compartido por todas las instancias
- **`const val`**: Constante (no cambia)
- **`Log.i()`**: Escribir en el log (para debugging)

---

## 📚 Sesión 3: Crear Tu Primera Pantalla (1 hora)

### 3.1 Conceptos: Jetpack Compose

Android tiene 2 formas de crear UI:

1. **XML (Viejo)**: Escribes layouts en archivos XML
2. **Jetpack Compose (Moderno)**: Escribes UI con código Kotlin ✅ Usaremos esto

**¿Por qué Compose?**
- Más fácil y rápido
- Menos código
- UI reactiva (se actualiza sola cuando cambian los datos)
- Es el futuro de Android

**Analogía**: Como React o Flutter si has usado eso.

---

### 3.2 Crear MainActivity

Voy a crear un archivo simple de ejemplo para que veas cómo funciona Compose:

```kotlin
// MainActivity.kt - Tu primera pantalla

package com.oceanguard.ai.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {

    // onCreate() se ejecuta cuando se abre esta pantalla
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // setContent{} = aquí defines la UI con Compose
        setContent {
            // MaterialTheme = tema de Material Design 3
            MaterialTheme {
                // Llamar a nuestra función que dibuja la UI
                MainScreen()
            }
        }
    }
}

// @Composable = función que dibuja UI
@Composable
fun MainScreen() {
    // Surface = contenedor con fondo
    Surface(
        modifier = Modifier.fillMaxSize(),  // ← Ocupa toda la pantalla
        color = MaterialTheme.colorScheme.background  // ← Color de fondo
    ) {
        // Column = layout vertical (como FlexBox column)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),  // ← 16dp de padding
            horizontalAlignment = Alignment.CenterHorizontally,  // ← Centrar horizontal
            verticalArrangement = Arrangement.Center  // ← Centrar vertical
        ) {
            // Text = componente de texto
            Text(
                text = "🌊 OceanGuard AI",
                style = MaterialTheme.typography.headlineLarge
            )

            Spacer(modifier = Modifier.height(24.dp))  // ← Espacio vertical

            Text(
                text = "Marine Debris Detection",
                style = MaterialTheme.typography.bodyLarge
            )

            Spacer(modifier = Modifier.height(48.dp))

            // Button = botón
            Button(
                onClick = {
                    // Aquí va el código cuando se presiona
                    println("¡Botón presionado!")
                }
            ) {
                Text("Start Detection")
            }
        }
    }
}
```

**Conceptos Compose:**

- **`@Composable`**: Función que dibuja UI
- **`Modifier`**: Configura el componente (tamaño, padding, etc.)
- **`Column`**: Layout vertical (hijos uno debajo del otro)
- **`Row`**: Layout horizontal (hijos uno al lado del otro)
- **`Text`**: Muestra texto
- **`Button`**: Botón clickeable
- **`Spacer`**: Espacio vacío
- **`dp`**: Density-independent pixels (se adapta a diferentes pantallas)

---

### 3.3 Ejecutar la App

**Pasos:**

1. **Asegurar que el código esté guardado** (Ctrl+S)

2. **Seleccionar dispositivo**:
   ```
   → En la toolbar, en el dropdown junto al ▶️
   → Selecciona tu emulador "OceanGuard Test Device"
   → Si no está corriendo, Android Studio lo arrancará
   ```

3. **Ejecutar**:
   ```
   → Click en ▶️ (Run) o presiona Shift+F10
   → Verás en el panel Build: "Building..."
   → Espera a que compile (primera vez: 2-5 minutos)
   → La app se instalará en el emulador
   → Se abrirá automáticamente
   ```

4. **Ver tu app**:
   ```
   → Deberías ver tu pantalla con:
     - "🌊 OceanGuard AI"
     - "Marine Debris Detection"
     - Botón "Start Detection"
   ```

5. **Ver los logs**:
   ```
   → Abajo, click en tab "Logcat"
   → En el filtro, busca "OceanGuardApp"
   → Deberías ver: "OceanGuard AI starting..."
   ```

✅ **Checkpoint**: App corriendo en el emulador con tu primera pantalla.

---

## 📚 Sesión 4: Kotlin Básico (1 hora)

Antes de continuar, necesitas entender Kotlin. Aquí están los conceptos esenciales:

### 4.1 Variables

```kotlin
// val = inmutable (no puede cambiar) - preferir siempre que sea posible
val name: String = "OceanGuard"
val count: Int = 42
val price: Double = 19.99

// var = mutable (puede cambiar)
var score: Int = 0
score = 100  // ✅ OK
```

### 4.2 Null Safety

Kotlin previene errores de "null pointer" - uno de los bugs más comunes.

```kotlin
// Tipo normal = NO puede ser null
var name: String = "Juan"
name = null  // ❌ ERROR de compilación

// Tipo nullable (con ?) = SÍ puede ser null
var name: String? = "Juan"
name = null  // ✅ OK

// Safe call (?.) = solo ejecuta si no es null
val length = name?.length  // Si name es null, length será null

// Elvis operator (?:) = valor por defecto si es null
val length = name?.length ?: 0  // Si name es null, length será 0

// !! = asegurar que NO es null (crashea si lo es)
val length = name!!.length  // ⚠️ Usar con cuidado
```

### 4.3 Funciones

```kotlin
// Función simple
fun saludar() {
    println("Hola")
}

// Función con parámetros
fun saludar(nombre: String) {
    println("Hola $nombre")  // ← String interpolation con $
}

// Función con retorno
fun sumar(a: Int, b: Int): Int {
    return a + b
}

// Función con expresión (una sola línea)
fun sumar(a: Int, b: Int): Int = a + b

// Función con parámetros por defecto
fun saludar(nombre: String = "Mundo") {
    println("Hola $nombre")
}
saludar()  // "Hola Mundo"
saludar("Juan")  // "Hola Juan"
```

### 4.4 Clases y Data Classes

```kotlin
// Clase normal
class Persona(val nombre: String, var edad: Int) {
    fun cumplirAños() {
        edad++
    }
}

// Data class = clase para datos (auto-genera equals, hashCode, toString, copy)
data class Debris(
    val tipo: String,
    val confianza: Float,
    val bbox: BoundingBox
)

// Usar
val debris = Debris("Bottle", 0.95f, BoundingBox(10, 20, 30, 40))
println(debris)  // Debris(tipo=Bottle, confianza=0.95, ...)

// Copiar con cambios
val debris2 = debris.copy(confianza = 0.98f)
```

### 4.5 Lambdas (Funciones Anónimas)

```kotlin
// Lambda simple
val suma = { a: Int, b: Int -> a + b }
println(suma(2, 3))  // 5

// Lambda como parámetro
fun procesar(numeros: List<Int>, operacion: (Int) -> Int): List<Int> {
    return numeros.map { operacion(it) }  // 'it' es el parámetro único
}

val dobles = procesar(listOf(1, 2, 3)) { it * 2 }  // [2, 4, 6]

// Común en Compose:
Button(onClick = {
    println("Click!")
}) {
    Text("Press me")
}
```

### 4.6 Collections

```kotlin
// List (inmutable)
val numeros = listOf(1, 2, 3, 4, 5)
println(numeros[0])  // 1
println(numeros.size)  // 5

// MutableList (puede cambiar)
val numeros = mutableListOf(1, 2, 3)
numeros.add(4)  // [1, 2, 3, 4]
numeros.removeAt(0)  // [2, 3, 4]

// Map
val edades = mapOf(
    "Juan" to 25,
    "Ana" to 30
)
println(edades["Juan"])  // 25

// Operaciones útiles
val numeros = listOf(1, 2, 3, 4, 5)
numeros.filter { it > 2 }  // [3, 4, 5]
numeros.map { it * 2 }  // [2, 4, 6, 8, 10]
numeros.first()  // 1
numeros.last()  // 5
numeros.sum()  // 15
```

### 4.7 When (como switch)

```kotlin
fun describir(x: Any) = when (x) {
    1 -> "Uno"
    "Hola" -> "Saludo"
    in 1..10 -> "Entre 1 y 10"
    is String -> "Es un String"
    else -> "Otra cosa"
}
```

---

## 📚 Sesión 5: Estados y Reactividad en Compose (1 hora)

Este es el concepto MÁS IMPORTANTE de Compose.

### 5.1 El Problema que Resuelve State

**Sin Estado (no funciona):**
```kotlin
@Composable
fun Contador() {
    var count = 0  // ❌ NO FUNCIONA - se resetea en cada recomposición

    Button(onClick = { count++ }) {
        Text("Count: $count")
    }
}
```

**Con Estado (funciona):**
```kotlin
@Composable
fun Contador() {
    var count by remember { mutableStateOf(0) }  // ✅ FUNCIONA

    Button(onClick = { count++ }) {
        Text("Count: $count")
    }
}
```

### 5.2 remember y mutableStateOf

```kotlin
@Composable
fun MiComponente() {
    // remember = recordar valor entre recomposiciones
    // mutableStateOf = crear estado observable
    var texto by remember { mutableStateOf("") }

    // Cuando 'texto' cambia, Compose redibuja automáticamente
    TextField(
        value = texto,
        onValueChange = { nuevoTexto ->
            texto = nuevoTexto  // ← Esto dispara recomposición
        }
    )

    Text("Escribiste: $texto")
}
```

### 5.3 Estado para Loading/Error

```kotlin
@Composable
fun PantallaDeteccion() {
    // Estado para loading
    var isLoading by remember { mutableStateOf(false) }

    // Estado para resultado
    var resultado by remember { mutableStateOf<String?>(null) }

    // Estado para error
    var error by remember { mutableStateOf<String?>(null) }

    Column {
        Button(
            onClick = {
                isLoading = true
                error = null

                // Simular detección
                // (en realidad llamarías a oceanGuardInference.detectDebris())
                Thread.sleep(2000)  // Esperar 2 segundos

                isLoading = false
                resultado = "Se detectaron 3 objetos"
            }
        ) {
            Text("Detectar")
        }

        // UI reactiva basada en estado
        when {
            isLoading -> CircularProgressIndicator()
            error != null -> Text("Error: $error", color = Color.Red)
            resultado != null -> Text(resultado!!, color = Color.Green)
        }
    }
}
```

---

## 🎯 Tu Primer Ejercicio Práctico

Voy a guiarte para crear una pantalla funcional paso a paso. ¿Estás listo para empezar?

Te propongo que hagamos lo siguiente:

1. **Primero**: Instalar Java JDK 17 y Android Studio
2. **Segundo**: Abrir el proyecto y hacer el tour
3. **Tercero**: Crear tu primera pantalla simple y ver que funciona
4. **Cuarto**: Agregar un botón que "simule" detección (sin el modelo aún)

Una vez que domines eso, avanzaremos a:
- Agregar selección de imágenes
- Integrar el modelo de IA
- Mostrar resultados bonitos
- Etc.

**¿Quieres que comencemos con la instalación de Java y Android Studio?**

Dame luz verde y te guío paso a paso con instrucciones muy claras. Si encuentras algún error, me lo dices y te ayudo a solucionarlo. 🚀