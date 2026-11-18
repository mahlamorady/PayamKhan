package ir.payamkhan;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * «دستیار هوشمند» — سرویس دسترس‌پذیری (کاملاً اختیاری، فقط با فعال‌سازی آگاهانهٔ کاربر).
 *
 * بررسی فنی صادقانه:
 *  - اندروید ۱۰ به بعد خواندن کلیپ‌بورد را فقط به برنامهٔ «دارای فوکوس» یا «کیبورد پیش‌فرض»
 *    می‌دهد (ClipboardService → isUidFocused). سرویس دسترس‌پذیری از این قاعده **مستثنا نیست**؛
 *    یعنی با AccessibilityService نمی‌شود کلیپ‌بورد را مستقیم خواند.
 *  - ولی کاری که این سرویس می‌تواند بکند (و واقعاً برای این کاربرد مفید است) این است که
 *    «متن انتخاب‌شده/کپی‌شده» را از خودِ برنامهٔ مقصد (واتساپ، تلگرام، پیامک…) بگیرد،
 *    چون رویداد TYPE_VIEW_TEXT_SELECTION_CHANGED همراه با متن انتخاب‌شده می‌آید.
 *    این متن فقط در حافظهٔ موقت (RAM) نگه داشته می‌شود؛ نه روی حافظهٔ گوشی ذخیره می‌شود و نه جایی ارسال.
 *  - علاوه بر آن، امکان «خواندن متن روی صفحه» را فراهم می‌کند: با یک لمس، آخرین پیام‌های
 *    دیده‌شده در صفحه خوانده می‌شوند؛ بدون نیاز به کپی کردن.
 */
public class CopyCatcherService extends AccessibilityService {

    public static volatile CopyCatcherService INSTANCE;

    private volatile String lastSelected = "";
    private volatile long lastSelectedAt = 0L;
    private volatile String lastPackage = "";

    private static final int MAX_SCREEN_CHARS = 900;
    private static final int MAX_NODES = 900;

    // ------------------------------------------------------------------ وضعیت
    public static boolean isRunning() { return INSTANCE != null; }

    /** آخرین متنی که کاربر در هر برنامه‌ای انتخاب/کپی کرده است (فقط در RAM) */
    public static String lastSelectedText() {
        CopyCatcherService s = INSTANCE;
        return s == null ? "" : s.lastSelected;
    }

    public static long lastSelectedTime() {
        CopyCatcherService s = INSTANCE;
        return s == null ? 0L : s.lastSelectedAt;
    }

    public static String lastSelectedPackage() {
        CopyCatcherService s = INSTANCE;
        return s == null ? "" : s.lastPackage;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        INSTANCE = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        try {
            int type = event.getEventType();
            CharSequence pkg = event.getPackageName();
            if (pkg != null && getPackageName().contentEquals(pkg)) return; // برنامهٔ خودمان

            if (type == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
                    || type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
                String fromEvent = joinTexts(event.getText());
                if (isMeaningful(fromEvent)) cache(fromEvent, pkg);
                else {
                    String fromNode = selectedTextOfSource(event.getSource());
                    if (isMeaningful(fromNode)) cache(fromNode, pkg);
                }
            } else if (type == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                // کاربر روی «کپی» زد → متن انتخاب‌شدهٔ فعلی صفحه را بگیر
                String fromNode = selectedTextInActiveWindow();
                if (isMeaningful(fromNode)) cache(fromNode, pkg);
            }
        } catch (Throwable ignored) { }
    }

    private void cache(String text, CharSequence pkg) {
        lastSelected = text;
        lastSelectedAt = System.currentTimeMillis();
        lastPackage = pkg == null ? "" : pkg.toString();
    }

