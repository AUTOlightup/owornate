package dev.codex.watchworkshop;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Public HTTPS update discovery and verified download for the manager and its plugins. */
final class UpdateClient {
    static final String DEFAULT_SOURCE =
            "https://api.github.com/repos/AUTOlightup/owornate/releases?per_page=20";
    static final String MANAGER_ID = "dev.codex.watchworkshop";

    private static final String FEED_ASSET = "owornate-updates.json";
    private static final int GITHUB_RESPONSE_LIMIT = 2 * 1024 * 1024;
    private static final int MANIFEST_LIMIT = 256 * 1024;
    private static final int MAX_DOWNLOAD_BYTES = 32 * 1024 * 1024;
    private static final int MAX_REDIRECTS = 5;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 20_000;
    private static final int MAX_SOURCE_LENGTH = 2048;
    private static final int MAX_ID_LENGTH = 80;
    private static final int MAX_VERSION_NAME_LENGTH = 64;
    private static final int MAX_NOTES_LENGTH = 4096;
    private static final int MAX_RELEASES = 200;
    private static final Pattern ID_PATTERN = Pattern.compile(
            "[a-z0-9](?:[a-z0-9_-]*[a-z0-9])?"
                    + "(?:\\.[a-z0-9](?:[a-z0-9_-]*[a-z0-9])?)+");
    private static final Pattern SHA256_PATTERN = Pattern.compile("[0-9A-Fa-f]{64}");

    static final class Release {
        final String id;
        final int versionCode;
        final String versionName;
        final String url;
        final String sha256;
        final String notes;

        Release(String id, int versionCode, String versionName, String url,
                String sha256, String notes) {
            this.id = id;
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.url = url;
            this.sha256 = sha256;
            this.notes = notes;
        }
    }

    /** One HTTP response, before redirect handling and response-size enforcement. */
    interface Transport {
        Response get(String url) throws IOException;
    }

    static final class Response implements Closeable {
        final int status;
        final String location;
        final long contentLength;
        final InputStream body;
        private final HttpURLConnection connection;

        Response(int status, String location, long contentLength, InputStream body) {
            this(status, location, contentLength, body, null);
        }

        Response(int status, String location, long contentLength, InputStream body,
                HttpURLConnection connection) {
            this.status = status;
            this.location = location;
            this.contentLength = contentLength;
            this.body = body;
            this.connection = connection;
        }

