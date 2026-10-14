import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Fetches LeetCode problem descriptions by frontend question id.
 *
 * <p>LeetCode has no simple "fetch by numeric id" REST endpoint. The mapping
 * {@code frontendId -> titleSlug} is resolved via {@code /api/problems/all/},
 * then the description is fetched via the GraphQL endpoint. Both steps use
 * only the JDK HTTP client.
 */
public class LeetCodeFetcher {

    public static final String LEETCODE_API_ALL = "https://leetcode.com/api/problems/all/";
    public static final String LEETCODE_GRAPHQL = "https://leetcode.com/graphql";

    private static final String USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    private static final int READ_TIMEOUT_MILLIS = 15_000;
    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;

    // Cache for frontendId -> titleSlug mapping, to avoid re-downloading the
    // full problem list on every request. Refreshed every 10 minutes.
    private static final long CACHE_TTL_MILLIS = 10 * 60 * 1000L;
    private static final Object CACHE_LOCK = new Object();
    private static Map<Integer, String> slugCache = null;
    private static long cacheExpiry = 0L;

    public static int getCacheSize() {
        synchronized (CACHE_LOCK) {
            return slugCache != null ? slugCache.size() : 0;
        }
    }

    public static boolean isCacheWarm() {
        synchronized (CACHE_LOCK) {
            return slugCache != null && System.currentTimeMillis() < cacheExpiry;
        }
    }

    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;

    public LeetCodeFetcher() {
        this(CONNECT_TIMEOUT_MILLIS, READ_TIMEOUT_MILLIS);
    }

    public LeetCodeFetcher(int connectTimeoutMillis, int readTimeoutMillis) {
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
    }

    /**
     * Fetches a LeetCode problem by its frontend numeric id (e.g. 1 for Two Sum).
     *
     * @param frontendId 1-based problem number as shown on LeetCode
     * @return the problem, or {@code null} if no such problem exists
     * @throws IOException if LeetCode is unreachable or returns a non-200 status
     */
    private static void prof(String msg) {
        // Profiling log visible in server stdout (/tmp/java_opt.log) and devserver console.
        System.out.println("[LeetCode][PROF] " + msg);
    }

    public LeetCodeProblem fetchProblem(int frontendId) throws IOException {
        long t0 = System.nanoTime();
        prof("fetchProblem start id=" + frontendId);
        if (frontendId <= 0) {
            throw new IllegalArgumentException("problem number must be positive, got " + frontendId);
        }
        long tSlug0 = System.nanoTime();
        String slug = resolveSlug(frontendId);
        long tSlug1 = System.nanoTime();
        prof("fetchProblem id=" + frontendId + " resolveSlug slug=" + slug + " took=" + ((tSlug1 - tSlug0) / 1_000_000) + "ms");
        if (slug == null) {
            prof("fetchProblem id=" + frontendId + " not found (no slug) total=" + ((System.nanoTime() - t0) / 1_000_000) + "ms");
            return null;
        }
        long tDet0 = System.nanoTime();
        LeetCodeProblem p = fetchDetails(slug, String.valueOf(frontendId));
        long tDet1 = System.nanoTime();
        prof("fetchProblem id=" + frontendId + " fetchDetails took=" + ((tDet1 - tDet0) / 1_000_000) + "ms title=" + (p != null ? p.getTitle() : "null"));
        prof("fetchProblem id=" + frontendId + " total=" + ((System.nanoTime() - t0) / 1_000_000) + "ms");
        return p;
    }

