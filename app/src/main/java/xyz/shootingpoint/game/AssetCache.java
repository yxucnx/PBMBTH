package xyz.shootingpoint.game;

import android.net.Uri;
import android.util.Log;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Keeps the game's /assets/ files on the phone, so a player downloads each one once. The site answers no-cache, so a browser asks again for
 * every file on every visit.
 *
 * Every failure here returns null, which sends that one request down WebView's normal network path: this class can make a load faster,
 * never break it. A file's SHA-256 is checked on each read, so a file changed on the phone is thrown away and fetched again. That stops casual
 * edits; someone with root can change the record too. Only pictures, models and sounds live here, never code or game rules.
 * A kept file is checked against the site at most once a day, in the background, so a changed file arrives on the next load.
 *
 * (2026-11-12, owner: "make the app lighter") The hash is checked on a file's first read in each run of the app, not on every read: a
 * map's files are read again at every match, and each read loaded the whole file (up to 32 MB) into memory and hashed it while the game
 * waited. A file already checked this run is streamed from disk, while its size and file time are still the ones checked.
 */
final class AssetCache {
    private static final String TAG = "AssetCache";
    private static final String HOST = "shootingpoint.xyz";
    private static final long MAX_BYTES = 2L * 1024 * 1024 * 1024;
    private static final long KEEP_BYTES = MAX_BYTES / 10 * 8;
    private static final long RECHECK_MS = 24L * 3600 * 1000;
    private static final int MAX_FILE = 32 * 1024 * 1024;
    private static final int TRIM_EVERY_WRITES = 500;

