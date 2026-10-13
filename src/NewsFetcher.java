import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Fetches top finance headlines from the Google Finance home page.
 *
 * <p>Google Finance has no public news API, so the page HTML is downloaded and the headline
 * anchors are extracted. Google's CSS class names are obfuscated and rotate over time, so the
 * parser keys off the stable markup shape instead:
 * {@code <a href="https://..."><div class="...">Headline</div></a>}.
 *
 * <h2>Concurrency</h2>
 * <p>A single {@code NewsFetcher} instance is safe to share across all request threads. Parsed
 * headlines are cached for a short TTL behind a {@code volatile} snapshot, so the common case is a
 * lock-free memory read. When the snapshot expires, a <em>single-flight</em> refresh runs: exactly
 * one thread downloads Google Finance while every other thread keeps serving the last-known
 * headlines instead of piling a duplicate request onto the upstream (thundering-herd protection).
 * If a refresh fails but an earlier snapshot exists, the stale snapshot is served rather than
 * failing the request.
 */
public class NewsFetcher {

    /** Google Finance home page, which carries the market-wide top news feed. */
    public static final String GOOGLE_FINANCE_URL = "https://www.google.com/finance/";

    private static final String USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/120.0.0.0 Safari/537.36";

    /** An external article link wrapping a single line of text: the headline. */
    private static final Pattern HEADLINE_PATTERN = Pattern.compile(
            "<a\\s+[^>]*href=\"(https?://[^\"]+)\"[^>]*>\\s*<div[^>]*>([^<]+)</div>\\s*</a>",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern HTML_ENTITY = Pattern.compile("&(#[xX]?[0-9a-fA-F]+|[a-zA-Z]+);");

    /** Guards against picking up navigation chrome that happens to match the markup shape. */
    private static final int MIN_TITLE_LENGTH = 10;

    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;

    /** How long a parsed snapshot is served before a refresh is triggered. */
    private static final long DEFAULT_CACHE_TTL_MILLIS = 60_000L;

    /**
     * How many headlines to parse and cache per refresh. Kept well above any single request's
     * {@code limit} so the same cached snapshot can satisfy varying limits without re-downloading.
     */
    private static final int CACHE_HEADLINE_CAPACITY = 50;

    private final String sourceUrl;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;
    private final long cacheTtlMillis;

    /**
     * Last successfully parsed snapshot, or {@code null} before the first successful fetch.
     * Published via {@code volatile} so reader threads see a fully-constructed, immutable snapshot.
     */
    private volatile CachedData cache;

    /** Serializes refreshes so only one thread downloads the upstream at a time (single-flight). */
    private final ReentrantLock refreshLock = new ReentrantLock();

    public NewsFetcher() {
        this(GOOGLE_FINANCE_URL, 10_000, 15_000, DEFAULT_CACHE_TTL_MILLIS);
    }

    public NewsFetcher(String sourceUrl, int connectTimeoutMillis, int readTimeoutMillis) {
        this(sourceUrl, connectTimeoutMillis, readTimeoutMillis, DEFAULT_CACHE_TTL_MILLIS);
    }

    public NewsFetcher(String sourceUrl, int connectTimeoutMillis, int readTimeoutMillis,
            long cacheTtlMillis) {
        this.sourceUrl = sourceUrl;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
        this.cacheTtlMillis = cacheTtlMillis;
    }

    /**
     * Returns up to {@code limit} unique headlines, in the order Google ranks them. Duplicates —
     * the same story syndicated under the same headline, or the same article reachable through
     * different tracking URLs — are collapsed into one entry.
     *
     * <p>Results are served from an in-memory snapshot when one is fresh; otherwise a single-flight
     * refresh downloads Google Finance (see the class-level concurrency notes). The returned list
     * is an immutable view safe to read from any thread.
     *
     * @throws IOException if Google Finance cannot be reached (and no snapshot exists to fall back
     *     on) or returns a non-200 status
     */
    public List<News> fetchTopNews(int limit) throws IOException {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive, got " + limit);
        }
        List<News> headlines = cachedHeadlines();
        int size = Math.min(limit, headlines.size());
        // subList of an unmodifiable list is itself unmodifiable, so callers cannot mutate the
        // shared snapshot.
        return headlines.subList(0, size);
    }

    /**
     * Returns the cached headline snapshot, refreshing it if stale. At most one thread downloads
     * the upstream at a time; concurrent callers serve the last-known snapshot rather than block or
     * launch duplicate downloads.
     */
    private List<News> cachedHeadlines() throws IOException {
        CachedData current = cache;
        if (current != null && current.isFresh(System.currentTimeMillis())) {
            return current.headlines;
        }

        // Snapshot is stale or absent. Try to become the single refresher.
        if (refreshLock.tryLock()) {
            try {
                // Another thread may have refreshed between our check and acquiring the lock.
                current = cache;
                if (current != null && current.isFresh(System.currentTimeMillis())) {
                    return current.headlines;
                }
                return refreshLocked(current);
            } finally {
                refreshLock.unlock();
            }
        }

        // A refresh is already in flight elsewhere.
        current = cache;
        if (current != null) {
            // Serve the previous snapshot immediately; don't block or duplicate the download.
            return current.headlines;
        }

        // Cold start: no snapshot exists yet, so we must wait for the in-flight refresh.
        refreshLock.lock();
        try {
            current = cache;
            if (current != null) {
                return current.headlines;
            }
            // The refresh that held the lock failed before publishing a snapshot; try ourselves.
            return refreshLocked(null);
        } finally {
            refreshLock.unlock();
        }
    }

