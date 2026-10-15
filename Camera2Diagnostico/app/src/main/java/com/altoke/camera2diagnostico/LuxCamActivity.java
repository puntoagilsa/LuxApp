package com.altoke.camera2diagnostico;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.CamcorderProfile;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Range;
import android.util.Rational;
import android.util.Size;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * LuxCam Fase 2: prueba funcional de fotografia JPEG y video AVC 1080p.
 * Sin librerias externas. La correccion de exposicion funciona desde Camera2,
 * no modifica ni recompone artificialmente pixeles capturados.
 * Las salidas de la camara se deben validar en el Infinix X678B.
 */
public final class LuxCamActivity extends Activity {
    private static final int CAMERA_PERMISSION = 101;
    private static final int MICROPHONE_PERMISSION = 102;
    private static final int WHITE = Color.rgb(246, 249, 252);
    private static final int ACCENT = Color.rgb(144, 220, 242);
    private static final int PANEL = Color.rgb(13, 19, 29);
    private static final String PICTURES_FOLDER = "Pictures/LuxCam";
    private static final String VIDEOS_FOLDER = "Movies/LuxCam";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Semaphore cameraLock = new Semaphore(1);
    private final AtomicBoolean openingCamera = new AtomicBoolean(false);
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private TextureView textureView;
    private GridView gridView;
    private TextView statusText;
    private TextView evLabel;
    private TextView headingSub;
    private TextView timerText;
    private TextView resolutionLabel;
    private Button photoModeButton;
    private Button videoModeButton;
    private Button shutterButton;
    private Button protectButton;
    private Button cameraButton;
    private Button gridButton;
    private SeekBar evBar;

