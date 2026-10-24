package dev.codex.watchworkshop;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Strict parser and value model for shell plugins and inert launcher payloads. */
final class PluginManifest {
    static final String SHELL_RUNTIME = "shell-v1";
    static final String LAUNCHER_RUNTIME = "launcher-v1";
    private static final Set<String> TOP_LEVEL_KEYS = keys(
            "schemaVersion", "runtime", "permissions", "device", "id", "name",
            "description", "versionName", "versionCode", "settings", "updateUrl", "nativeSettings");
    private static final Set<String> REQUIRED_TOP_LEVEL_KEYS = keys(
            "schemaVersion", "runtime", "permissions", "device", "id", "name",
            "description", "versionName", "versionCode", "settings");
    private static final Set<String> DEVICE_KEYS = keys("model", "firmware", "sdk", "abi");
    private static final Set<String> SETTING_KEYS = keys(
            "type", "key", "title", "default", "options");
    private static final Set<String> OPTION_KEYS = keys("value", "label");
    private static final Set<String> RESERVED_IDS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "monet", "widgets", "capture",
                    "dev.owornate.monet", "dev.owornate.widgets", "dev.owornate.capture")));
    private static final Pattern ID_PATTERN = Pattern.compile(
            "[a-z0-9](?:[a-z0-9_-]*[a-z0-9])?"
                    + "(?:\\.[a-z0-9](?:[a-z0-9_-]*[a-z0-9])?)+");
    private static final Pattern KEY_PATTERN = Pattern.compile("[a-z][a-z0-9_]{0,31}");
    private static final Pattern VALUE_PATTERN = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    final String runtime;
    final String id;
    final String name;
    final String description;
    final String versionName;
    final int versionCode;
    final String updateUrl;
    final List<Setting> settings;
    /** Host-backed preferences, separate from shell configuration in data/<id>. */
    final List<Setting> nativeSettings;

    static final class Setting {
        final String key;
        final String title;
        final String defaultValue;
        final Map<String, String> options;

        Setting(String key, String title, String defaultValue, Map<String, String> options) {
            this.key = key;
            this.title = title;
            this.defaultValue = defaultValue;
            this.options = options;
        }
    }

    private PluginManifest(String runtime, String id, String name, String description,
            String versionName, int versionCode, String updateUrl, List<Setting> settings,
            List<Setting> nativeSettings) {
        this.runtime = runtime;
        this.id = id;
        this.name = name;
        this.description = description;
        this.versionName = versionName;
        this.versionCode = versionCode;
        this.updateUrl = updateUrl;
        this.settings = settings;
        this.nativeSettings = nativeSettings;
    }

    static PluginManifest parse(String json) throws IOException {
        if (json == null) throw invalid("内容为空");

        final JSONObject object;
        try {
            object = new JSONObject(json);
        } catch (JSONException | IllegalArgumentException e) {
            throw invalid("JSON 无法解析", e);
        }

        requireKeys(object, REQUIRED_TOP_LEVEL_KEYS, TOP_LEVEL_KEYS, "顶层");
        requireInteger(object, "schemaVersion", 1);
        String runtime = string(object, "runtime");
        if (!SHELL_RUNTIME.equals(runtime) && !LAUNCHER_RUNTIME.equals(runtime)) {
            throw invalid("runtime 必须是 shell-v1 或 launcher-v1");
        }
        requirePermissions(object);
        requireDevice(object);

        String id = string(object, "id");
        if (SHELL_RUNTIME.equals(runtime)) {
            if (!isSafeId(id) || RESERVED_IDS.contains(id)) {
                throw invalid("id 不安全或已由内置模块占用");
            }
        } else if (!isLauncherId(id)) {
            throw invalid("launcher-v1 id 必须是安全的小写反向域名");
        }

        String name = string(object, "name");
        if (name.trim().isEmpty()) throw invalid("name 不能为空白");
        if (name.length() > 60) throw invalid("name 超过 60 个字符");

        String description = string(object, "description");
        if (description.trim().isEmpty()) throw invalid("description 不能为空白");
        if (description.length() > 800) throw invalid("description 超过 800 个字符");

        String versionName = string(object, "versionName");
        if (versionName.trim().isEmpty()) throw invalid("versionName 不能为空白");
        if (versionName.length() > 40) throw invalid("versionName 超过 40 个字符");

        int versionCode = integer(object, "versionCode");
        if (versionCode <= 0) throw invalid("versionCode 必须是正整数");

        String updateUrl = object.has("updateUrl") ? string(object, "updateUrl") : "";
        validateUpdateUrl(updateUrl);

        JSONArray settingsJson = array(object, "settings");
        if (settingsJson.length() > 12) throw invalid("settings 最多 12 项");
        ArrayList<Setting> settings = new ArrayList<>();
        Set<String> settingKeys = new HashSet<>();
        for (int i = 0; i < settingsJson.length(); i++) {
            JSONObject settingJson = objectAt(settingsJson, i, "setting");
            Setting setting = parseSetting(settingJson, i);
            if (!settingKeys.add(setting.key)) throw invalid("setting key 重复: " + setting.key);
            settings.add(setting);
        }
        if (LAUNCHER_RUNTIME.equals(runtime) && !settings.isEmpty()) {
            throw invalid("launcher-v1 settings 必须为空数组");
        }

        ArrayList<Setting> nativeSettings = new ArrayList<>();
        if (object.has("nativeSettings")) {
            if (!LAUNCHER_RUNTIME.equals(runtime) || !("dev.owornate.capture".equals(id)
                    || "dev.owornate.monet".equals(id))) {
                throw invalid("nativeSettings 目前仅支持 launcher-v1 捕获或莫奈插件");
            }
            JSONArray declared = array(object, "nativeSettings");
            boolean capture = "dev.owornate.capture".equals(id);
            Set<String> expected = capture ? keys("bitrate") : keys("mode", "corners");
            Set<String> found = new HashSet<>();
            for (int i = 0; i < declared.length(); i++) {
                Setting item = parseSetting(objectAt(declared, i, "nativeSetting"), i);
                if (!expected.contains(item.key) || !found.add(item.key)) {
                    throw invalid("nativeSettings 包含不支持或重复的设置: " + item.key);
                }
                Set<String> values = capture ? keys("600000", "1200000", "2400000")
                        : "mode".equals(item.key) ? keys("auto", "fixed", "original") : keys("true", "false");
                if (!item.options.keySet().equals(values)) {
                    throw invalid("nativeSettings 必须保留 " + item.key + " 的全部原生选项");
                }
                nativeSettings.add(item);
            }
            if (!found.equals(expected)) throw invalid("nativeSettings 缺少必需的原生设置");
        }
        return new PluginManifest(runtime, id, name, description, versionName, versionCode,
                updateUrl, Collections.unmodifiableList(settings), Collections.unmodifiableList(nativeSettings));
    }

    List<Setting> displaySettings() { return nativeSettings.isEmpty() ? settings : nativeSettings; }

    /** Official role name or third-party ID; empty for executable shell plugins. */
    String feature() {
        if (!LAUNCHER_RUNTIME.equals(runtime)) return "";
        String role = launcherFeature(id);
        return role.isEmpty() ? id : role;
    }

    boolean launcherExtension() {
        return LAUNCHER_RUNTIME.equals(runtime) && !isOfficialLauncherId(id);
    }

    Map<String, String> defaults() {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        for (Setting setting : settings) values.put(setting.key, setting.defaultValue);
        return Collections.unmodifiableMap(values);
    }

    /** Returns a fresh map in manifest setting order after applying defaults. */
    Map<String, String> validateConfig(Map<String, String> config) {
        Map<String, String> input = config == null ? Collections.<String, String>emptyMap() : config;
        Set<String> allowed = new HashSet<>();
        for (Setting setting : settings) allowed.add(setting.key);
        for (String key : input.keySet()) {
            if (key == null || !allowed.contains(key)) {
                throw new IllegalArgumentException("配置包含未知设置: " + key);
            }
        }

        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        for (Setting setting : settings) {
            String value = input.containsKey(setting.key)
                    ? input.get(setting.key) : setting.defaultValue;
            if (value == null || !setting.options.containsKey(value)) {
                throw new IllegalArgumentException("设置 " + setting.key + " 的值不受支持");
            }
            result.put(setting.key, value);
        }
        return result;
    }

    static boolean isSafeId(String id) {
        return id != null && id.length() <= 80 && ID_PATTERN.matcher(id).matches();
    }

    static boolean isLauncherId(String id) {
        return isSafeId(id) && !"monet.disabled".equals(id)
                && !"widgets.disabled".equals(id) && !"capture.disabled".equals(id);
    }

    static boolean isOfficialLauncherId(String id) {
        return !launcherFeature(id).isEmpty();
    }

    private static String launcherFeature(String id) {
        if ("dev.owornate.monet".equals(id)) return "monet";
        if ("dev.owornate.widgets".equals(id)) return "widgets";
        if ("dev.owornate.capture".equals(id)) return "capture";
        return "";
    }

    private static Setting parseSetting(JSONObject object, int index) throws IOException {
        requireKeys(object, SETTING_KEYS, "setting[" + index + "]");
        requireString(object, "type", "choice");

        String key = string(object, "key");
        if (!KEY_PATTERN.matcher(key).matches()) throw invalid("setting key 不合法: " + key);
        String title = string(object, "title");
        String defaultValue = string(object, "default");
        if (!VALUE_PATTERN.matcher(defaultValue).matches()) {
            throw invalid("setting default 不合法: " + key);
        }

        JSONArray optionArray = array(object, "options");
        if (optionArray.length() < 2 || optionArray.length() > 12) {
            throw invalid("setting " + key + " 必须包含 2 到 12 个选项");
        }
        LinkedHashMap<String, String> options = new LinkedHashMap<>();
        for (int i = 0; i < optionArray.length(); i++) {
            JSONObject option = objectAt(optionArray, i, "option");
            requireKeys(option, OPTION_KEYS, "setting " + key + " option[" + i + "]");
            String value = string(option, "value");
            String label = string(option, "label");
            if (!VALUE_PATTERN.matcher(value).matches()) {
                throw invalid("option value 不合法: " + value);
            }
            if (options.containsKey(value)) throw invalid("option value 重复: " + value);
            options.put(value, label);
        }
        if (!options.containsKey(defaultValue)) {
            throw invalid("setting " + key + " 的 default 不在 options 中");
        }
        return new Setting(key, title, defaultValue, Collections.unmodifiableMap(options));
    }

    private static void requirePermissions(JSONObject object) throws IOException {
        JSONArray permissions = array(object, "permissions");
        if (permissions.length() != 1 || !"root".equals(permissions.opt(0))) {
            throw invalid("permissions 必须精确为 [\"root\"]");
        }
    }

    private static void requireDevice(JSONObject object) throws IOException {
        JSONObject device;
        try {
            device = object.getJSONObject("device");
        } catch (JSONException e) {
            throw invalid("device 必须是对象", e);
        }
        requireKeys(device, DEVICE_KEYS, "device");
        requireString(device, "model", "OW20W1");
        requireString(device, "firmware", "OW20W1_11_A.92");
        requireInteger(device, "sdk", 27);
        requireString(device, "abi", "armeabi-v7a");
    }

    private static void requireInteger(JSONObject object, String key, int expected)
            throws IOException {
        if (integer(object, key) != expected) throw invalid(key + " 必须为 " + expected);
    }

    private static int integer(JSONObject object, String key) throws IOException {
        Object value = object.opt(key);
        if (!(value instanceof Integer) && !(value instanceof Long)) {
            throw invalid(key + " 必须是整数");
        }
        long number = ((Number) value).longValue();
        if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
            throw invalid(key + " 超出整数范围");
        }
        return (int) number;
    }

    private static void requireString(JSONObject object, String key, String expected)
            throws IOException {
        if (!expected.equals(string(object, key))) throw invalid(key + " 必须为 " + expected);
    }

    private static String string(JSONObject object, String key) throws IOException {
        Object value = object.opt(key);
        if (!(value instanceof String)) throw invalid(key + " 必须是字符串");
        return (String) value;
    }

    private static JSONArray array(JSONObject object, String key) throws IOException {
        Object value = object.opt(key);
        if (!(value instanceof JSONArray)) throw invalid(key + " 必须是数组");
        return (JSONArray) value;
    }

    private static JSONObject objectAt(JSONArray array, int index, String label)
            throws IOException {
        Object value = array.opt(index);
        if (!(value instanceof JSONObject)) throw invalid(label + " 必须是对象");
        return (JSONObject) value;
    }

    private static void requireKeys(JSONObject object, Set<String> expected, String label)
            throws IOException {
        requireKeys(object, expected, expected, label);
    }

    private static void requireKeys(JSONObject object, Set<String> required,
            Set<String> allowed, String label) throws IOException {
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!allowed.contains(key)) throw invalid(label + " 包含不支持的字段: " + key);
        }
        for (String key : required) {
            if (!object.has(key)) throw invalid(label + " 缺少字段: " + key);
        }
    }

    private static void validateUpdateUrl(String value) throws IOException {
        if (value.isEmpty()) return;
        if (value.length() > 2048) throw invalid("updateUrl 超过 2048 个字符");
        final URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            throw invalid("updateUrl 不是合法 URL", e);
        }
        if (!uri.isAbsolute() || uri.isOpaque()
                || !"https".equalsIgnoreCase(uri.getScheme())
                || uri.getRawAuthority() == null || uri.getHost() == null
                || uri.getHost().isEmpty()
                || uri.getRawUserInfo() != null || uri.getRawFragment() != null) {
            throw invalid("updateUrl 必须是无用户信息和 fragment 的 HTTPS URL");
        }
    }

    private static Set<String> keys(String... values) {
        Set<String> result = new HashSet<>(Arrays.asList(values));
        return Collections.unmodifiableSet(result);
    }

    private static IOException invalid(String message) {
        return new IOException("插件清单无效: " + message);
    }

    private static IOException invalid(String message, Throwable cause) {
        IOException error = invalid(message);
        error.initCause(cause);
        return error;
    }
}
