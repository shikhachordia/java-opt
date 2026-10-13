import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Application entry point: starts the REST server exposing GET /news
 * and the static frontend UI.
 */
public class App {

    private static final int DEFAULT_PORT = 9080;

    private static final int CORES = Runtime.getRuntime().availableProcessors();
    private static final int CORE_THREADS = envInt("WORKER_CORE_THREADS", Math.max(8, CORES * 2));
    private static final int MAX_THREADS = envInt("WORKER_MAX_THREADS", Math.max(CORE_THREADS, CORES * 8));
    private static final int QUEUE_CAPACITY = envInt("WORKER_QUEUE_CAPACITY", 1024);
    private static final long KEEP_ALIVE_SECONDS = 60L;
    private static final int SHUTDOWN_GRACE_SECONDS = 10;

    public static void main(String[] args) throws IOException {
        int port = resolvePort(args);

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/news", new NewsController());
        server.createContext("/", new StaticHandler("public"));

        final ThreadPoolExecutor workers = newWorkerPool();
        server.setExecutor(workers);
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread("shutdown-hook") {
            @Override
            public void run() {
                shutdown(server, workers);
            }
        });

        System.out.println("Java_OPT server listening on http://localhost:" + port);
        System.out.println("  workers: core=" + CORE_THREADS + " max=" + MAX_THREADS + " queue=" + QUEUE_CAPACITY);
        System.out.println("  GET /news  ->  top " + NewsController.TOP_NEWS_COUNT
                + " unique Google Finance headlines");
        System.out.println("  GET /      ->  frontend UI (public/index.html) with Refresh button");
    }

    private static ThreadPoolExecutor newWorkerPool() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                CORE_THREADS,
                MAX_THREADS,
                KEEP_ALIVE_SECONDS, TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(QUEUE_CAPACITY),
                new NamedThreadFactory("news-worker"),
                new ThreadPoolExecutor.CallerRunsPolicy());
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    private static void shutdown(HttpServer server, ThreadPoolExecutor workers) {
        server.stop(SHUTDOWN_GRACE_SECONDS);
        workers.shutdown();
        try {
            if (!workers.awaitTermination(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS)) {
                workers.shutdownNow();
            }
        } catch (InterruptedException e) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
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

    private static int envInt(String name, int defaultValue) {
        String v = System.getenv(name);
        if (v == null || v.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger counter = new AtomicInteger(1);

        NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, prefix + "-" + counter.getAndIncrement());
            t.setDaemon(false);
            return t;
        }
    }
}
