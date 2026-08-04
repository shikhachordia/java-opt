import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.List;

/**
 * Serves {@code GET /news}: the top unique headlines from Google Finance, as JSON.
 *
 * <pre>
 * {"source":"Google Finance","count":5,"news":[{"title":"...","link":"..."}, ...]}
 * </pre>
 */
public class NewsController implements HttpHandler {

    /** Number of headlines the endpoint returns. */
    public static final int TOP_NEWS_COUNT = 5;

    private static final String JSON_CONTENT_TYPE = "application/json; charset=utf-8";

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
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "GET");
                respond(exchange, HttpURLConnection.HTTP_BAD_METHOD,
                        error("Only GET is supported on /news"));
                return;
            }

            List<News> news = fetcher.fetchTopNews(TOP_NEWS_COUNT);
            respond(exchange, HttpURLConnection.HTTP_OK, toJson(news));
        } catch (IOException e) {
            // Upstream (Google Finance) is unreachable or misbehaving.
            respond(exchange, HttpURLConnection.HTTP_BAD_GATEWAY,
                    error("Could not fetch news from Google Finance: " + e.getMessage()));
        } catch (RuntimeException e) {
            respond(exchange, HttpURLConnection.HTTP_INTERNAL_ERROR,
                    error("Unexpected error: " + e.getMessage()));
        } finally {
            exchange.close();
        }
    }

    private static String toJson(List<News> news) {
        StringBuilder json = new StringBuilder();
        json.append("{\"source\":\"Google Finance\",\"count\":").append(news.size())
                .append(",\"news\":[");
        for (int i = 0; i < news.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(news.get(i).toJson());
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
