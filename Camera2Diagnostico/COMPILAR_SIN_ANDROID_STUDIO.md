# Compilar el APK SIN Android Studio — GitHub Actions

Este proyecto no necesita Android Studio en tu computadora para compilarse. GitHub Actions instalará Java 17, Gradle 8.9 y el SDK de Android automáticamente en un servidor y ejecutará la tarea `:app:assembleDebug`.

## Requisitos

- Cuenta gratuita de GitHub (https://github.com/).
- Acceso a internet.
- Un repositorio propio donde subir este código.

## Pasos

1. Extrae **Camera2Diagnostico_Fase1_GitHub_Actions.zip** en tu computadora. Dentro verás la carpeta `Camera2Diagnostico`.
2. Crea un repositorio **privado** nuevo en GitHub. Asegúrate de que su rama principal se llame `main`. Si te ofrece crear un README inicial, puedes omitirlo.
3. En la raíz del repositorio sube **el CONTENIDO INTERIOR** de la carpeta `Camera2Diagnostico`, no la carpeta contenedora. Es importante que `app/build.gradle`, `settings.gradle` y `.github/workflows/compilar-apk.yml` queden en la raíz indicada por sus respectivas rutas.
   - GitHub permite subir archivos desde **Add file > Upload files**, aunque algunos navegadores no conservan toda la estructura de directorios al arrastrar una carpeta. Comprueba las rutas que se muestran antes de confirmar; si es necesario usa Git en el ordenador.
   - La carpeta `.github` puede estar oculta en algunos exploradores. Asegúrate de subir también `.github/workflows/compilar-apk.yml`.
4. Ve a **Actions** en el repositorio. Abre **Compilar APK diagnostico Camera2**.
5. Si aún no se ejecutó automáticamente tras el `push`, pulsa **Run workflow** y confirma.
6. Espera a que el proceso termine con marca verde. Una marca roja significa que hubo un error y debes compartir el registro de compilación para corregirlo.
7. Abre la ejecución terminada. Al final de la página, en **Artifacts**, descarga `Camera2Diagnostico-Fase1-APK`. GitHub entregará un ZIP con `app-debug.apk` adentro.
8. Extrae `app-debug.apk` e instálalo en tu Infinix desde su gestor de archivos (Android puede pedir permiso para instalar apps desde esa fuente).
9. En el Infinix, abre Camera2 Diagnóstico, concede el permiso de cámara, toca **Analizar**, después **Probar apertura**, y finalmente **Guardar TXT**.

## Alcance y límites

- El APK producido será una **versión de diagnóstico**; no incluye aún captura de fotos ni grabación de video.
- No está compilado ni probado al generar esta documentación. El resultado dependerá de la primera ejecución real de GitHub Actions.
- GitHub Actions genera un APK `debug`, firmado por Android Gradle Plugin con una clave de depuración autogenerada para instalar en el móvil, NO una versión final de producción.
- Para nuevas versiones convendrá gestionar una clave de firma estable y un flujo de publicación separado.
