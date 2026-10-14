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

Returns a **page of unique** news headlines from Google Finance.

| Parameter | Aliases | Default | Meaning |
| --------- | ------- | ------- | ------- |
| `pageSize` | `size`, `limit`, `count` | `5` | Headlines per page, `1`–`50` |
| `page` | `p` | `1` | 1-based page number |

```bash
curl http://localhost:8080/news                      # first 5
curl "http://localhost:8080/news?pageSize=10"        # first 10
curl "http://localhost:8080/news?page=2&pageSize=10" # next 10
```

```json
{
  "source": "Google Finance",
  "page": 2,
  "pageSize": 10,
  "count": 10,
  "totalCount": 24,
  "totalPages": 3,
  "hasPrev": true,
  "hasNext": true,
  "news": [
    {
      "title": "Wall Street rallies, Dow closes at record on Iran talks optimism",
      "link": "https://www.marketscreener.com/news/wall-street-rallies-dow-closes-at-record"
    }
  ]
}
```

`count` is how many headlines this page carries; `totalCount` is how many the source page yielded
in total. A page past the last one is not an error — it returns `200` with an empty `news` array.

How many headlines exist depends entirely on the Google Finance home page for that fetch; if it
lists fewer than `pageSize` items, page 1 is the only page.

Every call re-scrapes the Google Finance page, so the frontend caches pages it has already shown
(see below) rather than re-requesting them each time you step back and forth.

## Frontend page cache

`public/index.html` keeps fetched pages in memory, keyed by `page` + `pageSize`, so Previous/Next
over pages you have already seen renders instantly and makes no request.

- Entries form a single **snapshot**: they share one expiry and are dropped together, so two pages
  on screen never come from two different versions of the feed.
- The snapshot lives for **2 minutes** (`CACHE_TTL_MS` in `public/index.html`).
- **Refresh** always bypasses the cache and starts a new snapshot.
- If a fetch comes back with a different `totalCount`, the feed changed underneath, so the old
  snapshot is discarded instead of being mixed with the new page.
- The "fetched Ns ago" note next to the pager shows the age of what is on screen.

Uniqueness is enforced on two keys, so a story that Google lists more than once — or that is
syndicated under different tracking URLs — appears only once:

- the headline, compared case- and whitespace-insensitively
- the article URL, compared with the query string and fragment stripped

Responses:

| Status | Meaning |
| ------ | ------- |
| `200`  | Headlines returned |
| `400`  | `page`/`pageSize` not a whole number, below 1, or `pageSize` above 50 |
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

## Note on the news source

Google Finance has no public news API, so `NewsFetcher` scrapes the home page HTML. Google's CSS
class names are obfuscated and rotate over time, so the parser keys off the stable markup shape
(`<a href="https://..."><div>Headline</div></a>`) rather than specific class names. If Google
changes that structure, `GET /news` will return an empty list and `NewsFetcher.HEADLINE_PATTERN`
is the place to adjust.
