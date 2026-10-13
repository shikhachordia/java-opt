import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Application entry point: starts the REST server exposing GET /news.
 *
 * <p>The server is backed by a bounded, named worker pool. Under a traffic spike the queue caps
 * memory growth and the {@link ThreadPoolExecutor.CallerRunsPolicy} applies backpressure — the
 * dispatch thread runs the overflow task itself, slowing intake — instead of dropping requests or
 * growing an unbounded queue toward OutOfMemoryError.
 */
public class App {

    private static final int DEFAULT_PORT = 8080;

    private static final int CORES = Runtime.getRuntime().availableProcessors();

    /**
     * Worker-pool sizing. Requests are cheap once {@link NewsFetcher}'s cache is warm (a memory
     * read), and only the occasional single-flight refresh blocks on the network — so a modest,
     * bounded pool absorbs high concurrency. All knobs are env-overridable for production tuning.
     */
    private static final int CORE_THREADS = envInt("WORKER_CORE_THREADS", Math.max(8, CORES * 2));
    private static final int MAX_THREADS =
            envInt("WORKER_MAX_THREADS", Math.max(CORE_THREADS, CORES * 8));
    private static final int QUEUE_CAPACITY = envInt("WORKER_QUEUE_CAPACITY", 1024);
    private static final long KEEP_ALIVE_SECONDS = 60L;

    /** Seconds to let in-flight exchanges drain before forcing the pool down. */
    private static final int SHUTDOWN_GRACE_SECONDS = 10;

    public static void main(String[] args) throws IOException {
        int port = resolvePort(args);

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/news", new NewsController());

        final ThreadPoolExecutor workers = newWorkerPool();
        server.setExecutor(workers);
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                shutdown(server, workers);
            }
        }, "shutdown-hook"));

        System.out.println("Java_OPT server listening on http://localhost:" + port);
        System.out.println("  workers: core=" + CORE_THREADS + " max=" + MAX_THREADS
                + " queue=" + QUEUE_CAPACITY);
        System.out.println("  GET /news  ->  top " + NewsController.TOP_NEWS_COUNT
                + " unique Google Finance headlines");
    }

    /**
     * A bounded thread pool with named worker threads and caller-runs backpressure.
     */
    private static ThreadPoolExecutor newWorkerPool() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                CORE_THREADS,
                MAX_THREADS,
                KEEP_ALIVE_SECONDS,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(QUEUE_CAPACITY),
                new NamedThreadFactory("news-worker"),
                new ThreadPoolExecutor.CallerRunsPolicy());
        // Let idle core threads retire so a post-spike pool doesn't hold threads indefinitely.
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    /**
     * Stops accepting new exchanges, drains those in flight for a grace period, then forces the
     * worker pool down. Safe to run from the JVM shutdown hook.
     */
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

    /** Reads a positive integer from an environment variable, falling back to {@code defaultValue}. */
    private static int envInt(String name, int defaultValue) {
        String value = System.getenv(name);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Produces sequentially-numbered, named worker threads with an uncaught-exception handler so a
     * thread that dies on an unexpected error is diagnosable rather than silent. Thread-safe: the
     * counter is atomic, so a growing pool never hands out a duplicate name.
     */
    private static final class NamedThreadFactory implements ThreadFactory {

        private final String prefix;
        private final AtomicInteger counter = new AtomicInteger(1);

        NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, prefix + "-" + counter.getAndIncrement());
            thread.setDaemon(false);
            thread.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread t, Throwable ex) {
                    System.err.println("Uncaught exception in worker " + t.getName() + ": " + ex);
                    ex.printStackTrace();
                }
            });
            return thread;
        }
    }
}
