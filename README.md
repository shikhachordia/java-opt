# Java_OPT

A Java project exposing a small REST API over the JDK's built-in HTTP server — no external
dependencies.

## Prerequisites

- JDK 8 or later (`java -version`)

## Build & Run

```bash
mkdir -p out
javac -d out src/*.java
java -cp out App
```

The server listens on port `8080` by default. Override it with the first CLI argument or the
`PORT` environment variable:

```bash
java -cp out App 9090
```

## API

### `GET /news`

Returns the top **5 unique** news headlines from Google Finance.

```bash
curl http://localhost:8080/news
```

```json
{
  "source": "Google Finance",
  "count": 5,
  "news": [
    {
      "title": "Wall Street rallies, Dow closes at record on Iran talks optimism",
      "link": "https://www.marketscreener.com/news/wall-street-rallies-dow-closes-at-record"
    }
  ]
}
```

Uniqueness is enforced on two keys, so a story that Google lists more than once — or that is
syndicated under different tracking URLs — appears only once:

- the headline, compared case- and whitespace-insensitively
- the article URL, compared with the query string and fragment stripped

Headlines are cached in memory for 60 seconds, so a burst of concurrent requests is served from
one snapshot rather than each triggering its own scrape of Google Finance.

Responses:

| Status | Meaning |
| ------ | ------- |
| `200`  | Headlines returned |
| `405`  | Method other than `GET`; body is `{"error":"..."}` |
| `502`  | Google Finance unreachable or returned a non-200 status |
| `500`  | Unexpected server error |

## Project Structure

```
Java_OPT/
├── src/
│   ├── App.java             # Entry point; starts the HTTP server
│   ├── NewsController.java  # GET /news handler, JSON serialization
│   ├── NewsFetcher.java     # Downloads and parses Google Finance headlines
│   └── News.java            # Headline model (title + link)
├── .gitignore
└── README.md
```

## Concurrency & production tuning

The server is built to absorb high-concurrency traffic safely:

- **Bounded worker pool with backpressure.** Requests are served by a bounded `ThreadPoolExecutor`.
  When the queue fills during a spike, overflow runs on the dispatch thread (caller-runs), which
  slows intake instead of dropping requests or growing an unbounded queue toward `OutOfMemoryError`.
  Worker threads are named (`news-worker-N`) with an uncaught-exception handler for diagnosability.
- **Single-flight, cached upstream fetch.** Parsed headlines live behind a `volatile` snapshot with
  a short TTL, so the common request is a lock-free memory read. When the snapshot expires, exactly
  one thread refreshes it from Google Finance while all others keep serving the last-known
  snapshot — no thundering herd on the upstream. A failed refresh serves the last good snapshot
  rather than failing the request.
- **Graceful shutdown.** On `SIGTERM`/`SIGINT`, the server stops accepting connections, drains
  in-flight exchanges for a grace period, then shuts the pool down.

Pool sizing is env-overridable (defaults scale to CPU count):

| Variable | Default | Meaning |
| -------- | ------- | ------- |
| `WORKER_CORE_THREADS`  | `max(8, cores × 2)`          | Core worker threads |
| `WORKER_MAX_THREADS`   | `max(core, cores × 8)`      | Max worker threads under load |
| `WORKER_QUEUE_CAPACITY`| `1024`                      | Bounded request queue depth |

## Note on the news source

Google Finance has no public news API, so `NewsFetcher` scrapes the home page HTML. Google's CSS
class names are obfuscated and rotate over time, so the parser keys off the stable markup shape
(`<a href="https://..."><div>Headline</div></a>`) rather than specific class names. If Google
changes that structure, `GET /news` will return an empty list and `NewsFetcher.HEADLINE_PATTERN`
is the place to adjust.
