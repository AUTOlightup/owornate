package dev.codex.watchworkshop;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.stream.Stream;

import org.json.JSONObject;

/** Offline checks for UpdateClient via a scripted HTTPS transport. */
final class UpdateClientTest {
    private static final String FEED = "https://updates.example/owornate-updates.json";
    private static final String ARTIFACT = "https://downloads.example/owornate.apk";
    private static Path runtimeRoot;
    private static int cases;
    private static int checks;

    private UpdateClientTest() {}

    public static void main(String[] args) throws Exception {
        runtimeRoot = Paths.get(System.getProperty("update.test.root"))
                .toAbsolutePath().normalize();
        Files.createDirectories(runtimeRoot);

        manifestAndVersions();
        githubDiscovery();
        manifestRejections();
        sourceAndResponseLimits();
        successfulDownload();
        redirectsAndDownloadFailures();

        System.out.println("UpdateClientTest: OK (" + cases + " cases, " + checks
                + " checks)");
    }

    private static void manifestAndVersions() throws Exception {
        String feed = manifest(release("dev.codex.watchworkshop", 28, "1.0.1-rc18",
                ARTIFACT, sha("payload"), "notes"));

        ScriptedTransport transport = new ScriptedTransport();
        transport.expect(FEED, response(feed));
        UpdateClient.Release release = new UpdateClient(transport)
                .check(FEED, UpdateClient.MANAGER_ID, 27);
        equal("dev.codex.watchworkshop", release.id, "release id");
        equal(28, release.versionCode, "release versionCode");
        equal("1.0.1-rc18", release.versionName, "release versionName");
        equal(ARTIFACT, release.url, "release url");
        equal(sha("payload"), release.sha256, "release sha256");
        equal("notes", release.notes, "release notes");
        transport.exhausted();

        transport = new ScriptedTransport();
        transport.expect(FEED, response(feed));
        equal(null, new UpdateClient(transport)
                .check(FEED, UpdateClient.MANAGER_ID, 28), "same version is current");
        transport.exhausted();

        transport = new ScriptedTransport();
        transport.expect(FEED, response(feed));
        equal(null, new UpdateClient(transport)
                .check(FEED, UpdateClient.MANAGER_ID, 29), "older version is current");
        transport.exhausted();

        String uppercase = manifest(release("dev.codex.watchworkshop", 28,
                "1.0.1-rc18", ARTIFACT, sha("payload").toUpperCase(), "notes"));
        transport = new ScriptedTransport();
        transport.expect(FEED, response(uppercase));
        equal(sha("payload"), new UpdateClient(transport)
                .check(FEED, UpdateClient.MANAGER_ID, 27).sha256,
                "sha256 normalized to lower case");
        transport.exhausted();

        String multi = manifest(
                release("dev.codex.watchworkshop", 28, "manager", ARTIFACT,
                        sha("manager"), ""),
                release("dev.codex.example_plugin", 7, "plugin", ARTIFACT,
                        sha("plugin"), "plugin notes"));
        transport = new ScriptedTransport();
        transport.expect(FEED, response(multi));
        UpdateClient.Release plugin = new UpdateClient(transport)
                .check(FEED, "dev.codex.example_plugin", 6);
        equal(7, plugin.versionCode, "plugin id selects its own release");
        equal("plugin notes", plugin.notes, "plugin release fields");
        transport.exhausted();
        finish("manifest and version selection");
    }

