package ir.payamkhan;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * موتور گفتار آنلاین رایگان (بدون کلید API):
 * صدای عصبی فارسی مایکروسافت (Edge TTS) — مرد: Farid، زن: Dilara
 * خروجی: بایت‌های MP3
 */
public final class NetTts {

    public static final String VOICE_MALE = "fa-IR-FaridNeural";
    public static final String VOICE_FEMALE = "fa-IR-DilaraNeural";

    private static final String EDGE_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4";
    private static final String EDGE_VERSION = "2-143.0.3650.75";
    private static final String EDGE_HOST = "speech.platform.bing.com";
    private static final String EDGE_PATH = "/consumer/speech/synthesize/readaloud/edge/v1";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0";

    private NetTts() { }

    // ------------------------------------------------------------ Edge (مایکروسافت)
    public static byte[] edgeSynth(String text, String voice, float rate) throws Exception {
        String gec = secMsGec();
        SSLSocketFactory f = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket sock = (SSLSocket) f.createSocket(EDGE_HOST, 443);
        sock.setSoTimeout(30000);
        try {
            OutputStream out = sock.getOutputStream();
            InputStream in = sock.getInputStream();

            byte[] key16 = new byte[16];
            new SecureRandom().nextBytes(key16);
            String wsKey = b64(key16);

            String hand = "GET " + EDGE_PATH + "?TrustedClientToken=" + EDGE_TOKEN
                    + "&ConnectionId=" + connectId()
                    + "&Sec-MS-GEC=" + gec + "&Sec-MS-GEC-Version=" + EDGE_VERSION + " HTTP/1.1\r\n"
                    + "Host: " + EDGE_HOST + "\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + wsKey + "\r\n"
                    + "Sec-WebSocket-Version: 13\r\n"
                    + "User-Agent: " + UA + "\r\n"
                    + "Origin: chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold\r\n"
                    + "Cookie: muid=" + muid() + ";\r\n"
                    + "Pragma: no-cache\r\n"
                    + "Cache-Control: no-cache\r\n"
                    + "\r\n";
            out.write(hand.getBytes("UTF-8"));
            out.flush();

            String resp = readHttpHead(in);
            if (!resp.startsWith("HTTP/1.1 101")) {
                throw new Exception("Edge handshake failed: " + resp.split("\r\n")[0]);
            }

            sendText(out, "X-Timestamp:" + ts() + "\r\n"
                    + "Content-Type:application/json; charset=utf-8\r\n"
                    + "Path:speech.config\r\n\r\n"
                    + "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":"
                    + "{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"},"
                    + "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n");

            int pct = Math.round((rate - 1f) * 100f);
            String rateStr = (pct >= 0 ? "+" : "") + pct + "%";
            String ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' "
                    + "xml:lang='fa-IR'><voice name='" + voice + "'>"
                    + "<prosody pitch='+0Hz' rate='" + rateStr + "' volume='+0%'>"
                    + escapeXml(text) + "</prosody></voice></speak>";
            sendText(out, "X-RequestId:" + connectId() + "\r\n"
                    + "Content-Type:application/ssml+xml\r\n"
                    + "X-Timestamp:" + ts() + "Z\r\n"
                    + "Path:ssml\r\n\r\n" + ssml);

            ByteArrayOutputStream audio = new ByteArrayOutputStream();
            int binFrames = 0;
            String lastTxt = "";
            while (true) {
                Frame fr = readFrame(in);
                if (fr == null) break;
                if (fr.opcode == 0x8) break;
                if (fr.opcode == 0x9) { sendFrame(out, 0xA, fr.payload); continue; }
                if (fr.opcode == 0xA) continue;
                if (fr.opcode == 0x1) {
                    String s = new String(fr.payload, "UTF-8");
                    lastTxt = s.length() > 300 ? s.substring(0, 300) : s;
                    if (s.contains("Path:turn.end")) break;
                } else if (fr.opcode == 0x2) {
                    binFrames++;
                    if (fr.payload.length >= 2) {
                        int hl = ((fr.payload[0] & 0xFF) << 8) | (fr.payload[1] & 0xFF);
                        if (hl <= fr.payload.length - 2 && fr.payload.length - 2 - hl > 0) {
                            audio.write(fr.payload, 2 + hl, fr.payload.length - 2 - hl);
                        }
                    }
                }
            }
            try { sendFrame(out, 0x8, new byte[0]); } catch (Throwable ignored) { }
            byte[] mp3 = audio.toByteArray();
            if (mp3.length < 500 || !isMp3(mp3)) {
                throw new Exception("Edge returned no audio (bin=" + binFrames
                        + " bytes=" + mp3.length + " last=" + lastTxt + ")");
            }
            return mp3;
        } finally {
            try { sock.close(); } catch (Throwable ignored) { }
        }
    }

    public static byte[] synth(String text, String voice, float rate) throws Exception {
        return edgeSynth(text, voice, rate);
    }

    private static String ts() {
        SimpleDateFormat f = new SimpleDateFormat(
                "EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("GMT"));
        return f.format(new Date());
    }

    private static String escapeXml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static String connectId() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static String muid() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02X", x));
        return sb.toString();
    }

    // ------------------------------------------------------------ DRM مایکروسافت (توکن v2)
    private static final long M = 0xFFFFFFFFL;
    private static final long F64 = 0x100000001L;

    private static long m(long v) { return v & M; }
    private static long rol16(long v) { long u = m(v); return m((u << 16) | (u >>> 16)); }
    private static long sig0(long v) { long u = m(v); return m((u >>> 7) ^ m(u << 25) ^ (u >>> 18) ^ m(u << 14) ^ (u >>> 3)); }
    private static long sig1(long v) { long u = m(v); return m((u >>> 17) ^ m(u << 15) ^ (u >>> 19) ^ m(u << 13) ^ (u >>> 10)); }
    private static long shE(long v) { return (v >>> 6) ^ (v >>> 11) ^ (v >>> 25); }
    private static long shA(long v) { return (v >>> 2) ^ (v >>> 13) ^ (v >>> 22); }

    private static final long[] SHA_INIT = {0x6A09E667L,0xBB67AE85L,0x3C6EF372L,0xA54FF53AL,
            0x510E527FL,0x9B05688CL,0x1F83D9ABL,0x5BE0CD19L};
    private static final long[] SHA_STATE = {0xFD9FBEA2L,0x91243CE7L,0xBE499094L,0xCD2E1E0AL,
            0x6C006F49L,0x04BD35ABL,0xFEB0A7E4L,0x03EFFBD2L};
    private static final long[] SHA_SCHED = {0xEB24BF9EL,0x1558691FL,0xA0C09EE2L,0x0CFA32BAL,
            0xA622EE8CL,0xD1DD9491L,0xC0DD25A0L,0xF9E70AB6L,0xF0C31573L,0x7058CE4EL,
            0x7188E606L,0x00E5AB33L,0xD258B060L,0x72766564L};
    private static final long SHA_ADJ = 0xC9DC06A7L;
    private static final long SIG0_ADJ = 0x059C0B80L;
    private static final long SIG1_ADJ = 0x000B92C0L;

    private static final long[] K = {
        0x428A2F98L,0x71374491L,0xB5C0FBCFL,0xE9B5DBA5L,0x3956C25BL,0x59F111F1L,0x923F82A4L,0xAB1C5ED5L,
        0xD807AA98L,0x12835B01L,0x243185BEL,0x550C7DC3L,0x72BE5D74L,0x80DEB1FEL,0x9BDC06A7L,0xC19BF174L,
        0xE49B69C1L,0xEFBE4786L,0x0FC19DC6L,0x240CA1CCL,0x2DE92C6FL,0x4A7484AAL,0x5CB0A9DCL,0x76F988DAL,
        0x983E5152L,0xA831C66DL,0xB00327C8L,0xBF597FC7L,0xC6E00BF3L,0xD5A79147L,0x06CA6351L,0x14292967L,
        0x27B70A85L,0x2E1B2138L,0x4D2C6DFCL,0x53380D13L,0x650A7354L,0x766A0ABBL,0x81C2C92EL,0x92722C85L,
        0xA2BFE8A1L,0xA81A664BL,0xC24B8B70L,0xC76C51A3L,0xD192E819L,0xD6990624L,0xF40E3585L,0x106AA070L,
        0x19A4C116L,0x1E376C08L,0x2748774CL,0x34B0BCB5L,0x391C0CB3L,0x4ED8AA4AL,0x5B9CCA4FL,0x682E6FF3L,
        0x748F82EEL,0x78A5636FL,0x84C87814L,0x8CC70208L,0x90BEFFFAL,0xA4506CEBL,0xBEF9A3F7L,0xC67178F2L};
    private static final long[] KB;
    static {
        KB = new long[64];
        System.arraycopy(K, 0, KB, 0, 14);
        KB[14] = SHA_ADJ;
        System.arraycopy(K, 15, KB, 15, 49);
    }

    private static final long[][] CS_COEF = {
        {0xE82E91B1L,0xB32700D0L},{0x44DE24C6L,0x88DFF51DL},{0x3BEB65BCL,0x29514038L},{0xC89C5933L,0xB328CBDFL},
        {0x8D1EC211L,0xD405B4D5L},{0x314EF5D5L,0x26E55F3CL},{0xE92491B1L,0x607801C6L},{0xF0A5B9D5L,0x82B2C1FBL},
        {0x06064CF3L,0xFA51A3DAL},{0xDA79FA60L,0x207CC464L},{0xBED20560L,0x3C3C74F3L},{0x89F9C9F8L,0xD2D14B61L},
        {0x35FA38C6L,0xBB04A2C1L},{0xCB49E3C6L,0xC5626167L}};
    private static final long CS_FACTOR = 0x82893933L;
    private static final long CS_ADJ0 = 0xF2F90B1AL;
    private static final long CS_ADJ1 = 0x0B1A0000L;

    private static long[] sha256Rounds(long[] st, long[] w, int firstRound) {
        long a=st[0], b=st[1], c=st[2], d=st[3], e=st[4], f=st[5], g=st[6], h=st[7];
        long[] cn = (firstRound == 14) ? KB : K;
        int aligned = Math.min(64, ((firstRound + 7) / 8) * 8);
        long t1, t2;
        for (int i = firstRound; i < aligned; i++) {
            long rotE = m(e) * F64, rotA = m(a) * F64;
            t1 = h + shE(rotE) + (g ^ (e & (f ^ g))) + cn[i] + w[i];
            t2 = shA(rotA) + ((a & b) | (c & (a | b)));
            long na = m(t1 + t2), ne = m(d + t1);
            h = g; g = f; f = e; e = ne; d = c; c = b; b = a; a = na;
        }
        for (int i = aligned; i < 64; i += 8) {
            long rotE, rotA;
            rotE = m(e)*F64; rotA = m(a)*F64;
            t1 = h + shE(rotE) + (g ^ (e & (f ^ g))) + cn[i] + w[i];
            d = m(d + t1);
            h = m(t1 + shA(rotA) + ((a & b) | (c & (a | b))));
            rotE = m(d)*F64; rotA = m(h)*F64;
            t1 = g + shE(rotE) + (f ^ (d & (e ^ f))) + cn[i+1] + w[i+1];
            c = m(c + t1);
            g = m(t1 + shA(rotA) + ((h & a) | (b & (h | a))));
            rotE = m(c)*F64; rotA = m(g)*F64;
            t1 = f + shE(rotE) + (e ^ (c & (d ^ e))) + cn[i+2] + w[i+2];
            b = m(b + t1);
            f = m(t1 + shA(rotA) + ((g & h) | (a & (g | h))));
            rotE = m(b)*F64; rotA = m(f)*F64;
            t1 = e + shE(rotE) + (d ^ (b & (c ^ d))) + cn[i+3] + w[i+3];
            a = m(a + t1);
            e = m(t1 + shA(rotA) + ((f & g) | (h & (f | g))));
            rotE = m(a)*F64; rotA = m(e)*F64;
            t1 = d + shE(rotE) + (c ^ (a & (b ^ c))) + cn[i+4] + w[i+4];
            h = m(h + t1);
            d = m(t1 + shA(rotA) + ((e & f) | (g & (e | f))));
            rotE = m(h)*F64; rotA = m(d)*F64;
            t1 = c + shE(rotE) + (b ^ (h & (a ^ b))) + cn[i+5] + w[i+5];
            g = m(g + t1);
            c = m(t1 + shA(rotA) + ((d & e) | (f & (d | e))));
            rotE = m(g)*F64; rotA = m(c)*F64;
            t1 = b + shE(rotE) + (a ^ (g & (h ^ a))) + cn[i+6] + w[i+6];
            f = m(f + t1);
            b = m(t1 + shA(rotA) + ((c & d) | (e & (c | d))));
            rotE = m(f)*F64; rotA = m(b)*F64;
            t1 = a + shE(rotE) + (h ^ (f & (g ^ h))) + cn[i+7] + w[i+7];
            e = m(e + t1);
            a = m(t1 + shA(rotA) + ((b & c) | (d & (b | c))));
        }
        return new long[]{a,b,c,d,e,f,g,h};
    }

    private static void expandSchedule(long[] w, int firstWord) {
        for (int i = firstWord; i < 64; i++) {
            long x = w[i - 2], y = w[i - 15];
            w[i] = m(sig1(x) + w[i - 7] + sig0(y) + w[i - 16]);
        }
    }

    private static long[] sha256InitState(byte[] data) {
        long[] w = new long[64];
        w[14] = ((data[0] & 0xFFL) << 16) | ((data[1] & 0xFFL) << 8) | (data[2] & 0xFFL);
        w[15] = ((data[3] & 0xFFL) << 24) | ((data[4] & 0xFFL) << 16) | ((data[5] & 0xFFL) << 8) | (data[6] & 0xFFL);
        for (int i = 16; i < 30; i++) {
            long value = SHA_SCHED[i - 16];
            value += (i == 16) ? (sig1(w[14]) ^ SIG1_ADJ) : sig1(w[i - 2]);
            if (i >= 21) value += w[i - 7];
            if (i >= 29) value += sig0(w[14]) ^ SIG0_ADJ;
            w[i] = m(value);
        }
        w[30] = m(sig1(w[28]) + w[23] + sig0(w[15]) + w[14] + SHA_ADJ - K[14]);
        expandSchedule(w, 31);
        long[] working = sha256Rounds(SHA_STATE, w, 14);
        long[] out = new long[8];
        for (int i = 0; i < 8; i++) out[i] = m(SHA_INIT[i] + working[i]);
        return out;
    }

    private static byte[] sha256Digest(byte[] data) {
        int length = 57 + data.length;
        int pad = ((55 - length) % 64 + 64) % 64;
        byte[] padded = new byte[(data.length - 7) + 1 + pad + 8];
        System.arraycopy(data, 7, padded, 0, data.length - 7);
        padded[data.length - 7] = (byte) 0x80;
        long bits = (long) length * 8;
        for (int i = 0; i < 8; i++) {
            padded[padded.length - 1 - i] = (byte) ((bits >>> (8 * i)) & 0xFF);
        }
        long[] state = sha256InitState(data);
        long[] w = new long[64];
        for (int off = 0; off < padded.length; off += 64) {
            for (int i = 0; i < 16; i++) {
                w[i] = ((padded[off + i * 4] & 0xFFL) << 24) | ((padded[off + i * 4 + 1] & 0xFFL) << 16)
                        | ((padded[off + i * 4 + 2] & 0xFFL) << 8) | (padded[off + i * 4 + 3] & 0xFFL);
            }
            expandSchedule(w, 16);
            long[] working = sha256Rounds(state, w, 0);
            for (int i = 0; i < 8; i++) state[i] = m(state[i] + working[i]);
        }
        byte[] out = new byte[32];
        for (int i = 0; i < 8; i++) {
            out[i * 4] = (byte) ((state[i] >>> 24) & 0xFF);
            out[i * 4 + 1] = (byte) ((state[i] >>> 16) & 0xFF);
            out[i * 4 + 2] = (byte) ((state[i] >>> 8) & 0xFF);
            out[i * 4 + 3] = (byte) (state[i] & 0xFF);
        }
        return out;
    }

    private static long[] wordsLe4Even(byte[] data) {
        int pad = (4 - (data.length % 4)) % 4;
        int n = data.length + pad;
        byte[] padded = new byte[n];
        System.arraycopy(data, 0, padded, 0, data.length);
        int count = n / 4;
        if (count % 2 == 1) count++;
        long[] words = new long[count];
        for (int i = 0; i < n / 4; i++) {
            words[i] = (padded[i * 4] & 0xFFL) | ((padded[i * 4 + 1] & 0xFFL) << 8)
                    | ((padded[i * 4 + 2] & 0xFFL) << 16) | ((padded[i * 4 + 3] & 0xFFL) << 24);
        }
        return words;
    }

    private static long[] cs64Digest(byte[] data, byte[] digest) {
        long keyA = (digest[0] & 0xFFL) | ((digest[1] & 0xFFL) << 8)
                | ((digest[2] & 0xFFL) << 16) | ((digest[3] & 0xFFL) << 24);
        long keyB = (digest[4] & 0xFFL) | ((digest[5] & 0xFFL) << 8)
                | ((digest[6] & 0xFFL) << 16) | ((digest[7] & 0xFFL) << 24);
        keyA |= 1;
        keyB |= 1;
        long facA = m(keyA * CS_FACTOR);
        long facB = m(keyB * CS_FACTOR);

        java.util.ArrayList<Long> terms = new java.util.ArrayList<>();
        java.util.ArrayList<Long> offsets = new java.util.ArrayList<>();
        for (int i = 0; i < CS_COEF.length; i++) {
            terms.add(m(CS_COEF[i][0] * (i % 2 == 0 ? facA : facB)));
            offsets.add(CS_COEF[i][1]);
        }
        byte[] withZero = new byte[data.length + 1];
        System.arraycopy(data, 0, withZero, 1, data.length);
        long[] words = wordsLe4Even(withZero);
        for (int i = 0; i < words.length; i++) {
            long word = words[i];
            long term = word * (i % 2 == 0 ? keyA : keyB);
            long offset = rol16(word) * (i % 2 == 0 ? 0xEF0569FBL : 0xC31713DBL);
            if (i == 0) {
                term += CS_ADJ0 * facA;
                offset += CS_ADJ1;
            }
            terms.add(m(term));
            offsets.add(m(offset));
        }

        long swapAcc = 0, swapLast = 0, state = 0, acc = 0;
        for (int i = 0; i < terms.size(); i += 2) {
            long x = terms.get(i), y = terms.get(i + 1);
            long t = rol16(m(offsets.get(i) + x));
            long q = m(t * 0x76F50F34L);
            swapAcc = m(swapAcc + m(m(rol16(q) * 0xC3EFEA97L) + q));
            t = rol16(m(offsets.get(i + 1) + y));
            q = m(t * 0x1881CE3CL);
            swapLast = m(m(rol16(q) * 0x35BD1EC9L) + q);
            swapAcc = m(swapAcc + swapLast);

            state = m(state * keyA + x);
            state = m(rol16(state) * 0xCF98B111L);
            state = m(rol16(state) * 0x87085B9FL);
            state = m(rol16(state) * 0x12CEB96DL);
            state = m(rol16(state) * 0x257E1D83L);
            acc = m(acc + state);
            t = m(state * keyB + y);
            t = m(rol16(t) * 0xA27416F5L);
            t = m(rol16(t) * 0xD38396FFL);
            state = m(rol16(m(rol16(t) * 0x7C932B89L)) * 0xBFA49F69L);
            acc = m(acc + state);
        }
        return new long[]{ (swapLast ^ state) & M, (swapAcc ^ acc) & M };
    }

    /** توکن Sec-MS-GEC نسخه 2 */
    public static String secMsGec() throws Exception {
        double t = System.currentTimeMillis() / 1000.0 + 11644473600.0;
        t -= t % 300;
        t *= 10000000.0;
        String str = String.format(Locale.US, "%.0f", t) + EDGE_TOKEN;
        byte[] data = str.getBytes("US-ASCII");
        byte[] digest = sha256Digest(data);
        long[] cs = cs64Digest(data, digest);
        return hexLe32(cs[0]) + hexLe32(cs[1]) + "-" + hexBe(digest);
    }

    /** برای تست: توکن با زمان دلخواه */
    public static String secMsGecAt(double unixSeconds) throws Exception {
        double t = unixSeconds + 11644473600.0;
        t -= t % 300;
        t *= 10000000.0;
        String str = String.format(Locale.US, "%.0f", t) + EDGE_TOKEN;
        byte[] data = str.getBytes("US-ASCII");
        byte[] digest = sha256Digest(data);
        long[] cs = cs64Digest(data, digest);
        return hexLe32(cs[0]) + hexLe32(cs[1]) + "-" + hexBe(digest);
    }

    private static String hexLe32(long v) {
        return String.format("%02X%02X%02X%02X", v & 0xFF, (v >> 8) & 0xFF, (v >> 16) & 0xFF, (v >> 24) & 0xFF);
    }

    private static String hexBe(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (byte x : d) sb.append(String.format("%02X", x));
        return sb.toString();
    }

    // ------------------------------------------------------------ وب‌ساکت خام
    private static final class Frame {
        int opcode;
        byte[] payload;
    }

    private static void sendText(OutputStream out, String s) throws Exception {
        sendFrame(out, 0x1, s.getBytes("UTF-8"));
    }

    private static void sendFrame(OutputStream out, int opcode, byte[] payload) throws Exception {
        SecureRandom rnd = new SecureRandom();
        byte[] mask = new byte[4];
        rnd.nextBytes(mask);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(0x80 | opcode);
        int len = payload.length;
        if (len < 126) b.write(0x80 | len);
        else if (len < 65536) {
            b.write(0x80 | 126);
            b.write((len >> 8) & 0xFF);
            b.write(len & 0xFF);
        } else {
            b.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) b.write((int) (((long) len >> (8 * i)) & 0xFF));
        }
        b.write(mask);
        byte[] masked = new byte[len];
        for (int i = 0; i < len; i++) masked[i] = (byte) (payload[i] ^ mask[i & 3]);
        b.write(masked);
        out.write(b.toByteArray());
        out.flush();
    }

    private static Frame readFrame(InputStream in) throws Exception {
        int b0 = in.read();
        if (b0 < 0) return null;
        int b1 = in.read();
        if (b1 < 0) return null;
        int opcode = b0 & 0x0F;
        long len = b1 & 0x7F;
        if (len == 126) {
            len = (in.read() << 8) | in.read();
        } else if (len == 127) {
            len = 0;
            for (int i = 0; i < 8; i++) len = (len << 8) | in.read();
        }
        if (len > 8 * 1024 * 1024) throw new Exception("frame too big");
        byte[] payload = readExact(in, (int) len);
        Frame fr = new Frame();
        fr.opcode = opcode;
        fr.payload = payload;
        return fr;
    }

    private static byte[] readExact(InputStream in, int n) throws Exception {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) throw new Exception("stream ended");
            off += r;
        }
        return buf;
    }

    private static String readHttpHead(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        while (true) {
            int c = in.read();
            if (c < 0) break;
            b.write(c);
            byte[] a = b.toByteArray();
            int n = a.length;
            if (n >= 4 && a[n - 1] == '\n' && a[n - 2] == '\r' && a[n - 3] == '\n' && a[n - 4] == '\r') break;
            if (n > 16384) break;
        }
        return new String(b.toByteArray(), "UTF-8");
    }

    private static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static boolean isMp3(byte[] b) {
        if (b.length < 4) return false;
        if (b[0] == 'I' && b[1] == 'D' && b[2] == '3') return true;
        return (b[0] & 0xFF) == 0xFF && ((b[1] & 0xE0) == 0xE0);
    }

    private static String b64(byte[] data) {
        final String AL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < data.length; i += 3) {
            int b0 = data[i] & 0xFF;
            int b1 = i + 1 < data.length ? data[i + 1] & 0xFF : 0;
            int b2 = i + 2 < data.length ? data[i + 2] & 0xFF : 0;
            sb.append(AL.charAt(b0 >> 2));
            sb.append(AL.charAt(((b0 & 3) << 4) | (b1 >> 4)));
            sb.append(i + 1 < data.length ? AL.charAt(((b1 & 0xF) << 2) | (b2 >> 6)) : '=');
            sb.append(i + 2 < data.length ? AL.charAt(b2 & 0x3F) : '=');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ تست دسکتاپ
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("gec")) {
            System.out.println(secMsGecAt(Double.parseDouble(args[1])));
            return;
        }
        String voice = args.length > 0 ? args[0] : VOICE_MALE;
        String text = args.length > 1 ? args[1]
                : "سلام چطوری؟ من سواد ندارم. این برنامه پیام‌های من را با صدای بلند می‌خواند.";
        long t0 = System.currentTimeMillis();
        byte[] mp3 = edgeSynth(text, voice, 0.9f);
        System.out.println("engine=edge bytes=" + mp3.length + " ms=" + (System.currentTimeMillis() - t0));
        java.io.FileOutputStream fo = new java.io.FileOutputStream("/tmp/tts_test_edge.mp3");
        fo.write(mp3);
        fo.close();
        System.out.println("saved /tmp/tts_test_edge.mp3");
    }
}
