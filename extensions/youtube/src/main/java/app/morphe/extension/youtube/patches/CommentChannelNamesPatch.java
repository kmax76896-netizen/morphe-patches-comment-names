package app.morphe.extension.youtube.patches;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.util.Log;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.components.ContextInterface;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Replaces the @handle shown as the comment author with the channel display name.
 *
 * Lookup (same idea as the "Return YouTube Comment Username" userscript):
 * 1. Resolve the handle to a channel id with InnerTube navigation/resolve_url.
 * 2. Read the channel name from the channel RSS feed (first &lt;title&gt;),
 *    falling back to InnerTube browse.
 * Names are cached in memory and on disk.
 */
@SuppressWarnings("unused")
public final class CommentChannelNamesPatch {

    private static final String TAG = "MorpheCommentNames";

    private static final boolean ENABLED = Settings.COMMENT_CHANNEL_NAMES.get();

    /**
     * Logs every Litho text that starts with '@' together with its component path.
     * Used to find the exact component of the comment author. Disable once verified.
     */
    private static final boolean DISCOVERY_LOGGING = false;

    /** Handle at the very start of the text. */
    private static final Pattern HANDLE_PATTERN =
            Pattern.compile("^@([\\p{L}\\p{N}\\p{M}_.\\-·]{3,30})");

    private static final String WEB_CLIENT_VERSION = "2.20251120.01.00";
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 5000;
    /** How long a layout thread waits for a name it does not have yet. */
    private static final long LAYOUT_WAIT_MS = 1500;

    private static final String PREFS_NAME = "morphe_comment_channel_names";
    private static final long CACHE_TTL_MS = TimeUnit.DAYS.toMillis(14);
    private static final long FAILED_TTL_MS = TimeUnit.HOURS.toMillis(6);

    /** Lower case handle → display name. Empty string means lookup failed. */
    private static final Map<String, String> names = new ConcurrentHashMap<>();
    private static final Map<String, Future<String>> pending = new ConcurrentHashMap<>();
    private static final Map<String, Long> failedAt = new ConcurrentHashMap<>();

