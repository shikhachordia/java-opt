import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Server-side telemetry for LeetCode fetches. In-memory aggregates + file log.
 *
 * <p>All methods are thread-safe. Data is exposed via {@code GET /telemetry}
 * and persisted as JSON lines to {@code /tmp/leetcode_telemetry.log}.
 */
public final class LeetCodeTelemetry {

    private static final String FILE_PATH = "/tmp/leetcode_telemetry.log";
    private static final int MAX_RECENT = 100;

    private static final Object LOCK = new Object();
    private static long totalRequests = 0;
    private static long success = 0;
    private static long notFound = 0;
    private static long badRequest = 0;
    private static long error = 0;
    private static long totalLatencyMs = 0;
    private static long minLatencyMs = Long.MAX_VALUE;
    private static long maxLatencyMs = 0;
    private static long cacheHits = 0;
    private static long cacheMisses = 0;
    private static final Map<String, Long> perStatus = new HashMap<String, Long>();
    private static final Map<Integer, Long> perId = new HashMap<Integer, Long>();
    private static final List<String> recent = new ArrayList<String>();
    private static final long startTimeMs = System.currentTimeMillis();

    private LeetCodeTelemetry() {}

    /**
     * Records one fetch. Called from {@link LeetCodeController} after each request.
     *
     * @param frontendId problem number, -1 if missing
     * @param slug resolved slug or null
     * @param title problem title or null
     * @param httpStatus HTTP status as string, e.g. "200","404","400","502"
     * @param latencyMs total handler latency in ms
     * @param cacheHit whether slug cache was hit (only meaningful for 200/404)
     */
    public static void record(int frontendId, String slug, String title, String httpStatus, long latencyMs, boolean cacheHit) {
        String entryJson;
        synchronized (LOCK) {
            totalRequests++;
            totalLatencyMs += latencyMs;
            if (latencyMs < minLatencyMs) minLatencyMs = latencyMs;
            if (latencyMs > maxLatencyMs) maxLatencyMs = latencyMs;
            if (cacheHit) cacheHits++; else if (frontendId > 0) cacheMisses++;

            Long c = perStatus.get(httpStatus);
            perStatus.put(httpStatus, c == null ? 1 : c + 1);
            if ("200".equals(httpStatus)) success++;
            else if ("404".equals(httpStatus)) notFound++;
            else if ("400".equals(httpStatus)) badRequest++;
            else error++;

            if (frontendId > 0) {
                Long pc = perId.get(frontendId);
                perId.put(frontendId, pc == null ? 1 : pc + 1);
            }

            long ts = System.currentTimeMillis();
            String safeSlug = slug != null ? slug : "";
            String safeTitle = title != null ? title : "";
            entryJson = "{\"ts\":" + ts + ",\"id\":" + frontendId + ",\"slug\":\"" + News.escapeJson(safeSlug) + "\",\"title\":\"" + News.escapeJson(safeTitle) + "\",\"status\":\"" + httpStatus + "\",\"latencyMs\":" + latencyMs + ",\"cacheHit\":" + cacheHit + "}";
            recent.add(entryJson);
            if (recent.size() > MAX_RECENT) {
                recent.remove(0);
            }
        }
        // stdout for /tmp/java_opt.log visibility
        System.out.println("[LeetCode][TELEMETRY] id=" + frontendId + " slug=" + (slug != null ? slug : "-") + " status=" + httpStatus + " latency=" + latencyMs + "ms cacheHit=" + cacheHit + " total=" + getTotalRequestsSnapshot());
        // append to dedicated file
        try {
            FileWriter fw = new FileWriter(FILE_PATH, true);
            try {
                fw.write(entryJson + "\n");
            } finally {
                fw.close();
            }
        } catch (IOException e) {
            System.err.println("[LeetCode][TELEMETRY] file log failed: " + e.getMessage());
        }
    }

    private static long getTotalRequestsSnapshot() {
        synchronized (LOCK) {
            return totalRequests;
        }
    }

    public static String toJson() {
        synchronized (LOCK) {
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            sb.append("\"service\":\"leetcode\",");
            sb.append("\"uptimeMs\":").append(System.currentTimeMillis() - startTimeMs).append(",");
            sb.append("\"totalRequests\":").append(totalRequests).append(",");
            sb.append("\"success\":").append(success).append(",");
            sb.append("\"notFound\":").append(notFound).append(",");
            sb.append("\"badRequest\":").append(badRequest).append(",");
            sb.append("\"error\":").append(error).append(",");
            long avg = totalRequests > 0 ? totalLatencyMs / totalRequests : 0;
            sb.append("\"avgLatencyMs\":").append(avg).append(",");
            sb.append("\"minLatencyMs\":").append(totalRequests > 0 ? minLatencyMs : 0).append(",");
            sb.append("\"maxLatencyMs\":").append(maxLatencyMs).append(",");
            sb.append("\"cacheHits\":").append(cacheHits).append(",");
            sb.append("\"cacheMisses\":").append(cacheMisses).append(",");
            sb.append("\"cacheSize\":").append(LeetCodeFetcher.getCacheSize()).append(",");
            sb.append("\"file\":\"").append(FILE_PATH).append("\",");
            sb.append("\"perStatus\":{");
            boolean first = true;
            for (Map.Entry<String, Long> e : perStatus.entrySet()) {
                if (!first) sb.append(",");
                sb.append("\"").append(e.getKey()).append("\":").append(e.getValue());
                first = false;
            }
            sb.append("},");
            sb.append("\"perId\":{");
            first = true;
            for (Map.Entry<Integer, Long> e : perId.entrySet()) {
                if (!first) sb.append(",");
                sb.append("\"").append(e.getKey()).append("\":").append(e.getValue());
                first = false;
            }
            sb.append("},");
            sb.append("\"recent\":[");
            for (int i = 0; i < recent.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(recent.get(i));
            }
            sb.append("]");
            sb.append("}");
            return sb.toString();
        }
    }

    public static String filePath() {
        return FILE_PATH;
    }
}