    private static void githubDiscovery() throws Exception {
        String draft = githubRelease(true, true,
                asset("owornate-updates.json", "https://updates.example/draft.json"));
        String prerelease = githubRelease(false, true,
                asset("unrelated.bin", "https://downloads.example/other.bin"),
                asset("owornate-updates.json", FEED));
        ScriptedTransport transport = new ScriptedTransport();
        transport.expect(UpdateClient.DEFAULT_SOURCE,
                response("[" + draft + "," + prerelease + "]"));
        transport.expect(FEED, response(manifest(release(
                "dev.codex.watchworkshop", 28, "rc18", ARTIFACT,
                sha("payload"), "prerelease"))));
        UpdateClient.Release release = new UpdateClient(transport)
                .check(UpdateClient.DEFAULT_SOURCE, UpdateClient.MANAGER_ID, 27);
        equal(28, release.versionCode, "GitHub prerelease is included");
        equal("prerelease", release.notes, "GitHub selected first non-draft feed");
        transport.exhausted();

        transport = new ScriptedTransport();
        transport.expect(UpdateClient.DEFAULT_SOURCE,
                response("[" + githubRelease(false, false,
                        asset("unrelated.bin", "https://downloads.example/other.bin"))
                        + "]"));
        final UpdateClient noFeedClient = new UpdateClient(transport);
        expectIOException("GitHub without a feed", "尚未发布更新清单", () ->
                noFeedClient.check(UpdateClient.DEFAULT_SOURCE,
                        UpdateClient.MANAGER_ID, 27));
        transport.exhausted();

        transport = new ScriptedTransport();
        transport.expect(UpdateClient.DEFAULT_SOURCE,
                response("[" + githubRelease(true, false,
                        asset("owornate-updates.json", FEED)) + "]"));
        final UpdateClient draftClient = new UpdateClient(transport);
        expectIOException("draft feed is ignored", "尚未发布更新清单", () ->
                draftClient.check(UpdateClient.DEFAULT_SOURCE,
                        UpdateClient.MANAGER_ID, 27));
        transport.exhausted();

        String firstFeed = "https://updates.example/first.json";
        String secondFeed = "https://updates.example/second.json";
        transport = new ScriptedTransport();
        transport.expect(UpdateClient.DEFAULT_SOURCE,
                response("["
                        + githubRelease(false, false,
                                asset("owornate-updates.json", firstFeed))
                        + ","
                        + githubRelease(false, false,
                                asset("owornate-updates.json", secondFeed))
                        + "]"));
        transport.expect(firstFeed, response(manifest(release(
                "dev.codex.other", 30, "other", ARTIFACT, sha("payload"), "other"))));
        final UpdateClient firstFeedClient = new UpdateClient(transport);
        expectIOException("first GitHub feed is authoritative", "缺少管理器 ID", () ->
                firstFeedClient.check(UpdateClient.DEFAULT_SOURCE,
                        UpdateClient.MANAGER_ID, 27));
        transport.exhausted();

        StringBuilder twentyOne = new StringBuilder("[");
        for (int i = 0; i < 20; i++) {
            if (i != 0) twentyOne.append(',');
            twentyOne.append(githubRelease(false, false,
                    asset("unrelated.bin", "https://downloads.example/" + i + ".bin")));
        }
        twentyOne.append(',')
                .append(githubRelease(false, false,
                        asset("owornate-updates.json", FEED)))
                .append(']');
        transport = new ScriptedTransport();
        transport.expect(UpdateClient.DEFAULT_SOURCE, response(twentyOne.toString()));
        final UpdateClient latestTwentyClient = new UpdateClient(transport);
        expectIOException("GitHub beyond first twenty is ignored",
                "尚未发布更新清单", () ->
                        latestTwentyClient.check(UpdateClient.DEFAULT_SOURCE,
                                UpdateClient.MANAGER_ID, 27));
        transport.exhausted();
        finish("GitHub prerelease, draft, and no-feed discovery");
    }

