package ir.payamkhan;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

/**
 * تنظیمات مشترک برنامه و «دستیار شناور».
 * همهٔ کلیدها در همان فایل SharedPreferences صفحهٔ اصلی ذخیره می‌شوند تا
 * صدای انتخابی (مرد/زن)، سرعت و حالت آفلاین بین برنامه و حباب شناور یکی باشد.
 */
public final class FloatPrefs {

    public static final String PREF = "payamkhan_prefs";

    // --- کلیدهای مشترک با صفحهٔ اصلی ---
    public static final String KEY_GENDER  = "gender_male";   // true = مرد
    public static final String KEY_RATE    = "rate";          // 50..200 (درصد)
    public static final String KEY_OFFLINE = "offline";       // فقط موتور گوشی
    public static final String KEY_VOICE   = "device_voice";  // نام صدای موتور گوشی

    // --- کلیدهای دستیار شناور ---
    public static final String KEY_FLOAT_ON      = "float_enabled";     // دستیار روشن است؟
    public static final String KEY_FLOAT_HIDDEN  = "float_hidden";      // حباب موقتاً مخفی شده؟
    public static final String KEY_FLOAT_X       = "float_x";           // موقعیت حباب
    public static final String KEY_FLOAT_Y       = "float_y";
    public static final String KEY_FLOAT_SIDE    = "float_side_left";   // چسبیده به لبهٔ چپ؟
    public static final String KEY_FLOAT_SHRUNK  = "float_shrunk";      // جمع‌شده در کنارهٔ صفحه؟
    public static final String KEY_INTRO_SHOWN   = "float_intro_shown"; // راهنمای اولیه نمایش داده شد؟
    public static final String KEY_LAST_TEXT     = "float_last_text";   // آخرین پیام خوانده‌شده (برای «خواندن مجدد»)
    public static final String KEY_AUTO_SHRINK   = "float_auto_shrink"; // جمع شدن خودکار حباب در کناره

    private FloatPrefs() { }

    public static SharedPreferences get(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    // --------------------------------------------------------------- وضعیت دستیار
    public static boolean isAssistantEnabled(Context c) {
        return get(c).getBoolean(KEY_FLOAT_ON, false);
    }

    public static void setAssistantEnabled(Context c, boolean on) {
        get(c).edit().putBoolean(KEY_FLOAT_ON, on).apply();
    }

    public static boolean isBubbleHidden(Context c) {
        return get(c).getBoolean(KEY_FLOAT_HIDDEN, false);
    }

    public static void setBubbleHidden(Context c, boolean hidden) {
        get(c).edit().putBoolean(KEY_FLOAT_HIDDEN, hidden).apply();
    }

    public static boolean isAutoShrink(Context c) {
        return get(c).getBoolean(KEY_AUTO_SHRINK, true);
    }

    // --------------------------------------------------------------- صدا
    public static boolean isMale(Context c) {
        return get(c).getBoolean(KEY_GENDER, true);
    }

    public static void setMale(Context c, boolean male) {
        get(c).edit().putBoolean(KEY_GENDER, male).apply();
    }

    /** سرعت به‌صورت float (0.5 تا 2.0) */
    public static float rate(Context c) {
        return get(c).getInt(KEY_RATE, 90) / 100f;
    }

    public static void setRatePercent(Context c, int percent) {
        get(c).edit().putInt(KEY_RATE, percent).apply();
    }

    public static boolean offlineMode(Context c) {
        return get(c).getBoolean(KEY_OFFLINE, false);
    }

    // --------------------------------------------------------------- آخرین پیام
    public static void saveLastText(Context c, String text) {
        get(c).edit().putString(KEY_LAST_TEXT, text == null ? "" : text).apply();
    }

    public static String lastText(Context c) {
        return get(c).getString(KEY_LAST_TEXT, "");
    }

    // --------------------------------------------------------------- مجوزها
    /** آیا مجوز «نمایش روی برنامه‌های دیگر» داده شده است؟ (اندروید ۶ به بالا نیاز به تأیید کاربر دارد) */
    public static boolean canDrawOverlays(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true; // در اندروید ۵ هنگام نصب داده می‌شود
        try {
            return Settings.canDrawOverlays(c);
        } catch (Throwable t) {
            return false;
        }
    }

    /** آیا «دستیار هوشمند» (سرویس دسترس‌پذیری) توسط کاربر فعال شده است؟ */
    public static boolean isA11yEnabled(Context c) {
        try {
            String enabled = Settings.Secure.getString(
                    c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null) return false;
            String me = c.getPackageName() + "/" + CopyCatcherService.class.getName();
            String meShort = c.getPackageName() + "/.CopyCatcherService";
            return enabled.contains(me) || enabled.contains(meShort)
                    || enabled.contains("CopyCatcherService");
        } catch (Throwable t) {
            return false;
        }
    }

    public static String rateLabel(float r) {
        if (r < 0.75f) return "خیلی آرام";
        if (r < 0.95f) return "آرام و واضح";
        if (r < 1.15f) return "معمولی";
        if (r < 1.5f) return "تند";
        return "خیلی تند";
    }
}
