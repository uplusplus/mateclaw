package vip.mate.llm.chatmodel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Captures lightweight request/response previews for a single LLM call so
 * downstream error paths can surface raw provider evidence in the UI.
 *
 * <p>Request capture starts from a thread-local token because the request body
 * is usually built on the caller thread. Response capture uses the explicit
 * token passed into reactive callbacks because those may execute on other
 * threads.</p>
 */
public final class LlmCallDiagnostics {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int MAX_REQUEST_PREVIEW_CHARS = 6_000;
    private static final int MAX_RESPONSE_PREVIEW_CHARS = 8_000;
    private static final int MAX_SINGLE_TEXT_CHARS = 1_200;
    private static final Set<String> SECRET_FIELDS = Set.of(
            "apikey", "api_key", "authorization", "proxy-authorization",
            "x-api-key", "access_token", "refresh_token", "token");
    private static final Set<String> BULKY_FIELDS = Set.of(
            "data", "bytes", "b64_json", "image_base64", "audio", "video");

    private static final ThreadLocal<String> CURRENT_TOKEN = new ThreadLocal<>();
    private static final ConcurrentMap<String, MutableSnapshot> SNAPSHOTS = new ConcurrentHashMap<>();

    private LlmCallDiagnostics() {
    }

    public static String begin() {
        String token = UUID.randomUUID().toString();
        begin(token);
        return token;
    }

    public static void begin(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        CURRENT_TOKEN.set(token);
        SNAPSHOTS.put(token, new MutableSnapshot());
    }

    public static String currentToken() {
        return CURRENT_TOKEN.get();
    }

    public static void clear(String token) {
        if (token != null) {
            SNAPSHOTS.remove(token);
            if (token.equals(CURRENT_TOKEN.get())) {
                CURRENT_TOKEN.remove();
            }
        }
    }

    public static void recordRequestCurrent(String source, String requestPreview) {
        recordRequest(currentToken(), source, requestPreview);
    }

    public static void recordRequest(String token, String source, String requestPreview) {
        if (token == null || requestPreview == null || requestPreview.isBlank()) {
            return;
        }
        MutableSnapshot snapshot = SNAPSHOTS.computeIfAbsent(token, ignored -> new MutableSnapshot());
        synchronized (snapshot) {
            snapshot.requestSource = normalizeSource(source);
            snapshot.requestPreview = sanitizePreview(requestPreview, MAX_REQUEST_PREVIEW_CHARS);
        }
    }

    public static boolean shouldCaptureResponse(String token) {
        if (token == null) {
            return false;
        }
        MutableSnapshot snapshot = SNAPSHOTS.get(token);
        if (snapshot == null) {
            return false;
        }
        synchronized (snapshot) {
            return !snapshot.responsePreviewTruncated
                    && snapshot.responsePreview.length() < MAX_RESPONSE_PREVIEW_CHARS;
        }
    }

    public static void recordResponseChunk(String token, String source, String responsePreview) {
        appendResponse(token, source, responsePreview, false);
    }

    public static void recordErrorResponse(String token, String source, String responsePreview) {
        appendResponse(token, source, responsePreview, true);
    }

    public static Snapshot snapshot(String token) {
        if (token == null) {
            return Snapshot.empty();
        }
        MutableSnapshot snapshot = SNAPSHOTS.get(token);
        if (snapshot == null) {
            return Snapshot.empty();
        }
        synchronized (snapshot) {
            return new Snapshot(
                    snapshot.requestSource,
                    snapshot.requestPreview,
                    snapshot.responseSource,
                    snapshot.responsePreview.toString(),
                    snapshot.responseChunks
            );
        }
    }

    private static void appendResponse(String token, String source, String responsePreview, boolean errorBody) {
        if (token == null || responsePreview == null || responsePreview.isBlank()) {
            return;
        }
        MutableSnapshot snapshot = SNAPSHOTS.computeIfAbsent(token, ignored -> new MutableSnapshot());
        String sanitized = sanitizePreview(responsePreview, Math.min(MAX_SINGLE_TEXT_CHARS, MAX_RESPONSE_PREVIEW_CHARS));
        synchronized (snapshot) {
            snapshot.responseSource = normalizeSource(source);
            snapshot.responseChunks++;
            StringBuilder entry = new StringBuilder();
            entry.append("[chunk ").append(snapshot.responseChunks).append("]");
            if (errorBody) {
                entry.append(" [error]");
            }
            entry.append('\n').append(sanitized);
            appendWithCap(snapshot.responsePreview, entry.toString(), MAX_RESPONSE_PREVIEW_CHARS, snapshot);
        }
    }

