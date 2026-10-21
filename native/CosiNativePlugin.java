package com.coslife.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.Settings;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Ponte entre o app (JavaScript) e recursos nativos do Android: Cosi flutuante e biometria. */
@CapacitorPlugin(name = "CosiNative")
public class CosiNativePlugin extends Plugin {
    private static final String ALIAS = "coslife_bio_key";

    private SharedPreferences prefs() {
        return getContext().getSharedPreferences("cosi", Context.MODE_PRIVATE);
    }

    // ---------------- Cosi flutuante (overlay) ----------------
    @PluginMethod
    public void overlayStatus(PluginCall call) {
        JSObject r = new JSObject();
        r.put("granted", Settings.canDrawOverlays(getContext()));
        r.put("running", CosiOverlayService.running);
        call.resolve(r);
    }

    @PluginMethod
    public void overlayRequest(PluginCall call) {
        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getContext().getPackageName()));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(i);
        call.resolve();
    }

    @PluginMethod
    public void overlayStart(PluginCall call) {
        if (!Settings.canDrawOverlays(getContext())) {
            call.reject("sem_permissao");
            return;
        }
        saveState(call);
        ContextCompat.startForegroundService(getContext(), new Intent(getContext(), CosiOverlayService.class));
        call.resolve();
    }

    @PluginMethod
    public void overlayStop(PluginCall call) {
        getContext().stopService(new Intent(getContext(), CosiOverlayService.class));
        call.resolve();
    }

    @PluginMethod
    public void overlayState(PluginCall call) {
        saveState(call);
        call.resolve();
    }

    /** Esconde o Cosi flutuante enquanto o app esta aberto (evita dois Cosis na tela). */
    @PluginMethod
    public void overlayShow(PluginCall call) {
        CosiOverlayService.show(Boolean.TRUE.equals(call.getBoolean("show", true)));
        call.resolve();
    }

    private void saveState(PluginCall c) {
        SharedPreferences.Editor e = prefs().edit();
        if (c.hasOption("mood")) e.putString("mood", c.getString("mood"));
        if (c.hasOption("stage")) e.putInt("stage", c.getInt("stage", 0));
        if (c.hasOption("sleepStart")) e.putInt("ss", c.getInt("sleepStart", 1350));
        if (c.hasOption("sleepEnd")) e.putInt("se", c.getInt("sleepEnd", 390));
        e.apply();
    }

    // ---------------- Biometria (guarda o PIN no Keystore, liberado so com digital/rosto) ----------------
    @PluginMethod
    public void bioAvailable(PluginCall call) {
        JSObject r = new JSObject();
        int ok = BiometricManager.from(getContext()).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG);
        r.put("available", ok == BiometricManager.BIOMETRIC_SUCCESS);
        r.put("enrolled", prefs().contains("ct"));
        call.resolve(r);
    }

    @PluginMethod
    public void bioClear(PluginCall call) {
        prefs().edit().remove("ct").remove("iv").apply();
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            ks.deleteEntry(ALIAS);
        } catch (Exception ignored) { }
        call.resolve();
    }

    private SecretKey key(boolean create) throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (create || !ks.containsAlias(ALIAS)) {
            if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS);
            KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            g.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(true)
                    .setInvalidatedByBiometricEnrollment(true)
                    .build());
            return g.generateKey();
        }
        return (SecretKey) ks.getKey(ALIAS, null);
    }

    private void prompt(final Cipher cipher, final String title, final BiometricPrompt.AuthenticationCallback cb) {
        final FragmentActivity a = (FragmentActivity) getActivity();
        a.runOnUiThread(() -> {
            BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                    .setTitle(title)
                    .setNegativeButtonText("Usar PIN")
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    .build();
            new BiometricPrompt(a, ContextCompat.getMainExecutor(a), cb)
                    .authenticate(info, new BiometricPrompt.CryptoObject(cipher));
        });
    }

    @PluginMethod
    public void bioEnroll(final PluginCall call) {
        final String pin = call.getString("pin");
        if (pin == null) { call.reject("pin"); return; }
        try {
            Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
            ci.init(Cipher.ENCRYPT_MODE, key(true));
            prompt(ci, "Ativar biometria", new BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r) {
                    try {
                        Cipher x = r.getCryptoObject().getCipher();
                        byte[] ct = x.doFinal(pin.getBytes("UTF-8"));
                        prefs().edit()
                                .putString("ct", Base64.encodeToString(ct, Base64.NO_WRAP))
                                .putString("iv", Base64.encodeToString(x.getIV(), Base64.NO_WRAP))
                                .apply();
                        call.resolve();
                    } catch (Exception e) {
                        call.reject("erro");
                    }
                }

                @Override
                public void onAuthenticationError(int code, CharSequence msg) {
                    call.reject("cancelado");
                }
            });
        } catch (Exception e) {
            call.reject("erro: " + e.getMessage());
        }
    }

    @PluginMethod
    public void bioUnlock(final PluginCall call) {
        final String ct = prefs().getString("ct", null);
        final String iv = prefs().getString("iv", null);
        if (ct == null || iv == null) { call.reject("sem_biometria"); return; }
        try {
            Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
            ci.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
            prompt(ci, "Desbloquear CosLife", new BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r) {
                    try {
                        byte[] pt = r.getCryptoObject().getCipher().doFinal(Base64.decode(ct, Base64.NO_WRAP));
                        JSObject o = new JSObject();
                        o.put("pin", new String(pt, "UTF-8"));
                        call.resolve(o);
                    } catch (Exception e) {
                        call.reject("erro");
                    }
                }

                @Override
                public void onAuthenticationError(int code, CharSequence msg) {
                    call.reject("cancelado");
                }
            });
        } catch (Exception e) {
            call.reject("erro: " + e.getMessage());
        }
    }
}
