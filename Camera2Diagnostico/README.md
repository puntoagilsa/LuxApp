# Camera2 Diagnóstico — Fase 1

## Objetivo

Aplicación Android en Java que obtiene los metadatos Camera2 directamente del dispositivo, enumera todas las cámaras visibles, consulta rangos de ISO y exposición, opciones de enfoque, RAW, tamaños de salida, estabilización, perfiles de grabación y codificadores de video. También hace una **prueba real de apertura y cierre** de cada cámara (sin hacer fotos ni grabar).

Este proyecto **no es la aplicación de fotografía** ni afirma que el dispositivo haya superado pruebas de captura RAW, estabilización o 4K. El archivo TXT contiene tanto datos declarados por el sistema como los resultados de apertura real, cada uno identificado.

## Requisitos

- Android Studio con SDK Platform Android 14 / API 34 instalado.
- JDK 17 para Gradle.
- Plugin Android Gradle 8.7.3 con Gradle 8.9.
- Dispositivo Android API >= 26. Objetivo prioritario: Infinix X678B Android 14 / API 34.
- Cable USB con transferencia de datos o depuración inalámbrica.

## Creación del proyecto en Android Studio (genera el Gradle Wrapper)

Para evitar distribuir un archivo JAR del Gradle Wrapper que no podemos verificar en este entorno, **crea una vez un proyecto nuevo** y conserva los archivos `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar` y `gradle/wrapper/gradle-wrapper.properties` que genera Android Studio.

1. Abre **Android Studio > New Project > Empty Views Activity** (no Compose).
2. **Name:** `Camera2Diagnostico`.
3. **Package name:** `com.altoke.camera2diagnostico`.
4. **Language:** `Java`.
5. **Minimum SDK:** `API 26` (Android 8.0).
6. Crea el proyecto.
7. En el proyecto recién generado, sustituye los archivos por **los archivos completos con la misma ruta relativa** contenidos en este paquete:
   - `settings.gradle`
   - `build.gradle`
   - `gradle.properties`
   - `app/build.gradle`
   - `app/src/main/AndroidManifest.xml`
   - `app/src/main/java/com/altoke/camera2diagnostico/MainActivity.java`
8. Si el asistente generó `settings.gradle.kts`, `build.gradle.kts` o `app/build.gradle.kts`, elimina esos **tres archivos `.kts`** antes de poner sus correspondientes archivos `.gradle` para no duplicar la configuración.
9. Conserva los cuatro archivos del wrapper generados por Android Studio. En `gradle/wrapper/gradle-wrapper.properties`, comprueba que `distributionUrl` termine en `gradle-8.9-bin.zip`; si no, edita la línea para que sea exactamente:

   ```properties
   distributionUrl=https\://services.gradle.org/distributions/gradle-8.9-bin.zip
   ```

10. Instala el SDK Platform API 34 en **Tools > SDK Manager** si falta.
11. Selecciona **JDK 17** en **Settings > Build, Execution, Deployment > Build Tools > Gradle > Gradle JDK**.
12. Pulsa **Sync Project with Gradle Files**.

No se requiere AndroidX, bibliotecas de terceros ni permisos de almacenamiento.

## Cómo compilar y ejecutar

### Windows (Terminal de Android Studio, ubicada en la raíz del proyecto)

```powershell
.\gradlew.bat :app:assembleDebug
```

Resultado esperado **si la compilación finaliza con éxito**:

`app\build\outputs\apk\debug\app-debug.apk`

No se ha ejecutado esta compilación en el entorno de entrega, ya que no hay Android SDK ni Gradle disponibles.

### Android Studio

1. Conecta el teléfono y activa **Ajustes > Acerca del teléfono > Número de compilación** varias veces (si es necesario para habilitar las Opciones de desarrollador).
2. Activa **Opciones de desarrollador > Depuración USB**.
3. Autoriza la huella RSA del equipo en la pantalla del teléfono.
4. Selecciona el dispositivo en la barra de Android Studio y pulsa **Run**.
5. Autoriza el permiso de cámara cuando se solicite.
6. Pulsa **Analizar** y después **Probar apertura**.
7. Pulsa **Guardar TXT** y elige una carpeta (por ejemplo, Descargas).
8. Envía el archivo `Camera2_X678B_fase1.txt` para revisar juntos los resultados antes de la siguiente fase.

## Qué verifica

| Prueba | Tipo |
|---|---|
| Nivel Camera2, ID, dirección, modos AF/AE/AWB | Metadatos declarados |
| RAW capability + salida RAW_SENSOR | Metadatos declarados |
| ISO, exposición, distancia focal y distancia mínima de enfoque | Metadatos declarados |
| OIS y EIS disponibles | Metadatos declarados |
| JPEG, YUV y MediaRecorder outputs | Metadatos declarados |
| Perfiles 480p/720p/1080p/2160p y codificadores AVC/HEVC | Metadatos/perfiles declarados |
| Apertura y cierre de CameraDevice para cada ID | Prueba funcional real |
| Captura RAW válida / DNG | PENDIENTE |
| Grabación 1080p/4K y FPS reales | PENDIENTE |
| Enfoque continuo, EIS, balance, JPEG y desempeño | PENDIENTE |

## Interpretación

- Si el nivel es FULL, **hay APIs avanzadas disponibles**, pero eso no prueba resultados fotográficos superiores a la cámara original.
- Si RAW capability es verdadero pero `RAW_SENSOR` no anuncia tamaños, **no se debe asumir RAW utilizable**.
- Si una cámara frontal tiene distancia mínima de enfoque 0.0 dioptrías, su óptica es de foco fijo, aunque otras herramientas marquen controles manuales genéricos.
- Si `MediaRecorder` indica 3840x2160, todavía falta un perfil de video y un codificador compatible; incluso si existen, **solo una grabación real** demuestra funcionamiento.
- El indicador de compatibilidad del codificador 4K30 es una consulta independiente del flujo de video de la cámara.
- Una prueba de apertura que falla por cámara ocupada no significa necesariamente fallo de hardware; cerrar otras aplicaciones y repetir.

## Estructura

```text
Camera2Diagnostico/
├── README.md
├── settings.gradle
├── build.gradle
├── gradle.properties
├── .gitignore
└── app/
    ├── build.gradle
    └── src/main/
        ├── AndroidManifest.xml
        └── java/com/altoke/camera2diagnostico/MainActivity.java
```

Los cuatro archivos del Gradle Wrapper se conservan del proyecto creado por Android Studio. No se han omitido fragmentos dentro de los archivos proporcionados.

## Registro de decisiones técnicas

- **Java puro + API de Android:** sin bibliotecas externas, evita dependencia de CameraX/Compose en el diagnóstico.
- **compileSdk/targetSdk 34:** centrado en Android 14.
- **AGP 8.7.3 / Gradle 8.9 / JDK 17:** conjunto coherente.
- **Storage Access Framework:** exporta TXT con `ACTION_CREATE_DOCUMENT`, sin solicitar permiso de almacenamiento.
- **No apertura permanente:** solo se abre y cierra cada `CameraDevice`, sin sesión de captura.
- **Sin correcciones del fabricante:** el programa reporta metadatos que puede inspeccionar; no se presentan como pruebas de calidad.
