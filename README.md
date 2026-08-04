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

## Note on the news source

Google Finance has no public news API, so `NewsFetcher` scrapes the home page HTML. Google's CSS
class names are obfuscated and rotate over time, so the parser keys off the stable markup shape
(`<a href="https://..."><div>Headline</div></a>`) rather than specific class names. If Google
changes that structure, `GET /news` will return an empty list and `NewsFetcher.HEADLINE_PATTERN`
is the place to adjust.