    @Override
    public void onInterrupt() { }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        INSTANCE = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        INSTANCE = null;
        super.onDestroy();
    }

    // ------------------------------------------------------------------ متن انتخاب‌شده
    private String selectedTextOfSource(AccessibilityNodeInfo node) {
        if (node == null) return "";
        try {
            int s = node.getTextSelectionStart();
            int e = node.getTextSelectionEnd();
            CharSequence t = node.getText();
            if (t != null && s >= 0 && e > s && e <= t.length()) return t.subSequence(s, e).toString();
            if (t != null) return t.toString();
        } catch (Throwable ignored) { } finally {
            safeRecycle(node);
        }
        return "";
    }

    private String selectedTextInActiveWindow() {
        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
            if (root == null) return "";
            return findSelected(root, new int[]{0});
        } catch (Throwable t) {
            return "";
        } finally {
            safeRecycle(root);
        }
    }

    private String findSelected(AccessibilityNodeInfo node, int[] budget) {
        if (node == null || budget[0]++ > MAX_NODES) return "";
        try {
            int s = node.getTextSelectionStart();
            int e = node.getTextSelectionEnd();
            CharSequence t = node.getText();
            if (t != null && s >= 0 && e > s && e <= t.length()) {
                String sel = t.subSequence(s, e).toString().trim();
                if (sel.length() >= 2) return sel;
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo c = node.getChild(i);
                String r = findSelected(c, budget);
                safeRecycle(c);
                if (!r.isEmpty()) return r;
            }
        } catch (Throwable ignored) { }
        return "";
    }

    // ------------------------------------------------------------------ خواندن متن روی صفحه
    /**
     * متن پیام‌های دیده‌شده در صفحهٔ فعال را برمی‌گرداند (بدون نیاز به کپی کردن).
     * فقط گره‌های متنیِ قابل‌مشاهده و به‌اندازهٔ کافی بلند جمع می‌شوند.
     */
    public String readScreenText() {
        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
            if (root == null) return "";
            LinkedHashSet<String> lines = new LinkedHashSet<>();
            collectText(root, lines, new int[]{0});
            List<String> list = new ArrayList<>(lines);
            // پیام‌های جدیدتر پایین صفحه‌اند → از انتها بردار
            StringBuilder sb = new StringBuilder();
            for (int i = list.size() - 1; i >= 0; i--) {
                String s = list.get(i);
                if (sb.length() + s.length() + 1 > MAX_SCREEN_CHARS) {
                    if (sb.length() > 0) break;
                    sb.insert(0, tail(s, MAX_SCREEN_CHARS));
                    break;
                }
                sb.insert(0, s + "\n");
            }
            return sb.toString().trim();
        } catch (Throwable t) {
            return "";
        } finally {
            safeRecycle(root);
        }
    }

    private void collectText(AccessibilityNodeInfo node, LinkedHashSet<String> out, int[] budget) {
        if (node == null || budget[0]++ > MAX_NODES) return;
        try {
            CharSequence pkg = node.getPackageName();
            boolean mine = pkg != null && getPackageName().contentEquals(pkg);
            if (!mine) {
                CharSequence t = node.getText();
                if (t != null) {
                    String s = t.toString().trim();
                    if (s.length() >= 4 && isVisible(node) && isReadableText(s)) {
                        out.add(s);
                    }
                }
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo c = node.getChild(i);
                collectText(c, out, budget);
                safeRecycle(c);
            }
        } catch (Throwable ignored) { }
    }

    private boolean isVisible(AccessibilityNodeInfo node) {
        try {
            return node.isVisibleToUser();
        } catch (Throwable t) {
            return true;
        }
    }

    /** حذف متن‌هایی که پیام نیستند (فقط ایموجی/عدد/علامت یا خیلی کوتاه) */
    private boolean isReadableText(String s) {
        if (s.length() < 4 || s.length() > 4000) return false;
        int letters = 0;
        for (int i = 0; i < s.length(); i++) {
            if (Character.isLetterOrDigit(s.charAt(i))) letters++;
        }
        return letters >= 3;
    }

    // ------------------------------------------------------------------ ابزارها
    private String joinTexts(List<CharSequence> texts) {
        if (texts == null || texts.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (CharSequence c : texts) {
            if (c == null) continue;
            String s = c.toString().trim();
            if (s.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(s);
        }
        return sb.toString().trim();
    }

    private boolean isMeaningful(String s) {
        if (s == null) return false;
        s = s.trim();
        if (s.length() < 2 || s.length() > 4000) return false;
        int letters = 0;
        for (int i = 0; i < s.length(); i++) if (Character.isLetterOrDigit(s.charAt(i))) letters++;
        return letters >= 2;
    }

    private String tail(String s, int max) {
        return s.length() <= max ? s : s.substring(s.length() - max);
    }

    private void safeRecycle(AccessibilityNodeInfo n) {
        try { if (n != null) n.recycle(); } catch (Throwable ignored) { }
    }
}