    private static void manifestRejections() throws Exception {
        expectManifestError("schema version", "{\"schemaVersion\":2,\"releases\":["
                + release("dev.codex.watchworkshop", 28, "v", ARTIFACT,
                        sha("payload"), "")
                + "]}", "schemaVersion");
        expectManifestError("missing requested id", manifest(release(
                "dev.codex.other", 28, "v", ARTIFACT, sha("payload"), "")),
                "缺少管理器 ID");
        expectManifestError("duplicate id", manifest(
                release("dev.codex.watchworkshop", 27, "v27", ARTIFACT,
                        sha("payload"), ""),
                release("dev.codex.watchworkshop", 28, "v28", ARTIFACT,
                        sha("payload"), "")), "ID 重复");
        expectManifestError("string versionCode", manifest(releaseRaw(
                "dev.codex.watchworkshop", "\"28\"", "v", ARTIFACT,
                sha("payload"), "")), "versionCode 必须是整数");
        expectManifestError("fractional versionCode", manifest(releaseRaw(
                "dev.codex.watchworkshop", "28.5", "v", ARTIFACT,
                sha("payload"), "")), "versionCode 必须是整数");
        expectManifestError("zero versionCode", manifest(release(
                "dev.codex.watchworkshop", 0, "v", ARTIFACT,
                sha("payload"), "")), "versionCode 必须是正整数");
        expectManifestError("http artifact", manifest(release(
                "dev.codex.watchworkshop", 28, "v",
                "http://downloads.example/file.apk", sha("payload"), "")),
                "HTTPS URL");
        expectManifestError("artifact userinfo", manifest(release(
                "dev.codex.watchworkshop", 28, "v",
                "https://user@downloads.example/file.apk", sha("payload"), "")),
                "HTTPS URL");
        expectManifestError("artifact fragment", manifest(release(
                "dev.codex.watchworkshop", 28, "v",
                "https://downloads.example/file.apk#x", sha("payload"), "")),
                "HTTPS URL");
        expectManifestError("short sha256", manifest(release(
                "dev.codex.watchworkshop", 28, "v", ARTIFACT, "abcd", "")),
                "64 位十六进制");
        expectManifestError("non-hex sha256", manifest(release(
                "dev.codex.watchworkshop", 28, "v", ARTIFACT,
                repeat("z", 64), "")), "64 位十六进制");
        expectManifestError("long versionName", manifest(release(
                "dev.codex.watchworkshop", 28, repeat("v", 65), ARTIFACT,
                sha("payload"), "")), "versionName");
        expectManifestError("long notes", manifest(release(
                "dev.codex.watchworkshop", 28, "v", ARTIFACT,
                sha("payload"), repeat("n", 4097))), "notes");
        expectManifestError("malformed JSON", "{not-json", "JSON 无法解析");

        List<String> releases = new ArrayList<>();
        for (int i = 0; i < 201; i++) {
            releases.add(release("dev.codex.id" + i, i + 1, "v", ARTIFACT,
                    sha("payload"), ""));
        }
        expectManifestError("too many releases", manifest(
                releases.toArray(new String[0])), "最多 200 项");
        finish("strict manifest validation");
    }

    private static void sourceAndResponseLimits() throws Exception {
        UpdateClient.validateSource(UpdateClient.DEFAULT_SOURCE);
        expectIOException("http source", "HTTPS URL", () ->
                UpdateClient.validateSource("http://updates.example/feed.json"));
        expectIOException("source userinfo", "HTTPS URL", () ->
                UpdateClient.validateSource("https://user@updates.example/feed.json"));
        expectIOException("source fragment", "HTTPS URL", () ->
                UpdateClient.validateSource("https://updates.example/feed.json#x"));
        expectIOException("relative source", "HTTPS URL", () ->
                UpdateClient.validateSource("/feed.json"));
        expectIOException("empty source", "不能为空", () ->
                UpdateClient.validateSource(null));
        expectIOException("long source", "2048", () ->
                UpdateClient.validateSource("https://updates.example/"
                        + repeat("a", 2048)));

        String valid = manifest(release("dev.codex.watchworkshop", 28, "v",
                ARTIFACT, sha("payload"), ""));
        int padding = 256 * 1024 - valid.getBytes(StandardCharsets.UTF_8).length;
        String exactLimit = valid + repeat(" ", padding);
        ScriptedTransport transport = new ScriptedTransport();
        transport.expect(FEED, response(exactLimit));
        equal(28, new UpdateClient(transport)
                .check(FEED, UpdateClient.MANAGER_ID, 27).versionCode,
                "manifest accepts exactly 256 KiB");
        transport.exhausted();

        transport = new ScriptedTransport();
        transport.expect(FEED, response(exactLimit + " "));
        final UpdateClient oversizedManifestClient = new UpdateClient(transport);
        expectIOException("manifest over 256 KiB", "超过大小限制", () ->
                oversizedManifestClient.check(FEED, UpdateClient.MANAGER_ID, 27));
        transport.exhausted();

        transport = new ScriptedTransport();
        transport.expect(UpdateClient.DEFAULT_SOURCE,
                new UpdateClient.Response(200, null, 2 * 1024 * 1024 + 1L,
                        new ByteArrayInputStream(new byte[0])));
        final UpdateClient oversizedGithubClient = new UpdateClient(transport);
        expectIOException("GitHub response over 2 MiB", "超过大小限制", () ->
                oversizedGithubClient.check(UpdateClient.DEFAULT_SOURCE,
                        UpdateClient.MANAGER_ID, 27));
        transport.exhausted();

        transport = new ScriptedTransport();
        transport.expect(FEED, new UpdateClient.Response(200, null,
                valid.getBytes(StandardCharsets.UTF_8).length + 1L,
                new ByteArrayInputStream(valid.getBytes(StandardCharsets.UTF_8))));
        final UpdateClient truncatedManifestClient = new UpdateClient(transport);
        expectIOException("truncated manifest", "被截断", () ->
                truncatedManifestClient.check(FEED, UpdateClient.MANAGER_ID, 27));
        transport.exhausted();
        finish("source and response bounds");
    }

