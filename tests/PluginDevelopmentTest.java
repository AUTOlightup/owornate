package dev.codex.watchworkshop;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Uses generated starter packages, the real runner, and fake Android commands. */
final class PluginDevelopmentTest {
    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]);
        Path v1 = root.resolve("v1/timeout-1.0.0.owo");
        Path v2 = root.resolve("v2/timeout-1.1.0.owo");
        PluginRunnerTest.root = Files.createDirectories(root.resolve("runtime"));
        PluginRunnerTest.archive = v1;
        PluginRunnerTest.bash = args[1];
        PluginRunnerTest.Fixture fixture = new PluginRunnerTest.Fixture();
        PluginRunnerTest.check(fixture.id.equals("dev.example.timeout"), "independent starter ID");
        PluginRunnerTest.check(!fixture.runner.read(fixture.id), "import starts disabled");
        fixture.runner.perform(fixture.id, "enable", null);
        PluginRunnerTest.check(fixture.value().equals("30000"), "v1 enable");
        fixture.runner.perform(fixture.id, "configure", PluginRunnerTest.timeout("60000"));
        PluginRunnerTest.check(fixture.value().equals("60000"), "v1 configure");
        fixture.runner.perform(fixture.id, "disable", null);
        PluginRunnerTest.check(fixture.value().equals("10000"), "v1 restores original");
        System.out.println("PASS generated starter import/enable/configure/disable via real script");

        byte[] catalog = Files.readAllBytes(root.resolve("v2/owornate-updates.json"));
        JSONArray entries = new JSONObject(new String(catalog, "UTF-8")).getJSONArray("releases");
        PluginRunnerTest.check(entries.length() == 1, "plugin-only catalog");
        String source = fixture.repository.load(fixture.id).updateUrl;
        String feedUrl = "https://github.com/example/plugins/releases/download/v1.1.0/owornate-updates.json";
        String packageUrl = entries.getJSONObject(0).getString("url");
        PluginRunnerTest.check(packageUrl.equals("https://github.com/example/plugins/releases/download/v1.1.0/timeout-1.1.0.owo"), "tag/name URL");
        Map<String, byte[]> responses = new HashMap<>();
        JSONObject releaseJson = new JSONObject().put("draft", false).put("prerelease", false)
                .put("assets", new JSONArray().put(new JSONObject()
                        .put("name", "owornate-updates.json").put("browser_download_url", feedUrl)));
        responses.put(source, new JSONArray().put(releaseJson).toString().getBytes("UTF-8"));
        responses.put(feedUrl, catalog);
        responses.put(packageUrl, Files.readAllBytes(v2));
        UpdateClient client = new UpdateClient(url -> {
            byte[] bytes = responses.get(url);
            if (bytes == null) throw new IOException("Unexpected URL: " + url);
            return new UpdateClient.Response(200, null, bytes.length, new ByteArrayInputStream(bytes));
        });
        UpdateClient.Release update = client.check(source, fixture.id, 1);
        PluginRunnerTest.check(update != null && update.versionCode == 2, "v2 discovered");
        Path download = root.resolve("download.owo");
        client.download(update, download.toFile(), 512 * 1024);
        PluginRunnerTest.check(Arrays.equals(Files.readAllBytes(v2), Files.readAllBytes(download)), "verified download bytes");
        PluginRunnerTest.check(client.check(source, fixture.id, 2) == null, "same version not offered");
        System.out.println("PASS generated plugin-only GitHub catalog/check/download (local transport)");

        PluginRepository.Pending candidate = fixture.repository.stage(Files.newInputStream(download));
        PluginRunnerTest.check(!fixture.runner.read(fixture.id), "old script disabled before upgrade");
        fixture.repository.upgrade(candidate, manifest ->
                PluginRunnerTest.check(!fixture.runner.read(manifest.id), "new script remains disabled"));
        PluginRunnerTest.check(fixture.repository.load(fixture.id).versionCode == 2, "v2 installed");
        PluginRunnerTest.check(fixture.repository.config(fixture.id).get("timeout").equals("60000"), "saved setting preserved");
        fixture.runner.perform(fixture.id, "enable", null);
        PluginRunnerTest.check(fixture.value().equals("60000"), "v2 uses retained setting");
        fixture.runner.perform(fixture.id, "uninstall", null);
        PluginRunnerTest.check(fixture.value().equals("10000") && fixture.repository.load(fixture.id) == null, "v2 uninstall restores original");

        PluginRunnerTest.Fixture defaults = new PluginRunnerTest.Fixture();
        PluginRepository.Pending next = defaults.repository.stage(Files.newInputStream(v2));
        defaults.repository.upgrade(next, manifest ->
                PluginRunnerTest.check(!defaults.runner.read(manifest.id), "default fixture remains disabled"));
        PluginRunnerTest.check(defaults.repository.config(defaults.id).get("timeout").equals("30000"), "old default preserved despite new default");
        System.out.println("PASS v1->v2 retains chosen/default settings and restores original on uninstall");
    }
}