    private final File dir;
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();
    private final AtomicInteger writes = new AtomicInteger();
    /** key -> "size:time" of the body as hashed this run (read or written): later reads of it stream from disk */
    private final ConcurrentHashMap<String, String> checked = new ConcurrentHashMap<>();
    private final ExecutorService background = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "asset-cache");
        thread.setPriority(Thread.MIN_PRIORITY); // the game comes first
        thread.setDaemon(true);
        return thread;
    });
    private volatile String userAgent = "";

    private static final class Entry {
        final byte[] bytes; // null: stream `file` instead
        final File file;
        final long length;
        final String type;
        final String etag;
        final boolean due;

        Entry(byte[] bytes, String type, String etag, boolean due) {
            this(bytes, null, bytes.length, type, etag, due);
        }

        Entry(byte[] bytes, File file, long length, String type, String etag, boolean due) {
            this.bytes = bytes;
            this.file = file;
            this.length = length;
            this.type = type;
            this.etag = etag;
            this.due = due;
        }
    }

    AssetCache(File cacheRoot) {
        dir = new File(cacheRoot, "game-assets");
        if (!dir.isDirectory() && !dir.mkdirs()) Log.w(TAG, "no cache folder");
        background.execute(this::trim);
    }

    void setUserAgent(String value) {
        userAgent = value;
    }

    /** Called on a WebView background thread, and may block. Null: WebView loads this request itself. */
    WebResourceResponse open(WebResourceRequest request) {
        try {
            Uri url = request.getUrl();
            String path = url.getPath();
            if (!"GET".equals(request.getMethod()) || !"https".equals(url.getScheme()) || !HOST.equals(url.getHost())) return null;
            if (path == null || !path.startsWith("/assets/")) return null;
            for (String name : request.getRequestHeaders().keySet()) if ("range".equalsIgnoreCase(name)) return null;
            String address = url.toString();
            String key = sha256(address.getBytes(StandardCharsets.UTF_8));
            Object lock = locks.computeIfAbsent(key, k -> new Object());
            synchronized (lock) { // two requests for one file: the second reads what the first stored
                Entry entry = read(key);
                if (entry == null) entry = download(address, key);
                else if (entry.due) recheckLater(address, key, entry.etag, lock);
                return entry == null ? null : response(entry);
            }
        } catch (Exception e) {
            Log.w(TAG, "using the network for this file", e);
            return null;
        }
    }

    private File body(String key) {
        return new File(dir, key + ".bin");
    }

    private File record(String key) {
        return new File(dir, key + ".txt");
    }

    /** The record's three lines: the body's hash, its content type, its ETag. The record's file time is the last check with the site. */
    private Entry read(String key) throws IOException, NoSuchAlgorithmException {
        File body = body(key), record = record(key);
        if (!body.isFile() || !record.isFile() || body.length() > MAX_FILE) return null;
        String[] lines;
        try (InputStream info = new FileInputStream(record)) {
            byte[] text = readAll(info, 4096);
            lines = text == null ? new String[0] : new String(text, StandardCharsets.UTF_8).split("\n", -1);
        }
        if (lines.length < 3) {
            forget(key);
            return null;
        }
        boolean due = System.currentTimeMillis() - record.lastModified() > RECHECK_MS;
        String now = stamp(body);
        if (now.equals(checked.get(key))) return new Entry(null, body, body.length(), lines[1], lines[2], due); // checked this run: stream it
        byte[] bytes;
        try (InputStream in = new FileInputStream(body)) {
            bytes = readAll(in, MAX_FILE);
        }
        if (bytes == null || !lines[0].equals(sha256(bytes))) {
            forget(key);
            return null;
        }
        checked.put(key, now);
        return new Entry(bytes, lines[1], lines[2], due);
    }

    private static String stamp(File body) {
        return body.length() + ":" + body.lastModified();
    }

    private void forget(String key) {
        checked.remove(key);
        if (!body(key).delete()) Log.d(TAG, "no body to delete");
        if (!record(key).delete()) Log.d(TAG, "no record to delete");
    }

    private HttpURLConnection connect(String address) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(15000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent", userAgent);
        connection.setRequestProperty("Referer", "https://" + HOST + "/"); // the asset guard answers foreign sites with 403
        return connection;
    }

    private Entry download(String address, String key) throws IOException, NoSuchAlgorithmException {
        HttpURLConnection connection = connect(address);
        Entry entry = take(connection);
        if (entry == null) {
            connection.disconnect();
            return null;
        }
        store(key, entry);
        return entry;
    }

    /** A complete, cacheable answer, or null. A Cloudflare challenge is an HTML page and must never be kept as a game file. */
    private Entry take(HttpURLConnection connection) throws IOException {
        if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
        String type = connection.getContentType();
        long length = connection.getContentLengthLong();
        if (type == null || type.startsWith("text/html") || length > MAX_FILE) return null;
        byte[] bytes;
        try (InputStream in = connection.getInputStream()) {
            bytes = readAll(in, MAX_FILE);
        }
        if (bytes == null || (length >= 0 && bytes.length != length)) return null;
        String etag = connection.getHeaderField("ETag");
        return new Entry(bytes, type, etag == null ? "" : etag, false);
    }

    private void store(String key, Entry entry) throws IOException, NoSuchAlgorithmException {
        String text = sha256(entry.bytes) + "\n" + entry.type.replace('\n', ' ') + "\n" + entry.etag.replace('\n', ' ');
        checked.remove(key);
        write(body(key), entry.bytes);
        write(record(key), text.getBytes(StandardCharsets.UTF_8));
        checked.put(key, stamp(body(key))); // hashed just now, from the bytes written
        if (writes.incrementAndGet() % TRIM_EVERY_WRITES == 0) background.execute(this::trim);
    }

    /** Via a temporary file: a phone dying mid-write leaves no half file under the real name. */
    private void write(File target, byte[] bytes) throws IOException {
        File temp = File.createTempFile("asset", ".tmp", dir);
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(bytes);
        }
        if (!temp.renameTo(target)) {
            if (!temp.delete()) Log.d(TAG, "temporary file left");
            throw new IOException("rename failed");
        }
    }

    private void recheckLater(String address, String key, String etag, Object lock) {
        if (!record(key).setLastModified(System.currentTimeMillis())) Log.d(TAG, "no file time set");
        background.execute(() -> {
            try {
                HttpURLConnection connection = connect(address);
                if (!etag.isEmpty()) connection.setRequestProperty("If-None-Match", etag);
                if (connection.getResponseCode() == HttpURLConnection.HTTP_NOT_MODIFIED) return;
                Entry fresh = take(connection);
                if (fresh == null) return;
                synchronized (lock) {
                    store(key, fresh);
                }
            } catch (Exception e) {
                Log.d(TAG, "check failed, the kept file stays", e);
            }
        });
    }

    private WebResourceResponse response(Entry entry) throws IOException {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", entry.type);
        headers.put("Content-Length", String.valueOf(entry.length));
        headers.put("Cache-Control", "no-store"); // this class is the cache: no second copy in WebView's
        String mime = entry.type.split(";", 2)[0].trim();
        InputStream data = entry.bytes != null ? new ByteArrayInputStream(entry.bytes) : new FileInputStream(entry.file);
        return new WebResourceResponse(mime, null, 200, "OK", headers, data);
    }

    /** Past the limit, the files unused longest go first. */
    private void trim() {
        File[] files = dir.listFiles();
        if (files == null) return;
        long total = 0;
        List<File> records = new ArrayList<>();
        long oldTemp = System.currentTimeMillis() - 3600 * 1000;
        for (File file : files) {
            String name = file.getName();
            if (name.endsWith(".tmp") && file.lastModified() < oldTemp && !file.delete()) Log.d(TAG, "temporary file left");
            if (name.endsWith(".bin")) total += file.length();
            if (name.endsWith(".txt")) records.add(file);
        }
        if (total <= MAX_BYTES) return;
        Collections.sort(records, Comparator.comparingLong(File::lastModified));
        for (File record : records) {
            if (total <= KEEP_BYTES) return;
            String name = record.getName();
            String key = name.substring(0, name.length() - 4);
            total -= body(key).length();
            forget(key);
        }
    }

    private static byte[] readAll(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        byte[] chunk = new byte[64 * 1024];
        for (int read = in.read(chunk); read >= 0; read = in.read(chunk)) {
            out.write(chunk, 0, read);
            if (out.size() > limit) return null;
        }
        return out.toByteArray();
    }

    private static String sha256(byte[] bytes) throws NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) hex.append(String.format("%02x", b & 255));
        return hex.toString();
    }
}