    private static void successfulDownload() throws Exception {
        Path directory = newCase("download-success");
        File target = directory.resolve("owornate.apk").toFile();
        Files.write(target.toPath(), bytes("old"));
        byte[] payload = bytes("verified apk payload");
        UpdateClient.Release release = releaseWith(payload);
        ScriptedTransport transport = new ScriptedTransport();
        transport.expect(ARTIFACT, response(payload));

        new UpdateClient(transport).download(release, target, 32 * 1024 * 1024);
        equal((long) bytes("verified apk payload").length, Files.size(target.toPath()),
                "download target size");
        equal("verified apk payload", readUtf8(target), "download replaces target");
        transport.exhausted();
        equal(0L, temporaryParts(directory), "successful download leaves no part file");
        finish("verified download");
    }

    private static void redirectsAndDownloadFailures() throws Exception {
        Path directory = newCase("download-redirect");
        byte[] payload = bytes("redirected payload");
        UpdateClient.Release release = releaseWith(payload);
        File target = directory.resolve("redirect.apk").toFile();
        ScriptedTransport transport = new ScriptedTransport();
        transport.expect(ARTIFACT, redirect(302,
                "https://cdn.example/releases/current.apk"));
        transport.expect("https://cdn.example/releases/current.apk", response(payload));
        new UpdateClient(transport).download(release, target, payload.length);
        equal("redirected payload", readUtf8(target), "HTTPS redirect download");
        transport.exhausted();

        directory = newCase("download-bad-sha");
        target = directory.resolve("existing.apk").toFile();
        Files.write(target.toPath(), bytes("keep"));
        release = new UpdateClient.Release("dev.codex.watchworkshop", 28, "v",
                ARTIFACT, sha("different"), "");
        transport = new ScriptedTransport();
        transport.expect(ARTIFACT, response(bytes("new")));
        final UpdateClient.Release badSha = release;
        final File existingTarget = target;
        final UpdateClient badShaClient = new UpdateClient(transport);
        expectIOException("sha mismatch", "SHA-256 不匹配", () ->
                badShaClient.download(badSha, existingTarget, 1024));
        equal("keep", readUtf8(target), "sha failure preserves existing target");
        equal(0L, temporaryParts(directory), "sha failure removes part file");
        transport.exhausted();

        directory = newCase("download-truncated");
        target = directory.resolve("existing.apk").toFile();
        Files.write(target.toPath(), bytes("keep"));
        payload = bytes("short");
        release = releaseWith(payload);
        transport = new ScriptedTransport();
        transport.expect(ARTIFACT, new UpdateClient.Response(200, null,
                payload.length + 3L, new ByteArrayInputStream(payload)));
        final UpdateClient.Release truncated = release;
        final File truncatedTarget = target;
        final UpdateClient truncatedClient = new UpdateClient(transport);
        expectIOException("content length truncation", "被截断", () ->
                truncatedClient.download(truncated, truncatedTarget, 1024));
        equal("keep", readUtf8(target), "truncation preserves existing target");
        equal(0L, temporaryParts(directory), "truncation removes part file");
        transport.exhausted();

        directory = newCase("download-too-large");
        target = directory.resolve("new.apk").toFile();
        payload = bytes("123456");
        release = releaseWith(payload);
        transport = new ScriptedTransport();
        transport.expect(ARTIFACT, response(payload));
        final UpdateClient.Release tooLarge = release;
        final File tooLargeTarget = target;
        final UpdateClient tooLargeClient = new UpdateClient(transport);
        expectIOException("download caller limit", "超过大小限制", () ->
                tooLargeClient.download(tooLarge, tooLargeTarget, 5));
        check(!Files.exists(target.toPath()), "size failure creates no target");
        check(temporaryParts(directory) == 0L, "size failure removes part file");
        transport.exhausted();

        directory = newCase("download-http-error");
        target = directory.resolve("existing.apk").toFile();
        Files.write(target.toPath(), bytes("keep"));
        release = releaseWith(bytes("payload"));
        transport = new ScriptedTransport();
        transport.expect(ARTIFACT, new UpdateClient.Response(503, null, -1L,
                new ByteArrayInputStream(bytes("unavailable"))));
        final UpdateClient.Release httpFailure = release;
        final File httpTarget = target;
        final UpdateClient httpFailureClient = new UpdateClient(transport);
        expectIOException("download HTTP failure", "HTTP 503", () ->
                httpFailureClient.download(httpFailure, httpTarget, 1024));
        equal("keep", readUtf8(target), "HTTP failure preserves existing target");
        transport.exhausted();

        directory = newCase("download-http-redirect");
        target = directory.resolve("new.apk").toFile();
        release = releaseWith(bytes("payload"));
        transport = new ScriptedTransport();
        transport.expect(ARTIFACT, redirect(302, "http://cdn.example/file.apk"));
        final UpdateClient.Release badRedirect = release;
        final File badRedirectTarget = target;
        final UpdateClient badRedirectClient = new UpdateClient(transport);
        expectIOException("download redirect to HTTP", "HTTPS URL", () ->
                badRedirectClient.download(badRedirect, badRedirectTarget, 1024));
        check(!Files.exists(target.toPath()), "rejected redirect creates no target");
        transport.exhausted();

        directory = newCase("download-too-many-redirects");
        target = directory.resolve("new.apk").toFile();
        release = releaseWith(bytes("payload"));
        transport = new ScriptedTransport();
        for (int i = 0; i < 6; i++) {
            String from = i == 0 ? ARTIFACT : "https://downloads.example/r" + i;
            String to = "https://downloads.example/r" + (i + 1);
            transport.expect(from, redirect(302, to));
        }
        final UpdateClient.Release redirectLimit = release;
        final File redirectLimitTarget = target;
        final UpdateClient redirectLimitClient = new UpdateClient(transport);
        expectIOException("redirect limit", "重定向超过 5 次", () ->
                redirectLimitClient.download(redirectLimit,
                        redirectLimitTarget, 1024));
        check(!Files.exists(target.toPath()), "redirect limit creates no target");
        transport.exhausted();

        directory = newCase("download-caller-bounds");
        target = directory.resolve("new.apk").toFile();
        release = releaseWith(bytes("payload"));
        transport = new ScriptedTransport();
        final UpdateClient.Release callerBounds = release;
        final File callerBoundsTarget = target;
        final UpdateClient callerBoundsClient = new UpdateClient(transport);
        expectIOException("download cap above 32 MiB", "32 MiB", () ->
                callerBoundsClient.download(callerBounds,
                        callerBoundsTarget, 32 * 1024 * 1024 + 1));
        transport.exhausted();
        finish("download redirects, integrity, truncation, and HTTP failures");
    }

