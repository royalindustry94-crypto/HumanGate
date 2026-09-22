package ai.leon.companion;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Base64;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

public class LeonOverlayService extends Service {
    private static final String CHANNEL_ID = "leon_overlay";
    private static final int NOTIFICATION_ID = 77;
    private WindowManager windowManager;
    private FrameLayout overlay;
    private WindowManager.LayoutParams params;
    private AnimatorSet idleAnimator;
    private BroadcastReceiver screenReceiver;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        registerScreenReceiver();
        if (Settings.canDrawOverlays(this)) showOverlay();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (Settings.canDrawOverlays(this) && overlay == null) showOverlay();
        return START_STICKY;
    }

    private void showOverlay() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (windowManager == null || overlay != null) return;

        overlay = new FrameLayout(this);
        overlay.setPadding(dp(6), dp(6), dp(6), dp(6));

        android.graphics.drawable.GradientDrawable card = new android.graphics.drawable.GradientDrawable();
        card.setColor(Color.argb(235, 12, 12, 16));
        card.setCornerRadius(dp(24));
        card.setStroke(dp(1), Color.argb(180, 95, 95, 108));
        overlay.setBackground(card);

        ImageView avatar = new ImageView(this);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatar.setImageBitmap(loadLeonBitmap());
        android.graphics.drawable.GradientDrawable clip = new android.graphics.drawable.GradientDrawable();
        clip.setColor(Color.BLACK);
        clip.setCornerRadius(dp(20));
        avatar.setBackground(clip);
        avatar.setClipToOutline(true);
        FrameLayout.LayoutParams avatarLp = new FrameLayout.LayoutParams(dp(142), dp(174));
        avatarLp.gravity = Gravity.CENTER;
        overlay.addView(avatar, avatarLp);

        TextView dot = new TextView(this);
        dot.setText("●");
        dot.setTextColor(Color.rgb(82, 225, 135));
        dot.setTextSize(14);
        dot.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams dotLp = new FrameLayout.LayoutParams(dp(28), dp(28));
        dotLp.gravity = Gravity.BOTTOM | Gravity.RIGHT;
        dotLp.setMargins(0,0,dp(4),dp(3));
        overlay.addView(dot, dotLp);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        params = new WindowManager.LayoutParams(
                dp(154), dp(188), type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.x = dp(12);
        params.y = dp(150);

        overlay.setOnTouchListener(new DragTouchListener());
        windowManager.addView(overlay, params);
        startIdleAnimation();
    }

    private void startIdleAnimation() {
        if (overlay == null) return;
        ObjectAnimator up = ObjectAnimator.ofFloat(overlay, View.TRANSLATION_Y, 0f, -dp(5));
        up.setDuration(1600);
        ObjectAnimator sx = ObjectAnimator.ofFloat(overlay, View.SCALE_X, 1f, 1.025f);
        sx.setDuration(1600);
        ObjectAnimator sy = ObjectAnimator.ofFloat(overlay, View.SCALE_Y, 1f, 1.025f);
        sy.setDuration(1600);
        idleAnimator = new AnimatorSet();
        idleAnimator.playTogether(up, sx, sy);
        idleAnimator.setStartDelay(250);
        idleAnimator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (overlay == null) return;
                overlay.animate().translationY(0f).scaleX(1f).scaleY(1f).setDuration(1600).withEndAction(LeonOverlayService.this::startIdleAnimation).start();
            }
        });
        idleAnimator.start();
    }

    private class DragTouchListener implements View.OnTouchListener {
        private int startX, startY;
        private float downX, downY;
        private long downAt;

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startX = params.x;
                    startY = params.y;
                    downX = event.getRawX();
                    downY = event.getRawY();
                    downAt = System.currentTimeMillis();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    params.x = startX + (int) (event.getRawX() - downX);
                    params.y = startY + (int) (event.getRawY() - downY);
                    if (windowManager != null && overlay != null) windowManager.updateViewLayout(overlay, params);
                    return true;
                case MotionEvent.ACTION_UP:
                    float distance = Math.abs(event.getRawX() - downX) + Math.abs(event.getRawY() - downY);
                    if (distance < dp(12) && System.currentTimeMillis() - downAt < 450) {
                        Intent open = new Intent(LeonOverlayService.this, MainActivity.class);
                        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        startActivity(open);
                    }
                    return true;
                default:
                    return false;
            }
        }
    }

    private void registerScreenReceiver() {
        screenReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (Intent.ACTION_USER_PRESENT.equals(action) || Intent.ACTION_SCREEN_ON.equals(action)) {
                    if (overlay != null) overlay.setVisibility(View.VISIBLE);
                    else if (Settings.canDrawOverlays(LeonOverlayService.this)) showOverlay();
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(screenReceiver, filter);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setContentTitle("Leon is active")
                .setContentText("Floating companion is staying on screen")
                .setSmallIcon(android.R.drawable.presence_online)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Leon Companion", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Keeps Leon active above your apps");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Bitmap loadLeonBitmap() {
        try (InputStream in = getResources().openRawResource(R.raw.leon_base64);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            byte[] data = Base64.decode(out.toByteArray(), Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(data, 0, data.length);
        } catch (Exception e) {
            return Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        }
    }

    @Override
    public void onDestroy() {
        if (idleAnimator != null) idleAnimator.cancel();
        if (overlay != null && windowManager != null) {
            try { windowManager.removeView(overlay); } catch (Exception ignored) {}
        }
        overlay = null;
        if (screenReceiver != null) {
            try { unregisterReceiver(screenReceiver); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
