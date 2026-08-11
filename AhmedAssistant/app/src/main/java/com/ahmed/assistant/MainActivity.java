package com.ahmed.assistant;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.content.pm.PackageManager;
import android.provider.MediaStore;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.TextUtils;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.herohan.uvcapp.CameraHelper;
import com.herohan.uvcapp.ICameraHelper;
import com.herohan.uvcapp.ImageCapture;
import com.serenegiant.usb.Size;
import com.serenegiant.widget.AspectRatioSurfaceView;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

/**
 * Client visible 0rus : caméra UVC, bouton Bluetooth, analyse par serveur privé et TTS.
 * La clé OpenAI n'est jamais présente dans l'APK.
 */
@SuppressLint("SetTextI18n")
public final class MainActivity extends Activity {

    private static final int TARGET_VENDOR_ID = 0x0DBA;
    private static final int TARGET_PRODUCT_ID = 0xD565;
    private static final int DEFAULT_WIDTH = 640;
    private static final int DEFAULT_HEIGHT = 480;
    private static final long CAPTURE_DEBOUNCE_MS = 700L;
    private static final String ACTION_USB_PERMISSION =
            "com.ahmed.assistant.action.USB_PERMISSION";
    private static final int REQUEST_ANDROID_CAMERA_PERMISSION = 4101;
    private static final String PREFS_NAME = "orus_settings";
    private static final String PREF_SERVER_URL = "server_url";
    private static final String PREF_ACCESS_TOKEN = "access_token";
    private static final String PREF_GUIDANCE = "guidance";
    private static final String PREF_PREVIOUS_RESPONSE_ID = "previous_response_id";
    private static final String UTTERANCE_SECTION_PREFIX = "0rus-section-";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<String> keyEvents = new ArrayDeque<>();
    private final SimpleDateFormat clockFormat = new SimpleDateFormat("HH:mm:ss", Locale.FRANCE);

    private AspectRatioSurfaceView cameraView;
    private TextView cameraStatus;
    private TextView usbInfo;
    private TextView previewPlaceholder;
    private TextView lastAction;
    private TextView keyLog;
    private TextView aiStatus;
    private TextView answerPosition;
    private TextView answerText;
    private Button captureButton;
    private Button settingsButton;

    private ICameraHelper cameraHelper;
    private UsbDevice selectedDevice;
    private boolean selectionRequested;
    private boolean cameraReady;
    private boolean captureInProgress;
    private boolean analysisInProgress;
    private long lastCaptureRequestAt;
    private int permissionDeniedDeviceId = -1;
    private UsbDevice pendingCameraPermissionDevice;