    private CameraDevice device;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder repeatingBuilder;
    private ImageReader jpegReader;
    private Surface previewSurface;
    private CameraCharacteristics characteristics;
    private Size previewSize;
    private final Size videoSize = new Size(1920, 1080);
    private String cameraId;
    private String backId;
    private String frontId;
    private int cameraFacing = CameraCharacteristics.LENS_FACING_BACK;
    private int sensorOrientation = 90;
    private boolean sessionReady;
    private boolean recording;
    private boolean startingRecording;
    private boolean takingPhoto;
    private boolean videoMode;
    private boolean highlightGuard;
    private float manualEv;
    private boolean paused;
    private int generation;
    private long recordStartMs;
    private Range<Integer> evRange = new Range<>(-20, 20);
    private float evStep = .1f;
    private int maxAfRegions;
    private int maxAeRegions;
    private Rect activeArray;
    private MeteringRectangle[] afRegions;
    private MeteringRectangle[] aeRegions;
    private MediaRecorder recorder;
    private ParcelFileDescriptor videoFd;
    private Uri videoUri;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(PANEL);
        getWindow().setNavigationBarColor(PANEL);
        getWindow().getDecorView().setSystemUiVisibility(0);
        buildInterface();
    }

    @Override protected void onResume() {
        super.onResume();
        paused = false;
        cameraThread = new HandlerThread("LuxCamCameraThread");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        if (textureView.isAvailable()) requestOrOpenCamera();
        else textureView.setSurfaceTextureListener(surfaceListener);
    }

    @Override protected void onPause() {
        paused = true;
        generation++;
        if (recording || startingRecording) stopRecording(false);
        closeCamera();
        if (cameraThread != null) {
            cameraThread.quitSafely();
            try { cameraThread.join(1200); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            cameraThread = null;
            cameraHandler = null;
        }
        super.onPause();
    }

    private int dp(float n) { return Math.round(getResources().getDisplayMetrics().density * n); }

    private GradientDrawable background(int color, int radius, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        if (stroke != Color.TRANSPARENT) d.setStroke(dp(1), stroke);
        return d;
    }

    private Button action(String text, int background, int foreground, int radius) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(text);
        b.setTextColor(foreground);
        b.setTextSize(13);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setBackground(background(background, radius, Color.TRANSPARENT));
        b.setPadding(dp(14), 0, dp(14), 0);
        b.setMinimumHeight(0); b.setMinHeight(0);
        b.setMinimumWidth(0); b.setMinWidth(0);
        return b;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value); t.setTextSize(sp); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private LinearLayout vertical() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        return v;
    }

    private LinearLayout horizontal() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.HORIZONTAL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        return v;
    }

    private LinearLayout.LayoutParams rowWeight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private void buildInterface() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(PANEL);
        textureView = new TextureView(this);
        root.addView(textureView, new FrameLayout.LayoutParams(-1, -1));
        textureView.setSurfaceTextureListener(surfaceListener);
        textureView.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                touchMetering(event.getX(), event.getY());
                return true;
            }
            return true;
        });

        gridView = new GridView();
        gridView.setVisibility(View.GONE);
        root.addView(gridView, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout top = vertical();
        top.setPadding(dp(19), dp(13), dp(19), dp(12));
        GradientDrawable topFade = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xEB080E18, 0xB5080E18, 0x00080E18});
        top.setBackground(topFade);
        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        root.addView(top, topLp);

        LinearLayout bar = horizontal();
        LinearLayout titleBox = vertical();
        TextView title = text("LUXCAM", 22, WHITE, true);
        title.setLetterSpacing(.12f);
        headingSub = text("CAMERA2  ·  FOTO", 10, ACCENT, true);
        headingSub.setLetterSpacing(.07f);
        titleBox.addView(title);
        titleBox.addView(headingSub);
        bar.addView(titleBox, new LinearLayout.LayoutParams(0, -2, 1f));
        gridButton = action("▦", 0xBB253343, WHITE, 26);
        bar.addView(gridButton, new LinearLayout.LayoutParams(dp(47), dp(45)));
        cameraButton = action("↻", 0xBB253343, WHITE, 26);
        LinearLayout.LayoutParams flipLp = new LinearLayout.LayoutParams(dp(47), dp(45));
        flipLp.leftMargin = dp(10);
        bar.addView(cameraButton, flipLp);
        top.addView(bar);
        gridButton.setOnClickListener(v -> {
            gridView.setVisibility(gridView.getVisibility() == View.GONE ? View.VISIBLE : View.GONE);
            gridButton.setText(gridView.getVisibility() == View.VISIBLE ? "▦ ✓" : "▦");
        });
        cameraButton.setOnClickListener(v -> flipCamera());

        LinearLayout bottom = vertical();
        bottom.setPadding(dp(18), dp(60), dp(18), dp(20));
        GradientDrawable bottomFade = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00080E18, 0xD9080E18, 0xFF080E18});
        bottom.setBackground(bottomFade);
        root.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        timerText = text("", 13, WHITE, true);
        timerText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams timerLp = new LinearLayout.LayoutParams(-1, dp(21));
        bottom.addView(timerText, timerLp);

        LinearLayout exposureTitle = horizontal();
        TextView lightLabel = text("EXPOSICIÓN", 11, 0xFFB0C2CD, true);
        exposureTitle.addView(lightLabel, new LinearLayout.LayoutParams(0, -2, 1f));
        evLabel = text("0.0 EV", 14, WHITE, true);
        exposureTitle.addView(evLabel);
        bottom.addView(exposureTitle);
        evBar = new SeekBar(this);
        evBar.setMax(40);
        evBar.setProgress(20);
        evBar.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        evBar.setThumbTintList(android.content.res.ColorStateList.valueOf(WHITE));
        LinearLayout.LayoutParams sliderLp = new LinearLayout.LayoutParams(-1, dp(44));
        bottom.addView(evBar, sliderLp);
        evBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (!user) return;
                manualEv = (p - 20) / 10f;
                updateExposureText();
                updateRepeating();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });

        LinearLayout chips = horizontal();
        protectButton = action("☀  Proteger luces: NO", 0xBB273643, WHITE, 22);
        chips.addView(protectButton, new LinearLayout.LayoutParams(0, dp(42), 1f));
        Button reset = action("Restablecer", 0xBB273643, WHITE, 22);
        LinearLayout.LayoutParams resetLp = new LinearLayout.LayoutParams(-2, dp(42));
        resetLp.leftMargin = dp(8);
        chips.addView(reset, resetLp);
        bottom.addView(chips);
        protectButton.setOnClickListener(v -> {
            highlightGuard = !highlightGuard;
            protectButton.setText(highlightGuard ? "☀  Proteger luces: SÍ" : "☀  Proteger luces: NO");
            protectButton.setBackground(background(highlightGuard ? 0xFF315164 : 0xBB273643, 22, Color.TRANSPARENT));
            updateExposureText(); updateRepeating();
        });
        reset.setOnClickListener(v -> { manualEv = 0f; evBar.setProgress(20); updateExposureText(); updateRepeating(); });

        LinearLayout modeRow = horizontal();
        modeRow.setGravity(Gravity.CENTER);
        photoModeButton = action("FOTO", ACCENT, PANEL, 24);
        videoModeButton = action("VIDEO", 0xFF263343, WHITE, 24);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(dp(112), dp(43));
        modeRow.addView(photoModeButton, mp);
        LinearLayout.LayoutParams mv = new LinearLayout.LayoutParams(dp(112), dp(43));
        mv.leftMargin = dp(9);
        modeRow.addView(videoModeButton, mv);
        LinearLayout.LayoutParams modeLp = new LinearLayout.LayoutParams(-1, -2);
        modeLp.topMargin = dp(14);
        bottom.addView(modeRow, modeLp);

        shutterButton = action("●", WHITE, PANEL, 99);
        shutterButton.setTextSize(36);
        LinearLayout shutterLine = horizontal();
        Button infoButton = action("i  DIAGNÓSTICO", 0x80263543, WHITE, 20);
        infoButton.setTextSize(10);
        shutterLine.addView(infoButton, new LinearLayout.LayoutParams(0, dp(41), 1f));
        FrameLayout shutterCenter = new FrameLayout(this);
        shutterCenter.addView(shutterButton, new FrameLayout.LayoutParams(dp(78), dp(78), Gravity.CENTER));
        shutterLine.addView(shutterCenter, new LinearLayout.LayoutParams(dp(108), dp(88)));
        resolutionLabel = text("12 MP\n1080p 30", 11, 0xFFC5D3DB, true);
        resolutionLabel.setGravity(Gravity.CENTER);
        shutterLine.addView(resolutionLabel, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams shutterLp = new LinearLayout.LayoutParams(-1, -2);
        shutterLp.topMargin = dp(16);
        bottom.addView(shutterLine, shutterLp);
        infoButton.setOnClickListener(v -> {
            if (recording || startingRecording) { show("Detén la grabación antes del diagnóstico"); return; }
            startActivity(new Intent(this, MainActivity.class));
        });
        photoModeButton.setOnClickListener(v -> changeMode(false));
        videoModeButton.setOnClickListener(v -> changeMode(true));
        shutterButton.setOnClickListener(v -> {
            if (videoMode) {
                if (recording) stopRecording(true);
                else if (!startingRecording) startRecording();
            } else takePhoto();
        });

        statusText = text("Cámara lista para iniciar", 12, WHITE, false);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(dp(10), dp(7), dp(10), dp(7));
        statusText.setBackground(background(0xAA0B1620, 18, Color.TRANSPARENT));
        FrameLayout.LayoutParams statusLp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        statusLp.topMargin = dp(92);
        root.addView(statusText, statusLp);
        updateExposureText();
        setContentView(root);
    }

    private final TextureView.SurfaceTextureListener surfaceListener = new TextureView.SurfaceTextureListener() {
        @Override public void onSurfaceTextureAvailable(SurfaceTexture t, int width, int height) { requestOrOpenCamera(); }
        @Override public void onSurfaceTextureSizeChanged(SurfaceTexture t, int width, int height) { configurePreviewMatrix(width, height); }
        @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture t) { return true; }
        @Override public void onSurfaceTextureUpdated(SurfaceTexture t) { }
    };

    private void show(String message) {
        main.post(() -> {
            statusText.setText(message);
            Toast.makeText(LuxCamActivity.this, message, Toast.LENGTH_SHORT).show();
        });
    }

    private void setStatus(String message) { main.post(() -> statusText.setText(message)); }

    private void requestOrOpenCamera() {
        if (paused || !textureView.isAvailable() || cameraHandler == null) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
            return;
        }
        openCamera();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        boolean granted = grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == CAMERA_PERMISSION) {
            if (granted) requestOrOpenCamera(); else show("Necesitamos permiso de cámara para continuar");
        } else if (requestCode == MICROPHONE_PERMISSION) {
            if (granted) startRecording(); else show("Autoriza el micrófono para grabar video con audio");
        }
    }

    private void findCameraIds(CameraManager manager) throws CameraAccessException {
        if (backId != null && frontId != null) return;
        for (String id : manager.getCameraIdList()) {
            Integer facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK && backId == null) backId = id;
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT && frontId == null) frontId = id;
        }
    }

    private void openCamera() {
        if (paused || device != null || openingCamera.get() || cameraHandler == null || !textureView.isAvailable()) return;
        CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            findCameraIds(manager);
            cameraId = cameraFacing == CameraCharacteristics.LENS_FACING_FRONT ? frontId : backId;
            if (cameraId == null) { show("No se encontró la cámara solicitada"); return; }
            characteristics = manager.getCameraCharacteristics(cameraId);
            Integer orientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorOrientation = orientation != null ? orientation : 90;
            evRange = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
            Rational rational = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
            evStep = rational == null || rational.floatValue() <= 0 ? .1f : rational.floatValue();
            if (evRange == null) evRange = new Range<>(0, 0);
            Integer af = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF);
            Integer ae = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE);
            maxAfRegions = af != null ? af : 0;
            maxAeRegions = ae != null ? ae : 0;
            activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) { show("Camera2 no presenta tamaños de salida"); return; }
            previewSize = choosePreview(map.getOutputSizes(SurfaceTexture.class));
            Size photo = choosePhoto(map.getOutputSizes(android.graphics.ImageFormat.JPEG));
            if (previewSize == null || photo == null) { show("No hay tamaños JPEG o de visor compatibles"); return; }
            jpegReader = ImageReader.newInstance(photo.getWidth(), photo.getHeight(), android.graphics.ImageFormat.JPEG, 2);
            int megaPixels = Math.round((photo.getWidth() * photo.getHeight()) / 1_000_000f);
            main.post(() -> resolutionLabel.setText(megaPixels + " MP\n1080p 30"));
            jpegReader.setOnImageAvailableListener(reader -> savePhoto(reader), cameraHandler);
            if (!cameraLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) { show("Cámara ocupada: reintenta"); return; }
            try {
                if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    cameraLock.release(); return;
                }
                final int openGeneration = generation;
                openingCamera.set(true);
                manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                    @Override public void onOpened(CameraDevice camera) {
                        releaseOpeningLock();
                        if (paused || openGeneration != generation) { camera.close(); return; }
                        device = camera;
                        main.post(() -> configurePreviewMatrix(textureView.getWidth(), textureView.getHeight()));
                        createPreviewSession();
                    }
                    @Override public void onDisconnected(CameraDevice camera) {
                        releaseOpeningLock(); camera.close(); device = null; setStatus("Cámara desconectada");
                    }
                    @Override public void onError(CameraDevice camera, int error) {
                        releaseOpeningLock(); camera.close(); device = null; setStatus("Error Camera2: " + error);
                    }
                }, cameraHandler);
            } catch (Exception e) { releaseOpeningLock(); throw e; }
        } catch (Exception e) {
            show("No se pudo abrir la cámara: " + e.getMessage());
            releaseImageReader();
        }
    }

    private Size choosePhoto(Size[] sizes) {
        if (sizes == null || sizes.length == 0) return null;
        Size best = sizes[0];
        long areaMax = (long) best.getWidth() * best.getHeight();
        for (Size s : sizes) {
            long area = (long)s.getWidth() * s.getHeight();
            if (area > areaMax && (float)s.getWidth() / s.getHeight() > 1.30f && (float)s.getWidth() / s.getHeight() < 1.37f) {
                areaMax = area; best = s;
            }
        }
        return best;
    }

    private Size choosePreview(Size[] sizes) {
        if (sizes == null || sizes.length == 0) return null;
        // Preferimos 1080p, 16:9, por fluidez del visor y futura grabación.
        for (Size s : sizes) if (s.getWidth() == 1920 && s.getHeight() == 1080) return s;
        Size best = sizes[0];
        for (Size s : sizes) if (Math.abs((float)s.getWidth()/s.getHeight()-16f/9f) < .015f &&
                (long)s.getWidth()*s.getHeight() <= 1920L*1080 &&
                (long)s.getWidth()*s.getHeight() > (long)best.getWidth()*best.getHeight()) best = s;
        return best;
    }

    private void configurePreviewMatrix(int width, int height) {
        if (width <= 0 || height <= 0 || previewSize == null) return;
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, width, height);
        RectF bufferRect = new RectF(0, 0, previewSize.getHeight(), previewSize.getWidth());
        float centerX = viewRect.centerX(), centerY = viewRect.centerY();
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max((float) height / previewSize.getHeight(), (float) width / previewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else if (rotation == Surface.ROTATION_180) {
            matrix.postRotate(180, centerX, centerY);
        } else {
            // Retrato: la rotación del sensor la resuelve la textura de Camera2.
            float ratioView = (float)width / height;
            float ratioImage = (float)previewSize.getHeight() / previewSize.getWidth();
            if (ratioView > ratioImage) matrix.setScale(1f, ratioView / ratioImage, centerX, centerY);
            else matrix.setScale(ratioImage / ratioView, 1f, centerX, centerY);
        }
        textureView.setTransform(matrix);
    }

    private void releaseOpeningLock() {
        if (openingCamera.getAndSet(false)) cameraLock.release();
    }

    private void closeCamera() {
        boolean acquired = false;
        try {
            acquired = cameraLock.tryAcquire(2500, TimeUnit.MILLISECONDS);
            if (!acquired) { setStatus("Cámara ocupada al cerrar"); return; }
            sessionReady = false;
            takingPhoto = false;
            if (captureSession != null) { captureSession.close(); captureSession = null; }
            if (device != null) { device.close(); device = null; }
            if (previewSurface != null) { previewSurface.release(); previewSurface = null; }
            releaseImageReader();
        } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        finally { if (acquired) cameraLock.release(); }
    }

    private void releaseImageReader() {
        if (jpegReader != null) { jpegReader.close(); jpegReader = null; }
    }

    private Surface prepareSurface() {
        SurfaceTexture texture = textureView.getSurfaceTexture();
        if (texture == null || previewSize == null) return null;
        texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
        if (previewSurface != null) previewSurface.release();
        previewSurface = new Surface(texture);
        return previewSurface;
    }

    private void createPreviewSession() {
        if (paused || device == null || jpegReader == null) return;
        sessionReady = false;
        try {
            if (captureSession != null) { captureSession.close(); captureSession = null; }
            Surface surface = prepareSurface();
            if (surface == null) return;
            CaptureRequest.Builder builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(surface);
            setupControls(builder, false);
            final CameraDevice current = device;
            current.createCaptureSession(Arrays.asList(surface, jpegReader.getSurface()), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession session) {
                    if (paused || current != device || recording || startingRecording) { session.close(); return; }
                    captureSession = session;
                    repeatingBuilder = builder;
                    sessionReady = true;
                    updateRepeating();
                    setStatus(cameraFacing == CameraCharacteristics.LENS_FACING_FRONT
                            ? "Frontal de foco fijo · Toca para medir luz"
                            : "Lista · Toca para enfocar y medir luz");
                }
                @Override public void onConfigureFailed(CameraCaptureSession session) { setStatus("No se pudo configurar la vista previa"); }
            }, cameraHandler);
        } catch (Exception e) { show("Error de vista previa: " + e.getMessage()); }
    }

    private void setupControls(CaptureRequest.Builder b, boolean photo) {
        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
        b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);
        b.set(CaptureRequest.CONTROL_AF_MODE,
                cameraFacing == CameraCharacteristics.LENS_FACING_BACK
                        ? (videoMode ? CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO : CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        : CaptureRequest.CONTROL_AF_MODE_OFF);
        b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, exposureUnits());
        if (aeRegions != null && maxAeRegions > 0) b.set(CaptureRequest.CONTROL_AE_REGIONS, aeRegions);
        if (afRegions != null && maxAfRegions > 0) b.set(CaptureRequest.CONTROL_AF_REGIONS, afRegions);
        if (photo) {
            b.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE);
            b.set(CaptureRequest.JPEG_QUALITY, (byte) 95);
            chooseImageQuality(b);
        } else {
            b.set(CaptureRequest.CONTROL_CAPTURE_INTENT,
                    recording || startingRecording ? CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD : CaptureRequest.CONTROL_CAPTURE_INTENT_PREVIEW);
        }
        // Foto/visor: la AE debe poder alargar exposición en interiores.
        // Video: priorizamos 30 fps nominales, pendiente validar fps reales.
        Range<Integer>[] ranges = characteristics != null && !photo && (recording || startingRecording)
                ? characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) : null;
        if (ranges != null) {
            for (Range<Integer> r : ranges) {
                if (r.getLower() == 30 && r.getUpper() == 30) {
                    b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, r); break;
                }
            }
        }
    }

    private void chooseImageQuality(CaptureRequest.Builder b) {
        setSupported(b, CaptureRequest.NOISE_REDUCTION_MODE,
                CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES,
                CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY);
        // No forzamos EDGE HIGH_QUALITY: podria introducir halos de nitidez.
        setSupported(b, CaptureRequest.TONEMAP_MODE,
                CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES,
                CaptureRequest.TONEMAP_MODE_HIGH_QUALITY);
        setSupported(b, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
                CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES,
                CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY);
    }

    private void setSupported(CaptureRequest.Builder b, CaptureRequest.Key<Integer> key,
                              CameraCharacteristics.Key<int[]> capabilities, int desired) {
        if (characteristics == null) return;
        int[] available = characteristics.get(capabilities);
        if (available == null) return;
        for (int mode : available) if (mode == desired) { b.set(key, desired); return; }
    }

    private int exposureUnits() {
        float effective = manualEv - (highlightGuard ? .3f : 0f);
        int steps = Math.round(effective / evStep);
        return Math.max(evRange.getLower(), Math.min(evRange.getUpper(), steps));
    }

    private void updateExposureText() {
        float effective = manualEv - (highlightGuard ? .3f : 0f);
        evLabel.setText(String.format(Locale.US, "%+.1f EV", effective));
    }

    private void updateRepeating() {
        if (!sessionReady || captureSession == null || repeatingBuilder == null || cameraHandler == null) return;
        try {
            setupControls(repeatingBuilder, false);
            captureSession.setRepeatingRequest(repeatingBuilder.build(), null, cameraHandler);
        } catch (Exception e) { setStatus("Exposición no aplicada: " + e.getMessage()); }
    }

    private void takePhoto() {
        if (!sessionReady || cameraHandler == null || takingPhoto || recording || device == null || captureSession == null) {
            show("Espera a que la cámara esté lista"); return;
        }
        takingPhoto = true;
        try {
            CaptureRequest.Builder photo = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            photo.addTarget(jpegReader.getSurface());
            setupControls(photo, true);
            photo.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation());
            captureSession.capture(photo.build(), new CameraCaptureSession.CaptureCallback() {
                @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult result) {
                    setStatus("Guardando JPEG original…");
                }
                @Override public void onCaptureFailed(CameraCaptureSession s, CaptureRequest r,
                        android.hardware.camera2.CaptureFailure failure) {
                    takingPhoto = false;
                    setStatus("No se pudo capturar la foto");
                }
            }, cameraHandler);
        } catch (Exception e) { takingPhoto = false; show("Error de fotografía: " + e.getMessage()); }
    }

    private String timestamp() { return new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date()); }

    private void savePhoto(ImageReader reader) {
        Image image = null;
        Uri uri = null;
        try {
            image = reader.acquireNextImage();
            if (image == null) return;
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, "LuxCam_" + timestamp() + ".jpg");
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, PICTURES_FOLDER);
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
            ContentResolver resolver = getContentResolver();
            uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("No se pudo reservar archivo JPEG");
            try (OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) throw new IOException("No se pudo abrir JPEG");
                out.write(bytes);
            }
            values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
            setStatus("Foto guardada en Pictures/LuxCam");
        } catch (Exception e) {
            if (uri != null) getContentResolver().delete(uri, null, null);
            setStatus("Error guardando foto: " + e.getMessage());
        } finally {
            if (image != null) image.close();
            takingPhoto = false;
        }
    }

    private int jpegOrientation() {
        int deviceRotation = getWindowManager().getDefaultDisplay().getRotation();
        int degrees = deviceRotation == Surface.ROTATION_90 ? 90 : deviceRotation == Surface.ROTATION_180 ? 180
                : deviceRotation == Surface.ROTATION_270 ? 270 : 0;
        return cameraFacing == CameraCharacteristics.LENS_FACING_FRONT
                ? (sensorOrientation + degrees) % 360
                : (sensorOrientation - degrees + 360) % 360;
    }

    private void startRecording() {
        if (!videoMode || !sessionReady || device == null || startingRecording || recording) {
            show("Espera a que la cámara esté lista para grabar"); return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MICROPHONE_PERMISSION); return;
        }
        startingRecording = true;
        sessionReady = false;
        try {
            configureRecorder();
            captureSession.close();
            captureSession = null;
            final CameraDevice current = device;
            Surface preview = previewSurface;
            Surface record = recorder.getSurface();
            CaptureRequest.Builder b = current.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            b.addTarget(preview); b.addTarget(record);
            setupControls(b, false);
            current.createCaptureSession(Arrays.asList(preview, record), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession session) {
                    if (current != device || paused) { session.close(); cleanupVideo(false); return; }
                    captureSession = session;
                    repeatingBuilder = b;
                    try {
                        session.setRepeatingRequest(b.build(), null, cameraHandler);
                        recorder.start();
                        recording = true;
                        startingRecording = false;
                        sessionReady = true;
                        recordStartMs = android.os.SystemClock.elapsedRealtime();
                        main.post(() -> {
                            shutterButton.setText("■");
                            shutterButton.setTextColor(0xFFEA515C);
                            timerText.setText("● 00:00 · GRABANDO");
                            setStatus("Grabando 1080p · Audio activado");
                            tickTimer.run();
                        });
                    } catch (Exception e) { failVideo("Error iniciando grabación", e); }
                }
                @Override public void onConfigureFailed(CameraCaptureSession session) {
                    session.close();
                    failVideo("Sesión de video no compatible", null);
                }
            }, cameraHandler);
        } catch (Exception e) { failVideo("No se pudo preparar video", e); }
    }

    private void configureRecorder() throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, "LuxCam_" + timestamp() + ".mp4");
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH, VIDEOS_FOLDER);
        values.put(MediaStore.Video.Media.IS_PENDING, 1);
        videoUri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
        if (videoUri == null) throw new IOException("No se pudo crear archivo MP4");
        videoFd = getContentResolver().openFileDescriptor(videoUri, "w");
        if (videoFd == null) throw new IOException("No hay descriptor de video");
        recorder = new MediaRecorder();
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setOutputFile(videoFd.getFileDescriptor());
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        recorder.setVideoSize(videoSize.getWidth(), videoSize.getHeight());
        recorder.setVideoFrameRate(30);
        recorder.setVideoEncodingBitRate(20_000_000);
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        recorder.setAudioEncodingBitRate(128_000);
        recorder.setAudioSamplingRate(44_100);
        recorder.setOrientationHint(jpegOrientation());
        recorder.prepare();
    }

    private void stopRecording(boolean resumePreview) {
        if (!recording && !startingRecording) return;
        boolean wasRecording = recording;
        recording = false;
        startingRecording = false;
        sessionReady = false;
        main.removeCallbacks(tickTimer);
        try { if (captureSession != null) { captureSession.stopRepeating(); captureSession.abortCaptures(); captureSession.close(); captureSession = null; } }
        catch (Exception ignored) { }
        boolean saved = false;
        if (wasRecording && recorder != null) {
            try { recorder.stop(); saved = true; } catch (RuntimeException e) { saved = false; }
        }
        cleanupVideo(saved);
        main.post(() -> {
            shutterButton.setText("●"); shutterButton.setTextColor(0xFFEA515C);
            timerText.setText("");
        });
        if (resumePreview && !paused && device != null) {
            createPreviewSession();
            setStatus(saved ? "Video guardado en Movies/LuxCam" : "Video no válido: grabación cancelada");
        }
    }

    private void cleanupVideo(boolean successful) {
        if (recorder != null) { try { recorder.reset(); } catch (Exception ignored) { }
            recorder.release(); recorder = null; }
        if (videoFd != null) { try { videoFd.close(); } catch (IOException ignored) { } videoFd = null; }
        if (videoUri != null) {
            try {
                if (successful) {
                    ContentValues update = new ContentValues();
                    update.put(MediaStore.Video.Media.IS_PENDING, 0);
                    getContentResolver().update(videoUri, update, null, null);
                } else getContentResolver().delete(videoUri, null, null);
            } catch (Exception ignored) { }
            videoUri = null;
        }
    }

    private void failVideo(String reason, Exception e) {
        recording = false; startingRecording = false; sessionReady = false;
        cleanupVideo(false);
        String msg = reason + (e != null && e.getMessage() != null ? ": " + e.getMessage() : "");
        setStatus(msg);
        main.post(() -> { shutterButton.setText("●"); shutterButton.setTextColor(0xFFEA515C); timerText.setText(""); });
        if (!paused && device != null) main.postDelayed(this::createPreviewSession, 250);
    }

    private final Runnable tickTimer = new Runnable() {
        @Override public void run() {
            if (!recording) return;
            long seconds = (android.os.SystemClock.elapsedRealtime() - recordStartMs) / 1000;
            timerText.setText(String.format(Locale.US, "● %02d:%02d · GRABANDO", seconds / 60, seconds % 60));
            main.postDelayed(this, 1000);
        }
    };

    private void changeMode(boolean video) {
        if (recording || startingRecording) { show("Detén el video antes de cambiar de modo"); return; }
        videoMode = video;
        photoModeButton.setBackground(background(video ? 0xFF263343 : ACCENT, 24, Color.TRANSPARENT));
        photoModeButton.setTextColor(video ? WHITE : PANEL);
        videoModeButton.setBackground(background(video ? ACCENT : 0xFF263343, 24, Color.TRANSPARENT));
        videoModeButton.setTextColor(video ? PANEL : WHITE);
        shutterButton.setText("●"); shutterButton.setTextColor(video ? 0xFFEA515C : PANEL);
        headingSub.setText(video ? "CAMERA2  ·  VIDEO 1080p" : "CAMERA2  ·  FOTO");
        setStatus(video ? "Video 1080p · Toca grabar" : "Foto JPEG original · Toca para enfocar");
        updateRepeating();
    }

    private void flipCamera() {
        if (recording || startingRecording || takingPhoto) { show("Finaliza la captura para cambiar de cámara"); return; }
        generation++;
        sessionReady = false;
        cameraFacing = cameraFacing == CameraCharacteristics.LENS_FACING_BACK
                ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK;
        afRegions = null; aeRegions = null;
        closeCamera();
        setStatus("Cambiando cámara…");
        main.postDelayed(this::requestOrOpenCamera, 350);
    }

    private void touchMetering(float x, float y) {
        if (activeArray == null || !sessionReady || captureSession == null) return;
        int w = textureView.getWidth();
        int h = textureView.getHeight();
        if (w == 0 || h == 0 || x < 0 || y < 0 || y > h) return;
        float nx = x / w, ny = y / h;
        // Orientación vertical: los ejes de sensor se permutan (90/270 grados).
        float sensorX = sensorOrientation == 270 ? 1f-ny : ny;
        float sensorY = sensorOrientation == 270 ? nx : 1f-nx;
        if (cameraFacing == CameraCharacteristics.LENS_FACING_FRONT) sensorY = 1f - sensorY;
        int px = activeArray.left + Math.round(sensorX * activeArray.width());
        int py = activeArray.top + Math.round(sensorY * activeArray.height());
        int side = Math.max(70, Math.min(activeArray.width(), activeArray.height()) / 7);
        int left = Math.max(activeArray.left, Math.min(px - side / 2, activeArray.right - side));
        int top = Math.max(activeArray.top, Math.min(py - side / 2, activeArray.bottom - side));
        MeteringRectangle[] box = new MeteringRectangle[]{new MeteringRectangle(left, top, side, side, 1000)};
        if (maxAeRegions > 0) aeRegions = box;
        if (maxAfRegions > 0 && cameraFacing == CameraCharacteristics.LENS_FACING_BACK) afRegions = box;
        updateRepeating();
        setStatus("Medición de luz en el punto seleccionado");
    }

    private final class GridView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        GridView() { super(LuxCamActivity.this); setClickable(false); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            paint.setColor(0x99FFFFFF); paint.setStrokeWidth(dp(1));
            float w = getWidth(), h = getHeight();
            for (int i = 1; i <= 2; i++) {
                canvas.drawLine(i * w / 3f, 0, i * w / 3f, h, paint);
                canvas.drawLine(0, i * h / 3f, w, i * h / 3f, paint);
            }
        }
    }
}