    /**
     * Downloads and parses a fresh snapshot, publishes it, and returns it. Must be called while
     * holding {@link #refreshLock}. On failure, falls back to {@code previous} if available so a
     * transient upstream outage doesn't fail otherwise-serviceable requests.
     */
    private List<News> refreshLocked(CachedData previous) throws IOException {
        try {
            List<News> fresh = Collections.unmodifiableList(
                    parseNews(download(), CACHE_HEADLINE_CAPACITY));
            cache = new CachedData(fresh, System.currentTimeMillis() + cacheTtlMillis);
            return fresh;
        } catch (IOException e) {
            if (previous != null) {
                return previous.headlines; // serve stale rather than fail
            }
            throw e;
        }
    }

    /** Immutable snapshot of parsed headlines plus the instant it should be refreshed. */
    private static final class CachedData {
        private final List<News> headlines;
        private final long expiresAtMillis;

        CachedData(List<News> headlines, long expiresAtMillis) {
            this.headlines = headlines;
            this.expiresAtMillis = expiresAtMillis;
        }

        boolean isFresh(long nowMillis) {
            return nowMillis < expiresAtMillis;
        }
    }

    /**
     * Extracts unique headlines from an already-downloaded Google Finance page.
     */
    List<News> parseNews(String html, int limit) {
        List<News> news = new ArrayList<News>();
        Set<String> seenTitles = new HashSet<String>();
        Set<String> seenLinks = new HashSet<String>();

        Matcher matcher = HEADLINE_PATTERN.matcher(html);
        while (matcher.find() && news.size() < limit) {
            String link = decodeHtml(matcher.group(1));
            String title = decodeHtml(matcher.group(2)).trim();

            if (title.length() < MIN_TITLE_LENGTH || isGoogleOwnedLink(link)) {
                continue;
            }
            if (!seenTitles.add(normalizeTitle(title)) || !seenLinks.add(normalizeLink(link))) {
                continue;
            }
            news.add(new News(title, link));
        }
        return news;
    }

    /**
     * Fetches the source page as UTF-8 text.
     */
    private String download() throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(sourceUrl).openConnection();
        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(connectTimeoutMillis);
            connection.setReadTimeout(readTimeoutMillis);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml");
            connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9");

            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException(
                        "Google Finance returned HTTP " + status + " for " + sourceUrl);
            }

            InputStream body = connection.getInputStream();
            if ("gzip".equalsIgnoreCase(connection.getContentEncoding())) {
                body = new GZIPInputStream(body);
            }
            try {
                return new String(readAll(body), "UTF-8");
            } finally {
                body.close();
            }
        } finally {
            connection.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(1 << 16);
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) != -1) {
            if (buffer.size() + read > MAX_RESPONSE_BYTES) {
                throw new IOException(
                        "Response exceeded " + MAX_RESPONSE_BYTES + " bytes");
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    /** Google's own links are site navigation (quotes, portfolios), not news articles. */
    private static boolean isGoogleOwnedLink(String link) {
        String lower = link.toLowerCase();
        return lower.startsWith("https://www.google.com/")
                || lower.startsWith("http://www.google.com/")
                || lower.startsWith("https://accounts.google.com/")
                || lower.startsWith("https://policies.google.com/")
                || lower.startsWith("https://support.google.com/");
    }

    /** Collapses case and whitespace so re-published headlines compare equal. */
    private static String normalizeTitle(String title) {
        return title.toLowerCase().replaceAll("\\s+", " ").trim();
    }

    /** Drops tracking query strings and fragments so one article yields one key. */
    private static String normalizeLink(String link) {
        String normalized = link;
        int cut = normalized.indexOf('#');
        if (cut >= 0) {
            normalized = normalized.substring(0, cut);
        }
        cut = normalized.indexOf('?');
        if (cut >= 0) {
            normalized = normalized.substring(0, cut);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.toLowerCase();
    }

    /**
     * Resolves the HTML entities Google emits in headline text (&amp;#39;, &amp;amp;, ...).
     */
    static String decodeHtml(String text) {
        Matcher matcher = HTML_ENTITY.matcher(text);
        StringBuffer out = new StringBuffer(text.length());
        while (matcher.find()) {
            String replacement = resolveEntity(matcher.group(1));
            matcher.appendReplacement(out, Matcher.quoteReplacement(
                    replacement != null ? replacement : matcher.group()));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** Returns the text for an entity body ("amp", "#39", "#x27"), or null if unrecognized. */
    private static String resolveEntity(String body) {
        if (body.charAt(0) == '#') {
            try {
                int codePoint = (body.charAt(1) == 'x' || body.charAt(1) == 'X')
                        ? Integer.parseInt(body.substring(2), 16)
                        : Integer.parseInt(body.substring(1));
                return new String(Character.toChars(codePoint));
            } catch (RuntimeException e) {
                return null;
            }
        }
        if ("amp".equals(body)) {
            return "&";
        } else if ("lt".equals(body)) {
            return "<";
        } else if ("gt".equals(body)) {
            return ">";
        } else if ("quot".equals(body)) {
            return "\"";
        } else if ("apos".equals(body)) {
            return "'";
        } else if ("nbsp".equals(body)) {
            return " ";
        } else if ("middot".equals(body)) {
            return "·";
        } else if ("mdash".equals(body)) {
            return "—";
        } else if ("ndash".equals(body)) {
            return "–";
        } else if ("hellip".equals(body)) {
            return "…";
        } else if ("rsquo".equals(body)) {
            return "’";
        } else if ("lsquo".equals(body)) {
            return "‘";
        } else if ("ldquo".equals(body)) {
            return "“";
        } else if ("rdquo".equals(body)) {
            return "”";
        }
        return null;
    }
}
