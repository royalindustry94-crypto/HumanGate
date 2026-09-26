package ai.leon.companion.overlay;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

import ai.leon.companion.LeonRuntime;
import ai.leon.companion.MainActivity;
import ai.leon.companion.R;
import ai.leon.companion.anim.LeonAnimationController;
import ai.leon.companion.render.LeonCharacterView;
import ai.leon.companion.render.ProductionLeonTexture;
import ai.leon.companion.rig.LeonRig;
import ai.leon.companion.rig.Rig;
import ai.leon.companion.state.LeonState;
import ai.leon.companion.state.LeonStateController;

/**
 * Keeps Leon on screen above other apps and drives his overlay lifecycle.
 *
 * <p>What changed from the prototype: the overlay no longer contains an {@link android.widget.ImageView}
 * on a dark card with the whole view being translated and scaled. It hosts a
 * {@link LeonCharacterView} on a fully transparent window, so Leon stands directly on the user's
 * screen and every part of him moves independently. The window itself only ever moves when the user
 * drags it.
 *
 * <p>Persistence: position, minimised state and animation state are written to {@link LeonPrefs} and
 * restored on creation, so a reboot, a process kill or an unlock puts Leon back where he was.
 *
 * <p>OEM restrictions are handled by reporting them, not by working around them: if the overlay
 * permission is missing or an auto-start restriction blocks the service, the notification and the
 * control centre say so. Nothing here attempts to bypass an Android security boundary or to draw
 * over a secure system surface.
 */
public final class LeonOverlayService extends Service implements LeonStateController.Listener {
    private static final String TAG = "LeonOverlay";
    private static final String CHANNEL_ID = "leon_overlay";
    private static final int NOTIFICATION_ID = 77;

    public static final String ACTION_SHOW = "ai.leon.companion.action.SHOW";
    public static final String ACTION_HIDE_TEMPORARILY = "ai.leon.companion.action.HIDE_TEMPORARILY";
    public static final String ACTION_TOGGLE_MINIMISE = "ai.leon.companion.action.TOGGLE_MINIMISE";
    public static final String ACTION_STOP = "ai.leon.companion.action.STOP";
    public static final String ACTION_SET_RIG_DEBUG = "ai.leon.companion.action.SET_RIG_DEBUG";
    public static final String EXTRA_ENABLED = "enabled";

    /** How long "Hide for a while" keeps Leon off screen. */
    private static final long HIDE_DURATION_MS = 10 * 60 * 1000L;

    private static final int EXPANDED_WIDTH_DP = 132;
    private static final int EXPANDED_HEIGHT_DP = 264;
    private static final int MINIMISED_WIDTH_DP = 62;
    private static final int MINIMISED_HEIGHT_DP = 124;

    /** Set while a service instance holds a live overlay, for the control centre's status line. */
    private static volatile boolean overlayLive;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private LeonRuntime runtime;
    private LeonPrefs prefs;
    private WindowManager windowManager;
    private FrameLayout host;
    private WindowManager.LayoutParams params;
    private LeonCharacterView characterView;
    private LeonAnimationController animation;
    private ProductionLeonTexture texture;
    private Rig rig;

    private View quickControls;
    private WindowManager.LayoutParams quickControlsParams;

    private BroadcastReceiver screenReceiver;
    private GestureDetector gestureDetector;
    private ValueAnimator settleAnimator;
    private boolean dragging;
    private int dragStartX;
    private int dragStartY;
    private float dragDownX;
    private float dragDownY;
    private int touchSlop;
    private String blockedReason;

    /** True while an overlay view is attached to the window manager. */
    public static boolean isOverlayLive() {
        return overlayLive;
    }

    public static void start(Context context) {
        Intent intent = new Intent(context, LeonOverlayService.class);
        try {
            context.startForegroundService(intent);
        } catch (Exception e) {
            // Some OEMs block background foreground-service starts until auto-start is allowed.
            Log.w(TAG, "Could not start Leon's overlay service", e);
        }
    }

