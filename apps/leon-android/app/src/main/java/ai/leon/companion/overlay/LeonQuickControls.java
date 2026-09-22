package ai.leon.companion.overlay;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The long-press menu: Chat, Minimise / Restore, Hide temporarily, Settings. A small rounded card
 * added to the window manager next to Leon and removed as soon as something is chosen or the user
 * touches elsewhere.
 */
public final class LeonQuickControls extends LinearLayout {
    public interface Callback {
        void onChat();

        void onToggleMinimise();

        void onHideTemporarily();

        void onSettings();

        void onDismiss();
    }

    private final Callback callback;

    public LeonQuickControls(Context context, boolean minimised, Callback callback) {
        super(context);
        this.callback = callback;
        setOrientation(VERTICAL);

        GradientDrawable card = new GradientDrawable();
        card.setColor(Color.argb(242, 22, 23, 28));
        card.setCornerRadius(dp(18));
        card.setStroke(Math.max(1, dp(1)), Color.argb(150, 96, 100, 116));
        setBackground(card);
        int padding = dp(6);
        setPadding(padding, padding, padding, padding);

        addItem("Chat", new Runnable() {
            @Override
            public void run() {
                if (callback != null) callback.onChat();
            }
        });
        addItem(minimised ? "Restore" : "Minimise", new Runnable() {
            @Override
            public void run() {
                if (callback != null) callback.onToggleMinimise();
            }
        });
        addItem("Hide for a while", new Runnable() {
            @Override
            public void run() {
                if (callback != null) callback.onHideTemporarily();
            }
        });
        addItem("Settings", new Runnable() {
            @Override
            public void run() {
                if (callback != null) callback.onSettings();
            }
        });
    }

    private void addItem(String label, final Runnable action) {
        TextView item = new TextView(getContext());
        item.setText(label);
        item.setTextSize(15f);
        item.setTextColor(Color.rgb(238, 239, 243));
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(dp(16), dp(12), dp(22), dp(12));
        GradientDrawable ripple = new GradientDrawable();
        ripple.setColor(Color.argb(0, 0, 0, 0));
        ripple.setCornerRadius(dp(12));
        item.setBackground(ripple);
        item.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        addView(item, lp);
    }

    /** Called by the service when a touch lands outside the card. */
    public void dismiss() {
        if (callback != null) callback.onDismiss();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