    private String resolveSlug(int targetId) throws IOException {
        // Check cache.
        synchronized (CACHE_LOCK) {
            if (slugCache != null && System.currentTimeMillis() < cacheExpiry) {
                if (slugCache.containsKey(targetId)) {
                    String cached = slugCache.get(targetId);
                    prof("resolveSlug cache HIT id=" + targetId + " slug=" + cached);
                    return cached;
                }
                prof("resolveSlug cache HIT but id=" + targetId + " not in map -> 404");
                // Cache is complete; absence means not found.
                return null;
            }
        }
        prof("resolveSlug cache MISS id=" + targetId + " downloading " + LEETCODE_API_ALL);

        long tDl0 = System.nanoTime();
        String json = downloadGet(LEETCODE_API_ALL);
        long tDl1 = System.nanoTime();
        prof("resolveSlug downloadGet bytes=" + json.length() + " took=" + ((tDl1 - tDl0) / 1_000_000) + "ms");

        // Manual scan avoids regex StackOverflowError on the ~6MB response (Java 8 regex recursion).
        Map<Integer, String> map = new HashMap<Integer, String>();
        int idx = 0;
        final String statKey = "\"stat\"";
        final String idKey = "\"frontend_question_id\"";
        final String slugKey = "\"question__title_slug\"";
        while (true) {
            int statPos = json.indexOf(statKey, idx);
            if (statPos == -1) {
                break;
            }
            int open = json.indexOf('{', statPos + statKey.length());
            if (open == -1) {
                break;
            }
            // stat object is flat – the next '}' closes it. We do a simple search for the next '}'
            // that is not inside a quoted string. Since the values inside stat are simple
            // (numbers, booleans, null or quoted strings without nested braces), a plain
            // indexOf('}') is sufficient and avoids regex backtracking.
            int close = json.indexOf('}', open + 1);
            if (close == -1) {
                break;
            }
            // Extract block without regex
            String block = json.substring(open, close + 1);
            Integer parsedId = parseIdFromBlock(block, idKey);
            String parsedSlug = parseStringFromBlock(block, slugKey);
            if (parsedId != null && parsedSlug != null) {
                map.put(parsedId, parsedSlug);
            }
            idx = close + 1;
        }

        long tParse0 = System.nanoTime();
        synchronized (CACHE_LOCK) {
            slugCache = map;
            cacheExpiry = System.currentTimeMillis() + CACHE_TTL_MILLIS;
        }
        long tParse1 = System.nanoTime();
        prof("resolveSlug parsed mapSize=" + map.size() + " took=" + ((tParse1 - tParse0) / 1_000_000) + "ms (total build+parse " + ((tParse1 - tDl0) / 1_000_000) + "ms)");

        return map.get(targetId);
    }

