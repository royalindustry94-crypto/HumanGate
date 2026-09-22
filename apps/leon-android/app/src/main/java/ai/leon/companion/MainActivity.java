package ai.leon.companion;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import ai.leon.companion.anim.GestureBehaviour;
import ai.leon.companion.anim.LeonAnimationController;
import ai.leon.companion.overlay.LeonOverlayService;
import ai.leon.companion.overlay.LeonPrefs;
import ai.leon.companion.render.LeonCharacterView;
import ai.leon.companion.render.ProductionLeonTexture;
import ai.leon.companion.rig.LeonRig;
import ai.leon.companion.rig.Rig;
import ai.leon.companion.state.LeonConversationController;
import ai.leon.companion.state.LeonState;
import ai.leon.companion.state.LeonStateController;

/**
 * Leon's control centre: a live preview of the character, the overlay's status, the state-machine
 * test controls and the diagnostics that say what the build is actually rendering.
 *
 * <p>The preview runs its own rig and its own {@link LeonAnimationController} but shares the
 * process-wide {@link LeonStateController} through {@link LeonRuntime}, so every button here drives
 * the same state machine the overlay is animating — not a local copy of it.
 */
public final class MainActivity extends Activity
        implements LeonStateController.Listener, LeonConversationController.Listener {
    public static final String EXTRA_OPEN_SETTINGS = "ai.leon.companion.extra.OPEN_SETTINGS";

    private static final int OVERLAY_REQUEST = 1201;
    private static final int NOTIFICATION_REQUEST = 1202;

    private LeonRuntime runtime;
    private LeonPrefs prefs;

    private Rig rig;
    private ProductionLeonTexture texture;
    private LeonAnimationController animation;
    private LeonCharacterView preview;

    private TextView permissionStatus;
    private TextView overlayStatus;
    private TextView stateStatus;
    private TextView artStatus;
    private TextView conversationStatus;
    private Button enableButton;
    private Button minimiseButton;
    private Button rigDebugButton;
    private View settingsSection;
    private ScrollView scroll;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        runtime = LeonRuntime.get(this);
        prefs = runtime.prefs();
        setContentView(buildUi());
        runtime.states().addListener(this);
        runtime.conversation().setListener(this);
        maybeAskNotificationPermission();
        if (getIntent() != null && getIntent().hasExtra(EXTRA_OPEN_SETTINGS)) revealSettings();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.hasExtra(EXTRA_OPEN_SETTINGS)) revealSettings();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (preview != null) preview.setPaused(false);
        refresh();
    }

    @Override
    protected void onPause() {
        // The preview is invisible, so stop rendering it. The overlay keeps running independently.
        if (preview != null) preview.setPaused(true);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        runtime.states().removeListener(this);
        runtime.conversation().setListener(null);
        if (animation != null) runtime.detachSurface(animation);
        if (preview != null) preview.release();
        if (texture != null) texture.close();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ ui

    private View buildUi() {
        scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(9, 9, 12));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(40));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(heading("LEON", 32));
        root.addView(caption("Always-on animated Android companion"), withBottom(18));

        root.addView(previewCard(), withBottom(16));

        stateStatus = statusLine("");
        root.addView(stateStatus, withBottom(6));
        permissionStatus = statusLine("");
        root.addView(permissionStatus, withBottom(6));
        overlayStatus = statusLine("");
        root.addView(overlayStatus, withBottom(18));

        enableButton = button("Enable Leon");
        enableButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleLeon();
            }
        });
        root.addView(enableButton, withBottom(8));

        minimiseButton = button("Minimise Leon");
        minimiseButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runtime.states().toggleMinimised();
                refresh();
            }
        });
        root.addView(minimiseButton, withBottom(22));

        root.addView(heading("Animation states", 19), withBottom(4));
        root.addView(caption("These drive the real state machine, in the overlay and here."),
                withBottom(10));
        root.addView(stateButtons(LeonState.IDLE, LeonState.LISTENING, LeonState.THINKING),
                withBottom(8));
        root.addView(stateButtons(LeonState.SPEAKING, LeonState.HAPPY, LeonState.SERIOUS),
                withBottom(8));
        root.addView(stateButtons(LeonState.SLEEPY, LeonState.ATTENTION), withBottom(20));

        root.addView(heading("Conversation", 19), withBottom(4));
        final EditText input = new EditText(this);
        input.setHint("Say something to Leon…");
        input.setHintTextColor(Color.rgb(122, 124, 136));
        input.setTextColor(Color.WHITE);
        input.setBackground(panel(Color.rgb(26, 27, 33)));
        input.setPadding(dp(14), dp(12), dp(14), dp(12));
        root.addView(input, withBottom(8));

        Button send = button("Send");
        send.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!runtime.conversation().submit(input.getText().toString().trim())) {
                    // No AI backend exists yet. Say so instead of faking a reply, but still show the
                    // listening and thinking animation so the rig can be exercised.
                    runtime.conversation().beginListening();
                }
            }
        });
        root.addView(send, withBottom(8));
        conversationStatus = caption("");
        root.addView(conversationStatus, withBottom(22));

        root.addView(heading("Developer", 19), withBottom(8));
        settingsSection = developerSection();
        root.addView(settingsSection);

        return scroll;
    }

    private View previewCard() {
        FrameLayout card = new FrameLayout(this);
        card.setBackground(panel(Color.rgb(16, 17, 21)));
        card.setClipToOutline(true);

        rig = LeonRig.build();
        texture = ProductionLeonTexture.load(this);
        animation = new LeonAnimationController(rig, runtime.states(), System.nanoTime() ^ 0x5EEDL);
        runtime.attachSurface(animation);

        preview = new LeonCharacterView(this, rig, animation, texture);
        preview.renderer().setShowRigDebug(prefs.isRigDebugEnabled());
        preview.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                animation.onTouched(0f, -0.2f);
                runtime.states().poke();
            }
        });

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(150), dp(300));
        lp.gravity = Gravity.CENTER;
        card.addView(preview, lp);
        card.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(316)));
        return card;
    }

    private View stateButtons(LeonState... states) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (final LeonState state : states) {
            Button b = button(state.label);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    runtime.states().request(state);
                    if (state == LeonState.HAPPY && animation != null) {
                        animation.triggerGesture(GestureBehaviour.Kind.EMPHASIS);
                    }
                    refresh();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = dp(6);
            row.addView(b, lp);
        }
        return row;
    }

    private View developerSection() {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);

        artStatus = caption("");
        section.addView(artStatus, withBottom(10));

        rigDebugButton = button("Show rig skeleton");
        rigDebugButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean enabled = !prefs.isRigDebugEnabled();
                prefs.setRigDebugEnabled(enabled);
                if (preview != null) preview.renderer().setShowRigDebug(enabled);
                Intent intent = new Intent(MainActivity.this, LeonOverlayService.class)
                        .setAction(LeonOverlayService.ACTION_SET_RIG_DEBUG)
                        .putExtra(LeonOverlayService.EXTRA_ENABLED, enabled);
                if (prefs.isEnabled()) startService(intent);
                refresh();
            }
        });
        section.addView(rigDebugButton, withBottom(8));

        Button gesture = button("Trigger a gesture");
        gesture.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (animation != null) animation.triggerGesture(GestureBehaviour.Kind.POINT);
            }
        });
        section.addView(gesture, withBottom(8));

        Button permission = button("Overlay permission settings");
        permission.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestOverlayPermission();
            }
        });
        section.addView(permission, withBottom(8));

        Button battery = button("App info (battery & auto-start)");
        battery.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Some OEMs need auto-start allowed manually; point the user at the right screen
                // rather than trying to defeat the restriction.
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName()));
                try {
                    startActivity(intent);
                } catch (Exception e) {
                    toast("Could not open app settings on this device");
                }
            }
        });
        section.addView(battery, withBottom(8));

        section.addView(caption("Voice and the live AI brain are deliberately not in this build. "
                + "The animation system already accepts viseme events, so speech can drive Leon's "
                + "mouth without any rig changes."));
        return section;
    }

    private void revealSettings() {
        if (settingsSection == null || scroll == null) return;
        // Posted so the scroll view has been laid out and the section has a real top.
        scroll.post(new Runnable() {
            @Override
            public void run() {
                scroll.smoothScrollTo(0, settingsSection.getTop());
            }
        });
    }

    // ------------------------------------------------------------------ actions

    private void toggleLeon() {
        if (prefs.isEnabled()) {
            prefs.setEnabled(false);
            LeonOverlayService.send(this, LeonOverlayService.ACTION_STOP);
            toast("Leon stopped");
            refresh();
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission();
            return;
        }
        prefs.setEnabled(true);
        LeonOverlayService.start(this);
        toast("Leon is on screen");
        refresh();
    }

    private void requestOverlayPermission() {
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        try {
            startActivityForResult(intent, OVERLAY_REQUEST);
        } catch (Exception e) {
            toast("This device has no overlay permission screen");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != OVERLAY_REQUEST) return;
        if (Settings.canDrawOverlays(this)) {
            prefs.setEnabled(true);
            LeonOverlayService.start(this);
        } else {
            toast("Leon needs display-over-other-apps to stay on screen");
        }
        refresh();
    }

    private void maybeAskNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    NOTIFICATION_REQUEST);
        }
    }

    // ------------------------------------------------------------------ status

    private void refresh() {
        boolean canOverlay = Settings.canDrawOverlays(this);
        boolean enabled = prefs.isEnabled();

        permissionStatus.setText(canOverlay
                ? "✓ Display over other apps granted"
                : "• Display over other apps not granted");
        permissionStatus.setTextColor(canOverlay ? Color.rgb(114, 226, 156) : Color.rgb(255, 188, 92));

        boolean live = LeonOverlayService.isOverlayLive();
        long hiddenUntil = prefs.hiddenUntilMillis();
        String overlayText;
        if (!enabled) {
            overlayText = "• Overlay stopped";
        } else if (hiddenUntil > System.currentTimeMillis()) {
            long minutes = Math.max(1L, (hiddenUntil - System.currentTimeMillis()) / 60000L);
            overlayText = "• Hidden for about " + minutes + " more minute" + (minutes == 1 ? "" : "s");
        } else if (live) {
            overlayText = "✓ Leon is on screen above other apps";
        } else {
            overlayText = "• Overlay starting…";
        }
        overlayStatus.setText(overlayText);
        overlayStatus.setTextColor(live ? Color.rgb(114, 226, 156) : Color.rgb(178, 180, 192));

        LeonState state = runtime.states().state();
        stateStatus.setText("State: " + state.label
                + (animation != null && animation.isInTransition() ? " (transitioning)" : ""));
        stateStatus.setTextColor(Color.rgb(226, 228, 236));

        enableButton.setText(enabled ? "Stop Leon" : "Enable Leon");
        minimiseButton.setText(runtime.states().isMinimised() ? "Restore Leon" : "Minimise Leon");
        rigDebugButton.setText(prefs.isRigDebugEnabled() ? "Hide rig skeleton" : "Show rig skeleton");

        if (artStatus != null && texture != null) {
            String detail = texture.report();
            if (preview != null) {
                detail += "\nContinuous weighted mesh vertices: "
                        + (preview.renderer().mesh().canonicalVertices().length / 2);
            }
            detail += "\nProduction mode: one real Leon texture; no procedural body/facial art.";
            artStatus.setText(detail);
        }
        if (conversationStatus != null
                && runtime.conversation().status() == LeonConversationController.Status.NO_BACKEND) {
            conversationStatus.setText("No AI backend is attached to this build yet. "
                    + "Leon will still listen, think and speak on demand.");
        }
    }

    @Override
    public void onLeonStateChanged(LeonState previous, LeonState current) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                refresh();
            }
        });
    }

    @Override
    public void onConversationStatusChanged(final LeonConversationController.Status status,
                                            final String detail) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (conversationStatus == null) return;
                conversationStatus.setText(detail != null ? detail : ("Conversation: " + status));
            }
        });
    }

    // ------------------------------------------------------------------ small view helpers

    private TextView heading(String text, int sp) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(sp);
        tv.setTextColor(Color.WHITE);
        tv.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return tv;
    }

    private TextView caption(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13.5f);
        tv.setTextColor(Color.rgb(166, 169, 182));
        tv.setLineSpacing(0f, 1.15f);
        return tv;
    }

    private TextView statusLine(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(14.5f);
        tv.setTextColor(Color.rgb(226, 228, 236));
        return tv;
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(14f);
        b.setTextColor(Color.rgb(238, 240, 246));
        b.setAllCaps(false);
        b.setBackground(panel(Color.rgb(31, 32, 39)));
        b.setPadding(dp(12), dp(11), dp(12), dp(11));
        return b;
    }

    private GradientDrawable panel(int colour) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(colour);
        d.setCornerRadius(dp(14));
        d.setStroke(Math.max(1, dp(1)), Color.rgb(58, 60, 70));
        return d;
    }

    private LinearLayout.LayoutParams withBottom(int marginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(marginDp);
        return lp;
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