    private static void expectManifestError(String label, String body, String snippet)
            throws Exception {
        ScriptedTransport transport = new ScriptedTransport();
        transport.expect(FEED, response(body));
        expectIOException(label, snippet, () ->
                new UpdateClient(transport)
                        .check(FEED, UpdateClient.MANAGER_ID, 27));
        transport.exhausted();
    }

    private static UpdateClient.Release releaseWith(byte[] payload) {
        return new UpdateClient.Release("dev.codex.watchworkshop", 28, "v", ARTIFACT,
                sha(payload), "");
    }

    private static String manifest(String... entries) {
        StringBuilder value = new StringBuilder("{\"schemaVersion\":1,\"releases\":[");
        for (int i = 0; i < entries.length; i++) {
            if (i != 0) value.append(',');
            value.append(entries[i]);
        }
        return value.append("]}").toString();
    }

    private static String release(String id, int code, String versionName,
            String url, String sha256, String notes) {
        return releaseRaw(id, Integer.toString(code), versionName, url, sha256, notes);
    }

    private static String releaseRaw(String id, String code, String versionName,
            String url, String sha256, String notes) {
        JSONObject object = new JSONObject();
        object.put("id", id);
        StringBuilder item = new StringBuilder(object.toString());
        item.setLength(item.length() - 1);
        item.append(",\"versionCode\":").append(code);
        item.append(",\"versionName\":").append(quote(versionName));
        item.append(",\"url\":").append(quote(url));
        item.append(",\"sha256\":").append(quote(sha256));
        item.append(",\"notes\":").append(quote(notes)).append('}');
        return item.toString();
    }

