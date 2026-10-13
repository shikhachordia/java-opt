import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Serves static files from a base directory (e.g. {@code public}).
 * Handles GET/HEAD, directory index, 404, content-type and CORS.
 */
public class StaticHandler implements HttpHandler {

    private final File baseDir;

    public StaticHandler(String baseDirPath) {
        this.baseDir = new File(baseDirPath);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
                exchange.getResponseHeaders().set("Allow", "GET, HEAD");
                byte[] body = "{\"error\":\"Only GET is supported\"}".getBytes("UTF-8");
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                addCors(exchange);
                exchange.sendResponseHeaders(405, body.length);
                OutputStream out = exchange.getResponseBody();
                try {
                    out.write(body);
                } finally {
                    out.close();
                }
                return;
            }

            String path = exchange.getRequestURI().getPath();
            if (path == null || path.isEmpty() || "/".equals(path)) {
                path = "/index.html";
            }
            String relative = path.startsWith("/") ? path.substring(1) : path;

            File target = new File(baseDir, relative).getCanonicalFile();
            String basePath = baseDir.getCanonicalPath();
            String targetPath = target.getCanonicalPath();

            if (!targetPath.equals(basePath) && !targetPath.startsWith(basePath + File.separator)) {
                send404(exchange);
                return;
            }
            if (target.isDirectory()) {
                target = new File(target, "index.html").getCanonicalFile();
                targetPath = target.getCanonicalPath();
                if (!targetPath.startsWith(basePath)) {
                    send404(exchange);
                    return;
                }
            }
            if (!target.isFile() || !target.exists()) {
                send404(exchange);
                return;
            }

            String contentType = contentType(target.getName());
            exchange.getResponseHeaders().set("Content-Type", contentType);
            addCors(exchange);

            long length = target.length();
            boolean isHead = "HEAD".equalsIgnoreCase(method);
            exchange.sendResponseHeaders(200, isHead ? -1 : length);
            if (isHead) {
                exchange.getResponseBody().close();
                return;
            }

            OutputStream out = exchange.getResponseBody();
            FileInputStream fis = new FileInputStream(target);
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = fis.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
            } finally {
                fis.close();
                out.close();
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        } finally {
            exchange.close();
        }
    }

    private static void send404(HttpExchange exchange) throws IOException {
        byte[] body = "{\"error\":\"Not found\"}".getBytes("UTF-8");
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        addCors(exchange);
        exchange.sendResponseHeaders(404, body.length);
        OutputStream out = exchange.getResponseBody();
        try {
            out.write(body);
        } finally {
            out.close();
        }
    }

    private static void addCors(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
    }

    private static String contentType(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            return "text/html; charset=utf-8";
        }
        if (lower.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }
        if (lower.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (lower.endsWith(".json")) {
            return "application/json; charset=utf-8";
        }
        if (lower.endsWith(".txt")) {
            return "text/plain; charset=utf-8";
        }
        if (lower.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".ico")) {
            return "image/x-icon";
        }
        return "application/octet-stream";
    }
}
