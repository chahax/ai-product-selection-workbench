package local.ai.collector;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads only the clipboard entry created by this product's Copy Link action. */
public final class ProductLinkActivity extends Activity {
    private static final Pattern URL = Pattern.compile("https://[^\\s<>「」【】，。！？]+");
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int attempts;
    private boolean finished;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        TextView label = new TextView(this);
        label.setText("正在保存抖音商品链接…");
        label.setGravity(android.view.Gravity.CENTER);
        setContentView(label);
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused && !finished && attempts == 0) handler.post(this::readLink);
    }

    private void readLink() {
        if (finished || isFinishing()) return;
        if (!hasWindowFocus()) {
            if (++attempts < 8) handler.postDelayed(this::readLink, 240);
            else complete("");
            return;
        }
        String link = "";
        try {
            ClipboardManager clipboard = getSystemService(ClipboardManager.class);
            ClipData data = clipboard == null ? null : clipboard.getPrimaryClip();
            ClipDescription description = data == null ? null : data.getDescription();
            if (description != null && description.getTimestamp() >= getIntent().getLongExtra("copied_after", Long.MAX_VALUE)
                    && data.getItemCount() > 0) {
                CharSequence copied = data.getItemAt(0).getText();
                if (copied != null) link = productLink(copied.toString());
            }
        } catch (RuntimeException ignored) { }
        if (!link.isEmpty() || ++attempts >= 8) complete(link);
        else handler.postDelayed(this::readLink, 240);
    }

    private void complete(String link) {
        finished = true;
        finishAndRemoveTask();
        CollectorService.acceptProductLink(getIntent().getStringExtra("task_id"), link);
    }

    static String productLink(String text) {
        Matcher matcher = URL.matcher(text);
        Set<String> matches = new LinkedHashSet<>();
        while (matcher.find()) {
            String candidate = matcher.group().replaceFirst("[.,;!?)]+$", "");
            try {
                URI uri = URI.create(candidate);
                String path = uri.getPath();
                if ("https".equals(uri.getScheme()) && uri.getUserInfo() == null && uri.getPort() == -1
                        && ("v.douyin.com".equals(uri.getHost()) && path.matches("/[A-Za-z0-9_-]+/?")
                            || MallNavigator.productId(candidate) != null)) {
                    matches.add(candidate);
                }
            } catch (IllegalArgumentException ignored) { }
        }
        return matches.size() == 1 ? matches.iterator().next() : "";
    }
}
