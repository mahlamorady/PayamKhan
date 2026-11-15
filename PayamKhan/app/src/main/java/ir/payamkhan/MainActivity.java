package ir.payamkhan;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * پیام‌خوان — خواندن بلند هر پیام متنی
 * صدای فارسی باکیفیت: اول موتور آنلاین رایگان (مایکروسافت Edge / گوگل)،
 * و در حالت آفلاین: موتور گفتار خود گوشی.
 * بلندی صدا عمدیً دست خود کاربر است (دکمه‌های ولوم گوشی)؛ برنامه صدا را دست‌کاری نمی‌کند.
 */
public class MainActivity extends Activity
        implements TextToSpeech.OnInitListener {

    private static final int C_BG     = 0xFF141218;
    private static final int C_CARD   = 0xFF231F29;
    private static final int C_CARD_IN= 0xFF2B2733;
    private static final int C_TEXT   = 0xFFEDE7F0;
    private static final int C_MUTED  = 0xFFB7AEC4;
    private static final int C_GREEN  = 0xFF00B389;
    private static final int C_AMBER  = 0xFFE8A33D;
    private static final int C_RED    = 0xFFD9534F;
    private static final int C_PURPLE = 0xFF7C5CFF;
    private static final int C_TEAL   = 0xFF00C9A7;

    private static final String PREF = "payamkhan_prefs";
    private static final String KEY_GENDER = "gender_male";
    private static final String KEY_RATE = "rate";
    private static final String KEY_OFFLINE = "offline";
    private static final String KEY_VOICE = "device_voice";

    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean ttsFailed = false;

    private EditText input;
    private TextView statusView;
    private TextView voiceView;
    private Button btnPlay, btnPause, btnStop;
    private SeekBar rateBar;
    private TextView tvRate;
    private CheckBox chkRepeat, chkOffline;
    private Button btnMale, btnFemale;

    private final List<String> chunks = new ArrayList<>();
    private int chunkIndex = 0;
    private boolean playing = false;
    private boolean paused = false;
    private boolean useDevice = false;      // true = موتور گوشی، false = آنلاین
    private boolean maleVoice = true;
    private int session = 0;
    private float rate = 0.9f;

    private MediaPlayer mp;
    private int preparedIdx = -1;
    private boolean fetchInFlight = false;
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private SharedPreferences prefs;
    private Typeface fontRegular, fontBold, fontMedium;

    // ---------------------------------------------------------- چرخه حیات
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREF, MODE_PRIVATE);
        maleVoice = prefs.getBoolean(KEY_GENDER, true);

        fontRegular = safeFont("vazirmatn_regular");
        fontBold = safeFont("vazirmatn_bold");
        fontMedium = safeFont("vazirmatn_medium");

        buildUi();
        handleIncomingIntent(getIntent());

        tts = new TextToSpeech(this, this);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIncomingIntent(intent);
    }

    @Override
    protected void onPause() {
        if (playing && !paused) pausePlayback();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        session++;
        releaseMp();
        try { net.shutdownNow(); } catch (Throwable ignored) { }
        if (tts != null) {
            try { tts.stop(); tts.shutdown(); } catch (Throwable ignored) { }
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------- رابط کاربری
    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable card(int fill, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private TextView newLabel(String text, float sizeSp, int color, Typeface tf) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        t.setTextColor(color);
        if (tf != null) t.setTypeface(tf);
        t.setTextDirection(View.TEXT_DIRECTION_RTL);
        return t;
    }

    private Button baseButton(String text, int color, float sizeSp, int heightDp, Typeface tf) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setBackground(card(color, 14));
        b.setMinHeight(dp(heightDp));
        b.setPadding(dp(6), dp(6), dp(6), dp(6));
        b.setTextDirection(View.TEXT_DIRECTION_RTL);
        if (tf != null) b.setTypeface(tf);
        b.setStateListAnimator(null);
        return b;
    }

    private Button bigButton(String t, int c) { return baseButton(t, c, 16, 58, fontBold); }
    private Button smallButton(String t, int c) { return baseButton(t, c, 13, 42, fontMedium); }

    private Typeface safeFont(String name) {
        try {
            int id = getResources().getIdentifier(name, "font", getPackageName());
            if (id != 0) return getResources().getFont(id);
        } catch (Throwable ignored) { }
        return null;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);
        root.setPadding(dp(14), dp(14), dp(14), dp(10));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        // سربرگ
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, dp(10));
        TextView title = newLabel("پیام‌خوان", 26, C_TEXT, fontBold);
        TextView subtitle = newLabel("هر پیامی را با صدای بلند و واضح فارسی بشنوید", 13, C_MUTED, fontRegular);
        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        titleCol.addView(title);
        titleCol.addView(subtitle);
        TextView logo = new TextView(this);
        logo.setBackgroundResource(R.mipmap.ic_launcher_round);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(dp(46), dp(46));
        logoLp.leftMargin = dp(10);
        logo.setLayoutParams(logoLp);
        header.addView(titleCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(logo);
        col.addView(header);

        // کارت صدا
        LinearLayout voiceCard = new LinearLayout(this);
        voiceCard.setOrientation(LinearLayout.VERTICAL);
        voiceCard.setBackground(card(C_CARD, 16));
        voiceCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams voiceLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        voiceLp.bottomMargin = dp(10);
        voiceCard.setLayoutParams(voiceLp);

        voiceView = newLabel("صدا: …", 15, C_TEXT, fontMedium);
        voiceView.setPadding(0, 0, 0, dp(8));
        voiceCard.addView(voiceView);

        LinearLayout voiceBtnRow = new LinearLayout(this);
        voiceBtnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnMale = smallButton("🚹 صدای مرد", C_GREEN);
        btnFemale = smallButton("🚺 صدای زن", C_CARD_IN);
        Button btnMore = smallButton("🎙 صداهای گوشی", C_CARD_IN);
        LinearLayout.LayoutParams vbLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        vbLp.leftMargin = dp(6);
        btnMale.setLayoutParams(vbLp); btnFemale.setLayoutParams(vbLp); btnMore.setLayoutParams(vbLp);
        btnMale.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setGender(true); }
        });
        btnFemale.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setGender(false); }
        });
        btnMore.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showVoicePicker(); }
        });
        voiceBtnRow.addView(btnMale); voiceBtnRow.addView(btnFemale); voiceBtnRow.addView(btnMore);
        voiceCard.addView(voiceBtnRow);
        col.addView(voiceCard);

        // کارت متن
        LinearLayout textCard = new LinearLayout(this);
        textCard.setOrientation(LinearLayout.VERTICAL);
        textCard.setBackground(card(C_CARD, 16));
        textCard.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        textLp.bottomMargin = dp(10);
        textCard.setLayoutParams(textLp);

        input = new EditText(this);
        input.setHint("متن پیام را اینجا تایپ یا Paste کنید…\nمثلاً: سلام چطوری؟ فردا می‌آیی خانه؟");
        input.setHintTextColor(0xFF8F8798);
        input.setTextColor(C_TEXT);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        input.setLineSpacing(dp(4), 1f);
        input.setGravity(Gravity.TOP | Gravity.RIGHT);
        input.setTextDirection(View.TEXT_DIRECTION_RTL);
        input.setBackground(card(C_CARD_IN, 12));
        input.setPadding(dp(12), dp(12), dp(12), dp(12));
        input.setMinLines(5);
        input.setMaxLines(10);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        if (fontRegular != null) input.setTypeface(fontRegular);
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(190));
        input.setLayoutParams(inputLp);
        textCard.addView(input);

        LinearLayout editRow = new LinearLayout(this);
        editRow.setOrientation(LinearLayout.HORIZONTAL);
        editRow.setPadding(0, dp(8), 0, 0);
        Button btnPaste = smallButton("📋 چسباندن متن", C_CARD_IN);
        Button btnClear = smallButton("🗑 پاک کردن", C_CARD_IN);
        LinearLayout.LayoutParams ebLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ebLp.leftMargin = dp(6);
        btnPaste.setLayoutParams(ebLp); btnClear.setLayoutParams(ebLp);
        btnPaste.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pasteFromClipboard(); }
        });
        btnClear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                input.setText("");
                setStatus("متن پاک شد. متن پیام را وارد کنید.", C_MUTED);
            }
        });
        editRow.addView(btnPaste); editRow.addView(btnClear);
        textCard.addView(editRow);
        col.addView(textCard);

        // دکمه‌های اصلی
        LinearLayout playRow = new LinearLayout(this);
        playRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        playLp.bottomMargin = dp(10);
        playRow.setLayoutParams(playLp);
        btnPlay = bigButton("🔊 خواندن پیام", C_GREEN);
        btnPause = bigButton("⏸ توقف موقت", C_AMBER);
        btnStop = bigButton("⏹ قطع کامل", C_RED);
        btnPause.setEnabled(false);
        btnStop.setEnabled(false);
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        pbLp.leftMargin = dp(6);
        btnPlay.setLayoutParams(pbLp); btnPause.setLayoutParams(pbLp); btnStop.setLayoutParams(pbLp);
        btnPlay.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startReading(); }
        });
        btnPause.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onPauseResume(); }
        });
        btnStop.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { stopPlayback(); }
        });
        playRow.addView(btnPlay); playRow.addView(btnPause); playRow.addView(btnStop);
        col.addView(playRow);

        // کارت تنظیمات
        LinearLayout setCard = new LinearLayout(this);
        setCard.setOrientation(LinearLayout.VERTICAL);
        setCard.setBackground(card(C_CARD, 16));
        setCard.setPadding(dp(14), dp(10), dp(14), dp(6));
        LinearLayout.LayoutParams setLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        setLp.bottomMargin = dp(10);
        setCard.setLayoutParams(setLp);

        tvRate = newLabel("سرعت خواندن: آرام و واضح", 14, C_TEXT, fontMedium);
        setCard.addView(tvRate);
        rateBar = new SeekBar(this);
        rateBar.setMax(150); // 50..200
        rateBar.setProgress(prefs.getInt(KEY_RATE, 90) - 50);
        rateBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                rate = (p + 50) / 100f;
                tvRate.setText("سرعت خواندن: " + rateLabel(rate));
                if (ttsReady) tts.setSpeechRate(rate);
                prefs.edit().putInt(KEY_RATE, p + 50).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        setCard.addView(rateBar);

        chkOffline = new CheckBox(this);
        chkOffline.setText("حالت آفلاین: فقط موتور گفتار خود گوشی (بدون اینترنت)");
        chkOffline.setTextColor(C_TEXT);
        chkOffline.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        chkOffline.setTextDirection(View.TEXT_DIRECTION_RTL);
        if (fontRegular != null) chkOffline.setTypeface(fontRegular);
        chkOffline.setChecked(prefs.getBoolean(KEY_OFFLINE, false));
        chkOffline.setOnCheckedChangeListener(new CompoundListener());
        setCard.addView(chkOffline);

        chkRepeat = new CheckBox(this);
        chkRepeat.setText("خواندن دوباره و پشت‌سرهم (تکرار)");
        chkRepeat.setTextColor(C_TEXT);
        chkRepeat.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        chkRepeat.setTextDirection(View.TEXT_DIRECTION_RTL);
        if (fontRegular != null) chkRepeat.setTypeface(fontRegular);
        chkRepeat.setPadding(0, dp(4), 0, 0);
        setCard.addView(chkRepeat);
        col.addView(setCard);

        // راهنما
        TextView hint = newLabel(
                "💡 صدای باکیفیت فارسی از اینترنت (رایگان) گرفته می‌شود؛ بلندی صدا را با دکمه‌های"
                        + " ولوم خود گوشی کم و زیاد کنید."
                        + " در واتساپ/تلگرام/پیامک روی پیام «اشتراک‌گذاری» بزنید و «پیام‌خوان» را انتخاب کنید"
                        + " تا پیام خودش بیاید و خوانده شود.",
                12, C_MUTED, fontRegular);
        hint.setLineSpacing(dp(3), 1f);
        hint.setPadding(0, 0, 0, dp(8));
        col.addView(hint);

        statusView = newLabel("آمادهٔ خواندن پیام شما", 15, C_TEAL, fontMedium);
        statusView.setPadding(dp(4), dp(2), dp(4), dp(2));
        col.addView(statusView);

        scroll.addView(col);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        rate = (rateBar.getProgress() + 50) / 100f;
        tvRate.setText("سرعت خواندن: " + rateLabel(rate));
        refreshVoiceLabel();
        refreshGenderButtons();
    }

    private class CompoundListener implements android.widget.CompoundButton.OnCheckedChangeListener {
        @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
            prefs.edit().putBoolean(KEY_OFFLINE, checked).apply();
            useDevice = checked;
            refreshVoiceLabel();
            setStatus(checked ? "حالت آفلاین: موتور گفتار گوشی استفاده می‌شود."
                              : "حالت آنلاین: صدای باکیفیت فارسی از اینترنت.", C_MUTED);
        }
    }

    private String rateLabel(float r) {
        if (r < 0.75f) return "خیلی آرام";
        if (r < 0.95f) return "آرام و واضح";
        if (r < 1.15f) return "معمولی";
        if (r < 1.5f) return "تند";
        return "خیلی تند";
    }

    // ---------------------------------------------------------- متن از بیرون
    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        String text = null;
        if (Intent.ACTION_SEND.equals(action) && "text/plain".equals(intent.getType())) {
            CharSequence cs = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (cs != null) text = cs.toString();
        } else if (Intent.ACTION_PROCESS_TEXT.equals(action)) {
            CharSequence cs = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
            if (cs != null) text = cs.toString();
        }
        if (text != null && !text.trim().isEmpty()) {
            input.setText(text);
            input.setSelection(input.getText().length());
            setStatus("متن پیام دریافت شد؛ در حال خواندن…", C_TEAL);
            startReading();
        }
    }

    private void pasteFromClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) {
            Toast.makeText(this, "حافظهٔ کپی خالی است؛ اول متن پیام را Copy کنید.", Toast.LENGTH_LONG).show();
            return;
        }
        ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return;
        CharSequence cs = clip.getItemAt(0).coerceToText(this);
        if (cs == null || cs.toString().trim().isEmpty()) return;
        String cur = input.getText().toString();
        if (cur.trim().isEmpty()) input.setText(cs);
        else input.setText(cur + "\n" + cs);
        input.setSelection(input.getText().length());
        setStatus("متن از حافظهٔ کپی آورده شد. دکمهٔ «خواندن پیام» را بزنید.", C_TEAL);
    }

    // ---------------------------------------------------------- صدا
    private void setGender(boolean male) {
        maleVoice = male;
        prefs.edit().putBoolean(KEY_GENDER, male).apply();
        refreshGenderButtons();
        refreshVoiceLabel();
        if (!chkOffline.isChecked()) {
            setStatus(male ? "صدای مرد فارسی (آنلاین) انتخاب شد."
                           : "صدای زن فارسی (آنلاین) انتخاب شد.", C_TEAL);
        } else {
            applyDeviceVoice();
            setStatus(male ? "صدای مرد انتخاب شد (اگر موتور گوشی داشته باشد)."
                           : "صدای زن انتخاب شد (اگر موتور گوشی داشته باشد).", C_TEAL);
        }
    }

    private void refreshGenderButtons() {
        btnMale.setBackground(card(maleVoice ? C_GREEN : C_CARD_IN, 14));
        btnFemale.setBackground(card(!maleVoice ? C_PURPLE : C_CARD_IN, 14));
    }

    private void refreshVoiceLabel() {
        if (chkOffline.isChecked()) {
            String dv = prefs.getString(KEY_VOICE, null);
            voiceView.setText("صدا: موتور گوشی" + (dv != null ? " — " + dv : " (پیش‌فرض)"));
            voiceView.setTextColor(C_AMBER);
        } else {
            voiceView.setText(maleVoice
                    ? "صدا: مرد فارسی — فرید (آنلاین، باکیفیت)"
                    : "صدا: زن فارسی — دلارا (آنلاین، باکیفیت)");
            voiceView.setTextColor(C_TEXT);
        }
    }

    // ---------------------------------------------------------- موتور گوشی
    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS && tts != null) {
            ttsReady = true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                tts.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build());
            }
            tts.setSpeechRate(rate);
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(final String id) {
                    final int s = session;
                    ui.post(new Runnable() { @Override public void run() {
                        if (s != session || !useDevice) return;
                        setStatus("🔊 در حال خواندن با موتور گوشی… (بخش "
                                + fa(parseIdx(id) + 1) + " از " + fa(chunks.size()) + ")", C_TEAL);
                    }});
                }
                @Override public void onDone(final String id) {
                    final int s = session;
                    ui.post(new Runnable() { @Override public void run() {
                        if (s != session || !useDevice) return;
                        int n = parseIdx(id);
                        if (n == chunkIndex && playing && !paused) {
                            chunkIndex++;
                            if (chunkIndex < chunks.size()) speakDeviceChunk(chunkIndex);
                            else if (chkRepeat.isChecked()) { chunkIndex = 0; speakDeviceChunk(0); }
                            else finishPlayback("✅ خواندن پیام تمام شد.");
                        }
                    }});
                }
                @Override public void onError(final String id) {
                    final int s = session;
                    ui.post(new Runnable() { @Override public void run() {
                        if (s != session || !useDevice) return;
                        if (playing && !paused) finishPlayback("⚠️ خطا در خواندن با موتور گوشی.");
                    }});
                }
            });
            applyDeviceVoice();
        } else {
            ttsFailed = true;
        }
    }

    private int parseIdx(String id) {
        try { return Integer.parseInt(id); } catch (Exception e) { return -1; }
    }

    private String fa(int n) {
        return String.format(new Locale("fa", "IR"), "%d", n);
    }

    private List<Voice> persianVoices() {
        List<Voice> out = new ArrayList<>();
        if (!ttsReady || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return out;
        try {
            java.util.Set<Voice> all = tts.getVoices();
            if (all == null) return out;
            for (Voice v : all) {
                if (v == null || v.getLocale() == null) continue;
                String lang = v.getLocale().getLanguage();
                if ("fa".equals(lang) || "fas".equals(lang) || "per".equals(lang)) out.add(v);
            }
        } catch (Throwable ignored) { }
        return out;
    }

    private boolean isMaleName(Voice v) {
        String n = v.getName().toLowerCase(Locale.ROOT);
        if (n.contains("female") || n.contains("woman") || n.endsWith("_f") || n.contains("-f-")) return false;
        if (n.contains("male") || n.contains("man") || n.endsWith("_m") || n.contains("-m-")) return true;
        return true;
    }

    private void applyDeviceVoice() {
        if (!ttsReady) return;
        String saved = prefs.getString(KEY_VOICE, null);
        Voice chosen = null;
        List<Voice> faVoices = persianVoices();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            if (saved != null) {
                for (Voice v : faVoices) if (v.getName().equals(saved)) { chosen = v; break; }
            }
            if (chosen == null && !faVoices.isEmpty()) {
                for (Voice v : faVoices) if (isMaleName(v) == maleVoice) { chosen = v; break; }
                if (chosen == null) chosen = faVoices.get(0);
            }
            if (chosen != null) {
                try { tts.setVoice(chosen); } catch (Throwable ignored) { }
            }
        }
        int r = tts.setLanguage(Build.VERSION.SDK_INT >= 21
                ? Locale.forLanguageTag("fa-IR") : new Locale("fa"));
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(new Locale("ar"));
        }
    }

    private void showVoicePicker() {
        if (!ttsReady) { showNoTtsDialog(); return; }
        final List<Voice> faVoices = persianVoices();
        final List<String> names = new ArrayList<>();
        names.add("🌐 صدای آنلاین باکیفیت (پیشنهادی) — مرد/زن");
        for (Voice v : faVoices) names.add("🎙 " + v.getName() + "  [" + v.getLocale() + "]");
        if (faVoices.isEmpty()) names.add("⚠️ موتور گوشی هیچ صدای فارسی ندارد");
        names.add("⚙️ تنظیمات موتور صدای گوشی");
        names.add("🛒 نصب صدای گوگل برای حالت آفلاین");

        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(
                this, android.R.layout.simple_list_item_1, names);
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("انتخاب منبع صدا")
                .setAdapter(adapter, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            chkOffline.setChecked(false);
                        } else if (which == names.size() - 2) {
                            try { startActivity(new Intent("com.android.settings.TTS_SETTINGS")); }
                            catch (Throwable t) {
                                try { startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS)); }
                                catch (Throwable ignored) { }
                            }
                        } else if (which == names.size() - 1) {
                            openPlayStoreTts();
                        } else {
                            Voice v = faVoices.get(which - 1);
                            prefs.edit().putString(KEY_VOICE, v.getName()).apply();
                            chkOffline.setChecked(true);
                            applyDeviceVoice();
                            setStatus("موتور گوشی انتخاب شد: " + v.getName(), C_TEAL);
                        }
                    }
                })
                .setNegativeButton("بستن", null)
                .create();
        dlg.show();
    }

    private void showNoTtsDialog() {
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("موتور گفتار گوشی پیدا نشد")
                .setMessage("برای حالت آفلاین نیاز به موتور گفتار روی گوشی است (مانند گفتار گوگل)."
                        + " ولی نگران نباشید: حالت آنلاین بدون آن هم کار می‌کند.")
                .setPositiveButton("نصب از فروشگاه", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { openPlayStoreTts(); }
                })
                .setNegativeButton("همین حالت آنلاین کافی است", null)
                .create();
        dlg.show();
    }

    private void openPlayStoreTts() {
        String[] urls = {
                "market://details?id=com.google.android.tts",
                "https://play.google.com/store/apps/details?id=com.google.android.tts",
                "market://details?id=com.samsung.SMT",
                "https://play.google.com/store/apps/details?id=com.samsung.SMT"
        };
        for (String u : urls) {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u))); return; }
            catch (Throwable ignored) { }
        }
        Toast.makeText(this, "فروشگاه پیدا نشد؛ «Google Speech Services» را دستی نصب کنید.", Toast.LENGTH_LONG).show();
    }

    // ---------------------------------------------------------- پخش
    private void setStatus(String text, int color) {
        statusView.setText(text);
        statusView.setTextColor(color);
    }

    private void startReading() {
        String text = input.getText().toString().trim();
        if (text.isEmpty()) {
            setStatus("✍️ اول متن پیام را وارد کنید یا Paste کنید.", C_AMBER);
            Toast.makeText(this, "کادر بالا را لمس کنید و متن پیام را بنویسید یا بچسبانید.", Toast.LENGTH_LONG).show();
            return;
        }
        releaseMp();
        chunks.clear();
        chunkText(text, chunks);
        chunkIndex = 0;
        playing = true;
        paused = false;
        session++;
        useDevice = chkOffline.isChecked();
        updateButtons();
        if (useDevice) {
            if (ttsFailed || !ttsReady) {
                setStatus("موتور گوشی آماده نیست؛ تلاش با صدای آنلاین…", C_AMBER);
                useDevice = false;
                playOnlineChunk(0);
                return;
            }
            try { tts.stop(); } catch (Throwable ignored) { }
            speakDeviceChunk(0);
        } else {
            playOnlineChunk(0);
        }
    }

    // ---------- آنلاین ----------
    private void playOnlineChunk(final int i) {
        if (!playing || i < 0 || i >= chunks.size()) return;
        chunkIndex = i;
        setStatus("⏳ دانلود صدای فارسی از اینترنت… (بخش " + fa(i + 1) + " از " + fa(chunks.size()) + ")", C_AMBER);
        final int s = session;
        final String voice = maleVoice ? NetTts.VOICE_MALE : NetTts.VOICE_FEMALE;
        final String text = chunks.get(i);
        final float r = rate;
        fetchInFlight = true;
        net.submit(new Runnable() {
            @Override public void run() {
                byte[] mp3 = null;
                try {
                    mp3 = NetTts.synth(text, voice, r);
                } catch (Throwable ignored) { }
                final byte[] f = mp3;
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (s != session) return;
                        fetchInFlight = false;
                        if (f == null) {
                            fallbackToDevice(i);
                            return;
                        }
                        File file = new File(getCacheDir(), "tts_" + i + "_" + s + ".mp3");
                        try {
                            FileOutputStream fo = new FileOutputStream(file);
                            fo.write(f);
                            fo.close();
                            playMp3(file, i);
                        } catch (Throwable t) {
                            fallbackToDevice(i);
                        }
                    }
                });
            }
        });
    }

    private void playMp3(File file, final int i) {
        releaseMp();
        preparedIdx = -1;
        try {
            mp = new MediaPlayer();
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mp.setDataSource(file.getPath());
            mp.prepare();
        } catch (Throwable t) {
            releaseMp();
            fallbackToDevice(i);
            return;
        }
        final File f = file;
        mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override public void onCompletion(MediaPlayer m) {
                releaseMp();
                try { f.delete(); } catch (Throwable ignored) { }
                if (!playing) return;
                int nx = i + 1;
                if (nx < chunks.size()) playOnlineChunk(nx);
                else if (chkRepeat.isChecked()) playOnlineChunk(0);
                else finishPlayback("✅ خواندن پیام تمام شد.");
            }
        });
        mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override public boolean onError(MediaPlayer m, int what, int extra) {
                releaseMp();
                fallbackToDevice(i);
                return true;
            }
        });
        if (paused) {
            preparedIdx = i;
            setStatus("⏸ آمادهٔ ادامه؛ دکمهٔ «ادامه خواندن» را بزنید.", C_AMBER);
            return;
        }
        mp.start();
        setStatus("🔊 در حال خواندن… (بخش " + fa(i + 1) + " از " + fa(chunks.size()) + ")"
                + (maleVoice ? " — صدای مرد" : " — صدای زن"), C_TEAL);
    }

    private void fallbackToDevice(int fromIndex) {
        if (!playing) return;
        if (ttsFailed || !ttsReady) {
            finishPlayback("❌ اینترنت در دسترس نیست و موتور گوشی هم نیست؛ اتصال اینترنت را وصل کنید.");
            showNoTtsDialog();
            return;
        }
        setStatus("⚠️ صدای آنلاین در دسترس نیست؛ ادامه با موتور گوشی…", C_AMBER);
        useDevice = true;
        List<String> rest = new ArrayList<>(chunks.subList(fromIndex, chunks.size()));
        chunks.clear();
        chunks.addAll(rest);
        chunkIndex = 0;
        try { tts.stop(); } catch (Throwable ignored) { }
        speakDeviceChunk(0);
    }

    // ---------- موتور گوشی ----------
    private void speakDeviceChunk(int i) {
        if (!playing || i < 0 || i >= chunks.size()) return;
        chunkIndex = i;
        Bundle params = new Bundle();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            tts.speak(chunks.get(i), TextToSpeech.QUEUE_ADD, params, String.valueOf(i));
        } else {
            java.util.HashMap<String, String> p = new java.util.HashMap<>();
            p.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, String.valueOf(i));
            tts.speak(chunks.get(i), TextToSpeech.QUEUE_ADD, p);
        }
    }

    // ---------- کنترل‌ها ----------
    private void onPauseResume() {
        if (!playing) return;
        if (!paused) pausePlayback();
        else resumePlayback();
    }

    private void pausePlayback() {
        if (!playing || paused) return;
        paused = true;
        if (!useDevice) {
            // اگر MediaPlayer در حال پخش است متوقفش کن؛ وگرنه (در حال دانلود)
            // خودِ playMp3 صدا را نگه می‌دارد تا با «ادامه» پخش شود
            if (mp != null) {
                try { if (mp.isPlaying()) mp.pause(); } catch (Throwable ignored) { }
            }
        } else {
            session++;
            try { tts.stop(); } catch (Throwable ignored) { }
        }
        updateButtons();
        setStatus("⏸ خواندن نگه داشته شد؛ برای ادامه دکمهٔ «ادامه خواندن» را بزنید.", C_AMBER);
    }

    private void resumePlayback() {
        if (!playing || !paused) return;
        paused = false;
        if (!useDevice) {
            if (mp != null) {
                try { mp.start(); preparedIdx = -1; } catch (Throwable ignored) { }
            } else if (!fetchInFlight) {
                playOnlineChunk(chunkIndex);
            }
        } else {
            session++;
            speakDeviceChunk(chunkIndex);
        }
        updateButtons();
        setStatus("🔊 در حال خواندن…", C_TEAL);
    }

    private void stopPlayback() {
        session++;
        playing = false;
        paused = false;
        preparedIdx = -1;
        releaseMp();
        try { tts.stop(); } catch (Throwable ignored) { }
        chunks.clear();
        chunkIndex = 0;
        cleanCache();
        updateButtons();
        setStatus("⏹ خواندن قطع شد.", C_MUTED);
    }

    private void finishPlayback(String message) {
        playing = false;
        paused = false;
        updateButtons();
        setStatus(message, C_TEAL);
    }

    private void updateButtons() {
        btnPause.setEnabled(playing);
        btnStop.setEnabled(playing || paused);
        btnPause.setText(paused ? "▶ ادامه خواندن" : "⏸ توقف موقت");
        btnPlay.setText(playing && !paused ? "🔊 خواندن از اول" : "🔊 خواندن پیام");
    }

    private void releaseMp() {
        if (mp != null) {
            try { mp.stop(); } catch (Throwable ignored) { }
            try { mp.release(); } catch (Throwable ignored) { }
            mp = null;
        }
    }

    private void cleanCache() {
        try {
            File[] fs = getCacheDir().listFiles();
            if (fs != null) for (File f : fs) if (f.getName().startsWith("tts_")) f.delete();
        } catch (Throwable ignored) { }
    }

    // ---------------------------------------------------------- تقسیم متن
    private static final int MAX_CHUNK = 300;

    private static void chunkText(String text, List<String> out) {
        String[] paras = text.split("\\n+");
        StringBuilder cur = new StringBuilder();
        for (String para : paras) {
            String p = para.trim();
            if (p.isEmpty()) continue;
            if (cur.length() + p.length() + 1 <= MAX_CHUNK) {
                if (cur.length() > 0) cur.append(' ');
                cur.append(p);
            } else {
                if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
                if (p.length() <= MAX_CHUNK) cur.append(p);
                else for (String s : splitLong(p, MAX_CHUNK)) out.add(s);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        if (out.isEmpty()) out.add(text);
    }

    private static List<String> splitLong(String s, int max) {
        List<String> res = new ArrayList<>();
        String[] sentences = s.split("(?<=[.!؟?؛;:])\\s*");
        StringBuilder cur = new StringBuilder();
        for (String sen : sentences) {
            if (sen.trim().isEmpty()) continue;
            if (cur.length() + sen.length() + 1 <= max) {
                if (cur.length() > 0) cur.append(' ');
                cur.append(sen);
            } else {
                if (cur.length() > 0) { res.add(cur.toString()); cur.setLength(0); }
                if (sen.length() <= max) cur.append(sen);
                else {
                    int i = 0;
                    while (i < sen.length()) {
                        res.add(sen.substring(i, Math.min(sen.length(), i + max)));
                        i += max;
                    }
                }
            }
        }
        if (cur.length() > 0) res.add(cur.toString());
        return res;
    }
}
