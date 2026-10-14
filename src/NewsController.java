import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URLDecoder;
import java.util.List;

/**
 * Serves {@code GET /news}: a page of unique headlines from Google Finance, as JSON.
 *
 * <p>Pagination is controlled by two optional query parameters:
 * <ul>
 *   <li>{@code pageSize} (aliases {@code size}, {@code limit}, {@code count}) — headlines per
 *       page; defaults to {@value #DEFAULT_PAGE_SIZE}, capped at {@value #MAX_PAGE_SIZE}</li>
 *   <li>{@code page} (alias {@code p}) — 1-based page number; defaults to 1</li>
 * </ul>
 *
 * <pre>
 * GET /news?page=2&amp;pageSize=10
 * {"source":"Google Finance","page":2,"pageSize":10,"count":10,"totalCount":24,
 *  "totalPages":3,"hasPrev":true,"hasNext":true,"news":[{"title":"...","link":"..."}, ...]}
 * </pre>
 *
 * <p>A page past the end is not an error: it returns {@code 200} with an empty {@code news} array.
 */
public class NewsController implements HttpHandler {

    /** Headlines per page when the caller does not ask for a specific size. */
    public static final int DEFAULT_PAGE_SIZE = 5;

    /** Upper bound on {@code pageSize}, so one request cannot ask for the whole feed at once. */
    public static final int MAX_PAGE_SIZE = 50;

    private static final String JSON_CONTENT_TYPE = "application/json; charset=utf-8";

    private static final String[] PAGE_SIZE_KEYS = {"pageSize", "size", "limit", "count"};
    private static final String[] PAGE_KEYS = {"page", "p"};

    private final NewsFetcher fetcher;

    public NewsController() {
        this(new NewsFetcher());
    }

    public NewsController(NewsFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            // CORS preflight
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                addCorsHeaders(exchange);
                exchange.getResponseHeaders().set("Allow", "GET, OPTIONS");
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_NO_CONTENT, -1);
                return;
            }

            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                addCorsHeaders(exchange);
                exchange.getResponseHeaders().set("Allow", "GET, OPTIONS");
                respond(exchange, HttpURLConnection.HTTP_BAD_METHOD,
                        error("Only GET is supported on /news"));
                return;
            }

            int pageSize;
            int page;
            try {
                String query = exchange.getRequestURI().getRawQuery();
                pageSize = parsePageSize(query);
                page = parsePage(query);
            } catch (IllegalArgumentException e) {
                addCorsHeaders(exchange);
                respond(exchange, HttpURLConnection.HTTP_BAD_REQUEST, error(e.getMessage()));
                return;
            }

            List<News> all = fetcher.fetchAllNews();
            addCorsHeaders(exchange);
            respond(exchange, HttpURLConnection.HTTP_OK, toJson(all, page, pageSize));
        } catch (IOException e) {
            // Upstream (Google Finance) is unreachable or misbehaving.
            addCorsHeaders(exchange);
            respond(exchange, HttpURLConnection.HTTP_BAD_GATEWAY,
                    error("Could not fetch news from Google Finance: " + e.getMessage()));
        } catch (RuntimeException e) {
            addCorsHeaders(exchange);
            respond(exchange, HttpURLConnection.HTTP_INTERNAL_ERROR,
                    error("Unexpected error: " + e.getMessage()));
        } finally {
            exchange.close();
        }
    }

    /**
     * Reads the requested page size, defaulting to {@value #DEFAULT_PAGE_SIZE}.
     *
     * @throws IllegalArgumentException if the value is not a number, is below 1, or exceeds
     *     {@value #MAX_PAGE_SIZE}
     */
    static int parsePageSize(String query) {
        String[] found = findParam(query, PAGE_SIZE_KEYS);
        if (found == null) {
            return DEFAULT_PAGE_SIZE;
        }
        int value = parsePositiveInt(found[0], found[1]);
        if (value > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("'" + found[0] + "' must be at most "
                    + MAX_PAGE_SIZE + ", got " + found[1]);
        }
        return value;
    }

    /**
     * Reads the requested 1-based page number, defaulting to 1.
     *
     * @throws IllegalArgumentException if the value is not a number or is below 1
     */
    static int parsePage(String query) {
        String[] found = findParam(query, PAGE_KEYS);
        if (found == null) {
            return 1;
        }
        return parsePositiveInt(found[0], found[1]);
    }

    /** Returns {@code {key, rawValue}} for the first key that is present, or null if none is. */
    private static String[] findParam(String query, String[] keys) {
        for (String key : keys) {
            String value = getQueryParam(query, key);
            if (value != null && !value.trim().isEmpty()) {
                return new String[]{key, value.trim()};
            }
        }
        return null;
    }

    private static int parsePositiveInt(String key, String value) {
        int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid '" + key + "': " + value
                    + " — expected a whole number");
        }
        if (parsed < 1) {
            throw new IllegalArgumentException("'" + key + "' must be at least 1, got " + value);
        }
        return parsed;
    }

    private static String getQueryParam(String query, String key) {
        if (query == null || query.isEmpty()) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String k = (eq >= 0) ? pair.substring(0, eq) : pair;
            String v = (eq >= 0) ? pair.substring(eq + 1) : "";
            if (decode(k).equals(key)) {
                return decode(v);
            }
        }
        return null;
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }

    private static void addCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        exchange.getResponseHeaders().set("Access-Control-Max-Age", "3600");
    }

    /**
     * Renders the requested slice of {@code all} plus the counters a client needs to page through
     * the rest. A page past the end yields an empty {@code news} array rather than an error.
     */
    static String toJson(List<News> all, int page, int pageSize) {
        int totalCount = all.size();
        int totalPages = (totalCount + pageSize - 1) / pageSize;
        int from = Math.min((page - 1) * pageSize, totalCount);
        int to = Math.min(from + pageSize, totalCount);
        List<News> pageItems = all.subList(from, to);

        StringBuilder json = new StringBuilder();
        json.append("{\"source\":\"Google Finance\"")
                .append(",\"page\":").append(page)
                .append(",\"pageSize\":").append(pageSize)
                .append(",\"count\":").append(pageItems.size())
                .append(",\"totalCount\":").append(totalCount)
                .append(",\"totalPages\":").append(totalPages)
                .append(",\"hasPrev\":").append(page > 1)
                .append(",\"hasNext\":").append(to < totalCount)
                .append(",\"news\":[");
        for (int i = 0; i < pageItems.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(pageItems.get(i).toJson());
        }
        return json.append("]}").toString();
    }

    private static String error(String message) {
        return "{\"error\":\"" + News.escapeJson(message) + "\"}";
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes("UTF-8");
        exchange.getResponseHeaders().set("Content-Type", JSON_CONTENT_TYPE);
        exchange.sendResponseHeaders(status, bytes.length);
        OutputStream out = exchange.getResponseBody();
        try {
            out.write(bytes);
        } finally {
            out.close();
        }
    }
}
