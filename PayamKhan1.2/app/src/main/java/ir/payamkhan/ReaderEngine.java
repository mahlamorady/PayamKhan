package ir.payamkhan;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * موتور خواندن متن — مشترک برای «دستیار شناور».
 * اول صدای عصبی آنلاین (مایکروسافت Edge، رایگان و بدون کلید API) و در صورت نبود اینترنت
 * یا فعال بودن حالت آفلاین، موتور گفتار خود گوشی.
 * این کلاس هیچ رابط کاربری‌ای ندارد؛ فقط وضعیت را به Listener گزارش می‌دهد.
 */
public class ReaderEngine {

    public interface Listener {
        /** پیام وضعیت (فارسی) — برای اعلان یا نوشتهٔ کوچک */
        void onEngineStatus(String text, boolean error);
        /** وضعیت پخش عوض شد (شروع / مکث / ادامه / پایان) */
        void onEngineStateChanged();
    }

    private final Context app;
    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();

    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean ttsFailed = false;

    private final List<String> chunks = new ArrayList<>();
    private int chunkIndex = 0;
    private boolean playing = false;
    private boolean paused = false;
    private boolean useDevice = false;
    private boolean male = true;
    private int session = 0;
    private float rate = 0.9f;

    private MediaPlayer mp;
    private File playingFile;
    private AudioManager am;
    private AudioManager.OnAudioFocusChangeListener focusListener;

    public ReaderEngine(Context context, Listener listener) {
        this.app = context.getApplicationContext();
        this.listener = listener;
    }

