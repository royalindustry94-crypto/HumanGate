package ai.leon.companion;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

public class MainActivity extends Activity {
    private static final int OVERLAY_REQUEST = 1201;
    private static final int NOTIFICATION_REQUEST = 1202;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        maybeAskNotificationPermission();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
        if (Settings.canDrawOverlays(this)) {
            startLeon();
        }
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(8, 8, 10));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24), dp(32), dp(24), dp(40));
        scroll.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = label("LEON", 34, Color.WHITE, true);
        root.addView(title);

        TextView subtitle = label("Always-on Android companion", 16, Color.rgb(185,185,195), false);
        LinearLayout.LayoutParams subParams = wrap();
        subParams.bottomMargin = dp(20);
        root.addView(subtitle, subParams);

        ImageView avatar = new ImageView(this);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatar.setImageBitmap(loadLeonBitmap());
        android.graphics.drawable.GradientDrawable frame = new android.graphics.drawable.GradientDrawable();
        frame.setColor(Color.rgb(18,18,22));
        frame.setCornerRadius(dp(26));
        frame.setStroke(dp(1), Color.rgb(75,75,85));
        avatar.setBackground(frame);
        avatar.setClipToOutline(true);
        LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(dp(260), dp(325));
        avatarParams.bottomMargin = dp(20);
        root.addView(avatar, avatarParams);

        status = label("", 15, Color.WHITE, true);
        LinearLayout.LayoutParams statusParams = wrap();
        statusParams.bottomMargin = dp(18);
        root.addView(status, statusParams);

        Button enable = button("ENABLE LEON");
        enable.setOnClickListener(v -> enableLeon());
        root.addView(enable, matchWithBottom(12));

        Button stop = button("STOP FLOATING LEON");
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, LeonOverlayService.class));
            Toast.makeText(this, "Leon stopped", Toast.LENGTH_SHORT).show();
        });
        root.addView(stop, matchWithBottom(24));

        TextView testTitle = label("Conversation shell", 18, Color.WHITE, true);
        root.addView(testTitle, wrap());

        EditText input = new EditText(this);
        input.setHint("Type something to Leon…");
        input.setHintTextColor(Color.rgb(130,130,140));
        input.setTextColor(Color.WHITE);
        input.setBackgroundColor(Color.rgb(28,28,34));
        input.setPadding(dp(14), dp(12), dp(14), dp(12));
        root.addView(input, matchWithBottom(10));

        TextView response = label("Voice and the live AI brain are intentionally left for the last build stage.", 14, Color.rgb(185,185,195), false);
        response.setPadding(0, dp(8), 0, 0);

        Button send = button("TEST LEON");
        send.setOnClickListener(v -> {
            String msg = input.getText().toString().trim();
            response.setText(msg.isEmpty()
                    ? "I'm here. Tap or drag me while you use other apps."
                    : "I'm here. I can stay above your other apps now. Live AI replies and your voice are the next layers.");
        });
        root.addView(send, matchWithBottom(8));
        root.addView(response, wrap());

        return scroll;
    }

    private void enableLeon() {
        if (!Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, OVERLAY_REQUEST);
            return;
        }
        startLeon();
        Toast.makeText(this, "Leon is active", Toast.LENGTH_SHORT).show();
    }

    private void startLeon() {
        Intent service = new Intent(this, LeonOverlayService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service);
        else startService(service);
        updateStatus();
    }

    private void maybeAskNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_REQUEST);
        }
    }

    private void updateStatus() {
        if (status == null) return;
        status.setText(Settings.canDrawOverlays(this)
                ? "✓ Display-over-apps permission enabled"
                : "Display-over-apps permission required");
        status.setTextColor(Settings.canDrawOverlays(this) ? Color.rgb(115,230,155) : Color.rgb(255,190,90));
    }

    private Bitmap loadLeonBitmap() {
        try (InputStream in = getResources().openRawResource(R.raw.leon_base64);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            byte[] encoded = out.toByteArray();
            byte[] data = Base64.decode(encoded, Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(data, 0, data.length);
        } catch (Exception e) {
            return Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        }
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(15);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setBackgroundColor(Color.rgb(32,32,38));
        b.setPadding(dp(14), dp(12), dp(14), dp(12));
        return b;
    }

    private TextView label(String text, int sp, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        if (bold) tv.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        tv.setGravity(Gravity.CENTER);
        return tv;
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams matchWithBottom(int marginDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = dp(marginDp);
        return p;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