        @Override
        public void close() throws IOException {
            try {
                if (body != null) body.close();
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
    }

    private final Transport transport;

    UpdateClient() {
        this(new HttpsTransport());
    }

    UpdateClient(Transport transport) {
        if (transport == null) throw new IllegalArgumentException("transport 不能为空");
        this.transport = transport;
    }

    /** Validates a user-configurable source before it is persisted by the settings UI. */
    static void validateSource(String source) throws IOException {
        validateHttpsUrl(source, "更新源");
    }

    /**
     * Returns the newer release for {@code id}, or {@code null} when the installed
     * version is current.
     */
    Release check(String source, String id, int currentVersion) throws Exception {
        validateSource(source);
        validateIdentifier(id, "管理器 ID");
        if (currentVersion < 0) {
            throw new IllegalArgumentException("currentVersion 不能为负数");
        }

        String manifestUrl = isGitHubReleasesSource(source)
                ? discoverManifest(source) : source;
        byte[] bytes = fetchBytes(manifestUrl, MANIFEST_LIMIT, "更新清单");
        return parseManifest(bytes, id, currentVersion);
    }

    /**
     * Downloads {@code release.url} to {@code target}. The caller supplies either the
     * 2 MiB plugin bound or the 32 MiB APK bound.
     */
    void download(Release release, File target, int maxBytes) throws Exception {
        if (release == null) throw new IllegalArgumentException("release 不能为空");
        if (target == null) throw new IllegalArgumentException("target 不能为空");
        validateHttpsUrl(release.url, "下载 URL");
        validateSha256(release.sha256, "sha256");
        if (maxBytes <= 0 || maxBytes > MAX_DOWNLOAD_BYTES) {
            throw new IOException("下载大小限制必须在 1 字节到 32 MiB 之间");
        }

        Path targetPath = target.toPath().toAbsolutePath().normalize();
        Path parent = targetPath.getParent();
        if (parent == null || targetPath.getFileName() == null) {
            throw new IOException("下载目标无效: " + target);
        }
        Files.createDirectories(parent);
        if (Files.exists(targetPath, LinkOption.NOFOLLOW_LINKS)
                && Files.isDirectory(targetPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("下载目标是目录: " + target);
        }

        Path temporary = Files.createTempFile(parent, ".owornate-update-", ".part");
        boolean installed = false;
        try {
            Response response = openFollowingRedirects(release.url);
            try {
                if (response.contentLength > maxBytes) {
                    throw new IOException("下载超过大小限制");
                }
                if (response.body == null) {
                    throw new IOException("下载响应没有内容");
                }

                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long total = 0;
                byte[] buffer = new byte[8192];
                try (InputStream input = response.body;
                        OutputStream output = Files.newOutputStream(temporary)) {
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        total += count;
                        if (total > maxBytes) throw new IOException("下载超过大小限制");
                        digest.update(buffer, 0, count);
                        output.write(buffer, 0, count);
                    }
                }

                if (response.contentLength >= 0 && total != response.contentLength) {
                    throw new IOException("下载响应被截断");
                }
                if (!release.sha256.equalsIgnoreCase(toHex(digest.digest()))) {
                    throw new IOException("下载文件 SHA-256 不匹配");
                }

                moveReplacing(temporary, targetPath);
                installed = true;
            } finally {
                closeQuietly(response);
            }
        } finally {
            if (!installed) deleteQuietly(temporary);
        }
    }

    private String discoverManifest(String source) throws Exception {
        byte[] bytes = fetchBytes(source, GITHUB_RESPONSE_LIMIT, "GitHub releases 响应");
        final JSONArray releases;
        try {
            releases = new JSONArray(decodeUtf8(bytes, "GitHub releases 响应"));
        } catch (JSONException e) {
            throw invalid("GitHub releases 响应不是 JSON 数组", e);
        }

        int count = Math.min(releases.length(), 20);
        for (int i = 0; i < count; i++) {
            JSONObject release = objectAt(releases, i, "GitHub releases[" + i + "]");
            Object draftValue = release.opt("draft");
            if (!(draftValue instanceof Boolean)) {
                throw invalid("GitHub releases[" + i + "].draft 必须是布尔值");
            }
            if ((Boolean) draftValue) continue;

            JSONArray assets = requiredArray(release, "assets",
                    "GitHub releases[" + i + "]");
            for (int j = 0; j < assets.length(); j++) {
                JSONObject asset = objectAt(assets, j,
                        "GitHub releases[" + i + "].assets[" + j + "]");
                String name = requiredString(asset, "name",
                        "GitHub releases[" + i + "].assets[" + j + "]");
                if (!FEED_ASSET.equals(name)) continue;
                String assetUrl = requiredString(asset, "browser_download_url",
                        "GitHub releases[" + i + "].assets[" + j + "]");
                validateHttpsUrl(assetUrl, "更新清单 asset URL");
                return assetUrl;
            }
        }
        throw new IOException("尚未发布更新清单");
    }

    private static Release parseManifest(byte[] bytes, String requestedId,
            int currentVersion) throws IOException {
        final JSONObject root;
        try {
            root = new JSONObject(decodeUtf8(bytes, "更新清单"));
        } catch (JSONException e) {
            throw invalid("JSON 无法解析", e);
        }

        if (requiredInteger(root, "schemaVersion", "更新清单") != 1) {
            throw invalid("schemaVersion 必须为 1");
        }
        JSONArray releasesJson = requiredArray(root, "releases", "更新清单");
        if (releasesJson.length() > MAX_RELEASES) {
            throw invalid("releases 最多 " + MAX_RELEASES + " 项");
        }

        Set<String> ids = new HashSet<>();
        Release requested = null;
        for (int i = 0; i < releasesJson.length(); i++) {
            String where = "releases[" + i + "]";
            JSONObject item = objectAt(releasesJson, i, where);
            String id = requiredString(item, "id", where);
            validateIdentifier(id, where + ".id");
            if (!ids.add(id)) throw invalid("releases ID 重复: " + id);

            int versionCode = requiredInteger(item, "versionCode", where);
            if (versionCode <= 0) throw invalid(where + ".versionCode 必须是正整数");

            String versionName = requiredString(item, "versionName", where);
            if (versionName.trim().isEmpty()
                    || versionName.length() > MAX_VERSION_NAME_LENGTH) {
                throw invalid(where + ".versionName 必须为 1 到 "
                        + MAX_VERSION_NAME_LENGTH + " 个字符");
            }

            String url = requiredString(item, "url", where);
            validateHttpsUrl(url, where + ".url");

            String sha256 = requiredString(item, "sha256", where);
            validateSha256(sha256, where + ".sha256");

            String notes = requiredString(item, "notes", where);
            if (notes.length() > MAX_NOTES_LENGTH) {
                throw invalid(where + ".notes 超过 " + MAX_NOTES_LENGTH + " 个字符");
            }

            Release release = new Release(id, versionCode, versionName, url,
                    sha256.toLowerCase(Locale.ROOT), notes);
            if (id.equals(requestedId)) requested = release;
        }

        if (requested == null) {
            throw invalid("更新清单缺少管理器 ID: " + requestedId);
        }
        return requested.versionCode <= currentVersion ? null : requested;
    }

    private byte[] fetchBytes(String url, int limit, String label) throws IOException {
        Response response = openFollowingRedirects(url);
        try {
            if (response.contentLength > limit) {
                throw new IOException(label + " 超过大小限制");
            }
            if (response.body == null) throw new IOException(label + " 响应没有内容");
            byte[] bytes = readLimited(response.body, limit, label);
            if (response.contentLength >= 0 && bytes.length != response.contentLength) {
                throw new IOException(label + " 响应被截断");
            }
            return bytes;
        } finally {
            closeQuietly(response);
        }
    }

    private Response openFollowingRedirects(String initialUrl) throws IOException {
        String current = initialUrl;
        int redirects = 0;
        while (true) {
            final Response response;
            try {
                response = transport.get(current);
            } catch (IOException e) {
                throw new IOException("网络请求失败: " + current, e);
            }
            if (response == null) throw new IOException("网络请求没有响应: " + current);

            if (!isRedirect(response.status)) {
                if (response.status == 200) return response;
                int status = response.status;
                closeQuietly(response);
                throw new IOException("更新请求失败: HTTP " + status);
            }
            final String location = response.location;
            closeQuietly(response);
            if (location == null || location.isEmpty()) {
                throw new IOException("重定向响应缺少 Location");
            }
            if (++redirects > MAX_REDIRECTS) {
                throw new IOException("重定向超过 " + MAX_REDIRECTS + " 次");
            }
            current = resolveRedirect(current, location);
        }
    }

    private static String resolveRedirect(String base, String location) throws IOException {
        try {
            URI target = new URI(base).resolve(new URI(location));
            validateHttpsUrl(target.toString(), "重定向 URL");
            return target.toString();
        } catch (URISyntaxException e) {
            throw new IOException("重定向 Location 不是合法 URL", e);
        }
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303
                || status == 307 || status == 308;
    }

    private static boolean isGitHubReleasesSource(String source) {
        try {
            URI uri = new URI(source);
            String path = uri.getPath();
            return "api.github.com".equalsIgnoreCase(uri.getHost())
                    && path != null && path.endsWith("/releases");
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private static byte[] readLimited(InputStream input, int limit, String label)
            throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > limit) {
                throw new IOException(label + " 超过大小限制");
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static String decodeUtf8(byte[] bytes, String label) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new IOException(label + " 不是合法 UTF-8", e);
        }
    }

    private static void validateHttpsUrl(String value, String label) throws IOException {
        if (value == null || value.isEmpty()) throw new IOException(label + " 不能为空");
        if (value.length() > MAX_SOURCE_LENGTH) {
            throw new IOException(label + " 超过 " + MAX_SOURCE_LENGTH + " 个字符");
        }
        final URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            throw new IOException(label + " 不是合法 URL", e);
        }
        if (!uri.isAbsolute() || uri.isOpaque()
                || !"https".equalsIgnoreCase(uri.getScheme())
                || uri.getRawAuthority() == null || uri.getHost() == null
                || uri.getHost().isEmpty() || uri.getRawUserInfo() != null
                || uri.getRawFragment() != null) {
            throw new IOException(label + " 必须是无用户信息和 fragment 的 HTTPS URL");
        }
    }