    private static final ExecutorService executor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "MorpheCommentNames");
        t.setDaemon(true);
        return t;
    });

    private static volatile boolean diskCacheLoaded;

    /**
     * Injection point.
     */
    public static CharSequence onLithoTextLoaded(ContextInterface contextInterface, CharSequence original) {
        try {
            if (!ENABLED || original == null || original.length() < 4 || original.charAt(0) != '@') {
                return original;
            }

            StringBuilder path = contextInterface.patch_getPathBuilder();
            String identifier = contextInterface.patch_getIdentifier();

            if (DISCOVERY_LOGGING) {
                Log.d(TAG, "text='" + original + "' identifier=" + identifier + " path=" + path);
            }

            if (!isCommentAuthor(identifier, path)) {
                return original;
            }

            Matcher matcher = HANDLE_PATTERN.matcher(original);
            if (!matcher.find()) {
                return original;
            }
            String handle = matcher.group(1);
            int handleEnd = matcher.end();
            // The handle must be a whole word (end of text or followed by a separator).
            if (handleEnd < original.length()
                    && !Character.isWhitespace(original.charAt(handleEnd))
                    && original.charAt(handleEnd) != '•' && original.charAt(handleEnd) != '·') {
                return original;
            }

            String name = getName(handle, !Utils.isCurrentlyOnMainThread());
            if (name == null || name.isEmpty()) {
                return original;
            }

            if (original instanceof Spanned) {
                SpannableStringBuilder builder = new SpannableStringBuilder(original);
                builder.replace(0, handleEnd, name);
                return builder;
            }
            return name + original.subSequence(handleEnd, original.length());
        } catch (Exception ex) {
            Logger.printException(() -> "onLithoTextLoaded failure", ex);
        }
        return original;
    }

    private static boolean isCommentAuthor(@Nullable String identifier, StringBuilder path) {
        // Only text inside a comment, and never the comment body (mentions in replies).
        if (!Utils.containsAny(path, "comment") && (identifier == null || !identifier.contains("comment"))) {
            return false;
        }
        return !Utils.containsAny(path, "comment_body", "comment_content", "content_text", "comment_text");
    }

    // region Lookup

    @Nullable
    private static String getName(String handle, boolean mayBlock) {
        loadDiskCacheIfNeeded();
        final String key = handle.toLowerCase(Locale.ROOT);

        String cached = names.get(key);
        if (cached != null) {
            if (!cached.isEmpty()) return cached;
            Long failed = failedAt.get(key);
            if (failed != null && System.currentTimeMillis() - failed < FAILED_TTL_MS) return null;
            names.remove(key);
        }

        Future<String> future = pending.computeIfAbsent(key, k -> executor.submit(() -> {
            try {
                String name = fetchName(handle);
                if (name == null || name.isEmpty()) {
                    names.put(k, "");
                    failedAt.put(k, System.currentTimeMillis());
                    return "";
                }
                names.put(k, name);
                saveToDisk(k, name);
                return name;
            } finally {
                pending.remove(k);
            }
        }));

        if (!mayBlock) return null;
        try {
            return future.get(LAYOUT_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception ex) {
            return null; // Not ready in time. Next layout of this comment will use the name.
        }
    }

    @Nullable
    private static String fetchName(String handle) {
        try {
            String channelId = resolveChannelId(handle);
            if (channelId == null) {
                Log.d(TAG, "Could not resolve channel id for @" + handle);
                return null;
            }
            String name = fetchNameFromFeed(channelId);
            if (name == null || name.isEmpty()) {
                name = fetchNameFromBrowse(channelId);
            }
            if (DISCOVERY_LOGGING) Log.d(TAG, "@" + handle + " -> " + channelId + " -> " + name);
            return name;
        } catch (Exception ex) {
            Log.d(TAG, "Lookup failed for @" + handle, ex);
            return null;
        }
    }

    @Nullable
    private static String resolveChannelId(String handle) throws Exception {
        JSONObject body = innerTubeBody();
        body.put("url", "https://www.youtube.com/@" + URLEncoder.encode(handle, "UTF-8"));
        JSONObject response = postInnerTube("navigation/resolve_url", body);
        if (response == null) return null;
        JSONObject browse = response.optJSONObject("endpoint");
        if (browse != null) browse = browse.optJSONObject("browseEndpoint");
        if (browse == null) return null;
        String id = browse.optString("browseId", "");
        return id.startsWith("UC") ? id : null;
    }

    @Nullable
    private static String fetchNameFromFeed(String channelId) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(
                    "https://www.youtube.com/feeds/videos.xml?channel_id=" + channelId).openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            if (connection.getResponseCode() != 200) return null;

            // The first <title> of the feed is the channel name. Stop reading once found.
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null && sb.length() < 16384) {
                    sb.append(line).append('\n');
                    int start = sb.indexOf("<title>");
                    if (start >= 0) {
                        int end = sb.indexOf("</title>", start);
                        if (end > start) {
                            return decodeXml(sb.substring(start + 7, end)).trim();
                        }
                    }
                }
            }
        } catch (Exception ex) {
            Log.d(TAG, "Feed request failed for " + channelId, ex);
        } finally {
            if (connection != null) connection.disconnect();
        }
        return null;
    }

    @Nullable
    private static String fetchNameFromBrowse(String channelId) throws Exception {
        JSONObject body = innerTubeBody();
        body.put("browseId", channelId);
        JSONObject response = postInnerTube("browse", body);
        if (response == null) return null;

        JSONObject metadata = response.optJSONObject("metadata");
        if (metadata != null) metadata = metadata.optJSONObject("channelMetadataRenderer");
        if (metadata != null && !metadata.optString("title").isEmpty()) {
            return metadata.optString("title");
        }
        JSONObject header = response.optJSONObject("header");
        if (header != null) header = header.optJSONObject("pageHeaderRenderer");
        if (header != null && !header.optString("pageTitle").isEmpty()) {
            return header.optString("pageTitle");
        }
        return null;
    }

    private static JSONObject innerTubeBody() throws Exception {
        JSONObject client = new JSONObject();
        client.put("clientName", "WEB");
        client.put("clientVersion", WEB_CLIENT_VERSION);
        client.put("hl", "en");
        JSONObject context = new JSONObject();
        context.put("client", client);
        JSONObject body = new JSONObject();
        body.put("context", context);
        return body;
    }

    @Nullable
    private static JSONObject postInnerTube(String endpoint, JSONObject body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(
                "https://www.youtube.com/youtubei/v1/" + endpoint + "?prettyPrint=false").openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream os = connection.getOutputStream()) {
                os.write(payload);
            }
            if (connection.getResponseCode() != 200) {
                Log.d(TAG, endpoint + " returned HTTP " + connection.getResponseCode());
                return null;
            }
            try (InputStream is = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = is.read(buffer)) != -1) out.write(buffer, 0, read);
                return new JSONObject(out.toString("UTF-8"));
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String decodeXml(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            int semi;
            if (c == '&' && (semi = s.indexOf(';', i)) > i && semi - i < 10) {
                String entity = s.substring(i + 1, semi);
                String decoded = null;
                switch (entity) {
                    case "amp": decoded = "&"; break;
                    case "lt": decoded = "<"; break;
                    case "gt": decoded = ">"; break;
                    case "quot": decoded = "\""; break;
                    case "apos": decoded = "'"; break;
                    default:
                        try {
                            if (entity.startsWith("#x")) {
                                decoded = new String(Character.toChars(Integer.parseInt(entity.substring(2), 16)));
                            } else if (entity.startsWith("#")) {
                                decoded = new String(Character.toChars(Integer.parseInt(entity.substring(1))));
                            }
                        } catch (Exception ignored) {
                        }
                }
                if (decoded != null) {
                    out.append(decoded);
                    i = semi + 1;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    // endregion

    // region Disk cache

    @Nullable
    private static SharedPreferences prefs() {
        Context context = Utils.getContext();
        return context == null ? null : context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static void loadDiskCacheIfNeeded() {
        if (diskCacheLoaded) return;
        synchronized (CommentChannelNamesPatch.class) {
            if (diskCacheLoaded) return;
            try {
                SharedPreferences prefs = prefs();
                if (prefs == null) return; // Try again later.
                long now = System.currentTimeMillis();
                SharedPreferences.Editor expired = null;
                for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
                    Object value = entry.getValue();
                    if (!(value instanceof String)) continue;
                    String s = (String) value;
                    int tab = s.indexOf('\t');
                    if (tab <= 0) continue;
                    long savedAt = Long.parseLong(s.substring(0, tab));
                    if (now - savedAt > CACHE_TTL_MS) {
                        if (expired == null) expired = prefs.edit();
                        expired.remove(entry.getKey());
                        continue;
                    }
                    names.put(entry.getKey(), s.substring(tab + 1));
                }
                if (expired != null) expired.apply();
            } catch (Exception ex) {
                Logger.printException(() -> "Failed to load comment name cache", ex);
            }
            diskCacheLoaded = true;
        }
    }

    private static void saveToDisk(String key, String name) {
        try {
            SharedPreferences prefs = prefs();
            if (prefs != null) {
                prefs.edit().putString(key, System.currentTimeMillis() + "\t" + name).apply();
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to save comment name", ex);
        }
    }

    // endregion
}
