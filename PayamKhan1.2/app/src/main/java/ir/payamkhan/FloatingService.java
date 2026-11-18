package ir.payamkhan;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * دستیار شناور پیام‌خوان (Floating Message Reader)
 *
 * یک حباب کوچک با علامت بلندگو که روی همهٔ برنامه‌ها (واتساپ، تلگرام، پیامک و…) می‌نشیند.
 * کاربر پیام را کپی می‌کند و با یک لمس روی حباب، پیام بدون باز شدن هیچ پنجرهٔ متنی
 * با صدای فارسی خوانده می‌شود.
 *
 * - Foreground Service با اعلان دائمی (پایداری در پس‌زمینه)
 * - جابه‌جایی با انگشت، چسبیدن به کنارهٔ صفحه، جمع شدن و ذخیرهٔ موقعیت
 * - منوی تنظیمات سریع با نگه‌داشتن انگشت روی حباب (بدون نمایش متن پیام)
 *
 * خواندن کلیپ‌بورد در اندروید ۱۰+ با سه لایهٔ مجاز انجام می‌شود (بدون دور زدن امنیت):
 *   ۱) خواندن مستقیم (اگر برنامه فوکوس داشته باشد)
 *   ۲) فوکوس موقت پنجرهٔ حباب (قاعدهٔ رسمی اندروید: برنامهٔ دارای پنجرهٔ فوکوس)
 *   ۳) پنجرهٔ شفاف و آنی ClipboardGrabActivity (پیش‌زمینه برای چند میلی‌ثانیه)
 *   ۴) در صورت فعال بودن «دستیار هوشمند»: متن انتخاب/کپی‌شده از خود برنامهٔ مقصد
 */
public class FloatingService extends Service implements ReaderEngine.Listener {

    // ------------------------------------------------------------------ اکشن‌ها
    public static final String ACTION_START       = "ir.payamkhan.float.START";
    public static final String ACTION_STOP        = "ir.payamkhan.float.STOP";
    public static final String ACTION_HIDE        = "ir.payamkhan.float.HIDE";
    public static final String ACTION_SHOW        = "ir.payamkhan.float.SHOW";
    public static final String ACTION_READ        = "ir.payamkhan.float.READ";
    public static final String ACTION_TOGGLE      = "ir.payamkhan.float.TOGGLE_PAUSE";
    public static final String ACTION_REPEAT      = "ir.payamkhan.float.REPEAT";
    public static final String ACTION_READ_SCREEN = "ir.payamkhan.float.READ_SCREEN";

    private static final int NOTIF_ID = 4711;
    private static final String CHANNEL_ID = "payamkhan_float";
    private static final long AUTO_SHRINK_MS = 5000L;
    private static final long MENU_LONG_PRESS_MS = 480L;

    // رنگ‌ها — همان پالت صفحهٔ اصلی برنامه
    private static final int C_CARD   = 0xF0231F29;
    private static final int C_CARD_IN= 0xFF2B2733;
    private static final int C_TEXT   = 0xFFEDE7F0;
    private static final int C_MUTED  = 0xFFB7AEC4;
    private static final int C_GREEN  = 0xFF00B389;
    private static final int C_AMBER  = 0xFFE8A33D;
    private static final int C_RED    = 0xFFD9534F;
    private static final int C_PURPLE = 0xFF7C5CFF;
    private static final int C_TEAL   = 0xFF00C9A7;

    /** STATE — وضعیت حباب */
    private static final int ST_IDLE = 0, ST_BUSY = 1, ST_SPEAK = 2, ST_PAUSE = 3, ST_ERR = 4;

    private static volatile FloatingService INSTANCE;
    private static volatile String pendingCapturedText = null;
    private static volatile boolean pendingCaptured = false;

    // ------------------------------------------------------------------ اجزا
    private WindowManager wm;
    private View bubble;
    private ImageView bubbleIcon;
    private GradientDrawable bubbleBg;
    private WindowManager.LayoutParams blp;

    private View menuRoot;
    private WindowManager.LayoutParams mlp;
    private TextView menuStatus, menuRateLabel, btnMale, btnFemale, btnPause, btnRepeat, btnScreen;
    private SeekBar rateSeek;
    private boolean menuOpen = false;

    private ReaderEngine reader;
    private SharedPreferences prefs;
    private Typeface fBold, fMed, fReg;

    private int bubbleSize, menuWidth;
    private int screenW = 1080, screenH = 1920;
    private boolean leftSide = true;
    private boolean shrunk = false;
    private boolean busy = false;
    private boolean dragging = false;
    private int state = ST_IDLE;
    private String statusText = "آماده — پیام را کپی کنید و روی حباب بزنید";
    private boolean statusIsError = false;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Runnable shrinkTask;
    private Runnable busyTimeout;

    // ------------------------------------------------------------------ API عمومی
    public static boolean isRunning() { return INSTANCE != null; }