    public static void send(Context context, String action) {
        Intent intent = new Intent(context, LeonOverlayService.class);
        intent.setAction(action);
        try {
            context.startForegroundService(intent);
        } catch (Exception e) {
            Log.w(TAG, "Could not deliver " + action, e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        runtime = LeonRuntime.get(this);
        prefs = runtime.prefs();
        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        registerScreenReceiver();
        runtime.states().addListener(this);

        if (!prefs.isEnabled()) {
            stopSelf();
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            blockedReason = "Display over other apps is not granted.";
            updateNotification();
            return;
        }
        long now = System.currentTimeMillis();
        long hiddenUntil = prefs.hiddenUntilMillis();
        if (LeonOverlayRestorePolicy.isTemporarilyHidden(hiddenUntil, now)) {
            scheduleUnhide(hiddenUntil - now);
            updateNotification();
            return;
        }
        prefs.setHiddenUntilMillis(0L);
        restoreOverlayIfEligible(now);
        updateNotification();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            prefs.setEnabled(false);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_HIDE_TEMPORARILY.equals(action)) {
            hideTemporarily();
            return START_STICKY;
        }
        if (ACTION_TOGGLE_MINIMISE.equals(action)) {
            runtime.states().toggleMinimised();
            return START_STICKY;
        }
        if (ACTION_SET_RIG_DEBUG.equals(action)) {
            boolean enabled = intent.getBooleanExtra(EXTRA_ENABLED, false);
            prefs.setRigDebugEnabled(enabled);
            if (characterView != null) characterView.renderer().setShowRigDebug(enabled);
            return START_STICKY;
        }

        // A sticky restart delivers a null intent. Only an explicit start means the user wants Leon
        // running; a restart must not re-enable him after he was stopped.
        if (intent != null) prefs.setEnabled(true);
        if (!prefs.isEnabled()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_SHOW.equals(action)) {
            prefs.setHiddenUntilMillis(0L);
            handler.removeCallbacks(unhide);
        }
        if (Settings.canDrawOverlays(this)) {
            blockedReason = null;
            restoreOverlayIfEligible(System.currentTimeMillis());
        } else {
            blockedReason = "Display over other apps is not granted.";
        }
        updateNotification();
        return START_STICKY;
    }

    // ------------------------------------------------------------------ overlay window

    private void showOverlay() {
        if (windowManager == null || host != null) return;

        rig = LeonRig.build();
        texture = ProductionLeonTexture.load(this);
        animation = new LeonAnimationController(rig, runtime.states(), System.nanoTime());
        runtime.attachSurface(animation);

        characterView = new LeonCharacterView(this, rig, animation, texture);
        characterView.renderer().setShowRigDebug(prefs.isRigDebugEnabled());

        host = new FrameLayout(this);
        // No card, no rounded rectangle, no background: Leon stands on the user's own screen.
        host.setBackgroundColor(Color.TRANSPARENT);
        host.addView(characterView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        boolean minimised = runtime.states().isMinimised();
        int width = dp(minimised ? MINIMISED_WIDTH_DP : EXPANDED_WIDTH_DP);
        int height = dp(minimised ? MINIMISED_HEIGHT_DP : EXPANDED_HEIGHT_DP);

        params = new WindowManager.LayoutParams(width, height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        applySavedPosition(width, height);

        gestureDetector = new GestureDetector(this, new GestureListener());
        host.setOnTouchListener(new OverlayTouchListener());

        try {
            windowManager.addView(host, params);
            overlayLive = true;
            blockedReason = null;
        } catch (Exception e) {
            // Nothing to work around here — report it and leave the user in control.
            Log.e(TAG, "Window manager refused Leon's overlay", e);
            blockedReason = "Android refused the overlay window on this device.";
            teardownOverlay();
        }
        updateNotification();
    }

    private void applySavedPosition(int width, int height) {
        ScreenMetrics metrics = ScreenMetrics.of(this, windowManager);
        OverlayPlacement placement = OverlayPlacement.fromFraction(
                prefs.positionFractionX(), prefs.positionFractionY(),
                width, height, metrics.width, metrics.height,
                metrics.insetLeft, metrics.insetTop, metrics.insetRight, metrics.insetBottom);
        params.x = placement.x;
        params.y = placement.y;
    }

    private void savePosition() {
        if (params == null || windowManager == null) return;
        ScreenMetrics metrics = ScreenMetrics.of(this, windowManager);
        prefs.savePosition(
                OverlayPlacement.toFractionX(params.x, params.width, metrics.width,
                        metrics.insetLeft, metrics.insetRight),
                OverlayPlacement.toFractionY(params.y, params.height, metrics.height,
                        metrics.insetTop, metrics.insetBottom));
    }

    private void resizeForState(boolean minimised) {
        if (host == null || params == null || windowManager == null) return;
        int width = dp(minimised ? MINIMISED_WIDTH_DP : EXPANDED_WIDTH_DP);
        int height = dp(minimised ? MINIMISED_HEIGHT_DP : EXPANDED_HEIGHT_DP);
        // Keep Leon's centre where it was so shrinking does not make him jump across the screen.
        int centreX = params.x + params.width / 2;
        int centreY = params.y + params.height / 2;
        params.width = width;
        params.height = height;

        ScreenMetrics metrics = ScreenMetrics.of(this, windowManager);
        OverlayPlacement placement = OverlayPlacement.clamp(
                centreX - width / 2, centreY - height / 2, width, height,
                metrics.width, metrics.height,
                metrics.insetLeft, metrics.insetTop, metrics.insetRight, metrics.insetBottom);
        params.x = placement.x;
        params.y = placement.y;
        try {
            windowManager.updateViewLayout(host, params);
        } catch (Exception e) {
            Log.w(TAG, "Could not resize Leon's overlay", e);
        }
        savePosition();
    }

    private void teardownOverlay() {
        if (settleAnimator != null) {
            settleAnimator.cancel();
            settleAnimator = null;
        }
        dismissQuickControls();
        overlayLive = false;
        if (host != null && windowManager != null) {
            try {
                windowManager.removeView(host);
            } catch (Exception ignored) {
                // Already detached.
            }
        }
        if (characterView != null) {
            characterView.release();
            characterView = null;
        }
        if (animation != null) {
            runtime.detachSurface(animation);
            animation = null;
        }
        if (texture != null) {
            texture.close();
            texture = null;
        }
        host = null;
        params = null;
        rig = null;
    }

    // ------------------------------------------------------------------ gestures

    private final class OverlayTouchListener implements View.OnTouchListener {
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if (params == null) return false;
            gestureDetector.onTouchEvent(event);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dragging = false;
                    dragStartX = params.x;
                    dragStartY = params.y;
                    dragDownX = event.getRawX();
                    dragDownY = event.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = event.getRawX() - dragDownX;
                    float dy = event.getRawY() - dragDownY;
                    if (!dragging && Math.hypot(dx, dy) > touchSlop) {
                        dragging = true;
                        if (settleAnimator != null) settleAnimator.cancel();
                        dismissQuickControls();
                    }
                    if (dragging) moveTo(dragStartX + Math.round(dx), dragStartY + Math.round(dy));
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (dragging) {
                        dragging = false;
                        settleToEdge();
                    }
                    return true;
                default:
                    return false;
            }
        }
    }

    /** Slides Leon to the nearer side edge after a drag, then saves where he ended up. */
    private void settleToEdge() {
        if (params == null || windowManager == null) {
            savePosition();
            return;
        }
        ScreenMetrics metrics = ScreenMetrics.of(this, windowManager);
        final int targetX = OverlayPlacement.snapToNearestEdge(params.x, params.width,
                metrics.width, metrics.insetLeft, metrics.insetRight);
        final int startX = params.x;
        final int y = params.y;
        if (startX == targetX) {
            savePosition();
            return;
        }
        if (settleAnimator != null) settleAnimator.cancel();
        settleAnimator = ValueAnimator.ofFloat(0f, 1f);
        settleAnimator.setDuration(180L);
        settleAnimator.setInterpolator(new DecelerateInterpolator());
        settleAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                float t = animation.getAnimatedFraction();
                moveTo(Math.round(startX + (targetX - startX) * t), y);
            }
        });
        settleAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                savePosition();
            }
        });
        settleAnimator.start();
    }

    private void moveTo(int x, int y) {
        if (host == null || params == null || windowManager == null) return;
        ScreenMetrics metrics = ScreenMetrics.of(this, windowManager);
        OverlayPlacement placement = OverlayPlacement.clamp(x, y, params.width, params.height,
                metrics.width, metrics.height,
                metrics.insetLeft, metrics.insetTop, metrics.insetRight, metrics.insetBottom);
        params.x = placement.x;
        params.y = placement.y;
        try {
            windowManager.updateViewLayout(host, params);
        } catch (Exception e) {
            Log.w(TAG, "Could not move Leon's overlay", e);
        }
    }

    private final class GestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDown(MotionEvent e) {
            return true;
        }

        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {
            if (dragging) return false;
            reactTo(e);
            // A tap opens the conversation surface; Leon acknowledges it where he stands first.
            openMainActivity(null);
            return true;
        }

        @Override
        public boolean onDoubleTap(MotionEvent e) {
            if (dragging) return false;
            reactTo(e);
            runtime.states().toggleMinimised();
            return true;
        }

        @Override
        public void onLongPress(MotionEvent e) {
            if (dragging) return;
            showQuickControls();
        }
    }

    /** Points Leon's head and eyes at the spot he was actually touched. */
    private void reactTo(MotionEvent event) {
        if (animation == null || characterView == null) return;
        int w = Math.max(1, characterView.getWidth());
        int h = Math.max(1, characterView.getHeight());
        float relX = (event.getX() / w) * 2f - 1f;
        float relY = (event.getY() / h) * 2f - 1f;
        animation.onTouched(relX, relY);
        runtime.states().poke();
    }

    // ------------------------------------------------------------------ quick controls

    private void showQuickControls() {
        if (windowManager == null || host == null || params == null) return;
        dismissQuickControls();

        LeonQuickControls controls = new LeonQuickControls(this, runtime.states().isMinimised(),
                new LeonQuickControls.Callback() {
                    @Override
                    public void onChat() {
                        dismissQuickControls();
                        openMainActivity(null);
                    }

                    @Override
                    public void onToggleMinimise() {
                        dismissQuickControls();
                        runtime.states().toggleMinimised();
                    }

                    @Override
                    public void onHideTemporarily() {
                        dismissQuickControls();
                        hideTemporarily();
                    }

                    @Override
                    public void onSettings() {
                        dismissQuickControls();
                        openMainActivity(MainActivity.EXTRA_OPEN_SETTINGS);
                    }
                });

        quickControlsParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        quickControlsParams.gravity = Gravity.TOP | Gravity.START;

        ScreenMetrics metrics = ScreenMetrics.of(this, windowManager);
        int estimatedWidth = dp(178);
        int estimatedHeight = dp(196);
        // Open on whichever side of Leon has room, and keep the card inside the usable area.
        int x = params.x + params.width + dp(8);
        if (x + estimatedWidth > metrics.width - metrics.insetRight) {
            x = params.x - estimatedWidth - dp(8);
        }
        int y = params.y + params.height / 2 - estimatedHeight / 2;
        OverlayPlacement placement = OverlayPlacement.clamp(x, y, estimatedWidth, estimatedHeight,
                metrics.width, metrics.height,
                metrics.insetLeft, metrics.insetTop, metrics.insetRight, metrics.insetBottom);
        quickControlsParams.x = placement.x;
        quickControlsParams.y = placement.y;

        controls.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                    dismissQuickControls();
                    return true;
                }
                return false;
            }
        });

        try {
            windowManager.addView(controls, quickControlsParams);
            quickControls = controls;
        } catch (Exception e) {
            Log.w(TAG, "Could not show Leon's quick controls", e);
            quickControls = null;
        }
    }

    /**
     * Brings the control centre forward. {@code extraKey}, when given, is set as a boolean extra so
     * the activity can open on a particular section.
     */
    private void openMainActivity(String extraKey) {
        Intent intent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (extraKey != null) intent.putExtra(extraKey, true);
        try {
            startActivity(intent);
        } catch (Exception e) {
            Log.w(TAG, "Could not open Leon's control centre", e);
        }
    }

    private void dismissQuickControls() {
        if (quickControls == null) return;
        try {
            windowManager.removeView(quickControls);
        } catch (Exception ignored) {
            // Already detached.
        }
        quickControls = null;
        quickControlsParams = null;
    }

    // ------------------------------------------------------------------ visibility

    private final Runnable unhide = new Runnable() {
        @Override
        public void run() {
            prefs.setHiddenUntilMillis(0L);
            restoreOverlayIfEligible(System.currentTimeMillis());
            updateNotification();
        }
    };

    private void hideTemporarily() {
        long until = System.currentTimeMillis() + HIDE_DURATION_MS;
        prefs.setHiddenUntilMillis(until);
        teardownOverlay();
        scheduleUnhide(HIDE_DURATION_MS);
        updateNotification();
    }

    private void scheduleUnhide(long delayMs) {
        handler.removeCallbacks(unhide);
        handler.postDelayed(unhide, Math.max(0L, delayMs));
    }

    private void registerScreenReceiver() {
        screenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    // Nothing is visible, so stop rendering entirely rather than drawing to nobody.
                    dismissQuickControls();
                    if (characterView != null) characterView.setPaused(true);
                    return;
                }
                if (Intent.ACTION_SCREEN_ON.equals(action) || Intent.ACTION_USER_PRESENT.equals(action)) {
                    restoreOverlayIfEligible(System.currentTimeMillis());
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenReceiver, filter);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        dismissQuickControls();
        if (host == null || params == null) return;
        // Re-resolve the saved fraction against the new screen shape rather than keeping raw pixels.
        applySavedPosition(params.width, params.height);
        try {
            windowManager.updateViewLayout(host, params);
        } catch (Exception e) {
            Log.w(TAG, "Could not reposition after a configuration change", e);
        }
    }

    @Override
    public void onLeonStateChanged(LeonState previous, LeonState current) {
        boolean wasMinimised = previous == LeonState.MINIMISED;
        boolean isMinimised = current == LeonState.MINIMISED;
        if (wasMinimised != isMinimised) {
            resizeForState(isMinimised);
        }
        updateNotification();
    }

    // ------------------------------------------------------------------ notification

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.channel_description));
        channel.setShowBadge(false);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID);

        String text;
        if (blockedReason != null) {
            text = blockedReason;
        } else if (prefs.hiddenUntilMillis() > System.currentTimeMillis()) {
            text = "Hidden for a few minutes";
        } else {
            text = "On screen · " + runtime.states().state().label;
        }

        builder.setContentTitle(getString(R.string.notification_title))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.presence_online)
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false);

        boolean hidden = host == null;
        builder.addAction(action(hidden ? "Show" : "Hide",
                hidden ? ACTION_SHOW : ACTION_HIDE_TEMPORARILY));
        builder.addAction(action(runtime.states().isMinimised() ? "Restore" : "Minimise",
                ACTION_TOGGLE_MINIMISE));
        builder.addAction(action("Stop", ACTION_STOP));
        return builder.build();
    }

    private Notification.Action action(String title, String serviceAction) {
        Intent intent = new Intent(this, LeonOverlayService.class).setAction(serviceAction);
        PendingIntent pending = PendingIntent.getService(this, serviceAction.hashCode(), intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_menu_view), title, pending)
                .build();
    }

    private void updateNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification());
    }

    private void restoreOverlayIfEligible(long nowMillis) {
        if (!LeonOverlayRestorePolicy.shouldRestoreOverlay(
                prefs.isEnabled(),
                Settings.canDrawOverlays(this),
                isScreenInteractive(),
                isKeyguardLocked(),
                prefs.hiddenUntilMillis(),
                nowMillis)) {
            return;
        }
        blockedReason = null;
        if (host == null) {
            showOverlay();
            return;
        }
        host.setVisibility(View.VISIBLE);
        if (characterView == null) return;
        characterView.setPaused(false);
        if (animation != null) {
            // Restart the behaviour timers so intervals that elapsed with the
            // screen off do not all fire at once on the first visible frame.
            animation.resetBehaviours();
            // A blink on unlock reads as Leon waking up with the screen.
            animation.triggerBlink();
        }
    }

    private boolean isScreenInteractive() {
        PowerManager power = getSystemService(PowerManager.class);
        return power == null || power.isInteractive();
    }

    private boolean isKeyguardLocked() {
        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        return keyguard != null && keyguard.isKeyguardLocked();
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onDestroy() {
        handler.removeCallbacks(unhide);
        if (runtime != null) {
            runtime.states().removeListener(this);
            prefs.saveState(runtime.states().state(), runtime.states().restoreState());
        }
        savePosition();
        teardownOverlay();
        if (screenReceiver != null) {
            try {
                unregisterReceiver(screenReceiver);
            } catch (Exception ignored) {
                // Never registered.
            }
            screenReceiver = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