    private static String githubRelease(boolean draft, boolean prerelease,
            String... assets) {
        StringBuilder result = new StringBuilder("{\"draft\":").append(draft)
                .append(",\"prerelease\":").append(prerelease)
                .append(",\"assets\":[");
        for (int i = 0; i < assets.length; i++) {
            if (i != 0) result.append(',');
            result.append(assets[i]);
        }
        return result.append("]}").toString();
    }

    private static String asset(String name, String url) {
        return "{\"name\":" + quote(name)
                + ",\"browser_download_url\":" + quote(url) + "}";
    }

    private static String quote(String value) {
        return JSONObject.quote(value);
    }

    private static UpdateClient.Response response(String text) {
        return response(bytes(text));
    }

    private static UpdateClient.Response response(byte[] bytes) {
        return new UpdateClient.Response(200, null, bytes.length,
                new ByteArrayInputStream(bytes));
    }

    private static UpdateClient.Response redirect(int status, String location) {
        return new UpdateClient.Response(status, location, 0L, null);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String readUtf8(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static String sha(String value) throws Exception {
        return sha(bytes(value));
    }

    private static String sha(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format("%02x", item & 0xff));
            }
            return result.toString();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }

    private static Path newCase(String name) throws Exception {
        Path directory = runtimeRoot.resolve(name);
        if (Files.exists(directory)) {
            try (Stream<Path> files = Files.walk(directory)) {
                files.sorted((left, right) -> right.getNameCount()
                        - left.getNameCount()).forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        });
            }
        }
        Files.createDirectories(directory);
        return directory;
    }

    private static long temporaryParts(Path directory) throws Exception {
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".part"))
                    .count();
        }
    }

    private interface Action {
        void run() throws Exception;
    }

    private static void expectIOException(String label, String snippet, Action action)
            throws Exception {
        try {
            action.run();
        } catch (IOException e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            if (!message.contains(snippet)) {
                throw new AssertionError(label + ": expected message containing '"
                        + snippet + "', got '" + message + "'");
            }
            check(true, label);
            return;
        } catch (Exception e) {
            throw new AssertionError(label + ": expected IOException, got "
                    + e.getClass().getName(), e);
        }
        throw new AssertionError(label + ": expected IOException");
    }

    private static void equal(Object expected, Object actual, String label) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
        check(true, label);
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }

    private static void finish(String label) {
        cases++;
        System.out.println("PASS " + label + " (" + checks + " checks so far)");
    }

    private static final class ScriptedTransport implements UpdateClient.Transport {
        private final Deque<Exchange> exchanges = new ArrayDeque<>();
        private int calls;

        void expect(String url, UpdateClient.Response response) {
            exchanges.addLast(new Exchange(url, response));
        }

        @Override
        public UpdateClient.Response get(String url) throws IOException {
            calls++;
            Exchange exchange = exchanges.pollFirst();
            if (exchange == null) {
                throw new IOException("unexpected request " + calls + ": " + url);
            }
            if (!exchange.url.equals(url)) {
                throw new IOException("request " + calls + ": expected "
                        + exchange.url + ", got " + url);
            }
            return exchange.response;
        }

        void exhausted() {
            check(exchanges.isEmpty(), "all scripted requests consumed");
        }
    }

    private static final class Exchange {
        final String url;
        final UpdateClient.Response response;

        Exchange(String url, UpdateClient.Response response) {
            this.url = url;
            this.response = response;
        }
    }
}