    private static void appendWithCap(StringBuilder builder, String chunk, int maxChars, MutableSnapshot snapshot) {
        if (builder.length() >= maxChars) {
            snapshot.responsePreviewTruncated = true;
            return;
        }
        if (!builder.isEmpty()) {
            builder.append("\n\n");
        }
        int remaining = maxChars - builder.length();
        if (chunk.length() <= remaining) {
            builder.append(chunk);
            return;
        }
        String marker = "\n...[diagnostics preview truncated]...";
        int keep = Math.max(0, remaining - marker.length());
        if (keep > 0) {
            builder.append(chunk, 0, keep);
        }
        builder.append(marker);
        snapshot.responsePreviewTruncated = true;
    }

    private static String sanitizePreview(String raw, int maxChars) {
        String normalized = normalize(raw);
        if (normalized.isEmpty()) {
            return "";
        }
        String maybeJson = normalizeJsonPreview(normalized);
        return abbreviate(maybeJson, maxChars);
    }

    private static String normalizeJsonPreview(String raw) {
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        char first = trimmed.charAt(0);
        if (first != '{' && first != '[') {
            return raw;
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(trimmed);
            JsonNode sanitized = sanitizeNode(node, null);
            return OBJECT_MAPPER.writeValueAsString(sanitized);
        } catch (Exception ignored) {
            return raw;
        }
    }

    private static JsonNode sanitizeNode(JsonNode node, String fieldName) {
        if (node == null || node.isNull()) {
            return NullNode.instance;
        }
        if (node.isObject()) {
            ObjectNode out = OBJECT_MAPPER.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                out.set(entry.getKey(), sanitizeNode(entry.getValue(), entry.getKey()));
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = OBJECT_MAPPER.createArrayNode();
            for (JsonNode item : node) {
                out.add(sanitizeNode(item, fieldName));
            }
            return out;
        }
        if (node.isTextual()) {
            String key = fieldName == null ? "" : fieldName.toLowerCase(Locale.ROOT);
            String value = node.asText();
            if (SECRET_FIELDS.contains(key)) {
                return TextNode.valueOf("[redacted]");
            }
            if (looksBinaryLike(key, value)) {
                return TextNode.valueOf("[omitted bulky data, len=" + value.length() + "]");
            }
            return TextNode.valueOf(abbreviate(value, MAX_SINGLE_TEXT_CHARS));
        }
        return node.deepCopy();
    }

    private static boolean looksBinaryLike(String fieldName, String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        if ((BULKY_FIELDS.contains(fieldName) || "url".equals(fieldName) || "uri".equals(fieldName))
                && value.startsWith("data:")) {
            return true;
        }
        if (BULKY_FIELDS.contains(fieldName) && value.length() > 256) {
            return true;
        }
        return value.startsWith("data:") && value.contains(";base64,");
    }

    private static String abbreviate(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        if (maxChars <= 0 || text.length() <= maxChars) {
            return text;
        }
        int markerLen = 32;
        int head = Math.max(128, (maxChars - markerLen) * 2 / 3);
        head = Math.min(head, text.length());
        int tail = Math.max(48, maxChars - head - markerLen);
        tail = Math.min(tail, text.length() - head);
        int omitted = text.length() - head - tail;
        if (omitted <= 0) {
            return text.substring(0, maxChars);
        }
        return text.substring(0, head)
                + "\n...[truncated " + omitted + " chars]...\n"
                + text.substring(text.length() - tail);
    }

    private static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace("\u0000", "")
                .trim();
    }

    private static String normalizeSource(String source) {
        return source == null || source.isBlank() ? "unknown" : source;
    }

    private static final class MutableSnapshot {
        private String requestSource;
        private String requestPreview;
        private String responseSource;
        private final StringBuilder responsePreview = new StringBuilder();
        private int responseChunks;
        private boolean responsePreviewTruncated;
    }

    public record Snapshot(
            String requestSource,
            String requestPreview,
            String responseSource,
            String responsePreview,
            int responseChunks
    ) {
        private static Snapshot empty() {
            return new Snapshot(null, null, null, null, 0);
        }
    }
}
