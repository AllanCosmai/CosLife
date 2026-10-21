package com.coslife.app;

import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebView;

import java.util.Calendar;
import java.util.Random;

/**
 * Servico em primeiro plano que desenha o Cosi (o mesmo boneco animado do app, em um WebView)
 * por cima dos outros aplicativos. O Android exige a notificacao fixa "Cosi esta ativo".
 */
public class CosiOverlayService extends Service {
    public static boolean running = false;
    private static CosiOverlayService instance;

    private WindowManager wm;
    private WebView web;
    private WindowManager.LayoutParams lp;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();
    private Runnable longPress, tick;
    private boolean dragging = false, moved = false, walking = false;
    private float downX, downY, density;
    private int startX, startY, screenW, screenH;
    private long nextWalk = 0;

    public static void show(final boolean show) {
        final CosiOverlayService s = instance;
        if (s == null) return;
        s.h.post(() -> { if (s.web != null) s.web.setVisibility(show ? View.VISIBLE : View.GONE); });
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        if (i != null && "STOP".equals(i.getAction())) { stopSelf(); return START_NOT_STICKY; }
        startForegroundNotification();
        if (web == null) buildOverlay();
        instance = this;
        running = true;
        return START_STICKY;
    }

    private void startForegroundNotification() {
        String ch = "cosi_ativo";
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(ch, "Cosi ativo", NotificationManager.IMPORTANCE_MIN));
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                getPackageManager().getLaunchIntentForPackage(getPackageName()), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, CosiOverlayService.class).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, ch) : new Notification.Builder(this);
        b.setContentTitle("Cosi está ativo")
                .setContentText("Toque para abrir o CosLife")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(0, "Parar", stop).build());
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(7, b.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(7, b.build());
        }
    }

    private void buildOverlay() {
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getMetrics(dm);
        density = dm.density; screenW = dm.widthPixels; screenH = dm.heightPixels;
        lp = new WindowManager.LayoutParams((int) (100 * density), (int) (130 * density),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = screenW - lp.width - (int) (8 * density);
        lp.y = (int) (screenH * 0.6f);

        web = new WebView(this);
        web.setBackgroundColor(Color.TRANSPARENT);
        web.getSettings().setJavaScriptEnabled(true);
        web.setOnTouchListener(this::onTouch);
        web.loadUrl("file:///android_asset/public/cosi-overlay.html");
        wm.addView(web, lp);

        tick = new Runnable() {
            @Override public void run() { onTick(); h.postDelayed(this, 8000); }
        };
        h.postDelayed(tick, 2500);
    }

    private int clamp(int v, int a, int b) { return Math.max(a, Math.min(b, v)); }

    private void js(String code) { if (web != null) web.evaluateJavascript(code, null); }

    private boolean sleeping() {
        SharedPreferences p = getSharedPreferences("cosi", MODE_PRIVATE);
        int ss = p.getInt("ss", 1350), se = p.getInt("se", 390);
        Calendar c = Calendar.getInstance();
        int mm = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        return ss > se ? (mm >= ss || mm < se) : (mm >= ss && mm < se);
    }

    private void onTick() {
        if (web == null || dragging || walking) return;
        SharedPreferences p = getSharedPreferences("cosi", MODE_PRIVATE);
        js("setMood('" + p.getString("mood", "calm") + "'," + p.getInt("stage", 0) + ")");
        boolean sl = sleeping();
        js("setSleep(" + sl + ")");
        if (sl || SystemClock.uptimeMillis() < nextWalk || rnd.nextInt(3) == 0) return;
        final int tx = rnd.nextInt(Math.max(1, screenW - lp.width));
        final int ty = (int) (screenH * (0.35f + rnd.nextFloat() * 0.5f));
        final int fx = lp.x, fy = lp.y;
        final int dir = tx < fx ? -1 : 1;
        long dur = (long) (Math.hypot(tx - fx, ty - fy) / density * 14);
        walking = true;
        js("setWalk(true," + dir + ")");
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(Math.max(1500, dur));
        a.addUpdateListener(v -> {
            if (dragging || web == null) { v.cancel(); return; }
            float f = (float) v.getAnimatedValue();
            lp.x = (int) (fx + (tx - fx) * f); lp.y = (int) (fy + (ty - fy) * f);
            try { wm.updateViewLayout(web, lp); } catch (Exception ignored) { }
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator an) { walking = false; js("setWalk(false,1)"); }
            @Override public void onAnimationCancel(android.animation.Animator an) { walking = false; js("setWalk(false,1)"); }
        });
        a.start();
    }

    private void vibrate() {
        try {
            Vibrator vb = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) vb.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE));
            else vb.vibrate(20);
        } catch (Exception ignored) { }
    }

    private boolean onTouch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getRawX(); downY = e.getRawY(); startX = lp.x; startY = lp.y;
                dragging = false; moved = false;
                longPress = () -> { dragging = true; vibrate(); js("setSleep(false);setHeld(true)"); };
                h.postDelayed(longPress, 350);
                return true;
            case MotionEvent.ACTION_MOVE:
                float mx = e.getRawX() - downX, my = e.getRawY() - downY;
                if (!dragging && Math.hypot(mx, my) > 12 * density) { h.removeCallbacks(longPress); moved = true; }
                if (dragging) {
                    lp.x = clamp((int) (startX + mx), 0, screenW - lp.width);
                    lp.y = clamp((int) (startY + my), 0, screenH - lp.height);
                    wm.updateViewLayout(web, lp);
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                h.removeCallbacks(longPress);
                if (dragging) {
                    dragging = false; js("setHeld(false)");
                    nextWalk = SystemClock.uptimeMillis() + 20000;
                } else if (!moved && e.getActionMasked() == MotionEvent.ACTION_UP) {
                    Intent i = getPackageManager().getLaunchIntentForPackage(getPackageName());
                    if (i != null) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); }
                }
                return true;
        }
        return false;
    }

    @Override
    public void onDestroy() {
        h.removeCallbacksAndMessages(null);
        try { if (web != null) { wm.removeView(web); web.destroy(); } } catch (Exception ignored) { }
        web = null; running = false; instance = null;
        super.onDestroy();
    }
}