    private static void validateIdentifier(String value, String label) throws IOException {
        if (value == null || value.isEmpty()) throw new IOException(label + " 不能为空");
        if (value.length() > MAX_ID_LENGTH || !ID_PATTERN.matcher(value).matches()) {
            throw new IOException(label + " 格式不合法: " + value);
        }
    }

    private static void validateSha256(String value, String label) throws IOException {
        if (value == null || !SHA256_PATTERN.matcher(value).matches()) {
            throw new IOException(label + " 必须是 64 位十六进制字符串");
        }
    }

    private static int requiredInteger(JSONObject object, String key, String where)
            throws IOException {
        Object value = object.opt(key);
        if (!(value instanceof Integer) && !(value instanceof Long)) {
            throw invalid(where + "." + key + " 必须是整数");
        }
        long number = ((Number) value).longValue();
        if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
            throw invalid(where + "." + key + " 超出整数范围");
        }
        return (int) number;
    }

    private static String requiredString(JSONObject object, String key, String where)
            throws IOException {
        Object value = object.opt(key);
        if (!(value instanceof String)) {
            throw invalid(where + "." + key + " 必须是字符串");
        }
        return (String) value;
    }

    private static JSONArray requiredArray(JSONObject object, String key, String where)
            throws IOException {
        Object value = object.opt(key);
        if (!(value instanceof JSONArray)) {
            throw invalid(where + "." + key + " 必须是数组");
        }
        return (JSONArray) value;
    }