    /** شروع دستیار شناور (جلوگیری از سرویس تکراری) */
    public static void start(Context c) {
        Context app = c.getApplicationContext();
        if (!FloatPrefs.canDrawOverlays(app)) return;
        Intent i = new Intent(app, FloatingService.class);
        i.setAction(ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(i);
            else app.startService(i);
        } catch (Throwable t) {
            try { app.startService(i); } catch (Throwable ignored) { }
        }
    }

    public static void stop(Context c) {
        Intent i = new Intent(c.getApplicationContext(), FloatingService.class);
        i.setAction(ACTION_STOP);
        try { c.getApplicationContext().startService(i); } catch (Throwable ignored) { }
    }

    public static void send(Context c, String action) {
        Intent i = new Intent(c.getApplicationContext(), FloatingService.class);
        i.setAction(action);
        try {
            if (INSTANCE != null) c.getApplicationContext().startService(i);
            else FloatingService.start(c.getApplicationContext());
        } catch (Throwable ignored) { }
    }

    /** نتیجهٔ گرفتن متن از پنجرهٔ شفاف ClipboardGrabActivity */
    public static void onClipboardCaptured(String text) {
        FloatingService s = INSTANCE;
        if (s != null) {
            s.ui.post(new Runnable() {
                @Override public void run() { s.deliverCaptured(text); }
            });
        } else {
            pendingCapturedText = text;
            pendingCaptured = true;
        }
    }

    // ------------------------------------------------------------------ چرخهٔ حیات سرویس
    @Override
    public void onCreate() {
        super.onCreate();
        if (INSTANCE != null && INSTANCE != this) {
            // جلوی اجرای سرویس تکراری گرفته شود (باید اول startForeground صدا زده شود)
            goForeground();
            stopSelf();
            return;
        }
        INSTANCE = this;
        prefs = FloatPrefs.get(this);
        fReg = safeFont("vazirmatn_regular");
        fMed = safeFont("vazirmatn_medium");
        fBold = safeFont("vazirmatn_bold");

        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        bubbleSize = dp(58);
        menuWidth = dp(252);
        measureScreen();

        reader = new ReaderEngine(this, this);
        reader.init();

        goForeground();

        if (!FloatPrefs.isAssistantEnabled(this)) {
            // کاربر دستیار را خاموش کرده؛ سرویس بی‌دلیل باز نگردد
            stopForegroundCompat();
            stopSelf();
            return;
        }

        if (!FloatPrefs.isBubbleHidden(this)) addBubble();

        if (pendingCaptured) {
            pendingCaptured = false;
            String t = pendingCapturedText;
            pendingCapturedText = null;
            if (!isBlank(t)) speakIt(t, true);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (action == null) action = ACTION_START;
        switch (action) {
            case ACTION_STOP:
                disableAssistant();
                return START_NOT_STICKY;
            case ACTION_HIDE:
                hideBubble(true);
                break;
            case ACTION_SHOW:
                showBubble();
                break;
            case ACTION_READ:
                readCopiedAndSpeak();
                break;
            case ACTION_TOGGLE:
                if (reader.isPlaying()) reader.togglePause();
                else readCopiedAndSpeak();
                break;
            case ACTION_REPEAT:
                repeatLast();
                break;
            case ACTION_READ_SCREEN:
                readScreen();
                break;
            case ACTION_START:
            default:
                if (!FloatPrefs.isBubbleHidden(this) && bubble == null) addBubble();
                if (bubble == null && FloatPrefs.isBubbleHidden(this)) updateNotification();
                break;
        }
        // اگر سیستم سرویس را دوباره ساخت، حباب برگردد (پایداری در پس‌زمینه)
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        measureScreen();
        clampPosition();
        closeMenu();
    }

    @Override
    public void onDestroy() {
        cancelShrink();
        cancelBusyTimeout();
        closeMenu();
        removeBubble();
        if (reader != null) reader.shutdown();
        if (INSTANCE == this) INSTANCE = null;
        super.onDestroy();
    }

    // ------------------------------------------------------------------ اعلان و Foreground
    private void goForeground() {
        Notification n = buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Throwable t) {
            try { startForeground(NOTIF_ID, n); } catch (Throwable ignored) { }
        }
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            try {
                NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                        getString(R.string.float_notif_channel), NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("اعلان دائمی دستیار شناور پیام‌خوان");
                ch.setShowBadge(false);
                ch.enableVibration(false);
                nm.createNotificationChannel(ch);
            } catch (Throwable ignored) { }
        }

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        boolean hidden = FloatPrefs.isBubbleHidden(this);
        String title = hidden ? "حباب شناور مخفی است" : getString(R.string.float_notif_title);
        String text = hidden ? "برای نمایش دوبارهٔ حباب اینجا را بزنید." : statusText;

