import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URLDecoder;

/**
 * Serves {@code GET /leetcode}: fetches a LeetCode problem description by its
 * numeric id.
 *
 * <p>Accepted forms:
 * <ul>
 *   <li>{@code GET /leetcode?id=1}</li>
 *   <li>{@code GET /leetcode?num=1}</li>
 *   <li>{@code GET /leetcode?number=1}</li>
 *   <li>{@code GET /leetcode/1}</li>
 * </ul>
 * Responses mirror {@link NewsController} conventions:
 * <pre>
 * 200  {"frontendId":"1","title":"Two Sum", ...}
 * 400  {"error":"..."}  missing/invalid param
 * 404  {"error":"..."}  problem not found
 * 502  {"error":"..."}  LeetCode unreachable
 * </pre>
 */
public class LeetCodeController implements HttpHandler {

    private static final String JSON_CONTENT_TYPE = "application/json; charset=utf-8";

    private final LeetCodeFetcher fetcher;

    public LeetCodeController() {
        this(new LeetCodeFetcher());
    }

    public LeetCodeController(LeetCodeFetcher fetcher) {
        this.fetcher = fetcher;
    }

    private static void prof(String msg) {
        System.out.println("[LeetCode][PROF] " + msg);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        long reqStart = System.nanoTime();
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().toString();
        String client = exchange.getRemoteAddress() != null ? exchange.getRemoteAddress().toString() : "-";
        prof("HTTP " + method + " " + path + " from " + client + " start");
        int recordedId = -1;
        String recordedStatus = null;
        String recordedSlug = null;
        String recordedTitle = null;
        boolean cacheHitBefore = false;
        try {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                addCorsHeaders(exchange);
                exchange.getResponseHeaders().set("Allow", "GET, OPTIONS");
                long dur = (System.nanoTime() - reqStart) / 1_000_000;
                prof("HTTP " + method + " " + path + " -> 204 OPTIONS in " + dur + "ms");
                recordedStatus = "204";
                recordedId = -1;
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_NO_CONTENT, -1);
                LeetCodeTelemetry.record(recordedId, null, null, recordedStatus, dur, false);
                return;
            }
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                addCorsHeaders(exchange);
                exchange.getResponseHeaders().set("Allow", "GET, OPTIONS");
                long dur = (System.nanoTime() - reqStart) / 1_000_000;
                prof("HTTP " + method + " " + path + " -> 405 method=" + method + " in " + dur + "ms");
                recordedStatus = "405";
                recordedId = -1;
                respond(exchange, HttpURLConnection.HTTP_BAD_METHOD,
                        error("Only GET is supported on /leetcode"));
                LeetCodeTelemetry.record(recordedId, null, null, recordedStatus, dur, false);
                return;
            }

            int problemId;
            try {
                problemId = parseProblemId(exchange);
            } catch (IllegalArgumentException e) {
                addCorsHeaders(exchange);
                long dur = (System.nanoTime() - reqStart) / 1_000_000;
                prof("HTTP " + method + " " + path + " -> 400 parse error " + e.getMessage() + " in " + dur + "ms");
                recordedStatus = "400";
                recordedId = -1;
                respond(exchange, HttpURLConnection.HTTP_BAD_REQUEST, error(e.getMessage()));
                LeetCodeTelemetry.record(recordedId, null, null, recordedStatus, dur, false);
                return;
            }
            recordedId = problemId;
            if (problemId == -1) {
                addCorsHeaders(exchange);
                long dur = (System.nanoTime() - reqStart) / 1_000_000;
                prof("HTTP " + method + " " + path + " -> 400 missing id in " + dur + "ms");
                recordedStatus = "400";
                respond(exchange, HttpURLConnection.HTTP_BAD_REQUEST,
                        error("Missing LeetCode problem number. Use /leetcode?id=1 or /leetcode/1"));
                LeetCodeTelemetry.record(recordedId, null, null, recordedStatus, dur, false);
                return;
            }

            cacheHitBefore = LeetCodeFetcher.isCacheWarm();
            LeetCodeProblem problem;
            try {
                problem = fetcher.fetchProblem(problemId);
            } catch (IllegalArgumentException e) {
                addCorsHeaders(exchange);
                long dur = (System.nanoTime() - reqStart) / 1_000_000;
                prof("HTTP " + method + " " + path + " -> 400 bad id=" + problemId + " err=" + e.getMessage() + " in " + dur + "ms");
                recordedStatus = "400";
                respond(exchange, HttpURLConnection.HTTP_BAD_REQUEST, error(e.getMessage()));
                LeetCodeTelemetry.record(recordedId, null, null, recordedStatus, dur, cacheHitBefore);
                return;
            }
            if (problem == null) {
                addCorsHeaders(exchange);
                long dur = (System.nanoTime() - reqStart) / 1_000_000;
                prof("HTTP " + method + " " + path + " -> 404 not found id=" + problemId + " in " + dur + "ms");
                recordedStatus = "404";
                respond(exchange, HttpURLConnection.HTTP_NOT_FOUND,
                        error("LeetCode problem #" + problemId + " not found"));
                LeetCodeTelemetry.record(recordedId, null, null, recordedStatus, dur, cacheHitBefore);
                return;
            }

            recordedSlug = problem.getTitleSlug();
            recordedTitle = problem.getTitle();
            addCorsHeaders(exchange);
            long dur = (System.nanoTime() - reqStart) / 1_000_000;
            prof("HTTP " + method + " " + path + " -> 200 ok id=" + problemId + " title=\"" + problem.getTitle() + "\" slug=" + problem.getTitleSlug() + " in " + dur + "ms");
            recordedStatus = "200";
            respond(exchange, HttpURLConnection.HTTP_OK, problem.toJson());
            LeetCodeTelemetry.record(recordedId, recordedSlug, recordedTitle, recordedStatus, dur, cacheHitBefore);
        } catch (IOException e) {
            addCorsHeaders(exchange);
            long dur = (System.nanoTime() - reqStart) / 1_000_000;
            prof("HTTP " + method + " " + path + " -> 502 io err=" + e.getMessage() + " in " + dur + "ms");
            if (recordedStatus == null) {
                recordedStatus = "502";
                // recordedId already set if parsed, else -1
            }
            respond(exchange, HttpURLConnection.HTTP_BAD_GATEWAY,
                    error("Could not fetch LeetCode problem: " + e.getMessage()));
            LeetCodeTelemetry.record(recordedId, recordedSlug, recordedTitle, recordedStatus, dur, cacheHitBefore);
        } catch (RuntimeException e) {
            addCorsHeaders(exchange);
            long dur = (System.nanoTime() - reqStart) / 1_000_000;
            prof("HTTP " + method + " " + path + " -> 500 unexpected err=" + e.getMessage() + " in " + dur + "ms");
            if (recordedStatus == null) recordedStatus = "500";
            respond(exchange, HttpURLConnection.HTTP_INTERNAL_ERROR,
                    error("Unexpected error: " + e.getMessage()));
            LeetCodeTelemetry.record(recordedId, recordedSlug, recordedTitle, recordedStatus, dur, cacheHitBefore);
        } finally {
            long total = (System.nanoTime() - reqStart) / 1_000_000;
            prof("HTTP " + method + " " + path + " completed total=" + total + "ms");
            exchange.close();
        }
    }

    /**
     * Extracts the problem number from either the path (/leetcode/1) or the
     * query string (?id=1, ?num=1, ?number=1).
     */
    private static int parseProblemId(HttpExchange exchange) {
        String path = exchange.getRequestURI().getPath();
        if (path != null && path.length() > "/leetcode".length()) {
            String rest = path.substring("/leetcode".length());
            // rest is like "/1", "/1/", "/1?..." not possible (query separate), "/"
            if (rest.startsWith("/")) {
                rest = rest.substring(1);
            }
            if (rest.endsWith("/")) {
                rest = rest.substring(0, rest.length() - 1);
            }
            int slash = rest.indexOf('/');
            if (slash >= 0) {
                rest = rest.substring(0, slash);
            }
            // Also strip any remaining query-like suffix that may have been encoded in path? Not needed.
            if (!rest.isEmpty() && rest.indexOf('?') >= 0) {
                rest = rest.substring(0, rest.indexOf('?'));
            }
            rest = rest.trim();
            if (!rest.isEmpty()) {
                // If rest is numeric, treat as id; otherwise it's an invalid id
                // rather than falling back to query param.
                try {
                    return Integer.parseInt(decode(rest).trim());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid LeetCode problem number in path: " + rest);
                }
            }
        }

        String query = exchange.getRequestURI().getRawQuery();
        String[] keys = new String[]{"id", "num", "number", "frontendId"};
        for (String k : keys) {
            String v = getQueryParam(query, k);
            if (v != null && !v.trim().isEmpty()) {
                String decoded = decode(v.trim());
                try {
                    return Integer.parseInt(decoded);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid LeetCode problem number for '" + k + "': " + v);
                }
            }
        }
        return -1;
    }

    private static String getQueryParam(String query, String key) {
        if (query == null || query.isEmpty() || key == null) {
            return null;
        }
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int eq = pair.indexOf('=');
            String k;
            String v;
            if (eq >= 0) {
                k = pair.substring(0, eq);
                v = pair.substring(eq + 1);
            } else {
                k = pair;
                v = "";
            }
            try {
                k = URLDecoder.decode(k, "UTF-8");
            } catch (UnsupportedEncodingException ignored) {
            }
            if (k.equals(key)) {
                try {
                    return URLDecoder.decode(v, "UTF-8");
                } catch (UnsupportedEncodingException e) {
                    return v;
                }
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
