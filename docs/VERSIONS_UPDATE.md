# 📦 Actualización de Versiones - Diciembre 2025

Este documento explica las versiones que estamos usando actualmente vs las últimas disponibles.

---

## 🎯 Versiones Actuales (Estables y Probadas)

### Decisión: Usar Versiones Estables

**Razón**: Priorizamos **estabilidad** sobre tener lo más reciente. Las versiones elegidas:
- ✅ Tienen meses de uso en producción
- ✅ Bugs conocidos ya fueron corregidos
- ✅ Compatible entre sí (probado)
- ✅ Documentación abundante

---

## 📊 Comparación de Versiones

### Build Tools

| Componente | Actual (Usando) | Última Disponible | ¿Actualizar? |
|-----------|-----------------|-------------------|--------------|
| Android Gradle Plugin | **8.7.3** | 8.13 (Nov 2025) | ⚠️ No aún |
| Gradle | **8.7.3** | 8.13 (Nov 2025) | ⚠️ No aún |
| Kotlin | **2.1.0** | 2.2.21 (Nov 2025) | ⚠️ No aún |
| JDK | **17** | 17 (LTS, actual) | ✅ OK |

**Nota AGP/Gradle**:
- AGP 8.7.3 es la última versión estable probada con Kotlin 2.1.0
- AGP 8.13+ puede tener incompatibilidades no resueltas
- Actualizaremos a AGP 8.13 cuando Kotlin 2.2.x sea estable en Android

**Nota Kotlin**:
- Kotlin 2.1.0 es estable y muy probado
- Kotlin 2.2.x es muy reciente (Noviembre 2025)
- Esperaremos 2-3 meses para adopción masiva

### Jetpack Compose

| Componente | Actual (Usando) | Última Disponible | ¿Actualizar? |
|-----------|-----------------|-------------------|--------------|
| Compose BOM | **2024.12.01** | 2025.08.00 (Ago 2025) | ⚠️ No aún |
| Compose Compiler | **Auto (2.1.0)** | Auto (2.2.21) | ⚠️ Con Kotlin |

**Nota Compose BOM**:
- 2024.12.01 es extremadamente estable
- 2025.08.00 incluye nuevas features pero puede tener bugs
- **Recomendación**: Mantener 2024.12.01 hasta Febrero 2026

### AndroidX Core

| Componente | Actual (Usando) | Última Disponible | ¿Actualizar? |
|-----------|-----------------|-------------------|--------------|
| core-ktx | **1.15.0** | 1.17.0 (Nov 2025) | ✅ Sí |
| lifecycle-runtime-ktx | **2.8.7** | 2.10.0 (Nov 2025) | ✅ Sí |
| activity-compose | **1.9.3** | 1.12.0 (Oct 2025) | ✅ Sí |

**Actualizar**: Estas son actualizaciones seguras y retrocompatibles.

### Databases & Navigation

| Componente | Actual (Usando) | Última Disponible | ¿Actualizar? |
|-----------|-----------------|-------------------|--------------|
| Room | **2.6.1** | 2.8.4 (Nov 19, 2025) | ✅ Sí |
| Navigation Compose | **2.8.5** | 2.9.0-rc01 | ⚠️ No (RC) |
| Navigation3 | N/A | 1.0.0 (Nov 19, 2025) | ⚠️ Considerar |

**Nota Navigation**:
- Navigation 2.9.0 aún es Release Candidate
- Navigation3 1.0.0 es estable pero es una reescritura completa
- **Recomendación**: Mantener 2.8.5 ahora, migrar a Navigation3 en v2.0 de la app

### Camera & Media

| Componente | Actual (Usando) | Última Disponible | ¿Actualizar? |
|-----------|-----------------|-------------------|--------------|
| CameraX | **1.4.1** | 1.5.1 (Oct 2025) | ✅ Sí |
| MediaPipe GenAI | **0.10.24** | 0.10.27 (Nov 2025) | ✅ Sí |

**Actualizar**: Mejoras de rendimiento y correcciones de bugs.

### Coroutines

| Componente | Actual (Usando) | Última Disponible | ¿Actualizar? |
|-----------|-----------------|-------------------|--------------|
| Coroutines | **1.10.1** | 1.10.2 (companion Kotlin 2.1) | ✅ Sí |

---

## 🔄 Plan de Actualización

### Fase 1: Actualizaciones Seguras (AHORA)

Actualizar estas versiones es seguro y recomendado:

```kotlin
// En app/build.gradle.kts

dependencies {
    // AndroidX Core - ACTUALIZAR
    implementation("androidx.core:core-ktx:1.17.0")  // Was 1.15.0
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")  // Was 2.8.7
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")  // Was 2.8.7
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")  // Was 2.8.7
    implementation("androidx.activity:activity-compose:1.12.0")  // Was 1.9.3

    // Room Database - ACTUALIZAR
    implementation("androidx.room:room-runtime:2.8.4")  // Was 2.6.1
    implementation("androidx.room:room-ktx:2.8.4")  // Was 2.6.1
    ksp("androidx.room:room-compiler:2.8.4")  // Was 2.6.1

    // CameraX - ACTUALIZAR
    implementation("androidx.camera:camera-core:1.5.1")  // Was 1.4.1
    implementation("androidx.camera:camera-camera2:1.5.1")  // Was 1.4.1
    implementation("androidx.camera:camera-lifecycle:1.5.1")  // Was 1.4.1
    implementation("androidx.camera:camera-view:1.5.1")  // Was 1.4.1

    // Coroutines - ACTUALIZAR
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")  // Was 1.10.1

    // MediaPipe GenAI - ACTUALIZAR
    implementation("com.google.mediapipe:tasks-genai:0.10.27")  // Was 0.10.24
}
```