    private static Integer parseIdFromBlock(String block, String idKey) {
        int keyPos = block.indexOf(idKey);
        if (keyPos == -1) {
            return null;
        }
        int colon = block.indexOf(':', keyPos + idKey.length());
        if (colon == -1) {
            return null;
        }
        int p = colon + 1;
        while (p < block.length() && Character.isWhitespace(block.charAt(p))) {
            p++;
        }
        boolean quoted = false;
        if (p < block.length() && block.charAt(p) == '"') {
            quoted = true;
            p++;
        }
        int start = p;
        while (p < block.length() && Character.isDigit(block.charAt(p))) {
            p++;
        }
        if (start == p) {
            return null;
        }
        try {
            return Integer.parseInt(block.substring(start, p));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String parseStringFromBlock(String block, String key) {
        int keyPos = block.indexOf(key);
        if (keyPos == -1) {
            return null;
        }
        int colon = block.indexOf(':', keyPos + key.length());
        if (colon == -1) {
            return null;
        }
        int q1 = block.indexOf('"', colon + 1);
        if (q1 == -1) {
            return null;
        }
        int q2 = q1 + 1;
        while (q2 < block.length()) {
            char c = block.charAt(q2);
            if (c == '\\') {
                q2 += 2;
                continue;
            }
            if (c == '"') {
                break;
            }
            q2++;
        }
        if (q2 >= block.length()) {
            return null;
        }
        // Slug never contains escapes, but handle generically
        String raw = block.substring(q1 + 1, q2);
        // Unescape minimal (only needed if slash etc.)
        if (raw.indexOf('\\') >= 0) {
            return unescapeJson(raw);
        }
        return raw;
    }

    private LeetCodeProblem fetchDetails(String slug, String expectedFrontendId) throws IOException {
        long t0 = System.nanoTime();
        prof("fetchDetails start slug=" + slug);
        String query = "query questionContent($titleSlug: String!) { question(titleSlug: $titleSlug) { questionId questionFrontendId title titleSlug content difficulty } }";
        // Escape query for JSON string literal.
        String escapedQuery = News.escapeJson(query);
        String escapedSlug = News.escapeJson(slug);
        String body = "{\"query\":\"" + escapedQuery + "\",\"variables\":{\"titleSlug\":\"" + escapedSlug + "\"}}";

        long tPost0 = System.nanoTime();
        String resp = postGraphql(body);
        long tPost1 = System.nanoTime();
        prof("fetchDetails postGraphql slug=" + slug + " respBytes=" + resp.length() + " took=" + ((tPost1 - tPost0) / 1_000_000) + "ms");

        // LeetCode returns {"data":{"question":null}} for unknown slugs.
        if (resp.contains("\"question\":null") || resp.contains("\"question\": null")) {
            return null;
        }

        String title = extractJsonString(resp, "title");
        String frontendId = extractJsonString(resp, "questionFrontendId");
        String titleSlug = extractJsonString(resp, "titleSlug");
        String content = extractJsonString(resp, "content");
        String difficulty = extractJsonString(resp, "difficulty");

        if (title == null || content == null) {
            prof("fetchDetails slug=" + slug + " parse FAILED titleNull=" + (title == null) + " contentNull=" + (content == null) + " took=" + ((System.nanoTime() - t0) / 1_000_000) + "ms");
            return null;
        }
        if (frontendId == null || frontendId.isEmpty()) {
            frontendId = expectedFrontendId;
        }
        if (titleSlug == null || titleSlug.isEmpty()) {
            titleSlug = slug;
        }
        if (difficulty == null) {
            difficulty = "";
        }
        LeetCodeProblem p = new LeetCodeProblem(frontendId, title, titleSlug, difficulty, content);
        prof("fetchDetails slug=" + slug + " parsed title=\"" + title + "\" difficulty=" + difficulty + " contentLen=" + content.length() + " total=" + ((System.nanoTime() - t0) / 1_000_000) + "ms");
        return p;
    }

    // -----------------------------------------------------------------------
    // HTTP helpers
    // -----------------------------------------------------------------------

    private String downloadGet(String urlStr) throws IOException {
        long t0 = System.nanoTime();
        prof("downloadGet start url=" + urlStr);
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(connectTimeoutMillis);
            conn.setReadTimeout(readTimeoutMillis);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept", "application/json, text/html;q=0.9, */*;q=0.8");
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            conn.setRequestProperty("Referer", "https://leetcode.com/");
            int status = conn.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("LeetCode returned HTTP " + status + " for " + urlStr);
            }
            InputStream in = conn.getInputStream();
            if ("gzip".equalsIgnoreCase(conn.getContentEncoding())) {
                in = new GZIPInputStream(in);
            }
            try {
                String result = new String(readAll(in), "UTF-8");
                prof("downloadGet success url=" + urlStr + " bytes=" + result.length() + " took=" + ((System.nanoTime() - t0) / 1_000_000) + "ms");
                return result;
            } finally {
                in.close();
            }
        } finally {
            conn.disconnect();
        }
    }

    private String postGraphql(String jsonBody) throws IOException {
        long t0 = System.nanoTime();
        prof("postGraphql start bytes=" + jsonBody.length());
        HttpURLConnection conn = (HttpURLConnection) new URL(LEETCODE_GRAPHQL).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(connectTimeoutMillis);
            conn.setReadTimeout(readTimeoutMillis);
            conn.setDoOutput(true);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("Referer", "https://leetcode.com/");
            conn.setRequestProperty("Origin", "https://leetcode.com");
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            byte[] bytes = jsonBody.getBytes("UTF-8");
            conn.setRequestProperty("Content-Length", String.valueOf(bytes.length));
            conn.getOutputStream().write(bytes);
            conn.getOutputStream().flush();

            int status = conn.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                InputStream err = conn.getErrorStream();
                String errBody = "";
                if (err != null) {
                    if ("gzip".equalsIgnoreCase(conn.getContentEncoding())) {
                        err = new GZIPInputStream(err);
                    }
                    try {
                        errBody = new String(readAll(err), "UTF-8");
                    } catch (Exception ignored) {
                    } finally {
                        try { err.close(); } catch (Exception ignored) {}
                    }
                    if (errBody.length() > 600) {
                        errBody = errBody.substring(0, 600);
                    }
                }
                throw new IOException("LeetCode GraphQL returned HTTP " + status + (errBody.isEmpty() ? "" : " body: " + errBody));
            }
            InputStream in = conn.getInputStream();
            if ("gzip".equalsIgnoreCase(conn.getContentEncoding())) {
                in = new GZIPInputStream(in);
            }
            try {
                String result = new String(readAll(in), "UTF-8");
                prof("postGraphql success respBytes=" + result.length() + " took=" + ((System.nanoTime() - t0) / 1_000_000) + "ms");
                return result;
            } finally {
                in.close();
            }
        } finally {
            conn.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(1 << 16);
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) != -1) {
            if (buffer.size() + read > MAX_RESPONSE_BYTES) {
                throw new IOException("Response exceeded " + MAX_RESPONSE_BYTES + " bytes");
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    // -----------------------------------------------------------------------
    // JSON extraction
    // -----------------------------------------------------------------------

    static String extractJsonString(String json, String key) {
        // Manual scan to avoid Java 8 regex stack issues on escaped content.
        String quotedKey = "\"" + key + "\"";
        int keyPos = json.indexOf(quotedKey);
        if (keyPos == -1) {
            return null;
        }
        int colon = json.indexOf(':', keyPos + quotedKey.length());
        if (colon == -1) {
            return null;
        }
        int qStart = -1;
        for (int i = colon + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"') {
                qStart = i;
                break;
            } else if (!Character.isWhitespace(c)) {
                // Value is not a quoted string (e.g., null, number) -> not found for string extractor
                return null;
            }
        }
        if (qStart == -1) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        int i = qStart + 1;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '\\') {
                if (i + 1 >= json.length()) {
                    break;
                }
                char n = json.charAt(i + 1);
                switch (n) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (i + 5 < json.length()) {
                            String hex = json.substring(i + 2, i + 6);
                            try {
                                int cp = Integer.parseInt(hex, 16);
                                sb.append((char) cp);
                                i += 4;
                            } catch (NumberFormatException e) {
                                sb.append(n);
                            }
                        } else {
                            sb.append(n);
                        }
                        break;
                    default: sb.append(n); break;
                }
                i += 2;
            } else if (c == '"') {
                return sb.toString();
            } else {
                sb.append(c);
                i++;
            }
        }
        return null;
    }

    static String unescapeJson(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(i + 1);
                switch (n) {
                    case '"': out.append('"'); i++; break;
                    case '\\': out.append('\\'); i++; break;
                    case '/': out.append('/'); i++; break;
                    case 'b': out.append('\b'); i++; break;
                    case 'f': out.append('\f'); i++; break;
                    case 'n': out.append('\n'); i++; break;
                    case 'r': out.append('\r'); i++; break;
                    case 't': out.append('\t'); i++; break;
                    case 'u':
                        if (i + 5 < s.length()) {
                            String hex = s.substring(i + 2, i + 6);
                            try {
                                int cp = Integer.parseInt(hex, 16);
                                out.append((char) cp);
                                i += 5;
                            } catch (NumberFormatException e) {
                                out.append(c);
                            }
                        } else {
                            out.append(c);
                        }
                        break;
                    default:
                        out.append(n);
                        i++;
                        break;
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
