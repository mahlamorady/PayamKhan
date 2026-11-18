package ir.payamkhan;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;

/**
 * پنجرهٔ کاملاً شفاف و نامرئی.
 *
 * چرا لازم است؟ از اندروید ۱۰ به بعد، سیستم فقط به برنامه‌ای اجازهٔ خواندن کلیپ‌بورد را می‌دهد
 * که «پنجرهٔ دارای فوکوس» داشته باشد (کد AOSP: ClipboardService → mWm.isUidFocused).
 * سرویس پس‌زمینه به‌تنهایی این شرط را ندارد؛ بنابراین وقتی حباب شناور نتواند مستقیم
 * کلیپ‌بورد را بخواند، این Activity شفاف برای چند میلی‌ثانیه بالا می‌آید، متن کپی‌شده را
 * می‌گیرد، آن را به سرویس می‌دهد و فوراً بسته می‌شود. هیچ متنی نمایش داده نمی‌شود.
 *
 * نکته: این روش کاملاً مجاز است — هیچ محدودیت امنیتی دور زده نمی‌شود؛ برنامه واقعاً
 * با تعامل کاربر (لمس حباب خودش) به پیش‌زمینه می‌آید و سیستم خودش دسترسی را تأیید می‌کند.
 */
public class ClipboardGrabActivity extends Activity {

    private static final long TIMEOUT_MS = 900L;
    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean delivered = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH);
        } catch (Throwable ignored) { }
        // پنجرهٔ نامرئی — هیچ محتوایی ندارد
        getWindow().setContentView(new android.view.View(this));
        overridePendingTransition(0, 0);

        h.postDelayed(timeout, TIMEOUT_MS);
    }

    private final Runnable timeout = new Runnable() {
        @Override public void run() { finishNow(null); }
    };

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && !delivered) {
            // به‌محض گرفتن فوکوس، کلیپ‌بورد مجاز است خوانده شود
            h.postDelayed(new Runnable() {
                @Override public void run() { finishNow(readClipboard()); }
            }, 40);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // اگر فوکوس گزارش نشد، نهایتاً بعد از یک مکث کوتاه تلاش کن
        h.postDelayed(new Runnable() {
            @Override public void run() {
                if (!delivered) finishNow(readClipboard());
            }
        }, 220);
    }

    private String readClipboard() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return null;
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return null;
            CharSequence cs = clip.getItemAt(0).coerceToText(this);
            return cs == null ? null : cs.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private void finishNow(String text) {
        if (delivered) return;
        delivered = true;
        h.removeCallbacksAndMessages(null);
        try {
            FloatingService.onClipboardCaptured(text);
        } catch (Throwable ignored) { }
        finish();
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
