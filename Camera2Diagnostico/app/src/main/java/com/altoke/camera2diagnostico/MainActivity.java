package com.altoke.camera2diagnostico;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.CamcorderProfile;
import android.media.EncoderProfiles;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Range;
import android.util.Rational;
import android.util.Size;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/**
 * FASE 1: solo consulta metadatos Camera2 y comprueba la apertura de las cámaras.
 * NO captura imágenes, NO graba vídeos y NO certifica que RAW/4K/EIS funcionen.
 */
public class MainActivity extends Activity {
    private static final int REQ_CAMERA = 11;
    private static final int REQ_EXPORT_TXT = 12;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final StringBuilder report = new StringBuilder();
    private TextView output;
    private TextView state;
    private Button openTestButton;
    private CameraManager cameraManager;
    private String[] cameraIds = new String[0];
    private boolean testing = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        buildInterface();
        if (hasCameraPermission()) {
            scan();
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            state.setText("Concede el permiso de cámara para el diagnóstico completo.");
        }
    }

    private void buildInterface() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(16), dp(12), dp(12));
        root.setBackgroundColor(0xFFF8F9FC);

        TextView title = new TextView(this);
        title.setText("Camera2 Diagnóstico | Fase 1");
        title.setTextSize(21);
        title.setTextColor(0xFF17244A);
        title.setPadding(0, 0, 0, dp(8));
        root.addView(title);

        state = new TextView(this);
        state.setText("Preparando diagnóstico...");
        state.setTextSize(13);
        state.setTextColor(0xFF45536B);
        state.setPadding(0, 0, 0, dp(8));
        root.addView(state);

        LinearLayout buttonsA = new LinearLayout(this);
        buttonsA.setOrientation(LinearLayout.HORIZONTAL);
        Button scanButton = button("Analizar");
        scanButton.setOnClickListener(v -> {
            if (!hasCameraPermission()) {
                requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            } else if (!testing) {
                scan();
            }
        });
        buttonsA.addView(scanButton, new LinearLayout.LayoutParams(0, dp(52), 1));
        openTestButton = button("Probar apertura");
        openTestButton.setOnClickListener(v -> startOpenTest());
        buttonsA.addView(openTestButton, new LinearLayout.LayoutParams(0, dp(52), 1));
        root.addView(buttonsA);

        LinearLayout buttonsB = new LinearLayout(this);
        buttonsB.setOrientation(LinearLayout.HORIZONTAL);
        Button saveButton = button("Guardar TXT");
        saveButton.setOnClickListener(v -> saveReport());
        buttonsB.addView(saveButton, new LinearLayout.LayoutParams(0, dp(52), 1));
        Button shareButton = button("Compartir");
        shareButton.setOnClickListener(v -> shareReport());
        buttonsB.addView(shareButton, new LinearLayout.LayoutParams(0, dp(52), 1));
        Button copyButton = button("Copiar");
        copyButton.setOnClickListener(v -> copyReport());
        buttonsB.addView(copyButton, new LinearLayout.LayoutParams(0, dp(52), 1));
        root.addView(buttonsB);

        ScrollView scroll = new ScrollView(this);
        output = new TextView(this);
        output.setTextIsSelectable(true);
        output.setTextSize(13);
        output.setTextColor(0xFF17244A);
        output.setPadding(dp(8), dp(10), dp(8), dp(16));
        scroll.addView(output);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(11);
        b.setAllCaps(false);
        return b;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private boolean hasCameraPermission() {
        return checkSelfPermission(Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA) {
            if (grantResults.length > 0 &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                scan();
            } else {
                state.setText("Permiso no concedido. Pulsa Analizar para volver a solicitarlo.");
            }
        }
    }

    private void line(String key, Object value) {
        report.append(key).append(": ").append(value == null ? "NO INFORMADO" : value)
                .append('\n');
    }

    private void section(String name) {
        report.append("\n========== ").append(name).append(" ==========\n");
    }

    private static String describeHardwareLevel(Integer level) {
        if (level == null) return "NO INFORMADO";
        if (level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) return "LEGACY";
        if (level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED) return "LIMITED";
        if (level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL) return "FULL";
        if (level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3) return "LEVEL_3";
        if (level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL) return "EXTERNAL";
        return "DESCONOCIDO (" + level + ")";
    }

    private static String describeLens(Integer facing) {
        if (facing == null) return "NO INFORMADO";
        if (facing == CameraCharacteristics.LENS_FACING_BACK) return "TRASERA";
        if (facing == CameraCharacteristics.LENS_FACING_FRONT) return "FRONTAL";
        if (facing == CameraCharacteristics.LENS_FACING_EXTERNAL) return "EXTERNA";
        return "DESCONOCIDA (" + facing + ")";
    }

    private static boolean hasCapability(int[] capabilities, int requested) {
        if (capabilities == null) return false;
        for (int value : capabilities) {
            if (value == requested) return true;
        }
        return false;
    }

    private static String sizes(Size[] sizes, int maxCount) {
        if (sizes == null) return "NO ANUNCIADO";
        if (sizes.length == 0) return "NINGUNA";
        Size[] copy = sizes.clone();
        Arrays.sort(copy, (a, b) -> Long.compare(
                (long) b.getWidth() * b.getHeight(),
                (long) a.getWidth() * a.getHeight()));
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < Math.min(maxCount, copy.length); i++) {
            if (i > 0) b.append(", ");
            b.append(copy[i].getWidth()).append('x').append(copy[i].getHeight());
        }
        if (copy.length > maxCount) b.append(" ... (total ").append(copy.length).append(')');
        return b.toString();
    }

    private static String arrayOrUnknown(int[] values) {
        return values == null ? "NO INFORMADO" : Arrays.toString(values);
    }

    private static String arrayOrUnknown(float[] values) {
        return values == null ? "NO INFORMADO" : Arrays.toString(values);
    }

    private void scan() {
        if (testing) return;
        report.setLength(0);
        section("AVISO");
        report.append("Resultado: CARACTERISTICAS ANUNCIADAS POR ANDROID.\n")
                .append("Solo la prueba de apertura indica apertura real.\n")
                .append("RAW, DNG, 4K, FPS, enfoque, EIS y calidad de imagen\n")
                .append("requieren pruebas posteriores con capturas reales.\n");
        section("DISPOSITIVO");
        line("Fecha", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new Date()));
        line("Fabricante", Build.MANUFACTURER);
        line("Marca", Build.BRAND);
        line("Modelo", Build.MODEL);
        line("Android", Build.VERSION.RELEASE);
        line("API", Build.VERSION.SDK_INT);
        if (Build.VERSION.SDK_INT >= 31) {
            line("SoC fabricante", Build.SOC_MANUFACTURER);
            line("SoC modelo", Build.SOC_MODEL);
        } else {
            line("SoC", "No expuesto por la API en este Android");
        }
        line("Hardware", Build.HARDWARE);
        line("Build", Build.DISPLAY);
        line("Resolucion logica", getResources().getDisplayMetrics().widthPixels + "x" +
                getResources().getDisplayMetrics().heightPixels);
        line("Densidad dpi", getResources().getDisplayMetrics().densityDpi);
        try {
            cameraIds = cameraManager.getCameraIdList();
            section("INVENTARIO");
            line("IDs visibles", Arrays.toString(cameraIds));
            line("Total", cameraIds.length);
            for (String id : cameraIds) {
                scanCamera(id);
            }
            scanVideoEncoders();
            report.append("\n=== FIN DEL DIAGNOSTICO ESTATICO ===\n");
            state.setText("Lectura de metadatos terminada. Ahora pulsa 'Probar apertura'.");
        } catch (Exception e) {
            line("ERROR GENERAL", e.getClass().getSimpleName() + ": " + e.getMessage());
            state.setText("Se produjo un error. Guarda el TXT para revisarlo.");
        }
        refresh();
    }

    private void scanCamera(String id) {
        section("CAMARA ID " + id);
        try {
            CameraCharacteristics c = cameraManager.getCameraCharacteristics(id);
            line("Ubicacion", describeLens(c.get(CameraCharacteristics.LENS_FACING)));
            line("Nivel Camera2", describeHardwareLevel(
                    c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)));
            int[] capabilities = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
            line("Capabilities (numeros Android)", arrayOrUnknown(capabilities));
            line("MANUAL_SENSOR", hasCapability(capabilities,
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR));
            line("MANUAL_POST_PROCESSING", hasCapability(capabilities,
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING));
            line("RAW capacidad", hasCapability(capabilities,
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW));
            line("BURST_CAPTURE", hasCapability(capabilities,
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE));
            line("LOGICAL_MULTI_CAMERA", hasCapability(capabilities,
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA));
            line("DEPTH_OUTPUT", hasCapability(capabilities,
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT));
            line("DYNAMIC_RANGE_TEN_BIT", hasCapability(capabilities,
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT));
            if (Build.VERSION.SDK_INT >= 28) {
                line("IDs fisicos visibles por Camera2", c.getPhysicalCameraIds().toString());
            } else {
                line("IDs fisicos visibles por Camera2", "Requiere API 28+");
            }

            section("CAMARA " + id + " / SENSOR Y CONTROLES");
            line("Sensor pixel array", c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE));
            line("Sensor area activa", c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE));
            line("Sensor tamano fisico (mm)", c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE));
            line("Rango ISO", c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE));
            line("Rango exposicion (ns)", c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE));
            line("Duracion maxima de cuadro (ns)",
                    c.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION));
            line("Distancia focal (mm)", arrayOrUnknown(
                    c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)));
            Float focusMin = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
            line("Min focus distance (dioptrias)", focusMin);
            if (focusMin != null && focusMin == 0.0f) {
                line("Interpretacion foco", "OPTICA DE FOCO FIJO; control manual no util");
            }
            line("Modos AF (codigos)", arrayOrUnknown(
                    c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)));
            line("Modos AE (codigos)", arrayOrUnknown(
                    c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)));
            line("Modos AWB (codigos)", arrayOrUnknown(
                    c.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)));
            line("Rango compensacion AE", c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE));
            Rational comp = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
            line("Paso compensacion AE (EV)", comp == null ? null : comp.doubleValue());
            Range<Integer>[] fps = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            line("Rangos FPS AE", fps == null ? "NO INFORMADO" : Arrays.toString(fps));
            line("Flash presente", c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE));
            line("Modos estabilizacion video (0=OFF,1=ON, 2=PREVIEW)",
                    arrayOrUnknown(c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)));
            line("Modos OIS (0=OFF,1=ON)", arrayOrUnknown(
                    c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)));
            line("Zoom digital maximo", c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM));

            section("CAMARA " + id + " / SALIDAS CAMERA2");
            StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                line("StreamConfigurationMap", "NO INFORMADO");
            } else {
                line("JPEG (10 mayores)", sizes(map.getOutputSizes(ImageFormat.JPEG), 10));
                line("YUV_420_888 (10 mayores)",
                        sizes(map.getOutputSizes(ImageFormat.YUV_420_888), 10));
                Size[] raw = map.getOutputSizes(ImageFormat.RAW_SENSOR);
                line("RAW_SENSOR", sizes(raw, 10));
                line("MediaRecorder (15 mayores)",
                        sizes(map.getOutputSizes(MediaRecorder.class), 15));
                try {
                    line("JPEG HIGH_RES (10 mayores)",
                            sizes(map.getHighResolutionOutputSizes(ImageFormat.JPEG), 10));
                } catch (RuntimeException e) {
                    line("JPEG HIGH_RES", "No disponible: " + e.getClass().getSimpleName());
                }
                boolean rawCapability = hasCapability(capabilities,
                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW);
                line("RAW con capacidad y flujo anunciado", rawCapability && raw != null && raw.length > 0);
                line("NOTA", "Este resultado NO confirma un DNG capturado correctamente.");
            }
            scanVideoProfiles(id);
        } catch (Exception e) {
            line("ERROR CAMARA " + id,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void scanVideoProfiles(String cameraId) {
        section("CAMARA " + cameraId + " / PERFILES DE GRABACION");
        int[] qualities = {
                CamcorderProfile.QUALITY_2160P, CamcorderProfile.QUALITY_1080P,
                CamcorderProfile.QUALITY_720P, CamcorderProfile.QUALITY_480P,
                CamcorderProfile.QUALITY_HIGH, CamcorderProfile.QUALITY_LOW
        };
        String[] labels = {"2160p", "1080p", "720p", "480p", "HIGH", "LOW"};
        int numericId;
        try {
            numericId = Integer.parseInt(cameraId);
        } catch (NumberFormatException e) {
            line("Nota perfiles", "ID no numerico: no se consulta hasProfile(int, quality)");
            return;
        }
        for (int i = 0; i < qualities.length; i++) {
            try {
                boolean has = CamcorderProfile.hasProfile(numericId, qualities[i]);
                line("Perfil " + labels[i], has ? "ANUNCIADO" : "NO ANUNCIADO");
                if (has && Build.VERSION.SDK_INT >= 31) {
                    EncoderProfiles profiles = CamcorderProfile.getAll(cameraId, qualities[i]);
                    if (profiles != null) {
                        for (EncoderProfiles.VideoProfile video : profiles.getVideoProfiles()) {
                            line("   video " + labels[i],
                                    video.getWidth() + "x" + video.getHeight() +
                                            " @" + video.getFrameRate() + "fps" +
                                            " codec=" + video.getMediaType() +
                                            " bitrate=" + video.getBitrate() +
                                            " hdr=" + video.getHdrFormat());
                        }
                    }
                }
            } catch (RuntimeException e) {
                line("Perfil " + labels[i], "ERROR " + e.getClass().getSimpleName());
            }
        }
        line("NOTA", "Un perfil no garantiza grabacion estable ni FPS sostenidos.");
    }

    private void scanVideoEncoders() {
        section("ENCODERS VIDEO DEL DISPOSITIVO");
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            for (MediaCodecInfo info : list.getCodecInfos()) {
                if (!info.isEncoder()) continue;
                for (String type : info.getSupportedTypes()) {
                    if (!type.equalsIgnoreCase("video/avc") &&
                            !type.equalsIgnoreCase("video/hevc")) continue;
                    try {
                        MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(type);
                        MediaCodecInfo.VideoCapabilities vc = caps.getVideoCapabilities();
                        if (vc == null) continue;
                        line("Encoder", info.getName() + " / " + type +
                                " / 1080p30=" + vc.areSizeAndRateSupported(1920, 1080, 30) +
                                " / 4K30=" + vc.areSizeAndRateSupported(3840, 2160, 30));
                    } catch (RuntimeException e) {
                        line("Encoder " + info.getName(), "ERROR " + e.getClass().getSimpleName());
                    }
                }
            }
        } catch (RuntimeException e) {
            line("ERROR ENCODERS", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        line("NOTA", "Un encoder 4K no demuestra que la camara pueda alimentarlo en 4K.");
    }

    private void startOpenTest() {
        if (testing) return;
        if (!hasCameraPermission()) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            return;
        }
        if (cameraIds.length == 0) {
            scan();
        }
        if (cameraIds.length == 0) {
            state.setText("No hay cámaras enumeradas; revisa el informe.");
            return;
        }
        testing = true;
        openTestButton.setEnabled(false);
        section("PRUEBA DE APERTURA REAL (SIN CAPTURAR)");
        refresh();
        testOpenAt(0);
    }

    private void testOpenAt(int index) {
        if (index >= cameraIds.length) {
            testing = false;
            openTestButton.setEnabled(true);
            state.setText("Prueba de apertura finalizada. Guarda o comparte el TXT.");
            line("Estado prueba apertura", "FINALIZADA");
            refresh();
            return;
        }
        final String id = cameraIds[index];
        state.setText("Comprobando apertura de cámara " + id + "...");
        try {
            cameraManager.openCamera(id, new CameraDevice.StateCallback() {
                private boolean handled = false;

                private void complete(CameraDevice camera, String status) {
                    if (handled) return;
                    handled = true;
                    if (camera != null) camera.close();
                    line("Apertura camara " + id, status);
                    refresh();
                    mainHandler.postDelayed(() -> testOpenAt(index + 1), 400);
                }

                @Override
                public void onOpened(CameraDevice camera) {
                    complete(camera, "CORRECTA: onOpened recibido");
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    complete(camera, "DESCONECTADA: onDisconnected recibido");
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    complete(camera, "ERROR DE APERTURA, codigo CameraDevice=" + error);
                }
            }, mainHandler);
        } catch (CameraAccessException | SecurityException | IllegalArgumentException e) {
            line("Apertura camara " + id,
                    "EXCEPCION " + e.getClass().getSimpleName() + ": " + e.getMessage());
            refresh();
            mainHandler.postDelayed(() -> testOpenAt(index + 1), 400);
        }
    }

    private void refresh() {
        output.setText(report.toString());
    }

    private void copyReport() {
        if (report.length() == 0) {
            Toast.makeText(this, "No hay informe todavía", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("Camera2 Report", report.toString()));
        Toast.makeText(this, "Informe copiado", Toast.LENGTH_SHORT).show();
    }

    private void shareReport() {
        if (report.length() == 0) return;
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, "Diagnostico Camera2 " + Build.MODEL);
        send.putExtra(Intent.EXTRA_TEXT, report.toString());
        startActivity(Intent.createChooser(send, "Compartir diagnostico"));
    }

    private void saveReport() {
        if (report.length() == 0) return;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, "Camera2_" + Build.MODEL + "_fase1.txt");
        startActivityForResult(intent, REQ_EXPORT_TXT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_EXPORT_TXT && resultCode == RESULT_OK &&
                data != null && data.getData() != null) {
            try (OutputStream stream = getContentResolver().openOutputStream(data.getData())) {
                if (stream == null) throw new IllegalStateException("No se obtuvo flujo de escritura");
                stream.write(report.toString().getBytes(StandardCharsets.UTF_8));
                stream.flush();
                Toast.makeText(this, "Informe TXT guardado", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, "No se pudo guardar: " + e.getMessage(),
                        Toast.LENGTH_LONG).show();
            }
        }
    }
}
