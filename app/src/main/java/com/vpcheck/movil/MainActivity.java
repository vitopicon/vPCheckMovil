package com.vpcheck.movil;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.nfc.NfcAdapter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.StatFs;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.MediaStore;
import android.telephony.TelephonyManager;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * vPCheck Móvil: muestra la app de pruebas (assets/index.html) en un WebView
 * y le da acceso a lo que el navegador no puede leer (batería, botones, auricular,
 * proximidad, linterna, guardar informes en Descargas...).
 */
public class MainActivity extends Activity {

    private static final String URL_APP = "https://appassets.androidplatform.net/assets/index.html";
    private static final int PERMISOS_MEDIA = 1;
    private static final int PERMISOS_UBICACION = 2;
    private static final int UI_NORMAL = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;

    private WebView web;
    private PermissionRequest peticionPendiente;
    private GeolocationPermissions.Callback geoCallback;
    private String geoOrigen;

    private volatile boolean capturarTeclas = false;
    private SensorManager sensores;
    private SensorEventListener oyenteSensores;
    private ToneGenerator tono;
    private long ultimaLuz = 0;

    private int pantallaAncho, pantallaAlto;
    private float refresco;

    // ------------------------------------------------------------------
    //  Ciclo de vida
    // ------------------------------------------------------------------
    @Override
    @SuppressWarnings("deprecation")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Color.WHITE);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        getWindow().getDecorView().setSystemUiVisibility(UI_NORMAL);

        Display d = getWindowManager().getDefaultDisplay();
        DisplayMetrics dm = new DisplayMetrics();
        d.getRealMetrics(dm);
        pantallaAncho = Math.min(dm.widthPixels, dm.heightPixels);
        pantallaAlto = Math.max(dm.widthPixels, dm.heightPixels);
        refresco = d.getRefreshRate();

        sensores = (SensorManager) getSystemService(SENSOR_SERVICE);

        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setGeolocationEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setTextZoom(100);

        final WebViewAssetLoader cargador = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return cargador.shouldInterceptRequest(r.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if ("appassets.androidplatform.net".equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }

            @Override
            public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail detail) {
                // Si el motor del navegador se cierra (p. ej. por falta de memoria), se reinicia la app
                recreate();
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest req) {
                runOnUiThread(() -> gestionarPermiso(req));
            }

            @Override
            public void onPermissionRequestCanceled(PermissionRequest req) {
                if (req == peticionPendiente) peticionPendiente = null;
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origen, GeolocationPermissions.Callback cb) {
                if (tiene(Manifest.permission.ACCESS_FINE_LOCATION) || tiene(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                    cb.invoke(origen, true, false);
                } else {
                    geoOrigen = origen;
                    geoCallback = cb;
                    requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, PERMISOS_UBICACION);
                }
            }
        });

        web.addJavascriptInterface(new Puente(), "VPCNative");
        web.loadUrl(URL_APP);
    }

    @Override
    protected void onPause() {
        super.onPause();
        emitir("pause", "{}");
    }

    @Override
    protected void onResume() {
        super.onResume();
        emitir("resume", "{}");
    }

    @Override
    protected void onDestroy() {
        pararSensores();
        auricular(false);
        if (web != null) web.destroy();
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        // La app web decide (cerrar un diálogo, cancelar la revisión o salir)
        emitir("back", "{}");
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean fisica = k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN
                || k == KeyEvent.KEYCODE_CAMERA || k == KeyEvent.KEYCODE_HEADSETHOOK
                || k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || k == KeyEvent.KEYCODE_FOCUS;
        if (capturarTeclas && fisica) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                emitir("key", "{\"k\":\"" + KeyEvent.keyCodeToString(k) + "\"}");
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    // ------------------------------------------------------------------
    //  Permisos (cámara, micrófono, ubicación)
    // ------------------------------------------------------------------
    private boolean tiene(String permiso) {
        return checkSelfPermission(permiso) == PackageManager.PERMISSION_GRANTED;
    }

    private void gestionarPermiso(PermissionRequest req) {
        List<String> faltan = new ArrayList<>();
        for (String r : req.getResources()) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r) && !tiene(Manifest.permission.CAMERA)) faltan.add(Manifest.permission.CAMERA);
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r) && !tiene(Manifest.permission.RECORD_AUDIO)) faltan.add(Manifest.permission.RECORD_AUDIO);
        }
        if (faltan.isEmpty()) {
            req.grant(req.getResources());
        } else {
            peticionPendiente = req;
            requestPermissions(faltan.toArray(new String[0]), PERMISOS_MEDIA);
        }
    }

    @Override
    public void onRequestPermissionsResult(int codigo, String[] permisos, int[] resultados) {
        super.onRequestPermissionsResult(codigo, permisos, resultados);
        if (codigo == PERMISOS_MEDIA && peticionPendiente != null) {
            List<String> concedidos = new ArrayList<>();
            for (String r : peticionPendiente.getResources()) {
                if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r) && tiene(Manifest.permission.CAMERA)) concedidos.add(r);
                if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r) && tiene(Manifest.permission.RECORD_AUDIO)) concedidos.add(r);
            }
            if (concedidos.isEmpty()) peticionPendiente.deny();
            else peticionPendiente.grant(concedidos.toArray(new String[0]));
            peticionPendiente = null;
        } else if (codigo == PERMISOS_UBICACION && geoCallback != null) {
            boolean ok = tiene(Manifest.permission.ACCESS_FINE_LOCATION) || tiene(Manifest.permission.ACCESS_COARSE_LOCATION);
            geoCallback.invoke(geoOrigen, ok, false);
            geoCallback = null;
        }
    }

    // ------------------------------------------------------------------
    //  Utilidades
    // ------------------------------------------------------------------
    private void emitir(String tipo, String json) {
        if (web == null) return;
        final String js = "window.vpcEvent&&window.vpcEvent('" + tipo + "'," + json + ")";
        runOnUiThread(() -> { if (web != null) web.evaluateJavascript(js, null); });
    }

    @SuppressWarnings("deprecation")
    private void inmersivo(boolean on) {
        runOnUiThread(() -> {
            View v = getWindow().getDecorView();
            if (on) {
                v.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
            } else {
                v.setSystemUiVisibility(UI_NORMAL);
            }
        });
    }

    private void pararSensores() {
        if (oyenteSensores != null && sensores != null) sensores.unregisterListener(oyenteSensores);
        oyenteSensores = null;
    }

    private synchronized void auricular(boolean on) {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        try {
            if (on) {
                am.setMode(AudioManager.MODE_IN_COMMUNICATION);
                if (Build.VERSION.SDK_INT >= 31) {
                    for (AudioDeviceInfo d : am.getAvailableCommunicationDevices()) {
                        if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) { am.setCommunicationDevice(d); break; }
                    }
                } else {
                    setAltavozManosLibres(am, false);
                }
                try { am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL), 0); } catch (Exception ignored) { }
                if (tono != null) { tono.release(); }
                tono = new ToneGenerator(AudioManager.STREAM_VOICE_CALL, 100);
                tono.startTone(ToneGenerator.TONE_SUP_DIAL, 3000);
            } else {
                if (tono != null) { tono.stopTone(); tono.release(); tono = null; }
                if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice();
                am.setMode(AudioManager.MODE_NORMAL);
            }
        } catch (Exception ignored) { }
    }

    @SuppressWarnings("deprecation")
    private static void setAltavozManosLibres(AudioManager am, boolean on) {
        am.setSpeakerphoneOn(on);
    }

    // ------------------------------------------------------------------
    //  Puente con la página (window.VPCNative)
    // ------------------------------------------------------------------
    private class Puente {

        @JavascriptInterface
        public String version() { return "1.0"; }

        @JavascriptInterface
        public String getInfo() {
            JSONObject o = new JSONObject();
            try {
                PackageManager pm = getPackageManager();
                o.put("manufacturer", Build.MANUFACTURER);
                o.put("brand", Build.BRAND);
                o.put("model", Build.MODEL);
                o.put("device", Build.DEVICE);
                o.put("product", Build.PRODUCT);
                o.put("hardware", Build.HARDWARE);
                o.put("release", Build.VERSION.RELEASE);
                o.put("sdk", Build.VERSION.SDK_INT);
                o.put("securityPatch", Build.VERSION.SECURITY_PATCH);
                if (Build.VERSION.SDK_INT >= 31) {
                    o.put("soc", Build.SOC_MODEL);
                    o.put("socManufacturer", Build.SOC_MANUFACTURER);
                }

                ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                o.put("ramTotal", mi.totalMem);

                StatFs st = new StatFs(Environment.getDataDirectory().getPath());
                o.put("storageTotal", st.getTotalBytes());
                o.put("storageFree", st.getAvailableBytes());

                o.put("screenW", pantallaAncho);
                o.put("screenH", pantallaAlto);
                o.put("refresh", refresco);

                o.put("hasFingerprint", pm.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT));
                o.put("hasFace", Build.VERSION.SDK_INT >= 29 && pm.hasSystemFeature("android.hardware.biometrics.face"));
                o.put("hasNfc", pm.hasSystemFeature(PackageManager.FEATURE_NFC));
                o.put("hasFlash", pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH));

                int camaras = 0;
                try { camaras = ((CameraManager) getSystemService(CAMERA_SERVICE)).getCameraIdList().length; } catch (Exception ignored) { }
                o.put("cameras", camaras);

                JSONObject sen = new JSONObject();
                sen.put("acc", sensores.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null);
                sen.put("gyr", sensores.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null);
                sen.put("mag", sensores.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) != null);
                sen.put("prox", sensores.getDefaultSensor(Sensor.TYPE_PROXIMITY) != null);
                sen.put("luz", sensores.getDefaultSensor(Sensor.TYPE_LIGHT) != null);
                sen.put("baro", sensores.getDefaultSensor(Sensor.TYPE_PRESSURE) != null);
                o.put("sensors", sen);
            } catch (Exception ignored) { }
            return o.toString();
        }

        @JavascriptInterface
        public String getBattery() {
            JSONObject o = new JSONObject();
            try {
                Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                if (b == null) return "{}";
                int nivel = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int escala = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                int pct = escala > 0 ? Math.round(nivel * 100f / escala) : nivel;
                int enchufe = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
                o.put("level", pct);
                o.put("plugged", enchufe);
                o.put("pluggedTxt", enchufe == BatteryManager.BATTERY_PLUGGED_AC ? "cargador"
                        : enchufe == BatteryManager.BATTERY_PLUGGED_USB ? "USB"
                        : enchufe == BatteryManager.BATTERY_PLUGGED_WIRELESS ? "inalámbrica" : "");
                o.put("status", b.getIntExtra(BatteryManager.EXTRA_STATUS, -1));
                o.put("health", b.getIntExtra(BatteryManager.EXTRA_HEALTH, 1));
                o.put("tempC", b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0);
                o.put("voltage", b.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0));
                o.put("technology", b.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY));
                o.put("cycles", b.getIntExtra("android.os.extra.CYCLE_COUNT", -1));   // Android 14+

                // Capacidad actual estimada = carga restante / porcentaje
                BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
                int cc = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
                if (cc > 0 && pct > 0) {
                    double mah = cc > 50000 ? cc / 1000.0 : cc;   // casi todos dan µAh; algunos, mAh
                    o.put("fullMah", mah * 100.0 / pct);
                }
                // Capacidad de diseño (dato interno de Android; no todos los móviles lo exponen)
                try {
                    Class<?> c = Class.forName("com.android.internal.os.PowerProfile");
                    Object pp = c.getConstructor(Context.class).newInstance(MainActivity.this);
                    Object v = c.getMethod("getBatteryCapacity").invoke(pp);
                    double dis = ((Number) v).doubleValue();
                    if (dis > 500) o.put("designMah", dis);
                } catch (Throwable ignored) { }
            } catch (Exception ignored) { }
            return o.toString();
        }

        @JavascriptInterface
        @SuppressWarnings("deprecation")
        public String getNet() {
            JSONObject o = new JSONObject();
            try {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
                Network nw = cm.getActiveNetwork();
                NetworkCapabilities nc = nw != null ? cm.getNetworkCapabilities(nw) : null;
                boolean wifi = nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
                o.put("wifi", wifi);
                o.put("cell", nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR));
                o.put("eth", nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
                o.put("validated", nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));

                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                o.put("wifiOn", wm != null && wm.isWifiEnabled());
                if (wifi && wm != null) {
                    WifiInfo wi = wm.getConnectionInfo();
                    if (wi != null) {
                        int rssi = wi.getRssi();
                        if (rssi < 0 && rssi > -127) o.put("wifiDbm", rssi);
                        if (wi.getLinkSpeed() > 0) o.put("wifiMbps", wi.getLinkSpeed());
                    }
                }

                PackageManager pm = getPackageManager();
                BluetoothManager btm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
                BluetoothAdapter ba = btm != null ? btm.getAdapter() : null;
                o.put("btPresent", ba != null && pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH));
                boolean btOn = false;
                try { btOn = ba != null && ba.isEnabled(); } catch (SecurityException ignored) { }
                o.put("btOn", btOn);

                NfcAdapter nfc = NfcAdapter.getDefaultAdapter(MainActivity.this);
                o.put("nfcPresent", nfc != null);
                o.put("nfcOn", nfc != null && nfc.isEnabled());

                TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
                if (tm != null && pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
                    o.put("simReady", tm.getSimState() == TelephonyManager.SIM_STATE_READY);
                    o.put("operator", tm.getSimOperatorName());
                    o.put("slots", Build.VERSION.SDK_INT >= 30 ? tm.getActiveModemCount() : tm.getPhoneCount());
                }
            } catch (Exception ignored) { }
            return o.toString();
        }

        @JavascriptInterface
        public String getAudioOutputs() {
            JSONObject o = new JSONObject();
            try {
                AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
                boolean cable = false;
                String nombre = "";
                for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                    int t = d.getType();
                    if (t == AudioDeviceInfo.TYPE_WIRED_HEADSET || t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || t == AudioDeviceInfo.TYPE_USB_HEADSET) {
                        cable = true;
                        nombre = t == AudioDeviceInfo.TYPE_USB_HEADSET ? "USB-C" : "jack";
                    }
                }
                o.put("wired", cable);
                o.put("wiredName", nombre);
            } catch (Exception ignored) { }
            return o.toString();
        }

        @JavascriptInterface
        public void vibrate(int ms) {
            try {
                Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
                if (v != null) v.vibrate(VibrationEffect.createOneShot(Math.max(50, ms), VibrationEffect.DEFAULT_AMPLITUDE));
            } catch (Exception ignored) { }
        }

        @JavascriptInterface
        public boolean torch(boolean on) {
            try {
                CameraManager cm = (CameraManager) getSystemService(CAMERA_SERVICE);
                for (String id : cm.getCameraIdList()) {
                    CameraCharacteristics c = cm.getCameraCharacteristics(id);
                    Boolean flash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                    Integer lado = c.get(CameraCharacteristics.LENS_FACING);
                    if (Boolean.TRUE.equals(flash) && lado != null && lado == CameraCharacteristics.LENS_FACING_BACK) {
                        cm.setTorchMode(id, on);
                        return true;
                    }
                }
            } catch (Exception ignored) { }
            return false;
        }

        @JavascriptInterface
        public void earpiece(boolean on) { auricular(on); }

        @JavascriptInterface
        public void setMaxVolume() {
            try {
                AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
                am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0);
            } catch (Exception ignored) { }
        }

        @JavascriptInterface
        public String startSensors() {
            pararSensores();
            final Sensor prox = sensores.getDefaultSensor(Sensor.TYPE_PROXIMITY);
            final Sensor luz = sensores.getDefaultSensor(Sensor.TYPE_LIGHT);
            final Sensor acc = sensores.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            final Sensor gyr = sensores.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
            final Sensor mag = sensores.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
            final long[] ultimo = new long[3];
            oyenteSensores = new SensorEventListener() {
                @Override
                public void onSensorChanged(SensorEvent e) {
                    int t = e.sensor.getType();
                    int i = t == Sensor.TYPE_ACCELEROMETER ? 0 : t == Sensor.TYPE_GYROSCOPE ? 1 : t == Sensor.TYPE_MAGNETIC_FIELD ? 2 : -1;
                    if (i >= 0) {
                        long ahora = System.currentTimeMillis();
                        if (ahora - ultimo[i] < 120) return;
                        ultimo[i] = ahora;
                        String n = i == 0 ? "acc" : i == 1 ? "gyr" : "mag";
                        emitir("sensor", "{\"t\":\"" + n + "\",\"x\":" + e.values[0] + ",\"y\":" + e.values[1] + ",\"z\":" + e.values[2] + "}");
                        return;
                    }
                    if (t == Sensor.TYPE_PROXIMITY) {
                        emitir("sensor", "{\"t\":\"prox\",\"v\":" + e.values[0] + ",\"max\":" + e.sensor.getMaximumRange() + "}");
                    } else if (e.sensor.getType() == Sensor.TYPE_LIGHT) {
                        long ahora = System.currentTimeMillis();
                        if (ahora - ultimaLuz < 150) return;
                        ultimaLuz = ahora;
                        emitir("sensor", "{\"t\":\"luz\",\"v\":" + e.values[0] + "}");
                    }
                }

                @Override
                public void onAccuracyChanged(Sensor s, int a) { }
            };
            if (prox != null) sensores.registerListener(oyenteSensores, prox, SensorManager.SENSOR_DELAY_UI);
            if (luz != null) sensores.registerListener(oyenteSensores, luz, SensorManager.SENSOR_DELAY_UI);
            if (acc != null) sensores.registerListener(oyenteSensores, acc, SensorManager.SENSOR_DELAY_UI);
            if (gyr != null) sensores.registerListener(oyenteSensores, gyr, SensorManager.SENSOR_DELAY_UI);
            if (mag != null) sensores.registerListener(oyenteSensores, mag, SensorManager.SENSOR_DELAY_UI);
            return "{\"prox\":" + (prox != null) + ",\"luz\":" + (luz != null) + "}";
        }

        @JavascriptInterface
        public void stopSensors() { pararSensores(); }

        @JavascriptInterface
        public void captureKeys(boolean on) { capturarTeclas = on; }

        @JavascriptInterface
        public void setImmersive(boolean on) { inmersivo(on); }

        /** Guarda (o sobrescribe) un archivo en Descargas/vPCheck. Devuelve la ruta o "ERROR: ...". */
        @JavascriptInterface
        public String saveFile(String nombre, String tipo, String contenido) {
            try {
                byte[] datos = contenido.getBytes(StandardCharsets.UTF_8);
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentResolver cr = getContentResolver();
                    Uri coleccion = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                    String carpeta = Environment.DIRECTORY_DOWNLOADS + "/vPCheck/";
                    Uri uri = null;
                    try (Cursor c = cr.query(coleccion, new String[]{MediaStore.MediaColumns._ID},
                            MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + MediaStore.MediaColumns.RELATIVE_PATH + "=?",
                            new String[]{nombre, carpeta}, null)) {
                        if (c != null && c.moveToFirst()) uri = ContentUris.withAppendedId(coleccion, c.getLong(0));
                    } catch (Exception ignored) { }
                    if (uri == null) {
                        ContentValues cv = new ContentValues();
                        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, nombre);
                        cv.put(MediaStore.MediaColumns.MIME_TYPE, tipo);
                        cv.put(MediaStore.MediaColumns.RELATIVE_PATH, carpeta);
                        uri = cr.insert(coleccion, cv);
                    }
                    if (uri == null) return "ERROR: no se pudo crear el archivo";
                    try (OutputStream os = cr.openOutputStream(uri, "wt")) {
                        if (os == null) return "ERROR: no se pudo abrir el archivo";
                        os.write(datos);
                    }
                    return "Descargas/vPCheck/" + nombre;
                } else {
                    File dir = new File(getExternalFilesDir(null), "Informes");
                    if (!dir.exists() && !dir.mkdirs()) return "ERROR: no se pudo crear la carpeta";
                    File f = new File(dir, nombre);
                    try (FileOutputStream fo = new FileOutputStream(f, false)) { fo.write(datos); }
                    return f.getAbsolutePath();
                }
            } catch (Exception e) {
                return "ERROR: " + e.getMessage();
            }
        }

        @JavascriptInterface
        public void share(String nombre, String tipo, String contenido) {
            try {
                File dir = new File(getCacheDir(), "compartir");
                if (!dir.exists()) dir.mkdirs();
                File f = new File(dir, nombre);
                try (FileOutputStream fo = new FileOutputStream(f, false)) { fo.write(contenido.getBytes(StandardCharsets.UTF_8)); }
                Uri uri = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".archivos", f);
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType(tipo);
                i.putExtra(Intent.EXTRA_STREAM, uri);
                i.putExtra(Intent.EXTRA_SUBJECT, "Informe vPCheck");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                runOnUiThread(() -> startActivity(Intent.createChooser(i, "Compartir informe")));
            } catch (Exception ignored) { }
        }

        @JavascriptInterface
        public void exitApp() { runOnUiThread(MainActivity.this::finish); }
    }
}
