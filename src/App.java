import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Application entry point: starts the REST server exposing GET /news.
 */
public class App {

    private static final int DEFAULT_PORT = 8080;
    private static final int WORKER_THREADS = 4;

    public static void main(String[] args) throws IOException {
        int port = resolvePort(args);

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/news", new NewsController());

        // Headline fetches block on the network, so serve requests off a small pool.
        final ExecutorService workers = Executors.newFixedThreadPool(WORKER_THREADS);
        server.setExecutor(workers);
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                server.stop(0);
                workers.shutdownNow();
            }
        });

        System.out.println("Java_OPT server listening on http://localhost:" + port);
        System.out.println("  GET /news  ->  top " + NewsController.TOP_NEWS_COUNT
                + " unique Google Finance headlines");
    }

    /**
     * Port precedence: first CLI argument, then the PORT environment variable, then the default.
     */
    private static int resolvePort(String[] args) {
        String value = (args.length > 0) ? args[0] : System.getenv("PORT");
        if (value == null || value.trim().isEmpty()) {
            return DEFAULT_PORT;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid port: " + value, e);
        }
    }
}
