package ir.payamkhan;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * اگر کاربر «دستیار شناور» را روشن گذاشته باشد، بعد از روشن شدن گوشی
 * (یا به‌روزرسانی برنامه) حباب دوباره فعال می‌شود.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            String action = intent == null ? "" : String.valueOf(intent.getAction());
            if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                    && !"android.intent.action.MY_PACKAGE_REPLACED".equals(action)) return;
            if (!FloatPrefs.isAssistantEnabled(context)) return;
            if (!FloatPrefs.canDrawOverlays(context)) return;
            FloatingService.start(context);
        } catch (Throwable ignored) {
            // در برخی نسخه‌های اندروید شروع سرویس از پس‌زمینه محدود است؛
            // در این صورت حباب با اولین ورود کاربر به برنامه فعال می‌شود.
        }
    }
}