    private TextToSpeech textToSpeech;
    private boolean ttsReady;
    private MediaSession mediaSession;
    private UsbManager usbManager;
    private BroadcastReceiver usbReceiver;
    private boolean usbReceiverRegistered;
    private SharedPreferences preferences;
    private final AiClient aiClient = new AiClient();
    private AiResponse currentResponse;
    private int currentSectionIndex;
    private boolean answerPlaybackActive;
    private boolean speechPaused;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        bindViews();
        configurePreviewSurface();
        configureCaptureButton();
        configureSettingsButton();
        configureTextToSpeech();
        configureMediaSession();
        configureUsbReceiver();
    }

    private void bindViews() {
        cameraView = findViewById(R.id.cameraView);
        cameraStatus = findViewById(R.id.tvCameraStatus);
        usbInfo = findViewById(R.id.tvUsbInfo);
        previewPlaceholder = findViewById(R.id.tvPreviewPlaceholder);
        lastAction = findViewById(R.id.tvLastAction);
        keyLog = findViewById(R.id.tvKeyLog);
        aiStatus = findViewById(R.id.tvAiStatus);
        answerPosition = findViewById(R.id.tvAnswerPosition);
        answerText = findViewById(R.id.tvAnswer);
        captureButton = findViewById(R.id.btnCapture);
        settingsButton = findViewById(R.id.btnSettings);
        cameraView.setAspectRatio(DEFAULT_WIDTH, DEFAULT_HEIGHT);
        updateAiConfigurationStatus();
    }

    private void configurePreviewSurface() {
        cameraView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                addPreviewSurfaceIfReady(holder.getSurface());
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                // Le ratio est mis à jour avec la taille annoncée par la caméra.
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                removePreviewSurface(holder.getSurface());
            }
        });
    }

    private void configureCaptureButton() {
        captureButton.setOnClickListener(view -> {
            if (cameraReady) {
                stopAnswerPlayback();
                requestCapture("Écran");
            } else {
                retryUsbPermission();
            }
        });
    }

    private void configureSettingsButton() {
        settingsButton.setOnClickListener(view -> showSettingsDialog());
    }

    private void showSettingsDialog() {
        int padding = Math.round(20 * getResources().getDisplayMetrics().density);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(padding, padding / 2, padding, 0);

        EditText serverInput = new EditText(this);
        serverInput.setHint("https://mon-serveur.example");
        serverInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        serverInput.setSingleLine(true);
        serverInput.setText(preferences.getString(PREF_SERVER_URL, ""));
        form.addView(serverInput);

        EditText tokenInput = new EditText(this);
        tokenInput.setHint("Jeton d’accès 0rus");
        tokenInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        tokenInput.setSingleLine(true);
        tokenInput.setText(preferences.getString(PREF_ACCESS_TOKEN, ""));
        form.addView(tokenInput);

        EditText guidanceInput = new EditText(this);
        guidanceInput.setHint("Consigne personnelle (facultatif)\nEx. Appliquer la méthode de commentaire du cours");
        guidanceInput.setMinLines(3);
        guidanceInput.setGravity(android.view.Gravity.TOP);
        guidanceInput.setText(preferences.getString(PREF_GUIDANCE, ""));
        form.addView(guidanceInput);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Configuration IA")
                .setMessage("L’APK envoie la photo à votre serveur privé. La clé OpenAI reste sur ce serveur.")
                .setView(form)
                .setNegativeButton("Annuler", null)
                .setNeutralButton("Effacer la mémoire", (ignored, which) -> clearConversationMemory())
                .setPositiveButton("Enregistrer", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    String serverUrl = serverInput.getText().toString().trim();
                    String token = tokenInput.getText().toString().trim();
                    if (!serverUrl.startsWith("https://")) {
                        serverInput.setError("Adresse HTTPS requise");
                        return;
                    }
                    if (token.isEmpty()) {
                        tokenInput.setError("Jeton requis");
                        return;
                    }
                    preferences.edit()
                            .putString(PREF_SERVER_URL, serverUrl)
                            .putString(PREF_ACCESS_TOKEN, token)
                            .putString(PREF_GUIDANCE, guidanceInput.getText().toString().trim())
                            .apply();
                    updateAiConfigurationStatus();
                    Toast.makeText(this, "Configuration enregistrée", Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                }));
        dialog.show();
    }

    private void clearConversationMemory() {
        preferences.edit().remove(PREF_PREVIOUS_RESPONSE_ID).apply();
        currentResponse = null;
        currentSectionIndex = 0;
        stopAnswerPlayback();
        answerPosition.setText(R.string.no_answer_position);
        answerText.setText(R.string.no_answer);
        Toast.makeText(this, "Mémoire de conversation effacée", Toast.LENGTH_SHORT).show();
    }

    private boolean isAiConfigured() {
        return !preferences.getString(PREF_SERVER_URL, "").isBlank()
                && !preferences.getString(PREF_ACCESS_TOKEN, "").isBlank();
    }

    private void updateAiConfigurationStatus() {
        if (aiStatus == null || preferences == null) {
            return;
        }
        boolean configured = isAiConfigured();
        aiStatus.setText(configured
                ? "IA prête · mémoire de session active"
                : "IA à configurer · appuyez sur Paramètres IA");
        aiStatus.setTextColor(getColor(configured ? R.color.accent : R.color.warning));
    }

    private void configureTextToSpeech() {
        textToSpeech = new TextToSpeech(this, status -> {
            ttsReady = status == TextToSpeech.SUCCESS;
            if (ttsReady) {
                int result = textToSpeech.setLanguage(Locale.FRANCE);
                if (result == TextToSpeech.LANG_MISSING_DATA
                        || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    textToSpeech.setLanguage(Locale.FRENCH);
                }
                textToSpeech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override
                    public void onStart(String utteranceId) {
                        runOnUiThread(() -> updatePlaybackState());
                    }

                    @Override
                    public void onDone(String utteranceId) {
                        if (utteranceId != null && utteranceId.startsWith(UTTERANCE_SECTION_PREFIX)) {
                            runOnUiThread(() -> handleSectionSpeechFinished(utteranceId));
                        }
                    }

                    @Override
                    public void onError(String utteranceId) {
                        runOnUiThread(() -> {
                            answerPlaybackActive = false;
                            speechPaused = false;
                            aiStatus.setText("Erreur de lecture vocale");
                            aiStatus.setTextColor(getColor(R.color.error));
                            updatePlaybackState();
                        });
                    }
                });
            }
        });
    }

    private void configureMediaSession() {
        mediaSession = new MediaSession(this, "0rusHardwareSession");
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override
            public boolean onMediaButtonEvent(Intent mediaButtonIntent) {
                KeyEvent event = readMediaKeyEvent(mediaButtonIntent);
                if (event != null && event.getAction() == KeyEvent.ACTION_DOWN
                        && event.getRepeatCount() == 0) {
                    return handleHardwareKey(event.getKeyCode(), "Bluetooth");
                }
                return super.onMediaButtonEvent(mediaButtonIntent);
            }

            @Override
            public void onPlay() {
                handlePrimaryActionOnMainThread("Bluetooth · Lecture");
            }

            @Override
            public void onPause() {
                handlePrimaryActionOnMainThread("Bluetooth · Pause");
            }

            @Override
            public void onSkipToNext() {
                runOnUiThread(() -> moveAnswerSection(1, "Bluetooth"));
            }

            @Override
            public void onSkipToPrevious() {
                runOnUiThread(() -> moveAnswerSection(-1, "Bluetooth"));
            }
        });
        updatePlaybackState();
    }

    @SuppressWarnings("deprecation")
    private KeyEvent readMediaKeyEvent(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent.class);
        }
        return intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
    }

    private void updatePlaybackState() {
        long actions = PlaybackState.ACTION_PLAY
                | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_SKIP_TO_NEXT
                | PlaybackState.ACTION_SKIP_TO_PREVIOUS;
        boolean speaking = textToSpeech != null && textToSpeech.isSpeaking();
        PlaybackState state = new PlaybackState.Builder()
                .setActions(actions)
                .setState(speaking ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build();
        mediaSession.setPlaybackState(state);
    }

    @SuppressWarnings("deprecation")
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void configureUsbReceiver() {
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        usbReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                UsbDevice device = readUsbDevice(intent);
                if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                    permissionDeniedDeviceId = -1;
                    addKeyEvent("USB branché · " + shortDevice(device));
                    mainHandler.postDelayed(MainActivity.this::scanRawUsbDevices, 150L);
                } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                    addKeyEvent("USB débranché · " + shortDevice(device));
                    if (isSelectedCamera(device)) {
                        resetDetachedCameraState();
                    }
                    mainHandler.postDelayed(MainActivity.this::scanRawUsbDevices, 150L);
                } else if (ACTION_USB_PERMISSION.equals(action)) {
                    handleExplicitUsbPermissionResult(intent, device);
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        filter.addAction(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(usbReceiver, filter);
        }
        usbReceiverRegistered = true;
    }

    @SuppressWarnings("deprecation")
    private UsbDevice readUsbDevice(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
        }
        return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
    }

    @Override
    protected void onStart() {
        super.onStart();
        mediaSession.setActive(true);
        initCameraHelper();
        mainHandler.postDelayed(this::scanRawUsbDevices, 100L);
    }

    @Override
    protected void onResume() {
        super.onResume();
        cameraView.setFocusableInTouchMode(true);
        cameraView.requestFocus();
    }

    @Override
    protected void onStop() {
        mediaSession.setActive(false);
        clearCameraHelper();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        aiClient.shutdown();
        if (usbReceiverRegistered && usbReceiver != null) {
            unregisterReceiver(usbReceiver);
            usbReceiverRegistered = false;
        }
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
        }
        if (mediaSession != null) {
            mediaSession.release();
        }
        super.onDestroy();
    }

    private void initCameraHelper() {
        if (cameraHelper != null) {
            return;
        }
        setCameraState("Recherche de la caméra USB…", R.color.warning);
        cameraHelper = new CameraHelper();
        cameraHelper.setStateCallback(cameraStateCallback);
        mainHandler.postDelayed(this::scanRawUsbDevices, 250L);
    }

    private void clearCameraHelper() {
        ICameraHelper helper = cameraHelper;
        cameraHelper = null;
        if (helper != null) {
            helper.release();
        }
        cameraReady = false;
        captureInProgress = false;
        selectionRequested = false;
        selectedDevice = null;
        captureButton.setEnabled(false);
        captureButton.setText(R.string.capture);
        previewPlaceholder.setVisibility(View.VISIBLE);
    }

    private void scanRawUsbDevices() {
        if (usbManager == null) {
            setCameraState("Service USB Android indisponible", R.color.error);
            return;
        }

        Map<String, UsbDevice> devices = usbManager.getDeviceList();
        UsbDevice exactTarget = null;
        UsbDevice uvcFallback = null;
        for (UsbDevice device : devices.values()) {
            if (isTargetCamera(device)) {
                exactTarget = device;
                break;
            }
            if (uvcFallback == null && hasUvcVideoInterface(device)) {
                uvcFallback = device;
            }
        }

        usbInfo.setText(formatUsbInventory(devices));
        if (cameraHelper == null || selectionRequested || cameraReady) {
            return;
        }
        if (exactTarget != null) {
            selectCamera(exactTarget, true);
        } else if (uvcFallback != null) {
            selectCamera(uvcFallback, false);
        } else if (devices.isEmpty()) {
            setCameraState("Aucun périphérique USB vu par Android", R.color.error);
        } else {
            setCameraState("USB détecté, mais aucune interface caméra UVC", R.color.error);
        }
    }

    private boolean isTargetCamera(UsbDevice device) {
        return device != null
                && device.getVendorId() == TARGET_VENDOR_ID
                && device.getProductId() == TARGET_PRODUCT_ID;
    }

    private boolean hasUvcVideoInterface(UsbDevice device) {
        if (device == null) {
            return false;
        }
        if (device.getDeviceClass() == 14) {
            return true;
        }
        for (int index = 0; index < device.getInterfaceCount(); index++) {
            UsbInterface usbInterface = device.getInterface(index);
            if (usbInterface.getInterfaceClass() == 14) {
                return true;
            }
        }
        return false;
    }

    private boolean isSupportedCamera(UsbDevice device) {
        return isTargetCamera(device) || hasUvcVideoInterface(device);
    }

    private boolean isSelectedCamera(UsbDevice device) {
        return device != null && selectedDevice != null
                && device.getDeviceId() == selectedDevice.getDeviceId();
    }

    private void selectCamera(UsbDevice device, boolean exactMatch) {
        if (cameraHelper == null || selectionRequested || !isSupportedCamera(device)) {
            return;
        }
        if (permissionDeniedDeviceId == device.getDeviceId()) {
            showUsbPermissionDenied();
            return;
        }
        selectionRequested = true;
        selectedDevice = device;
        runOnUiThread(() -> {
            setCameraState(exactMatch
                    ? "Caméra SYX détectée · autorisation USB…"
                    : "Caméra UVC détectée · autorisation USB…", R.color.warning);
            usbInfo.setText(formatDevice(device));
        });
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestAndroidCameraPermission(device);
            return;
        }
        continueUsbAuthorization(device);
    }

    private void requestAndroidCameraPermission(UsbDevice device) {
        pendingCameraPermissionDevice = device;
        runOnUiThread(() -> {
            setCameraState("Autorisation Caméra Android requise", R.color.warning);
            lastAction.setText(R.string.camera_permission_explanation);
            captureButton.setEnabled(false);
        });
        requestPermissions(
                new String[]{Manifest.permission.CAMERA},
                REQUEST_ANDROID_CAMERA_PERMISSION);
    }

    private void continueUsbAuthorization(UsbDevice device) {
        if (usbManager.hasPermission(device)) {
            openCameraWithLibrary(device);
        } else {
            requestExplicitUsbPermission(device);
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_ANDROID_CAMERA_PERMISSION) {
            return;
        }
        UsbDevice device = pendingCameraPermissionDevice;
        pendingCameraPermissionDevice = null;
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            permissionDeniedDeviceId = -1;
            if (device != null && isSupportedCamera(device)) {
                selectedDevice = device;
                selectionRequested = true;
                setCameraState("Permission Caméra accordée · autorisation USB…", R.color.warning);
                continueUsbAuthorization(device);
            } else {
                selectionRequested = false;
                scanRawUsbDevices();
            }
        } else {
            selectionRequested = false;
            if (device != null) {
                selectedDevice = device;
            }
            showAndroidCameraPermissionDenied();
        }
    }

    private void requestExplicitUsbPermission(UsbDevice device) {
        Intent permissionIntent = new Intent(ACTION_USB_PERMISSION)
                .setPackage(getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_MUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                this, device.getDeviceId(), permissionIntent, flags);
        try {
            usbManager.requestPermission(device, pendingIntent);
            runOnUiThread(() -> {
                setCameraState("Autorisez 0rus dans la fenêtre Android", R.color.warning);
                lastAction.setText("Appuyez sur OK pour autoriser la caméra USB");
            });
        } catch (RuntimeException error) {
            permissionDeniedDeviceId = device.getDeviceId();
            selectionRequested = false;
            runOnUiThread(() -> {
                lastAction.setText("Android : " + error.getClass().getSimpleName());
                showUsbPermissionDenied();
            });
        }
    }

    private void handleExplicitUsbPermissionResult(Intent intent, UsbDevice device) {
        if (device == null || !isSupportedCamera(device)) {
            return;
        }
        boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                && usbManager.hasPermission(device);
        if (granted) {
            permissionDeniedDeviceId = -1;
            selectedDevice = device;
            selectionRequested = true;
            setCameraState("Autorisation USB accordée · ouverture…", R.color.warning);
            lastAction.setText("Ouverture du flux UVC");
            captureButton.setEnabled(false);
            captureButton.setText(R.string.capture);
            openCameraWithLibrary(device);
        } else {
            permissionDeniedDeviceId = device.getDeviceId();
            selectionRequested = false;
            selectedDevice = device;
            showUsbPermissionDenied();
        }
    }

    private void openCameraWithLibrary(UsbDevice device) {
        if (cameraHelper == null) {
            selectionRequested = false;
            return;
        }
        cameraHelper.selectDevice(device);
    }

    private void showUsbPermissionDenied() {
        setCameraState("Autorisation USB refusée par Android", R.color.error);
        lastAction.setText("Appuyez sur Réessayer l’autorisation USB");
        captureButton.setText(R.string.retry_usb_permission);
        captureButton.setEnabled(true);
    }

    private void showAndroidCameraPermissionDenied() {
        setCameraState("Permission Caméra Android refusée", R.color.error);
        lastAction.setText("Paramètres → Applications → 0rus → Autorisations → Caméra");
        captureButton.setText(R.string.retry_usb_permission);
        captureButton.setEnabled(true);
    }

    private void retryUsbPermission() {
        UsbDevice device = selectedDevice;
        if (device == null || !isSupportedCamera(device)) {
            scanRawUsbDevices();
            return;
        }
        permissionDeniedDeviceId = -1;
        selectionRequested = false;
        captureButton.setEnabled(false);
        captureButton.setText(R.string.capture);
        selectCamera(device, isTargetCamera(device));
    }

    private String formatDevice(UsbDevice device) {
        return String.format(Locale.US,
                "%s · VID %04X · PID %04X · interfaces %s · %s",
                isTargetCamera(device) ? "SYX / Hua Que" : "Caméra UVC",
                device.getVendorId(), device.getProductId(),
                formatInterfaceClasses(device), device.getDeviceName());
    }

    private String formatUsbInventory(Map<String, UsbDevice> devices) {
        if (devices.isEmpty()) {
            return "USB vus par Android : aucun";
        }
        StringBuilder text = new StringBuilder("USB vus par Android : ");
        int count = 0;
        for (UsbDevice device : devices.values()) {
            if (count > 0) {
                text.append(" · ");
            }
            text.append(shortDevice(device));
            count++;
            if (count == 3 && devices.size() > 3) {
                text.append(" · +").append(devices.size() - 3);
                break;
            }
        }
        return text.toString();
    }

    private String shortDevice(UsbDevice device) {
        if (device == null) {
            return "inconnu";
        }
        return String.format(Locale.US, "%04X:%04X [i:%s]",
                device.getVendorId(), device.getProductId(), formatInterfaceClasses(device));
    }

    private String formatInterfaceClasses(UsbDevice device) {
        if (device == null || device.getInterfaceCount() == 0) {
            return device == null ? "?" : Integer.toString(device.getDeviceClass());
        }
        StringBuilder classes = new StringBuilder();
        for (int index = 0; index < device.getInterfaceCount(); index++) {
            if (index > 0) {
                classes.append(',');
            }
            classes.append(device.getInterface(index).getInterfaceClass());
        }
        return classes.toString();
    }

    private void resetDetachedCameraState() {
        cameraReady = false;
        captureInProgress = false;
        selectionRequested = false;
        permissionDeniedDeviceId = -1;
        pendingCameraPermissionDevice = null;
        selectedDevice = null;
        captureButton.setEnabled(false);
        captureButton.setText(R.string.capture);
        previewPlaceholder.setVisibility(View.VISIBLE);
        setCameraState("Caméra USB débranchée", R.color.error);
    }

    private final ICameraHelper.StateCallback cameraStateCallback = new ICameraHelper.StateCallback() {
        @Override
        public void onAttach(UsbDevice device) {
            if (isSupportedCamera(device)) {
                selectCamera(device, isTargetCamera(device));
            }
        }

        @Override
        public void onDeviceOpen(UsbDevice device, boolean isFirstOpen) {
            if (cameraHelper != null && isSelectedCamera(device)) {
                runOnUiThread(() -> setCameraState("Caméra autorisée · ouverture…", R.color.warning));
                cameraHelper.openCamera();
            }
        }

        @Override
        public void onCameraOpen(UsbDevice device) {
            ICameraHelper helper = cameraHelper;
            if (helper == null || !isSelectedCamera(device)) {
                return;
            }
            helper.startPreview();
            Size size = helper.getPreviewSize();
            runOnUiThread(() -> {
                cameraReady = true;
                captureInProgress = false;
                if (size != null) {
                    cameraView.setAspectRatio(size.width, size.height);
                }
                previewPlaceholder.setVisibility(View.GONE);
                captureButton.setEnabled(true);
                captureButton.setText(R.string.capture);
                setCameraState("Caméra prête", R.color.accent);
                lastAction.setText("Lecture/Pause = prendre une photo");
                addPreviewSurfaceIfReady(cameraView.getHolder().getSurface());
            });
        }

        @Override
        public void onCameraClose(UsbDevice device) {
            runOnUiThread(() -> {
                cameraReady = false;
                captureButton.setEnabled(false);
                previewPlaceholder.setVisibility(View.VISIBLE);
                setCameraState("Caméra fermée", R.color.warning);
                removePreviewSurface(cameraView.getHolder().getSurface());
            });
        }

        @Override
        public void onDeviceClose(UsbDevice device) {
            // onCameraClose porte l'état visible.
        }

        @Override
        public void onDetach(UsbDevice device) {
            if (!isSelectedCamera(device)) {
                return;
            }
            runOnUiThread(() -> {
                resetDetachedCameraState();
                usbInfo.setText(R.string.usb_expected);
            });
        }

        @Override
        public void onCancel(UsbDevice device) {
            if (!isSelectedCamera(device)) {
                return;
            }
            runOnUiThread(() -> {
                permissionDeniedDeviceId = device.getDeviceId();
                selectionRequested = false;
                showUsbPermissionDenied();
            });
        }
    };

    private void addPreviewSurfaceIfReady(Surface surface) {
        if (cameraReady && cameraHelper != null && surface != null && surface.isValid()) {
            cameraHelper.addSurface(surface, false);
        }
    }

    private void removePreviewSurface(Surface surface) {
        if (cameraHelper != null && surface != null) {
            try {
                cameraHelper.removeSurface(surface);
            } catch (RuntimeException ignored) {
                // La fermeture USB peut devancer la destruction de la Surface.
            }
        }
    }

    private void handlePrimaryActionOnMainThread(String source) {
        runOnUiThread(() -> handlePrimaryAction(source));
    }

    private void handlePrimaryAction(String source) {
        if (analysisInProgress || captureInProgress) {
            addKeyEvent(source + " · analyse en cours");
            return;
        }
        if (answerPlaybackActive) {
            toggleAnswerPlayback(source);
            return;
        }
        requestCapture(source);
    }

    private void requestCapture(String source) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastCaptureRequestAt < CAPTURE_DEBOUNCE_MS) {
            return;
        }
        lastCaptureRequestAt = now;
        addKeyEvent(source + " → Capture");

        if (!cameraReady || cameraHelper == null) {
            lastAction.setText("Capture impossible : caméra non prête");
            speak("Caméra non prête");
            return;
        }
        if (captureInProgress) {
            return;
        }

        if (!isAiConfigured()) {
            lastAction.setText("Configurez d’abord le serveur IA");
            aiStatus.setText("Configuration IA requise");
            aiStatus.setTextColor(getColor(R.color.warning));
            speak("Configuration I A requise");
            showSettingsDialog();
            return;
        }

        captureInProgress = true;
        captureButton.setEnabled(false);
        lastAction.setText("Capture en cours…");

        String fileName = "0rus_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date())
                + ".jpg";
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis());
        values.put(MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/0rus");

        ImageCapture.OutputFileOptions options = new ImageCapture.OutputFileOptions.Builder(
                getContentResolver(),
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values).build();

        cameraHelper.takePicture(options, new ImageCapture.OnImageCaptureCallback() {
            @Override
            public void onImageSaved(ImageCapture.OutputFileResults outputFileResults) {
                Uri savedUri = outputFileResults.getSavedUri();
                runOnUiThread(() -> {
                    captureInProgress = false;
                    lastAction.setText(savedUri == null
                            ? "Photo capturée : " + fileName
                            : "Photo capturée : " + savedUri);
                    addKeyEvent("JPEG enregistré · " + fileName);
                    if (savedUri == null) {
                        captureButton.setEnabled(cameraReady);
                        aiStatus.setText("Photo enregistrée, mais fichier inaccessible");
                        aiStatus.setTextColor(getColor(R.color.error));
                        speak("Photo inaccessible");
                    } else {
                        analyzeCapturedImage(savedUri);
                    }
                });
            }

            @Override
            public void onError(int imageCaptureError, String message, Throwable cause) {
                runOnUiThread(() -> {
                    captureInProgress = false;
                    captureButton.setEnabled(cameraReady);
                    lastAction.setText("Erreur de capture : " + message);
                    addKeyEvent("Erreur photo · code " + imageCaptureError);
                    speak("Erreur de capture");
                    updatePlaybackState();
                });
            }
        });
    }

    private void analyzeCapturedImage(Uri savedUri) {
        analysisInProgress = true;
        answerPlaybackActive = false;
        speechPaused = false;
        captureButton.setEnabled(false);
        captureButton.setText(R.string.analyzing);
        aiStatus.setText("Analyse de l’image et détection des questions…");
        aiStatus.setTextColor(getColor(R.color.warning));
        answerPosition.setText(R.string.analysis_in_progress);
        answerText.setText("0rus lit l’image, repère toutes les questions et choisit la longueur de réponse adaptée.");
        speak("Analyse en cours");

        aiClient.analyze(
                getContentResolver(),
                savedUri,
                preferences.getString(PREF_SERVER_URL, ""),
                preferences.getString(PREF_ACCESS_TOKEN, ""),
                preferences.getString(PREF_PREVIOUS_RESPONSE_ID, ""),
                preferences.getString(PREF_GUIDANCE, ""),
                new AiClient.Callback() {
                    @Override
                    public void onSuccess(AiResponse response) {
                        runOnUiThread(() -> showAiResponse(response));
                    }

                    @Override
                    public void onError(String message) {
                        runOnUiThread(() -> showAiError(message));
                    }
                });
    }

    private void showAiResponse(AiResponse response) {
        analysisInProgress = false;
        captureButton.setEnabled(cameraReady);
        captureButton.setText(R.string.capture);
        currentResponse = response;
        currentSectionIndex = 0;
        if (!response.responseId.isBlank()) {
            preferences.edit().putString(PREF_PREVIOUS_RESPONSE_ID, response.responseId).apply();
        }
        aiStatus.setText(response.sections.size() == 1
                ? "1 question détectée · réponse prête"
                : response.sections.size() + " questions détectées · réponses prêtes");
        aiStatus.setTextColor(getColor(R.color.accent));
        lastAction.setText("Lecture/Pause = pause · Suivant/Précédent = changer de question");
        renderCurrentSection();
        answerPlaybackActive = true;
        speechPaused = false;
        speakCurrentSection();
    }

    private void showAiError(String message) {
        analysisInProgress = false;
        answerPlaybackActive = false;
        speechPaused = false;
        captureButton.setEnabled(cameraReady);
        captureButton.setText(R.string.capture);
        aiStatus.setText("Analyse impossible");
        aiStatus.setTextColor(getColor(R.color.error));
        answerPosition.setText(R.string.analysis_error);
        answerText.setText(message);
        lastAction.setText("Vérifiez Internet, l’adresse du serveur et le jeton");
        speak("Analyse impossible");
        updatePlaybackState();
    }

    private void renderCurrentSection() {
        if (currentResponse == null || currentResponse.sections.isEmpty()) {
            return;
        }
        AiResponse.Section section = currentResponse.sections.get(currentSectionIndex);
        answerPosition.setText((currentSectionIndex + 1) + " / "
                + currentResponse.sections.size());
        StringBuilder display = new StringBuilder();
        if (!currentResponse.overview.isBlank()) {
            display.append(currentResponse.overview).append("\n\n");
        }
        display.append(section.displayText());
        if (!currentResponse.sourcesUsed.isEmpty()) {
            display.append("\n\nSources : ")
                    .append(TextUtils.join(" · ", currentResponse.sourcesUsed));
        }
        answerText.setText(display.toString());
    }

    private void speakCurrentSection() {
        if (currentResponse == null || currentResponse.sections.isEmpty()) {
            answerPlaybackActive = false;
            return;
        }
        if (!ttsReady || textToSpeech == null) {
            answerPlaybackActive = false;
            aiStatus.setText("Réponse affichée · synthèse vocale indisponible");
            return;
        }
        AiResponse.Section section = currentResponse.sections.get(currentSectionIndex);
        speechPaused = false;
        answerPlaybackActive = true;
        textToSpeech.speak(
                section.spokenAnswer,
                TextToSpeech.QUEUE_FLUSH,
                null,
                UTTERANCE_SECTION_PREFIX + currentSectionIndex);
        updatePlaybackState();
    }

    private void toggleAnswerPlayback(String source) {
        if (textToSpeech != null && textToSpeech.isSpeaking()) {
            textToSpeech.stop();
            speechPaused = true;
            addKeyEvent(source + " → Pause réponse");
            aiStatus.setText("Lecture en pause · Lecture/Pause pour reprendre");
            updatePlaybackState();
            return;
        }
        if (speechPaused) {
            addKeyEvent(source + " → Reprendre réponse");
            aiStatus.setText("Lecture de la réponse…");
            speakCurrentSection();
            return;
        }
        answerPlaybackActive = false;
        requestCapture(source);
    }

    private void moveAnswerSection(int delta, String source) {
        addKeyEvent((delta > 0 ? "Suivant" : "Précédent") + " · " + source);
        if (currentResponse == null || currentResponse.sections.isEmpty()) {
            return;
        }
        int target = Math.max(0, Math.min(
                currentResponse.sections.size() - 1,
                currentSectionIndex + delta));
        currentSectionIndex = target;
        renderCurrentSection();
        speakCurrentSection();
    }

    private void handleSectionSpeechFinished(String utteranceId) {
        String expected = UTTERANCE_SECTION_PREFIX + currentSectionIndex;
        if (!expected.equals(utteranceId) || speechPaused) {
            return;
        }
        if (currentResponse != null && currentSectionIndex + 1 < currentResponse.sections.size()) {
            currentSectionIndex++;
            renderCurrentSection();
            speakCurrentSection();
        } else {
            answerPlaybackActive = false;
            speechPaused = false;
            aiStatus.setText("Lecture terminée · Lecture/Pause pour une nouvelle capture");
            updatePlaybackState();
        }
    }

    private void stopAnswerPlayback() {
        if (textToSpeech != null && textToSpeech.isSpeaking()) {
            textToSpeech.stop();
        }
        answerPlaybackActive = false;
        speechPaused = false;
        updatePlaybackState();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            boolean handled = handleHardwareKey(event.getKeyCode(), "Clavier");
            if (handled) {
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private boolean handleHardwareKey(int keyCode, String source) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
            case KeyEvent.KEYCODE_CAMERA:
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_ENTER:
                handlePrimaryActionOnMainThread(source + " · " + KeyEvent.keyCodeToString(keyCode));
                return true;

            case KeyEvent.KEYCODE_MEDIA_NEXT:
                runOnUiThread(() -> moveAnswerSection(1, source));
                return true;

            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                runOnUiThread(() -> moveAnswerSection(-1, source));
                return true;

            case KeyEvent.KEYCODE_VOLUME_UP:
                addKeyEventOnMainThread("Volume + · " + source);
                return false;

            case KeyEvent.KEYCODE_VOLUME_DOWN:
                addKeyEventOnMainThread("Volume − · " + source);
                return false;

            default:
                return false;
        }
    }

    private void addKeyEventOnMainThread(String label) {
        runOnUiThread(() -> addKeyEvent(label));
    }

    private void addKeyEvent(String label) {
        keyEvents.addFirst(clockFormat.format(new Date()) + "  " + label);
        while (keyEvents.size() > 3) {
            keyEvents.removeLast();
        }
        keyLog.setText(TextUtils.join("\n", keyEvents));
    }

    private void setCameraState(String text, int colorResource) {
        cameraStatus.setText(text);
        cameraStatus.setTextColor(getColor(colorResource));
    }

    private void speak(String text) {
        if (ttsReady && textToSpeech != null) {
            textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, "0rus-status");
        }
    }
}