    /** ساخت موتور گفتار گوشی (برای حالت آفلاین و fallback) */
    public void init() {
        if (tts != null) return;
        try {
            tts = new TextToSpeech(app, new TextToSpeech.OnInitListener() {
                @Override public void onInit(int status) {
                    if (status == TextToSpeech.SUCCESS && tts != null) {
                        ttsReady = true;
                        ttsFailed = false;
                        tts.setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build());
                        tts.setSpeechRate(rate);
                        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                            @Override public void onStart(final String id) {
                                final int s = session;
                                ui.post(new Runnable() { @Override public void run() {
                                    if (s != session || !useDevice) return;
                                    status("در حال خواندن با موتور گوشی…", false);
                                    stateChanged();
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
                                        else finish("خواندن پیام تمام شد.");
                                    }
                                }});
                            }
                            @Override public void onError(final String id) {
                                final int s = session;
                                ui.post(new Runnable() { @Override public void run() {
                                    if (s != session || !useDevice) return;
                                    if (playing && !paused) finish("خواندن با موتور گوشی ناموفق بود.");
                                }});
                            }
                        });
                        applyDeviceVoice();
                    } else {
                        ttsFailed = true;
                    }
                }
            });
        } catch (Throwable t) {
            ttsFailed = true;
        }
    }

    public boolean isDeviceEngineReady() { return ttsReady && !ttsFailed; }

    // ------------------------------------------------------------------ پخش
    /** خواندن متن کامل */
    public void speak(String text, boolean maleVoice, float speechRate, boolean offline) {
        String clean = TextChunker.normalize(text);
        if (clean.isEmpty()) {
            status("متنی برای خواندن پیدا نشد.", true);
            return;
        }
        init();
        stopInternal(false);
        chunks.clear();
        TextChunker.chunk(clean, chunks);
        if (chunks.isEmpty()) {
            status("متنی برای خواندن پیدا نشد.", true);
            return;
        }
        chunkIndex = 0;
        playing = true;
        paused = false;
        session++;
        male = maleVoice;
        rate = speechRate;
        useDevice = offline;
        if (ttsReady) { try { tts.setSpeechRate(rate); } catch (Throwable ignored) { } }
        requestAudioFocus();
        stateChanged();
        if (useDevice) {
            if (ttsFailed || !ttsReady) {
                status("موتور گوشی آماده نیست؛ تلاش با صدای آنلاین…", false);
                useDevice = false;
                playOnlineChunk(0);
            } else {
                try { tts.stop(); } catch (Throwable ignored) { }
                applyDeviceVoice();
                speakDeviceChunk(0);
            }
        } else {
            playOnlineChunk(0);
        }
    }

    private void playOnlineChunk(final int i) {
        if (!playing || i < 0 || i >= chunks.size()) return;
        chunkIndex = i;
        status("دریافت صدای باکیفیت فارسی… (" + fa(i + 1) + " از " + fa(chunks.size()) + ")", false);
        final int s = session;
        final String voice = male ? NetTts.VOICE_MALE : NetTts.VOICE_FEMALE;
        final String text = chunks.get(i);
        final float r = rate;
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
                        if (f == null) { fallbackToDevice(i); return; }
                        File file = new File(app.getCacheDir(), "float_tts_" + i + "_" + s + ".mp3");
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
        try {
            mp = new MediaPlayer();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mp.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build());
            } else {
                mp.setAudioStreamType(android.media.AudioManager.STREAM_MUSIC);
            }
            try { mp.setWakeMode(app, PowerManager.PARTIAL_WAKE_LOCK); } catch (Throwable ignored) { }
            mp.setDataSource(file.getPath());
            mp.prepare();
        } catch (Throwable t) {
            releaseMp();
            fallbackToDevice(i);
            return;
        }
        playingFile = file;
        final int idx = i;
        mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override public void onCompletion(MediaPlayer m) {
                releaseMp();
                if (!playing) return;
                int nx = idx + 1;
                if (nx < chunks.size()) playOnlineChunk(nx);
                else finish("خواندن پیام تمام شد.");
            }
        });
        mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override public boolean onError(MediaPlayer m, int what, int extra) {
                releaseMp();
                fallbackToDevice(idx);
                return true;
            }
        });
        if (paused) return; // با «ادامه» پخش می‌شود
        mp.start();
        status("در حال خواندن… (" + fa(idx + 1) + " از " + fa(chunks.size()) + ")"
                + (male ? " — صدای مرد" : " — صدای زن"), false);
        stateChanged();
    }

    private void fallbackToDevice(int fromIndex) {
        if (!playing) return;
        if (ttsFailed || !ttsReady) {
            finish("برای صدای با کیفیت به اینترنت متصل باید باشید؛ در غیر این صورت در حالت آفلاین"
                    + " باید موتور صدای فارسی را روی گوشی دانلود کنید.");
            return;
        }
        status("صدای آنلاین در دسترس نیست؛ ادامه با موتور گوشی…", false);
        useDevice = true;
        List<String> rest = new ArrayList<>(chunks.subList(fromIndex, chunks.size()));
        chunks.clear();
        chunks.addAll(rest);
        chunkIndex = 0;
        try { tts.stop(); } catch (Throwable ignored) { }
        applyDeviceVoice();
        speakDeviceChunk(0);
    }

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
        status("در حال خواندن… (" + fa(i + 1) + " از " + fa(chunks.size()) + ")", false);
    }

    // ------------------------------------------------------------------ کنترل‌ها
    public boolean isPlaying() { return playing; }
    public boolean isPaused() { return paused; }

    public void togglePause() {
        if (!playing) return;
        if (!paused) pause(); else resume();
    }

    public void pause() {
        if (!playing || paused) return;
        paused = true;
        if (!useDevice) {
            if (mp != null) { try { if (mp.isPlaying()) mp.pause(); } catch (Throwable ignored) { } }
        } else {
            session++;
            try { tts.stop(); } catch (Throwable ignored) { }
        }
        status("خواندن نگه داشته شد؛ برای ادامه دوباره روی حباب بزنید.", false);
        stateChanged();
    }

    public void resume() {
        if (!playing || !paused) return;
        paused = false;
        session++;
        if (!useDevice) {
            if (mp != null) {
                try { mp.start(); } catch (Throwable ignored) { }
            } else {
                playOnlineChunk(chunkIndex);
            }
        } else {
            speakDeviceChunk(chunkIndex);
        }
        status("در حال خواندن…", false);
        stateChanged();
    }

    public void stop() { stopInternal(true); }

    private void stopInternal(boolean notify) {
        session++;
        playing = false;
        paused = false;
        releaseMp();
        try { if (tts != null) tts.stop(); } catch (Throwable ignored) { }
        chunks.clear();
        chunkIndex = 0;
        cleanCache();
        if (notify) abandonAudioFocus();
        if (notify) {
            status("خواندن قطع شد.", false);
            stateChanged();
        }
    }

    public void shutdown() {
        stopInternal(false);
        abandonAudioFocus();
        try { net.shutdownNow(); } catch (Throwable ignored) { }
        if (tts != null) {
            try { tts.stop(); tts.shutdown(); } catch (Throwable ignored) { }
            tts = null;
        }
    }

    // ------------------------------------------------------------------ موتور گوشی
    private void applyDeviceVoice() {
        if (!ttsReady || tts == null) return;
        String saved = FloatPrefs.get(app).getString(FloatPrefs.KEY_VOICE, null);
        Voice chosen = null;
        List<Voice> fa = persianVoices();
        if (saved != null) {
            for (Voice v : fa) if (saved.equals(v.getName())) { chosen = v; break; }
        }
        if (chosen == null && !fa.isEmpty()) {
            for (Voice v : fa) if (isMaleName(v) == male) { chosen = v; break; }
            if (chosen == null) chosen = fa.get(0);
        }
        if (chosen != null) { try { tts.setVoice(chosen); } catch (Throwable ignored) { } }
        int r = tts.setLanguage(Locale.forLanguageTag("fa-IR"));
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(new Locale("fa"));
        }
    }

    private List<Voice> persianVoices() {
        List<Voice> out = new ArrayList<>();
        if (!ttsReady || tts == null) return out;
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
        return true;
    }

    // ------------------------------------------------------------------ فوکوس صدا
    /** تا صدای پیام‌خوان با موزیک یا ویسِ برنامهٔ دیگری قاطی نشود */
    private void requestAudioFocus() {
        try {
            if (am == null) am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            focusListener = new AudioManager.OnAudioFocusChangeListener() {
                @Override public void onAudioFocusChange(int change) {
                    if (change == AudioManager.AUDIOFOCUS_LOSS) stop();
                    else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                        if (playing && !paused) pause();
                    }
                }
            };
            am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
        } catch (Throwable ignored) { }
    }

    private void abandonAudioFocus() {
        try {
            if (am != null && focusListener != null) am.abandonAudioFocus(focusListener);
        } catch (Throwable ignored) { }
        focusListener = null;
    }

    // ------------------------------------------------------------------ ابزارها
    private void releaseMp() {
        if (mp != null) {
            try { mp.stop(); } catch (Throwable ignored) { }
            try { mp.release(); } catch (Throwable ignored) { }
            mp = null;
        }
        if (playingFile != null) {
            try { playingFile.delete(); } catch (Throwable ignored) { }
            playingFile = null;
        }
    }

    private void cleanCache() {
        try {
            File[] fs = app.getCacheDir().listFiles();
            if (fs != null) for (File f : fs) if (f.getName().startsWith("float_tts_")) f.delete();
        } catch (Throwable ignored) { }
    }

    private void finish(String message) {
        playing = false;
        paused = false;
        releaseMp();
        abandonAudioFocus();
        status(message, false);
        stateChanged();
    }

    private void status(String text, boolean error) {
        if (listener != null) listener.onEngineStatus(text, error);
    }

    private void stateChanged() {
        if (listener != null) listener.onEngineStateChanged();
    }

    private int parseIdx(String id) {
        try { return Integer.parseInt(id); } catch (Exception e) { return -1; }
    }

    private String fa(int n) {
        return String.format(new Locale("fa", "IR"), "%d", n);
    }
}
