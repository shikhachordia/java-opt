/**
 * A single news headline: its title and the link to the full article.
 */
public final class News {

    private final String title;
    private final String link;

    public News(String title, String link) {
        if (title == null || title.trim().isEmpty()) {
            throw new IllegalArgumentException("title must not be empty");
        }
        if (link == null || link.trim().isEmpty()) {
            throw new IllegalArgumentException("link must not be empty");
        }
        this.title = title.trim();
        this.link = link.trim();
    }

    public String getTitle() {
        return title;
    }

    public String getLink() {
        return link;
    }

    /**
     * Renders this item as a JSON object, e.g. {"title":"...","link":"..."}.
     */
    public String toJson() {
        return "{\"title\":\"" + escapeJson(title) + "\",\"link\":\"" + escapeJson(link) + "\"}";
    }

    /**
     * Escapes a string so it can be embedded in a JSON string literal.
     */
    static String escapeJson(String value) {
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\b':
                    out.append("\\b");
                    break;
                case '\f':
                    out.append("\\f");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof News)) {
            return false;
        }
        News that = (News) other;
        return title.equals(that.title) && link.equals(that.link);
    }

    @Override
    public int hashCode() {
        return 31 * title.hashCode() + link.hashCode();
    }

    @Override
    public String toString() {
        return "News{title='" + title + "', link='" + link + "'}";
    }
}