### Fase 2: Actualizaciones Medianas (Febrero 2026)

Cuando estas versiones sean más estables:

```kotlin
// En build.gradle.kts (root)
plugins {
    id("com.android.application") version "8.13" apply false  // De 8.7.3
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false  // De 2.1.0
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false  // De 2.1.0
}

// En app/build.gradle.kts
dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2025.08.00")  // De 2024.12.01
}
```

### Fase 3: Grandes Cambios (v2.0 de la app)

Migraciones que requieren cambios de código:

- **Navigation3**: Reescritura completa del sistema de navegación
- **AGP 9.0**: Breaking changes importantes
- **Kotlin 2.3+**: Nuevas features del lenguaje

---

## 🛡️ Política de Actualización

### Reglas de Oro

1. **Nunca actualizar todo a la vez**
   - Actualiza una dependencia a la vez
   - Prueba exhaustivamente después de cada actualización
   - Commit después de cada actualización exitosa

2. **Esperar 2-3 meses para versiones mayores**
   - Ejemplo: Kotlin 2.2.21 salió en Nov 2025 → Actualizar en Feb 2026
   - Razón: Bugs críticos se descubren en las primeras semanas

3. **Priorizar estabilidad sobre features**
   - Features nuevas son atractivas, pero bugs en producción son costosos
   - Usa versiones "boring" que funcionan

4. **Leer CHANGELOG antes de actualizar**
   - Breaking changes
   - Deprecations
   - Bugs conocidos

5. **Testing completo post-actualización**
   - Unit tests
   - Integration tests
   - Manual testing en dispositivos reales

### Cuándo Actualizar de Inmediato

Actualiza **SIN esperar** si:
- ✅ Hay un **security patch** crítico
- ✅ Hay un **bug fix** que afecta tu app
- ✅ Necesitas una **feature específica** nueva
- ✅ Es una actualización **patch** (x.y.Z)

### Cuándo NO Actualizar

NO actualices si:
- ❌ Es versión **beta** o **RC** (excepto para testing)
- ❌ Salió hace **menos de 1 mes** (versión mayor)
- ❌ No hay **necesidad real** de actualizar
- ❌ No tienes **tiempo para testing** adecuado

---

## 📝 Checklist de Actualización

Antes de actualizar cualquier dependencia:

### Pre-Actualización
- [ ] Leer CHANGELOG completo
- [ ] Verificar breaking changes
- [ ] Buscar "known issues" en issue tracker
- [ ] Crear branch de Git separado
- [ ] Backup de proyecto funcionando

### Durante Actualización
- [ ] Actualizar UNA dependencia a la vez
- [ ] Sync Gradle y verificar sin errores
- [ ] Build exitoso
- [ ] Ejecutar todos los tests
- [ ] Testing manual en emulador
- [ ] Testing manual en dispositivo físico

### Post-Actualización
- [ ] Verificar performance (no empeoró)
- [ ] Verificar uso de memoria (no aumentó significativamente)
- [ ] Verificar tamaño de APK (no creció mucho)
- [ ] Documentar cambios en commit
- [ ] Merge a branch principal
- [ ] Notificar al equipo

---

## 🎯 Actualización Recomendada AHORA

Para actualizar ahora mismo de forma segura:

```powershell
# 1. Crear branch para actualizaciones
git checkout -b feature/update-dependencies

# 2. Abrir Android Studio

# 3. Editar mobile/android/app/build.gradle.kts
# Cambiar las versiones según "Fase 1" arriba

# 4. Sync Gradle
# File → Sync Project with Gradle Files

# 5. Build
# Build → Make Project

# 6. Testing
# Run → Run 'app'

# 7. Si todo funciona:
git add .
git commit -m "chore: update stable dependencies to latest safe versions

- core-ktx: 1.15.0 → 1.17.0
- lifecycle: 2.8.7 → 2.10.0
- activity-compose: 1.9.3 → 1.12.0
- room: 2.6.1 → 2.8.4
- camerax: 1.4.1 → 1.5.1
- coroutines: 1.10.1 → 1.10.2
- mediapipe-genai: 0.10.24 → 0.10.27"

git push origin feature/update-dependencies

# 8. Crear Pull Request para review
```

---

## 🔗 Referencias

### Release Notes Oficiales

- [AGP Releases](https://developer.android.com/build/releases/gradle-plugin)
- [Kotlin Releases](https://kotlinlang.org/docs/releases.html)
- [Compose BOM](https://developer.android.com/jetpack/androidx/releases/compose)
- [AndroidX Releases](https://developer.android.com/jetpack/androidx/versions)
- [MediaPipe Releases](https://github.com/google-ai-edge/mediapipe/releases)

### Compatibility Guides

- [Kotlin-AGP Compatibility](https://developer.android.com/build/kotlin-support)
- [Compose-Kotlin Compatibility](https://developer.android.com/jetpack/androidx/releases/compose-kotlin)
- [Gradle Compatibility](https://docs.gradle.org/current/userguide/compatibility.html)

---

## ✅ Resumen

### Usar AHORA (Estable)
- AGP 8.7.3
- Kotlin 2.1.0
- Compose BOM 2024.12.01
- AndroidX versiones actualizadas (core-ktx 1.17.0, etc.)
- MediaPipe 0.10.27

### Actualizar en Febrero 2026
- AGP 8.13 / 9.0
- Kotlin 2.2.21 / 2.3.0
- Compose BOM 2025.08.00+

### Considerar para v2.0 de la app
- Navigation3
- Nuevas arquitecturas de Compose
- Kotlin Multiplatform (si quieres iOS)

**Filosofía**: "If it ain't broke, don't fix it" 🛡️

Pero **sí actualiza** patches de seguridad y bug fixes importantes.