        b.setSmallIcon(R.drawable.ic_stat_speaker)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setColor(C_PURPLE)
                .setContentIntent(pi(hidden ? ACTION_SHOW : null, 1))
                .setPriority(Notification.PRIORITY_LOW);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            b.setCategory(Notification.CATEGORY_SERVICE);
        }
        if (!hidden) {
            b.addAction(action(R.drawable.ic_stat_speaker, "خواندن پیام کپی‌شده", ACTION_READ, 11));
            b.addAction(action(R.drawable.ic_stat_speaker, "مخفی کردن حباب", ACTION_HIDE, 12));
        } else {
            b.addAction(action(R.drawable.ic_stat_speaker, "نمایش حباب", ACTION_SHOW, 13));
        }
        b.addAction(action(R.drawable.ic_stat_speaker, "خاموش کردن", ACTION_STOP, 14));
        return b.build();
    }

    private Notification.Action action(int icon, String title, String action, int req) {
        return new Notification.Action.Builder(icon, title, pi(action, req)).build();
    }

    private PendingIntent pi(String action, int req) {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        if (action == null) {
            Intent open = new Intent(this, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            return PendingIntent.getActivity(this, req, open, flags);
        }
        Intent i = new Intent(this, FloatingService.class);
        i.setAction(action);
        return PendingIntent.getService(this, req, i, flags);
    }

    private void updateNotification() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_ID, buildNotification());
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ حباب شناور
    private int overlayType() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    private void addBubble() {
        if (bubble != null || wm == null) return;
        if (!FloatPrefs.canDrawOverlays(this)) {
            toast("مجوز «نمایش روی برنامه‌های دیگر» داده نشده است.");
            return;
        }
        FrameLayout root = new FrameLayout(this);
        bubbleBg = new GradientDrawable();
        bubbleBg.setShape(GradientDrawable.OVAL);
        bubbleBg.setColor(C_PURPLE);
        bubbleBg.setStroke(dp(2), 0x40FFFFFF);
        root.setBackground(bubbleBg);
        try { root.setElevation(dp(6f)); } catch (Throwable ignored) { }

        bubbleIcon = new ImageView(this);
        bubbleIcon.setImageResource(R.drawable.ic_stat_speaker);
        FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER);
        root.addView(bubbleIcon, iconLp);

        root.setContentDescription("دستیار شناور پیام‌خوان");
        root.setOnTouchListener(bubbleTouch);

        blp = new WindowManager.LayoutParams(
                bubbleSize, bubbleSize, overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        blp.gravity = Gravity.TOP | Gravity.LEFT;   // مختصات مطلق تا در حالت راست‌چین جابه‌جا نشود
        blp.windowAnimations = 0;

        restorePosition();
        try {
            wm.addView(root, blp);
            bubble = root;
        } catch (Throwable t) {
            bubble = null;
            toast("نمایش حباب ممکن نشد؛ مجوز «نمایش روی برنامه‌های دیگر» را بررسی کنید.");
            return;
        }
        leftSide = blp.x + bubbleSize / 2 <= screenW / 2;
        shrunk = prefs.getBoolean(FloatPrefs.KEY_FLOAT_SHRUNK, false);
        if (shrunk) applyShrunkVisual(false);
        scheduleAutoShrink();
        refreshState();
    }

    private void removeBubble() {
        if (bubble != null && wm != null) {
            try { wm.removeView(bubble); } catch (Throwable ignored) { }
        }
        bubble = null;
    }

    private void hideBubble(boolean notifyUser) {
        FloatPrefs.setBubbleHidden(this, true);
        removeBubble();
        closeMenu();
        cancelShrink();
        updateNotification();
        if (notifyUser) {
            toast("حباب مخفی شد. از اعلان یا داخل برنامه دوباره نمایشش دهید.");
        }
    }

    private void showBubble() {
        FloatPrefs.setBubbleHidden(this, false);
        if (bubble == null) addBubble();
        updateNotification();
    }

    private void disableAssistant() {
        FloatPrefs.setAssistantEnabled(this, false);
        FloatPrefs.setBubbleHidden(this, false);
        toast("دستیار شناور خاموش شد.");
        stopForegroundCompat();
        stopSelf();
    }

    private void stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= 24) stopForeground(Service.STOP_FOREGROUND_REMOVE);
            else stopForeground(true);
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ موقعیت حباب
    private void measureScreen() {
        try {
            if (Build.VERSION.SDK_INT >= 30 && wm != null) {
                Rect b = wm.getCurrentWindowMetrics().getBounds();
                screenW = b.width();
                screenH = b.height();
                return;
            }
        } catch (Throwable ignored) { }
        try {
            Point p = new Point();
            wm.getDefaultDisplay().getRealSize(p);
            screenW = p.x;
            screenH = p.y;
        } catch (Throwable ignored) { }
    }

    private void restorePosition() {
        int defX = screenW - bubbleSize - dp(8);
        int defY = screenH / 3;
        int x = prefs.getInt(FloatPrefs.KEY_FLOAT_X, defX);
        int y = prefs.getInt(FloatPrefs.KEY_FLOAT_Y, defY);
        leftSide = prefs.getBoolean(FloatPrefs.KEY_FLOAT_SIDE, false);
        shrunk = prefs.getBoolean(FloatPrefs.KEY_FLOAT_SHRUNK, false);
        blp.x = clampX(x, shrunk);
        blp.y = clampY(y);
    }

    private void savePosition() {
        prefs.edit()
                .putInt(FloatPrefs.KEY_FLOAT_X, blp.x)
                .putInt(FloatPrefs.KEY_FLOAT_Y, blp.y)
                .putBoolean(FloatPrefs.KEY_FLOAT_SIDE, leftSide)
                .putBoolean(FloatPrefs.KEY_FLOAT_SHRUNK, shrunk)
                .apply();
    }

    private int clampX(int x, boolean allowOff) {
        int pad = allowOff ? bubbleSize / 2 : 0;
        int min = -pad;
        int max = Math.max(min, screenW - bubbleSize + pad);
        return Math.min(Math.max(x, min), max);
    }

    private int clampY(int y) {
        int top = dp(4);
        int bottom = Math.max(top, screenH - bubbleSize - dp(4));
        return Math.min(Math.max(y, top), bottom);
    }

    private void clampPosition() {
        if (bubble == null || blp == null) return;
        blp.x = clampX(blp.x, shrunk);
        blp.y = clampY(blp.y);
        try { wm.updateViewLayout(bubble, blp); } catch (Throwable ignored) { }
    }

    /** چسباندن حباب به نزدیک‌ترین کنارهٔ صفحه */
    private void snapToEdge() {
        if (bubble == null) return;
        int center = blp.x + bubbleSize / 2;
        leftSide = center <= screenW / 2;
        int target = leftSide ? dp(2) : screenW - bubbleSize - dp(2);
        animateX(blp.x, target, 160);
    }

    /** جمع شدن حباب در کنارهٔ صفحه (نصفه دیده می‌شود) */
    private void shrink() {
        if (bubble == null || shrunk) return;
        shrunk = true;
        int target = leftSide ? -bubbleSize * 45 / 100 : screenW - bubbleSize * 55 / 100;
        animateX(blp.x, target, 200);
        applyShrunkVisual(true);
        savePosition();
    }

    private void expand() {
        if (bubble == null || !shrunk) return;
        shrunk = false;
        int target = leftSide ? dp(2) : screenW - bubbleSize - dp(2);
        animateX(blp.x, target, 180);
        bubble.animate().alpha(1f).setDuration(160).start();
        prefs.edit().putBoolean(FloatPrefs.KEY_FLOAT_SHRUNK, false).apply();
    }

    private void applyShrunkVisual(boolean animate) {
        if (bubble == null) return;
        if (animate) bubble.animate().alpha(0.55f).setDuration(200).start();
        else bubble.setAlpha(0.55f);
    }

    private void animateX(final int from, final int to, long dur) {
        if (bubble == null) return;
        if (from == to) { blp.x = to; safeUpdate(); return; }
        final long t0 = SystemClock.uptimeMillis();
        final Runnable step = new Runnable() {
            @Override public void run() {
                float p = (SystemClock.uptimeMillis() - t0) / (float) Math.max(1, dur);
                if (p >= 1f) { blp.x = to; safeUpdate(); return; }
                // نرم‌شونده (ease-out)
                float e = 1f - (1f - p) * (1f - p);
                blp.x = (int) (from + (to - from) * e);
                safeUpdate();
                ui.postDelayed(this, 16);
            }
        };
        ui.post(step);
    }

    private void safeUpdate() {
        try { if (bubble != null && blp != null) wm.updateViewLayout(bubble, blp); } catch (Throwable ignored) { }
    }

    private void scheduleAutoShrink() {
        cancelShrink();
        if (!FloatPrefs.isAutoShrink(this)) return;
        shrinkTask = new Runnable() {
            @Override public void run() {
                if (bubble == null || menuOpen || reader.isPlaying() || busy || dragging) return;
                if (!shrunk) {
                    snapToEdge();
                    ui.postDelayed(new Runnable() {
                        @Override public void run() {
                            if (!menuOpen && !reader.isPlaying() && !busy && !dragging) shrink();
                        }
                    }, 220);
                }
            }
        };
        ui.postDelayed(shrinkTask, AUTO_SHRINK_MS);
    }

    private void cancelShrink() {
        if (shrinkTask != null) { ui.removeCallbacks(shrinkTask); shrinkTask = null; }
    }

    // ------------------------------------------------------------------ لمس حباب
    private final View.OnTouchListener bubbleTouch = new View.OnTouchListener() {
        private float downX, downY;
        private int origX, origY;
        private boolean moved;
        private long downAt;
        private Runnable longPress;
        private int slop = 0;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    origX = blp.x;
                    origY = blp.y;
                    moved = false;
                    downAt = SystemClock.uptimeMillis();
                    if (slop == 0) slop = dp(8);
                    cancelShrink();
                    dragging = false;
                    longPress = new Runnable() {
                        @Override public void run() {
                            if (moved || bubble == null) return;
                            try {
                                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                            } catch (Throwable ignored) { }
                            if (shrunk) expand();
                            toggleMenu();
                        }
                    };
                    ui.postDelayed(longPress, MENU_LONG_PRESS_MS);
                    return true;

                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - downX;
                    float dy = e.getRawY() - downY;
                    if (!moved && (Math.abs(dx) > slop || Math.abs(dy) > slop)) {
                        moved = true;
                        if (longPress != null) ui.removeCallbacks(longPress);
                        if (shrunk) { shrunk = false; bubble.setAlpha(1f); }
                        if (menuOpen) closeMenu();
                    }
                    if (moved) {
                        dragging = true;
                        blp.x = clampX(origX + (int) dx, true);
                        blp.y = clampY(origY + (int) dy);
                        safeUpdate();
                    }
                    return true;
                }

                case MotionEvent.ACTION_UP: {
                    if (longPress != null) ui.removeCallbacks(longPress);
                    dragging = false;
                    long dur = SystemClock.uptimeMillis() - downAt;
                    if (!moved && dur < MENU_LONG_PRESS_MS) {
                        onBubbleTap();
                    } else if (moved) {
                        snapToEdge();
                        savePosition();
                    }
                    scheduleAutoShrink();
                    return true;
                }

                case MotionEvent.ACTION_CANCEL:
                    if (longPress != null) ui.removeCallbacks(longPress);
                    dragging = false;
                    scheduleAutoShrink();
                    return true;
            }
            return false;
        }
    };

    private void onBubbleTap() {
        if (menuOpen) { closeMenu(); return; }
        if (reader.isPlaying() && !reader.isPaused()) {
            // لمس مجدد = توقف خواندن
            reader.pause();
            return;
        }
        if (reader.isPaused()) {
            reader.resume();
            return;
        }
        if (shrunk) expand();
        readCopiedAndSpeak();
    }

    // ------------------------------------------------------------------ خواندن کلیپ‌بورد
    private void readCopiedAndSpeak() {
        if (busy) { toast("کمی صبر کنید…"); return; }
        busy = true;
        cancelShrink();
        setState(ST_BUSY, "در حال گرفتن متن کپی‌شده…", false);
        startBusyTimeout();

        // لایهٔ ۱: خواندن مستقیم (اگر برنامه فوکوس داشته باشد)
        String text = readClipboardNow();
        if (!isBlank(text)) { speakIt(text, true); return; }

        // لایهٔ ۲: فوکوس موقت پنجرهٔ حباب (قاعدهٔ رسمی اندروید ۱۰+)
        focusAssistRead();
    }

    private String readClipboardNow() {
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

    private void focusAssistRead() {
        if (bubble == null || blp == null) { useA11yOrGrab(); return; }
        boolean changed = false;
        try {
            blp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            blp.flags |= WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
            wm.updateViewLayout(bubble, blp);
            bubble.requestFocus();
            changed = true;
        } catch (Throwable t) {
            restoreNotFocusable();
            useA11yOrGrab();
            return;
        }
        final boolean didChange = changed;
        final long t0 = SystemClock.uptimeMillis();
        final Runnable poll = new Runnable() {
            @Override public void run() {
                if (bubble == null) { if (didChange) restoreNotFocusable(); useA11yOrGrab(); return; }
                boolean focused = false;
                try { focused = bubble.hasWindowFocus(); } catch (Throwable ignored) { }
                if (focused || SystemClock.uptimeMillis() - t0 > 420) {
                    String text = readClipboardNow();
                    if (didChange) restoreNotFocusable();
                    if (!isBlank(text)) { speakIt(text, true); return; }
                    useA11yOrGrab();
                } else {
                    ui.postDelayed(this, 30);
                }
            }
        };
        ui.postDelayed(poll, 40);
    }

    private void restoreNotFocusable() {
        if (bubble == null || blp == null) return;
        try {
            blp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            blp.flags &= ~WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
            wm.updateViewLayout(bubble, blp);
        } catch (Throwable ignored) { }
    }

    /** لایهٔ ۳: متن گرفته‌شده توسط دستیار هوشمند — لایهٔ ۴: پنجرهٔ شفاف */
    private void useA11yOrGrab() {
        if (CopyCatcherService.isRunning()) {
            String t = CopyCatcherService.lastSelectedText();
            long at = CopyCatcherService.lastSelectedTime();
            // فقط اگر متن تازه‌ای باشد (کمتر از ۳ دقیقه پیش)
            if (!isBlank(t) && at > 0 && System.currentTimeMillis() - at < 180000L) {
                speakIt(t, true);
                return;
            }
        }
        launchGrabActivity();
    }

    private void launchGrabActivity() {
        try {
            Intent i = new Intent(this, ClipboardGrabActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION
                    | Intent.FLAG_ACTIVITY_NO_USER_ACTION
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
        } catch (Throwable t) {
            onNoText("دسترسی به متن کپی‌شده داده نشد. برنامه را باز کنید و از دکمهٔ «چسباندن متن» استفاده کنید.");
        }
    }

    private void deliverCaptured(String text) {
        if (!busy) return;   // زمان انتظار تمام شده؛ پیام خطا قبلاً داده شده است
        if (isBlank(text)) {
            onNoText("حافظهٔ کپی خالی است یا اجازهٔ خواندن داده نشد. اول پیام را کپی کنید.");
            return;
        }
        speakIt(text, true);
    }

    private void startBusyTimeout() {
        cancelBusyTimeout();
        busyTimeout = new Runnable() {
            @Override public void run() {
                if (busy) onNoText("متن کپی‌شده گرفته نشد. دوباره تلاش کنید.");
            }
        };
        ui.postDelayed(busyTimeout, 4000);
    }

    private void cancelBusyTimeout() {
        if (busyTimeout != null) { ui.removeCallbacks(busyTimeout); busyTimeout = null; }
    }

    private void onNoText(String message) {
        busy = false;
        cancelBusyTimeout();
        setState(ST_ERR, message, true);
        announce(message);
        toast(message);
        scheduleAutoShrink();
    }

    // ------------------------------------------------------------------ خواندن
    private void speakIt(String text, boolean save) {
        busy = false;
        cancelBusyTimeout();
        if (isBlank(text)) { onNoText("متنی برای خواندن پیدا نشد."); return; }
        String clean = TextChunker.normalize(text);
        if (isBlank(clean)) { onNoText("متن کپی‌شده قابل خواندن نیست (مثلاً فقط تصویر یا ایموجی است)."); return; }
        if (save) FloatPrefs.saveLastText(this, clean);
        cancelShrink();
        if (shrunk) expand();
        reader.speak(clean, FloatPrefs.isMale(this), FloatPrefs.rate(this), FloatPrefs.offlineMode(this));
    }

    /** خواندن یک پیام کوتاه راهنما (بدون ذخیره به‌عنوان آخرین پیام) */
    private void announce(String text) {
        try {
            reader.speak(text, FloatPrefs.isMale(this), FloatPrefs.rate(this), FloatPrefs.offlineMode(this));
        } catch (Throwable ignored) { }
    }

    private void repeatLast() {
        String last = FloatPrefs.lastText(this);
        if (isBlank(last)) {
            toast("هنوز پیامی خوانده نشده است.");
            announce("هنوز پیامی خوانده نشده است.");
            return;
        }
        reader.stop();
        speakIt(last, false);
        toast("خواندن دوبارهٔ آخرین پیام");
    }

    private void readScreen() {
        CopyCatcherService s = CopyCatcherService.INSTANCE;
        if (s == null) {
            toast("برای این کار باید «دستیار هوشمند» را در تنظیمات فعال کنید.");
            return;
        }
        String text = "";
        try { text = s.readScreenText(); } catch (Throwable ignored) { }
        if (isBlank(text)) {
            toast("متنی روی صفحه پیدا نشد.");
            announce("متنی روی صفحه پیدا نشد.");
            return;
        }
        closeMenu();
        speakIt(text, true);
    }

    // ------------------------------------------------------------------ ReaderEngine.Listener
    @Override
    public void onEngineStatus(String text, boolean error) {
        statusText = text;
        statusIsError = error;
        updateNotification();
        if (menuOpen) refreshMenu();
    }

    @Override
    public void onEngineStateChanged() {
        if (reader.isPlaying() && !reader.isPaused()) {
            setState(ST_SPEAK, statusText, false);
            cancelShrink();
        } else if (reader.isPaused()) {
            setState(ST_PAUSE, statusText, false);
            scheduleAutoShrink();
        } else {
            setState(statusIsError ? ST_ERR : ST_IDLE, statusText, statusIsError);
            scheduleAutoShrink();
        }
        updateNotification();
        if (menuOpen) refreshMenu();
    }

    private void setState(int newState, String text, boolean error) {
        state = newState;
        if (text != null) { statusText = text; statusIsError = error; }
        refreshState();
        updateNotification();
        if (menuOpen) refreshMenu();
    }

    /** رنگ و آیکون حباب بر اساس وضعیت */
    private void refreshState() {
        if (bubbleBg == null || bubbleIcon == null) return;
        int color;
        int icon = R.drawable.ic_stat_speaker;
        switch (state) {
            case ST_SPEAK: color = C_TEAL;  icon = R.drawable.ic_stat_speaker; break;
            case ST_PAUSE: color = C_AMBER; icon = R.drawable.ic_float_pause;  break;
            case ST_BUSY:  color = C_GREEN; icon = R.drawable.ic_stat_speaker; break;
            case ST_ERR:   color = C_RED;   icon = R.drawable.ic_float_stop;   break;
            default:       color = C_PURPLE;icon = R.drawable.ic_stat_speaker; break;
        }
        try {
            bubbleBg.setColor(color);
            bubbleBg.invalidateSelf();
            bubbleIcon.setImageResource(icon);
        } catch (Throwable ignored) { }
        // یک تپش کوتاه برای دیدن بهتر تغییر وضعیت
        if (bubble != null && state == ST_SPEAK) {
            bubble.animate().scaleX(1.12f).scaleY(1.12f).setDuration(120)
                    .withEndAction(new Runnable() {
                        @Override public void run() {
                            if (bubble != null) bubble.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                        }
                    }).start();
        }
    }

    // ------------------------------------------------------------------ منوی تنظیمات سریع
    private void toggleMenu() {
        if (menuOpen) closeMenu(); else openMenu();
    }

    private void openMenu() {
        if (menuOpen || bubble == null) return;
        menuRoot = buildMenu();
        mlp = new WindowManager.LayoutParams(
                menuWidth, ViewGroup.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        mlp.gravity = Gravity.TOP | Gravity.LEFT;
        mlp.windowAnimations = 0;
        positionMenu();
        try {
            wm.addView(menuRoot, mlp);
            menuOpen = true;
        } catch (Throwable t) {
            menuRoot = null;
            menuOpen = false;
            toast("باز کردن منو ممکن نشد.");
            return;
        }
        // بعد از اندازه‌گیری، جای منو را دقیق تنظیم کن تا از صفحه بیرون نزند
        menuRoot.post(new Runnable() {
            @Override public void run() { positionMenu(); safeUpdateMenu(); }
        });
        cancelShrink();
    }

    private void closeMenu() {
        if (menuRoot != null && wm != null) {
            try { wm.removeView(menuRoot); } catch (Throwable ignored) { }
        }
        menuRoot = null;
        menuOpen = false;
        scheduleAutoShrink();
    }

    private void positionMenu() {
        if (menuRoot == null || mlp == null || blp == null) return;
        int w = menuWidth;
        int h = menuRoot.getHeight() > 0 ? menuRoot.getHeight() : dp(320);
        int x;
        if (leftSide) x = Math.max(dp(6), blp.x + bubbleSize + dp(8));
        else x = blp.x - w - dp(8);
        if (x < dp(6)) x = dp(6);
        if (x + w > screenW - dp(6)) x = Math.max(dp(6), screenW - w - dp(6));
        int y = blp.y - dp(30);
        if (y + h > screenH - dp(10)) y = screenH - h - dp(10);
        if (y < dp(24)) y = dp(24);
        mlp.x = x;
        mlp.y = y;
        mlp.width = Math.min(w, screenW - dp(12));
    }

    private void safeUpdateMenu() {
        try { if (menuRoot != null && mlp != null) wm.updateViewLayout(menuRoot, mlp); } catch (Throwable ignored) { }
    }

    private View buildMenu() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(C_CARD, 20));
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setLayoutParams(new ViewGroup.LayoutParams(menuWidth, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        // --- سربرگ ---
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label("🫧 دستیار شناور", 15, C_TEXT, fBold);
        TextView close = label("✕", 18, C_MUTED, fBold);
        close.setPadding(dp(10), dp(4), dp(2), dp(4));
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { closeMenu(); }
        });
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(close);
        card.addView(head);

        // --- وضعیت (بدون نمایش متن پیام) ---
        menuStatus = label(statusText, 12, C_MUTED, fReg);
        menuStatus.setPadding(0, dp(2), 0, dp(10));
        menuStatus.setMaxLines(2);
        card.addView(menuStatus);

        // --- صدای مرد / زن ---
        LinearLayout row1 = row();
        btnMale = menuButton("🚹 صدای مرد", C_CARD_IN, 13);
        btnFemale = menuButton("🚺 صدای زن", C_CARD_IN, 13);
        btnMale.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setGenderFromMenu(true); }
        });
        btnFemale.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setGenderFromMenu(false); }
        });
        half(row1, btnMale);
        half(row1, btnFemale);
        card.addView(row1);

        // --- سرعت ---
        menuRateLabel = label("سرعت خواندن: " + FloatPrefs.rateLabel(FloatPrefs.rate(this)), 12, C_TEXT, fMed);
        menuRateLabel.setPadding(0, dp(10), 0, 0);
        card.addView(menuRateLabel);
        rateSeek = new SeekBar(this);
        rateSeek.setMax(150);
        rateSeek.setProgress(Math.round(FloatPrefs.rate(this) * 100f) - 50);
        rateSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                int percent = p + 50;
                float r = percent / 100f;
                FloatPrefs.setRatePercent(FloatingService.this, percent);
                if (menuRateLabel != null) menuRateLabel.setText("سرعت خواندن: " + FloatPrefs.rateLabel(r));
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        card.addView(rateSeek);

        // --- توقف / دوباره ---
        LinearLayout row2 = row();
        btnPause = menuButton("⏸ توقف موقت", C_AMBER, 13);
        btnRepeat = menuButton("🔁 خواندن مجدد", C_CARD_IN, 13);
        btnPause.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (reader.isPlaying()) reader.togglePause();
                else readCopiedAndSpeak();
            }
        });
        btnRepeat.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { repeatLast(); }
        });
        half(row2, btnPause);
        half(row2, btnRepeat);
        card.addView(row2);

        // --- خواندن متن روی صفحه (فقط با دستیار هوشمند) ---
        if (CopyCatcherService.isRunning()) {
            btnScreen = menuButton("📖 خواندن متن روی صفحه", C_CARD_IN, 13);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(6);
            btnScreen.setLayoutParams(lp);
            btnScreen.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { readScreen(); }
            });
            card.addView(btnScreen);
        }

        // --- مخفی کردن / خاموش کردن ---
        LinearLayout row3 = row();
        TextView btnHide = menuButton("👁 مخفی کردن حباب", C_CARD_IN, 13);
        TextView btnOff = menuButton("⏹ خاموش کردن دستیار", C_RED, 13);
        btnHide.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { closeMenu(); hideBubble(true); }
        });
        btnOff.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { closeMenu(); disableAssistant(); }
        });
        half(row3, btnHide);
        half(row3, btnOff);
        card.addView(row3);

        TextView tip = label("راهنما: یک لمس = خواندن پیام کپی‌شده · لمس دوباره = توقف"
                + " · نگه‌داشتن انگشت = همین منو", 10, C_MUTED, fReg);
        tip.setPadding(0, dp(10), 0, 0);
        tip.setMaxLines(3);
        card.addView(tip);

        refreshMenu();
        return card;
    }

    private void setGenderFromMenu(boolean male) {
        FloatPrefs.setMale(this, male);
        toast(male ? "صدای مرد (فرید) انتخاب شد" : "صدای زن (دلارا) انتخاب شد");
        refreshMenu();
    }

    private void refreshMenu() {
        if (!menuOpen) return;
        boolean male = FloatPrefs.isMale(this);
        if (btnMale != null) btnMale.setBackground(rounded(male ? C_GREEN : C_CARD_IN, 13));
        if (btnFemale != null) btnFemale.setBackground(rounded(!male ? C_PURPLE : C_CARD_IN, 13));
        if (btnPause != null) {
            boolean playing = reader.isPlaying() && !reader.isPaused();
            btnPause.setText(playing ? "⏸ توقف خواندن" : (reader.isPaused() ? "▶ ادامه خواندن" : "🔊 خواندن پیام"));
            btnPause.setBackground(rounded(reader.isPaused() ? C_GREEN : C_AMBER, 13));
        }
        if (menuStatus != null) {
            menuStatus.setText(statusText == null ? "آماده" : statusText);
            menuStatus.setTextColor(statusIsError ? C_RED : (reader.isPlaying() ? C_TEAL : C_MUTED));
        }
    }

    // ------------------------------------------------------------------ ابزارهای رابط کاربری
    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        l.setLayoutParams(lp);
        return l;
    }

    private void half(LinearLayout parent, TextView child) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(4);
        lp.rightMargin = dp(4);
        parent.addView(child, lp);
    }

    private TextView menuButton(String text, int color, float sp) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        b.setTextColor(Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setBackground(rounded(color, 13));
        b.setMinHeight(dp(48));
        b.setPadding(dp(6), dp(12), dp(6), dp(12));
        b.setTextDirection(View.TEXT_DIRECTION_RTL);
        b.setSingleLine(false);
        b.setMaxLines(2);
        if (fMed != null) b.setTypeface(fMed);
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    private TextView label(String text, float sp, int color, Typeface tf) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTextDirection(View.TEXT_DIRECTION_RTL);
        t.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        if (tf != null) t.setTypeface(tf);
        return t;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private Typeface safeFont(String name) {
        try {
            int id = getResources().getIdentifier(name, "font", getPackageName());
            if (id != 0) return getResources().getFont(id);
        } catch (Throwable ignored) { }
        return null;
    }

    private void toast(String msg) {
        try { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) { }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