    private static JSONObject objectAt(JSONArray array, int index, String where)
            throws IOException {
        Object value = array.opt(index);
        if (!(value instanceof JSONObject)) {
            throw invalid(where + " 必须是对象");
        }
        return (JSONObject) value;
    }

    private static IOException invalid(String message) {
        return new IOException("更新数据无效: " + message);
    }

    private static IOException invalid(String message, Throwable cause) {
        IOException error = invalid(message);
        error.initCause(cause);
        return error;
    }

    private static String toHex(byte[] bytes) {
        char[] alphabet = "0123456789abcdef".toCharArray();
        char[] result = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            result[i * 2] = alphabet[value >>> 4];
            result[i * 2 + 1] = alphabet[value & 0x0f];
        }
        return new String(result);
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void closeQuietly(Response response) {
        try {
            response.close();
        } catch (IOException ignored) {
            // The response is no longer needed.
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best effort cleanup after a failed download.
        }
    }

    private static final class HttpsTransport implements Transport {
        @Override
        public Response get(String url) throws IOException {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setUseCaches(false);
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(READ_TIMEOUT_MS);
                connection.setRequestProperty("Accept",
                        "application/json, application/octet-stream;q=0.9");
                connection.setRequestProperty("Accept-Encoding", "identity");
                connection.setRequestProperty("User-Agent", "OWOrnate-UpdateClient/1");

                int status = connection.getResponseCode();
                String location = connection.getHeaderField("Location");
                long contentLength = parseContentLength(
                        connection.getHeaderField("Content-Length"));
                InputStream body = status == 200 ? connection.getInputStream()
                        : status >= 400 ? connection.getErrorStream() : null;
                return new Response(status, location, contentLength, body, connection);
            } catch (IOException | RuntimeException e) {
                if (connection != null) connection.disconnect();
                throw e;
            }
        }

        private static long parseContentLength(String value) {
            if (value == null) return -1;
            String trimmed = value.trim();
            if (trimmed.isEmpty()) return -1;
            try {
                long length = Long.parseLong(trimmed);
                return length >= 0 ? length : -1;
            } catch (NumberFormatException e) {
                return -1;
            }
        }
    }
}
