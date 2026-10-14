import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;

/**
 * Exposes server-side telemetry for LeetCode fetches.
 * <p>GET /telemetry -> JSON aggregates + recent requests.
 */
public class TelemetryController implements HttpHandler {

    private static final String JSON_CONTENT_TYPE = "application/json; charset=utf-8";

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                addCors(exchange);
                exchange.getResponseHeaders().set("Allow", "GET, OPTIONS");
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_NO_CONTENT, -1);
                return;
            }
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                addCors(exchange);
                exchange.getResponseHeaders().set("Allow", "GET, OPTIONS");
                respond(exchange, HttpURLConnection.HTTP_BAD_METHOD,
                        "{\"error\":\"Only GET is supported on /telemetry\"}");
                return;
            }
            addCors(exchange);
            String body = LeetCodeTelemetry.toJson();
            respond(exchange, HttpURLConnection.HTTP_OK, body);
        } finally {
            exchange.close();
        }
    }

    private static void addCors(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        exchange.getResponseHeaders().set("Access-Control-Max-Age", "3600");
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
