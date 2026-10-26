package dev.chunkscan;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Looks up the latest GitHub release of ChunkScan. Plain Java, blocking: call it off the main thread. */
public final class UpdateChecker {

    public static final String REPO = "alexander-rebello/ChunkScan";
    private static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final Pattern TAG = Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern URL = Pattern.compile("\"html_url\"\\s*:\\s*\"([^\"]+/releases/tag/[^\"]+)\"");

    /** Result of a check. */
    public static final class Release {
        public final String version; // without leading "v"
        public final String url;

        Release(String version, String url) {
            this.version = version;
            this.url = url;
        }
    }

    private UpdateChecker() {}

    /** @return the latest release; throws if GitHub can't be reached or answers unexpectedly. */
    public static Release fetchLatest(String installedVersion) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(API))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "ChunkScan/" + installedVersion)
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IOException("GitHub answered HTTP " + response.statusCode());
        return parse(response.body());
    }

    static Release parse(String json) throws IOException {
        Matcher tag = TAG.matcher(json);
        if (!tag.find()) throw new IOException("no tag_name in GitHub response");
        Matcher url = URL.matcher(json);
        String version = stripV(tag.group(1));
        String link = url.find() ? url.group(1) : "https://github.com/" + REPO + "/releases/latest";
        return new Release(version, link);
    }

    /** Compares versions like 1.3.0, v1.10.2 or 1.4.0-beta numerically; a pre-release suffix sorts lower. */
    public static int compare(String a, String b) {
        String[] pa = stripV(a).split("-", 2), pb = stripV(b).split("-", 2);
        String[] na = pa[0].split("\\."), nb = pb[0].split("\\.");
        for (int i = 0; i < Math.max(na.length, nb.length); i++) {
            long x = i < na.length ? number(na[i]) : 0, y = i < nb.length ? number(nb[i]) : 0;
            if (x != y) return Long.compare(x, y);
        }
        boolean preA = pa.length > 1, preB = pb.length > 1;
        if (preA != preB) return preA ? -1 : 1;
        return preA ? pa[1].compareTo(pb[1]) : 0;
    }

    private static long number(String s) {
        try {
            return Long.parseLong(s.replaceAll("\\D.*$", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String stripV(String s) {
        s = s.trim();
        return s.startsWith("v") || s.startsWith("V") ? s.substring(1) : s;
    }
}
