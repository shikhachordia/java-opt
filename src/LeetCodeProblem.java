/**
 * A single LeetCode problem: its id, title, difficulty and HTML description.
 */
public final class LeetCodeProblem {

    private final String frontendId;
    private final String title;
    private final String titleSlug;
    private final String difficulty;
    private final String content;

    public LeetCodeProblem(String frontendId, String title, String titleSlug,
                           String difficulty, String content) {
        if (frontendId == null || frontendId.trim().isEmpty()) {
            throw new IllegalArgumentException("frontendId must not be empty");
        }
        if (title == null || title.trim().isEmpty()) {
            throw new IllegalArgumentException("title must not be empty");
        }
        if (titleSlug == null || titleSlug.trim().isEmpty()) {
            throw new IllegalArgumentException("titleSlug must not be empty");
        }
        if (content == null) {
            throw new IllegalArgumentException("content must not be null");
        }
        this.frontendId = frontendId.trim();
        this.title = title.trim();
        this.titleSlug = titleSlug.trim();
        this.difficulty = difficulty != null ? difficulty.trim() : "";
        this.content = content;
    }

    public String getFrontendId() {
        return frontendId;
    }

    public String getTitle() {
        return title;
    }

    public String getTitleSlug() {
        return titleSlug;
    }

    public String getDifficulty() {
        return difficulty;
    }

    public String getContent() {
        return content;
    }

    public String getUrl() {
        return "https://leetcode.com/problems/" + titleSlug + "/";
    }

    /**
     * Renders this problem as JSON, e.g.
     * {"frontendId":"1","title":"Two Sum","titleSlug":"two-sum","difficulty":"Easy","content":"<p>...</p>","url":"https://..."}
     */
    public String toJson() {
        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"frontendId\":\"").append(News.escapeJson(frontendId)).append("\",");
        json.append("\"title\":\"").append(News.escapeJson(title)).append("\",");
        json.append("\"titleSlug\":\"").append(News.escapeJson(titleSlug)).append("\",");
        json.append("\"difficulty\":\"").append(News.escapeJson(difficulty)).append("\",");
        json.append("\"content\":\"").append(News.escapeJson(content)).append("\",");
        json.append("\"url\":\"").append(News.escapeJson(getUrl())).append("\"");
        json.append("}");
        return json.toString();
    }

    @Override
    public String toString() {
        return "LeetCodeProblem{frontendId='" + frontendId + "', title='" + title + "'}";
    }
}
