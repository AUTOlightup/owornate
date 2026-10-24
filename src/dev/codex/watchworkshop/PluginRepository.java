package dev.codex.watchworkshop;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONException;
import org.json.JSONObject;

/** Called after the candidate package is installed and before the upgrade is committed. */
interface UpgradeVerifier {
    void verify(PluginManifest manifest) throws Exception;
}

/** File-backed repository for reviewed shell plugins and inert launcher payloads. */
final class PluginRepository {
    private static final Set<Path> ACTIVE_UPGRADES = new HashSet<>();
    private static final String INSTALLED = "installed";
    private static final String DATA = "data";
    private static final String PENDING = "pending";
    private static final String UPGRADE = "upgrade";
    private static final String UPGRADE_STATE = "state";
    private static final String UPGRADE_BACKUP = "package";
    private static final String PREPARED = "prepared";
    private static final String COMMITTED = "committed";
    private static final String MANIFEST = "plugin.json";
    private static final String SCRIPT = "plugin.sh";
    private static final String HOOK = "hook.js";
    private static final String THEME = "theme.jar";
    private static final String CONFIG = "config.json";

    private static final int MAX_ARCHIVE = 512 * 1024;
    private static final int MAX_UNPACKED = 256 * 1024;
    private static final int MAX_LAUNCHER_ARCHIVE = 2 * 1024 * 1024;
    private static final int MAX_LAUNCHER_UNPACKED = 2 * 1024 * 1024;
    private static final int MAX_MANIFEST = 32 * 1024;
    private static final int MAX_CONFIG = 128 * 1024;
    private static final Pattern PENDING_TOKEN = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private final Path root;
    private boolean upgradeInProgress;

    static final class Pending {
        final String token;
        final PluginManifest manifest;

        Pending(String token, PluginManifest manifest) {
            this.token = token;
            this.manifest = manifest;
        }
    }

    PluginRepository(File root) {
        if (root == null) throw new IllegalArgumentException("插件根目录不能为空");
        this.root = root.getAbsoluteFile().toPath().normalize();
    }

    /** Reads and closes the supplied archive after validating all package files. */
    synchronized Pending stage(InputStream input) throws IOException {
        if (input == null) throw new IOException("插件 ZIP 输入不能为空");
        recoverInterruptedUpgrades();
        byte[] archive;
        try (InputStream closing = input) {
            archive = readLimited(closing, MAX_LAUNCHER_ARCHIVE, "插件 ZIP");
        }

        StagedPackage staged = readPackageBytes(archive);
        ensureRoot();
        Path pendingRoot = ensureArea(PENDING);
        String token = UUID.randomUUID().toString();
        Path stagedDir = safeChild(pendingRoot, token, "pending token");
        Files.createDirectory(stagedDir);
        boolean complete = false;
        try {
            writeNew(stagedDir.resolve(MANIFEST), staged.manifestBytes);
            if (staged.scriptBytes != null) writeNew(stagedDir.resolve(SCRIPT), staged.scriptBytes);
            if (staged.hookBytes != null) writeNew(stagedDir.resolve(HOOK), staged.hookBytes);
            if (staged.themeBytes != null) writeNew(stagedDir.resolve(THEME), staged.themeBytes);
            complete = true;
            return new Pending(token, staged.manifest);
        } finally {
            if (!complete) deleteTreeQuietly(stagedDir);
        }
    }

    synchronized Pending pending(String token) {
        if (!isPendingToken(token)) return null;
        try {
            ensureRoot();
            recoverInterruptedUpgrades();
            Path pendingRoot = ensureArea(PENDING);
            Path dir = safeChild(pendingRoot, token, "pending token");
            if (!exists(dir)) return null;
            StagedPackage staged = readStagedDirectory(dir);
            return new Pending(token, staged.manifest);
        } catch (IOException e) {
            throw storageFailure("读取待审核插件失败", e);
        }
    }

    synchronized void cancel(Pending pending) {
        if (pending == null || !isPendingToken(pending.token)) return;
        try {
            ensureRoot();
            recoverInterruptedUpgrades();
            Path pendingRoot = ensureArea(PENDING);
            Path dir = safeChild(pendingRoot, pending.token, "pending token");
            deleteTree(dir);
        } catch (IOException e) {
            throw storageFailure("取消待审核插件失败", e);
        }
    }

    synchronized PluginManifest install(Pending pending) {
        if (pending == null || !isPendingToken(pending.token)) {
            throw new IllegalArgumentException("待审核插件无效");
        }
        try {
            ensureRoot();
            recoverInterruptedUpgrades();
            Path pendingRoot = ensureArea(PENDING);
            Path stagedDir = safeChild(pendingRoot, pending.token, "pending token");
            StagedPackage staged = readStagedDirectory(stagedDir);
            String id = staged.manifest.id;

            Path installedRoot = ensureArea(INSTALLED);
            Path target = safeChild(installedRoot, id, "plugin id");
            if (exists(target)) {
                throw new IllegalStateException("同 id 插件已安装，不能覆盖: " + id);
            }

            try {
                Files.move(stagedDir, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                throw new IOException("文件系统不支持同卷原子注册", e);
            }
            return staged.manifest;
        } catch (IOException e) {
            throw storageFailure("注册插件失败", e);
        }
    }

    /** Read-only upgrade preflight. Returns the currently installed manifest. */
    synchronized PluginManifest upgradeFrom(Pending pending) {
        requirePending(pending);
        try {
            return inspectUpgrade(pending).current;
        } catch (IOException e) {
            throw storageFailure("预检插件升级失败", e);
        }
    }

    /**
     * Replaces an installed package with a strictly newer staged package. The verifier runs
     * while the new package is readable and before the package backup is removed.
     * Only the package directory is rolled back; the verifier must observe rather than mutate
     * root-level device state.
     */
    synchronized PluginManifest upgrade(Pending pending, UpgradeVerifier verifier)
            throws Exception {
        requirePending(pending);
        if (verifier == null) throw new IllegalArgumentException("升级验证器不能为空");

        try {
            ensureRoot();
            recoverInterruptedUpgrades();
        } catch (IOException e) {
            throw storageFailure("准备插件升级失败", e);
        }

        final Path activeRoot;
        try {
            activeRoot = beginUpgrade();
        } catch (IOException e) {
            throw storageFailure("登记插件升级失败", e);
        }
        if (activeRoot == null) {
            throw new IllegalStateException("同一插件仓库已有升级正在进行");
        }
        upgradeInProgress = true;
        try {
            final UpgradeAttempt attempt;
            try {
                attempt = prepareUpgrade(pending);
            } catch (IOException e) {
                throw storageFailure("替换插件失败", e);
            }

            try {
                verifier.verify(attempt.manifest);
            } catch (Exception failure) {
                try {
                    rollbackPreparedUpgrade(attempt.transaction, attempt.target, attempt.backup);
                } catch (Exception rollbackFailure) {
                    rollbackFailure.addSuppressed(failure);
                    throw rollbackFailure;
                }
                throw failure;
            }

            try {
                writeUpgradeState(attempt.transaction, COMMITTED);
            } catch (IOException commitFailure) {
                try {
                    rollbackPreparedUpgrade(attempt.transaction, attempt.target,
                            attempt.backup);
                } catch (IOException rollbackFailure) {
                    commitFailure.addSuppressed(rollbackFailure);
                }
                throw storageFailure("提交插件升级失败", commitFailure);
            }
            cleanupCommittedUpgrade(attempt.transaction, attempt.backup);
            return attempt.manifest;
        } finally {
            upgradeInProgress = false;
            endUpgrade(activeRoot);
        }
    }

    synchronized List<PluginManifest> list() {
        try {
            ensureRoot();
            recoverInterruptedUpgrades();
            Path installedRoot = ensureArea(INSTALLED);
            ArrayList<String> ids = new ArrayList<>();
            try (DirectoryStream<Path> children = Files.newDirectoryStream(installedRoot)) {
                for (Path child : children) {
                    if (Files.isSymbolicLink(child) || !Files.isDirectory(child,
                            LinkOption.NOFOLLOW_LINKS)) continue;
                    String id = child.getFileName().toString();
                    if (PluginManifest.isSafeId(id)) ids.add(id);
                }
            }
            Collections.sort(ids, Comparator.naturalOrder());
            ArrayList<PluginManifest> manifests = new ArrayList<>();
            for (String id : ids) {
                PluginManifest manifest = readInstalled(id);
                if (manifest != null) manifests.add(manifest);
            }
            return Collections.unmodifiableList(manifests);
        } catch (IOException e) {
            throw storageFailure("读取插件列表失败", e);
        }
    }

    synchronized PluginManifest load(String id) {
        requireId(id);
        try {
            ensureRoot();
            recoverInterruptedUpgrades();
            return readInstalled(id);
        } catch (IOException e) {
            throw storageFailure("读取插件失败", e);
        }
    }

    File packageDir(String id) {
        requireId(id);
        return root.resolve(INSTALLED).resolve(id).toFile();
    }

    synchronized File dataDir(String id) {
        requireId(id);
        try {
            ensureRoot();
            recoverInterruptedUpgrades();
            Path dataRoot = ensureArea(DATA);
            Path dir = safeChild(dataRoot, id, "plugin id");
            if (exists(dir)) requireRealDirectory(dir);
            else Files.createDirectory(dir);
            return dir.toFile();
        } catch (IOException e) {
            throw storageFailure("创建插件数据目录失败", e);
        }
    }

    /** Returns one declared payload byte-for-byte; loading a package never executes it. */
    synchronized byte[] payload(String id, String name) {
        requireId(id);
        if (name == null) throw new IllegalArgumentException("插件载荷文件名不能为空");
        try {
            ensureRoot();
            recoverInterruptedUpgrades();
            PluginManifest manifest = readInstalled(id);
            if (manifest == null) throw new IllegalStateException("插件未安装: " + id);
            Set<String> allowed = payloadFiles(manifest);
            if (!allowed.contains(name)) {
                throw new IOException("插件载荷文件名无效: " + name);
            }
            Path dir = safeChild(ensureArea(INSTALLED), id, "plugin id");
            Path file = dir.resolve(name);
            requireRegularFile(file);
            int limit = PluginManifest.LAUNCHER_RUNTIME.equals(manifest.runtime)
                    ? MAX_LAUNCHER_UNPACKED : MAX_UNPACKED;
            return readLimited(file, limit, "插件载荷");
        } catch (IOException e) {
            throw storageFailure("读取插件载荷失败", e);
        }
    }

    synchronized Map<String, String> config(String id) {
        requireId(id);
        try {
            ensureRoot();
            recoverInterruptedUpgrades();
            PluginManifest manifest = readInstalled(id);
            if (manifest == null) throw new IllegalStateException("插件未安装: " + id);
            return readConfigAt(ensureArea(DATA), id, manifest);
        } catch (IOException e) {
            throw storageFailure("读取插件配置失败", e);
        }
    }

    synchronized void saveConfig(String id, Map<String, String> values) {
        requireId(id);
        try {
            ensureRoot();
            recoverInterruptedUpgrades();
            PluginManifest manifest = readInstalled(id);
            if (manifest == null) throw new IllegalStateException("插件未安装: " + id);
            Map<String, String> validated = manifest.validateConfig(values);

            Path dataRoot = ensureArea(DATA);
            Path dir = safeChild(dataRoot, id, "plugin id");
            if (exists(dir)) requireRealDirectory(dir);
            else Files.createDirectory(dir);

            Path file = dir.resolve(CONFIG);
            if (exists(file)) requireRegularFile(file);
            writeConfigValues(file, validated);
        } catch (IOException e) {
            throw storageFailure("保存插件配置失败", e);
        }
    }

    synchronized void remove(String id) {
        requireId(id);
        try {
            ensureRoot();
            recoverInterruptedUpgrades();
            Path installedRoot = ensureArea(INSTALLED);
            Path target = safeChild(installedRoot, id, "plugin id");
            deleteTree(target);
        } catch (IOException e) {
            throw storageFailure("移除插件失败", e);
        }
    }

    private UpgradeInspection inspectUpgrade(Pending pending) throws IOException {
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("插件仓库目录不存在或不安全");
        }
        Path pendingRoot = safeChild(root, PENDING, "pending area");
        Path pendingDir = safeChild(pendingRoot, pending.token, "pending token");
        StagedPackage staged = readStagedDirectory(pendingDir);

        Path installedRoot = safeChild(root, INSTALLED, "installed area");
        if (!exists(installedRoot)) {
            throw new IllegalStateException("插件未安装，不能升级: " + staged.manifest.id);
        }
        requireRealDirectory(installedRoot);
        PluginManifest current = readInstalledAt(installedRoot, staged.manifest.id);
        if (current == null) {
            throw new IllegalStateException("插件未安装，不能升级: " + staged.manifest.id);
        }

        Path dataRoot = safeChild(root, DATA, "data area");
        Map<String, String> values = readConfigAt(dataRoot, current.id, current);
        requireUpgradeCompatible(current, staged.manifest, values);
        return new UpgradeInspection(pendingDir, installedRoot, staged, current);
    }

    private UpgradeAttempt prepareUpgrade(Pending pending) throws IOException {
        UpgradeInspection inspection = inspectUpgrade(pending);
        PluginManifest manifest = inspection.staged.manifest;
        Path dataRoot = safeChild(root, DATA, "data area");
        persistEffectiveConfig(dataRoot, manifest.id, inspection.current);
        Path upgradeRoot = ensureArea(UPGRADE);
        Path transaction = safeChild(upgradeRoot, manifest.id, "plugin id");
        if (exists(transaction)) throw new IOException("升级事务路径已存在");
        Files.createDirectory(transaction);

        Path backup = safeChild(transaction, UPGRADE_BACKUP, "upgrade backup");
        Path target = safeChild(inspection.installedRoot, manifest.id, "plugin id");
        try {
            writeUpgradeState(transaction, PREPARED);
            moveAtomically(target, backup);
            moveAtomically(inspection.pendingDir, target);
            return new UpgradeAttempt(manifest, transaction, target, backup);
        } catch (IOException failure) {
            try {
                rollbackPreparedUpgrade(transaction, target, backup);
            } catch (IOException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        }
    }

    private static void requireUpgradeCompatible(PluginManifest current,
            PluginManifest candidate, Map<String, String> currentConfig) {
        if (!current.id.equals(candidate.id)) {
            throw new IllegalStateException("升级包 id 与已安装插件不一致");
        }
        if (!current.runtime.equals(candidate.runtime)) {
            throw new IllegalStateException("升级包 runtime 与已安装插件不一致");
        }
        if (candidate.versionCode <= current.versionCode) {
            throw new IllegalStateException("升级包 versionCode 必须严格大于已安装版本");
        }
        try {
            candidate.validateConfig(currentConfig);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("现有插件配置与升级包不兼容", e);
        }
    }

    private Map<String, String> readConfigAt(Path dataRoot, String id, PluginManifest manifest)
            throws IOException {
        Path dir = safeChild(dataRoot, id, "plugin id");
        if (!exists(dir)) return manifest.defaults();
        requireRealDirectory(dir);
        Path file = dir.resolve(CONFIG);
        if (!exists(file)) return manifest.defaults();
        requireRegularFile(file);

        return manifest.validateConfig(readStoredConfig(file));
    }

    /** Persists effective old-manifest values before replacement only when keys are absent. */
    private void persistEffectiveConfig(Path dataRoot, String id, PluginManifest manifest)
            throws IOException {
        if (manifest.settings.isEmpty()) return;

        Path dir = safeChild(dataRoot, id, "plugin id");
        Path file = dir.resolve(CONFIG);
        if (!exists(dir) || !exists(file)) {
            if (!exists(dataRoot)) Files.createDirectory(dataRoot);
            requireRealDirectory(dataRoot);
            if (!exists(dir)) Files.createDirectory(dir);
            requireRealDirectory(dir);
            writeConfigValues(file, manifest.defaults());
            return;
        }

        requireRealDirectory(dir);
        requireRegularFile(file);
        LinkedHashMap<String, String> stored = readStoredConfig(file);
        Map<String, String> effective = manifest.validateConfig(stored);
        for (PluginManifest.Setting setting : manifest.settings) {
            if (!stored.containsKey(setting.key)) {
                writeConfigValues(file, effective);
                return;
            }
        }
    }

    private static LinkedHashMap<String, String> readStoredConfig(Path file) throws IOException {

        String text = decodeUtf8(readLimited(file, MAX_CONFIG, "插件配置"), "插件配置");
        final JSONObject object;
        try {
            object = new JSONObject(text);
        } catch (JSONException e) {
            throw new IllegalArgumentException("插件配置 JSON 无效", e);
        }
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = object.opt(key);
            if (!(value instanceof String)) {
                throw new IllegalArgumentException("插件配置值必须是字符串: " + key);
            }
            values.put(key, (String) value);
        }
        return values;
    }

    private static void writeConfigValues(Path file, Map<String, String> values)
            throws IOException {
        final byte[] bytes;
        try {
            JSONObject object = new JSONObject();
            for (Map.Entry<String, String> entry : values.entrySet()) {
                object.put(entry.getKey(), entry.getValue());
            }
            bytes = (object.toString(2) + "\n").getBytes(StandardCharsets.UTF_8);
        } catch (JSONException e) {
            throw new IOException("无法编码插件配置", e);
        }
        Path dir = file.getParent();
        Path temporary = Files.createTempFile(dir, ".config-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void recoverInterruptedUpgrades() throws IOException {
        if (upgradeInProgress || !exists(root)) return;
        Path canonical = canonicalRoot();
        synchronized (ACTIVE_UPGRADES) {
            if (ACTIVE_UPGRADES.contains(canonical)) return;

            Path upgradeRoot = safeChild(root, UPGRADE, "upgrade area");
            if (!exists(upgradeRoot)) return;
            requireRealDirectory(upgradeRoot);
            ArrayList<Path> transactions = new ArrayList<>();
            try (DirectoryStream<Path> children = Files.newDirectoryStream(upgradeRoot)) {
                for (Path child : children) transactions.add(child);
            }
            Collections.sort(transactions);
            for (Path transaction : transactions) {
                if (Files.isSymbolicLink(transaction)
                        || !Files.isDirectory(transaction, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("升级事务路径不安全: " + transaction);
                }
                String id = transaction.getFileName().toString();
                if (!PluginManifest.isSafeId(id)) {
                    throw new IOException("升级事务 id 不安全: " + id);
                }
                recoverUpgrade(transaction, id);
            }
        }
    }

    private Path beginUpgrade() throws IOException {
        Path canonical = canonicalRoot();
        synchronized (ACTIVE_UPGRADES) {
            if (!ACTIVE_UPGRADES.add(canonical)) return null;
            return canonical;
        }
    }

    private static void endUpgrade(Path canonical) {
        if (canonical == null) return;
        synchronized (ACTIVE_UPGRADES) {
            ACTIVE_UPGRADES.remove(canonical);
        }
    }

    private Path canonicalRoot() throws IOException {
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("插件仓库目录不存在或不安全");
        }
        return root.toRealPath(LinkOption.NOFOLLOW_LINKS).normalize();
    }

    private void recoverUpgrade(Path transaction, String id) throws IOException {
        Path state = safeChild(transaction, UPGRADE_STATE, "upgrade state");
        Path backup = safeChild(transaction, UPGRADE_BACKUP, "upgrade backup");
        Path target = safeChild(ensureArea(INSTALLED), id, "plugin id");
        String phase = readUpgradeState(state);

        if (COMMITTED.equals(phase)) {
            if (!exists(target)) {
                if (!exists(backup)) {
                    throw new IOException("已提交升级缺少新的或旧插件包: " + id);
                }
                requireRealDirectory(backup);
                moveAtomically(backup, target);
            }
            cleanupCommittedUpgrade(transaction, backup);
            return;
        }

        if (exists(backup)) {
            requireRealDirectory(backup);
            if (exists(target)) deleteTree(target);
            moveAtomically(backup, target);
        }
        deleteTree(transaction);
    }

    private static String readUpgradeState(Path state) throws IOException {
        if (!exists(state)) return "";
        requireRegularFile(state);
        return decodeUtf8(readLimited(state, 64, "升级状态"), "升级状态").trim();
    }

    private static void writeUpgradeState(Path transaction, String value) throws IOException {
        byte[] bytes = (value + "\n").getBytes(StandardCharsets.UTF_8);
        Path state = transaction.resolve(UPGRADE_STATE);
        Path temporary = Files.createTempFile(transaction, ".state-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try {
                Files.move(temporary, state, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, state, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void rollbackPreparedUpgrade(Path transaction, Path target, Path backup)
            throws IOException {
        if (exists(backup)) {
            requireRealDirectory(backup);
            if (exists(target)) deleteTree(target);
            moveAtomically(backup, target);
        }
        deleteTree(transaction);
    }

    private static void cleanupCommittedUpgrade(Path transaction, Path backup) {
        try {
            if (exists(backup)) deleteTree(backup);
            Files.deleteIfExists(transaction.resolve(UPGRADE_STATE));
            Files.deleteIfExists(transaction);
        } catch (IOException ignored) {
            // A committed transaction is cleaned by the next repository operation.
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            throw new IOException("文件系统不支持同卷原子替换", e);
        }
    }

    private PluginManifest readInstalled(String id) throws IOException {
        Path installedRoot = ensureArea(INSTALLED);
        return readInstalledAt(installedRoot, id);
    }

    private PluginManifest readInstalledAt(Path installedRoot, String id) throws IOException {
        Path dir = safeChild(installedRoot, id, "plugin id");
        if (!exists(dir)) return null;
        requireRealDirectory(dir);
        Path manifestFile = dir.resolve(MANIFEST);
        requireRegularFile(manifestFile);
        String json = decodeUtf8(readLimited(manifestFile, MAX_MANIFEST, "插件清单"), "插件清单");
        PluginManifest manifest = PluginManifest.parse(json);
        if (!id.equals(manifest.id)) throw new IOException("插件目录与清单 id 不一致");
        return manifest;
    }

    private StagedPackage readStagedDirectory(Path dir) throws IOException {
        if (!exists(dir)) throw new IOException("待审核插件不存在");
        requireRealDirectory(dir);
        Set<String> names = new HashSet<>();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(dir)) {
            for (Path child : children) names.add(child.getFileName().toString());
        }
        Path manifestFile = dir.resolve(MANIFEST);
        requireRegularFile(manifestFile);
        byte[] manifestBytes = readLimited(manifestFile, MAX_MANIFEST, "插件清单");
        PluginManifest manifest = parseManifest(manifestBytes);
        Set<String> expected = packageFiles(manifest);
        if (!names.equals(expected)) {
            throw new IOException("待审核插件文件不符合 " + manifest.runtime + " 协议");
        }
        byte[] scriptBytes = null;
        byte[] hookBytes = null;
        byte[] themeBytes = null;
        if (names.contains(SCRIPT)) {
            Path file = dir.resolve(SCRIPT);
            requireRegularFile(file);
            scriptBytes = readLimited(file, MAX_UNPACKED, "插件脚本");
        }
        if (names.contains(HOOK)) {
            Path file = dir.resolve(HOOK);
            requireRegularFile(file);
            hookBytes = readLimited(file, MAX_LAUNCHER_UNPACKED, "hook.js");
        }
        if (names.contains(THEME)) {
            Path file = dir.resolve(THEME);
            requireRegularFile(file);
            themeBytes = readLimited(file, MAX_LAUNCHER_UNPACKED, "theme.jar");
        }
        return validatePackageBytes(manifestBytes, scriptBytes, hookBytes, themeBytes, names, -1);
    }

    private static StagedPackage readPackageBytes(byte[] archive) throws IOException {
        if (!hasCompleteEndRecord(archive)) throw new IOException("插件 ZIP 不完整或已截断");

        byte[] manifestBytes = null;
        byte[] scriptBytes = null;
        byte[] hookBytes = null;
        byte[] themeBytes = null;
        Set<String> names = new HashSet<>();
        int total = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory() || name.endsWith("/")) {
                    throw new IOException("插件 ZIP 不允许目录项: " + name);
                }
                if (!isPackageFileName(name)) {
                    throw new IOException("插件 ZIP 包含路径不安全的额外项: " + name);
                }
                if (!names.add(name)) throw new IOException("插件 ZIP 重复包含 " + name);
                byte[] bytes = readZipEntry(zip, MAX_LAUNCHER_UNPACKED - total, name);
                if (MANIFEST.equals(name)) manifestBytes = bytes;
                else if (SCRIPT.equals(name)) scriptBytes = bytes;
                else if (HOOK.equals(name)) hookBytes = bytes;
                else themeBytes = bytes;
                total += bytes.length;
                zip.closeEntry();
            }
        } catch (IllegalArgumentException e) {
            throw new IOException("插件 ZIP 格式无效", e);
        }

        if (manifestBytes == null) throw new IOException("插件 ZIP 缺少 plugin.json");
        return validatePackageBytes(manifestBytes, scriptBytes, hookBytes, themeBytes, names,
                archive.length);
    }

    private static StagedPackage validatePackageBytes(byte[] manifestBytes, byte[] scriptBytes,
            byte[] hookBytes, byte[] themeBytes, Set<String> names, int archiveSize)
            throws IOException {
        if (manifestBytes == null) throw new IOException("插件缺少 plugin.json");
        if (manifestBytes.length > MAX_MANIFEST) throw new IOException("plugin.json 超过 32KiB");
        PluginManifest manifest = parseManifest(manifestBytes);
        if (!names.equals(packageFiles(manifest))) {
            throw new IOException("插件文件不符合 " + manifest.runtime + " 协议");
        }
        long total = manifestBytes.length;
        if (scriptBytes != null) total += scriptBytes.length;
        if (hookBytes != null) total += hookBytes.length;
        if (themeBytes != null) total += themeBytes.length;

        if (PluginManifest.SHELL_RUNTIME.equals(manifest.runtime)) {
            if (archiveSize >= 0 && archiveSize > MAX_ARCHIVE) {
                throw new IOException("插件 ZIP 超过 512KiB");
            }
            if (total > MAX_UNPACKED) throw new IOException("解压总大小超过 256KiB");
            validateShellScript(scriptBytes);
        } else {
            if (archiveSize >= 0 && archiveSize > MAX_LAUNCHER_ARCHIVE) {
                throw new IOException("插件 ZIP 超过 2MiB");
            }
            if (total > MAX_LAUNCHER_UNPACKED) throw new IOException("解压总大小超过 2MiB");
            validateHook(hookBytes);
            if (themeBytes != null && themeBytes.length == 0) {
                throw new IOException("theme.jar 不能为空");
            }
        }
        return new StagedPackage(manifest, manifestBytes, scriptBytes, hookBytes, themeBytes);
    }

    private static void validateShellScript(byte[] scriptBytes) throws IOException {
        if (scriptBytes == null) throw new IOException("插件 ZIP 缺少 plugin.sh");
        String script = decodeUtf8(scriptBytes, "plugin.sh");
        if (script.indexOf('\0') >= 0) throw new IOException("插件文件不能包含 NUL");
        if (script.isEmpty()) throw new IOException("plugin.sh 不能为空");
        if (script.charAt(0) == '\uFEFF') throw new IOException("plugin.sh 不能包含 UTF-8 BOM");
        if (script.indexOf('\r') >= 0) throw new IOException("plugin.sh 必须使用 LF 换行");
    }

    private static void validateHook(byte[] hookBytes) throws IOException {
        if (hookBytes == null) throw new IOException("插件缺少 hook.js");
        String hook = decodeUtf8(hookBytes, "hook.js");
        if (hook.isEmpty()) throw new IOException("hook.js 不能为空");
        if (hook.charAt(0) == '\uFEFF') throw new IOException("hook.js 不能包含 UTF-8 BOM");
        if (hook.indexOf('\0') >= 0) throw new IOException("hook.js 不能包含 NUL");
        if (hook.indexOf('\r') >= 0) throw new IOException("hook.js 必须使用 LF 换行");
    }

    private static PluginManifest parseManifest(byte[] manifestBytes) throws IOException {
        if (manifestBytes == null) throw new IOException("插件清单不存在");
        if (manifestBytes.length > MAX_MANIFEST) throw new IOException("plugin.json 超过 32KiB");
        String text = decodeUtf8(manifestBytes, "plugin.json");
        if (text.indexOf('\0') >= 0) throw new IOException("插件文件不能包含 NUL");
        return PluginManifest.parse(text);
    }

    private static boolean isPackageFileName(String name) {
        return MANIFEST.equals(name) || SCRIPT.equals(name) || HOOK.equals(name)
                || THEME.equals(name);
    }

    private static Set<String> packageFiles(PluginManifest manifest) {
        HashSet<String> names = new HashSet<>();
        names.add(MANIFEST);
        if (PluginManifest.SHELL_RUNTIME.equals(manifest.runtime)) names.add(SCRIPT);
        else {
            names.add(HOOK);
            if ("monet".equals(manifest.feature())) names.add(THEME);
        }
        return names;
    }

    private static Set<String> payloadFiles(PluginManifest manifest) {
        HashSet<String> names = new HashSet<>();
        if (PluginManifest.SHELL_RUNTIME.equals(manifest.runtime)) {
            names.add(SCRIPT);
        } else {
            names.add(HOOK);
            if ("monet".equals(manifest.feature())) names.add(THEME);
        }
        return names;
    }

    private static byte[] readZipEntry(ZipInputStream zip, int limit, String label)
            throws IOException {
        if (limit < 0) throw new IOException("解压总大小超过限制");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = zip.read(buffer)) != -1) {
            if (out.size() + count > limit) {
                throw new IOException(label + " 实际解压大小超过限制");
            }
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }

    private static boolean hasCompleteEndRecord(byte[] data) {
        if (data.length < 22) return false;
        int minimum = Math.max(0, data.length - 22 - 0xffff);
        for (int i = data.length - 22; i >= minimum; i--) {
            if (data[i] == 0x50 && data[i + 1] == 0x4b
                    && data[i + 2] == 0x05 && data[i + 3] == 0x06) {
                int comment = (data[i + 20] & 0xff) | ((data[i + 21] & 0xff) << 8);
                if (i + 22 + comment == data.length) return true;
            }
        }
        return false;
    }

    private void ensureRoot() throws IOException {
        if (Files.isSymbolicLink(root)) throw new IOException("插件根目录不能是符号链接");
        if (exists(root)) {
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("插件根路径不是目录");
            }
        } else {
            Files.createDirectories(root);
            if (Files.isSymbolicLink(root) || !Files.isDirectory(root,
                    LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("无法创建安全的插件根目录");
            }
        }
    }

    private Path ensureArea(String name) throws IOException {
        ensureRoot();
        Path area = safeChild(root, name, "repository area");
        if (exists(area)) {
            requireRealDirectory(area);
        } else {
            Files.createDirectory(area);
            requireRealDirectory(area);
        }
        return area;
    }

    private static void requireRealDirectory(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path,
                LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("拒绝使用符号链接或非目录路径: " + path);
        }
    }

    private static void requireRegularFile(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path,
                LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("文件不存在或不是普通文件: " + path);
        }
    }

    private Path safeChild(Path parent, String name, String label) throws IOException {
        Path child = parent.resolve(name).normalize();
        if (!parent.equals(child.getParent()) || !child.startsWith(root)) {
            throw new IOException(label + " 越出插件仓库根目录");
        }
        return child;
    }

    private void requireId(String id) {
        if (!PluginManifest.isSafeId(id)) throw new IllegalArgumentException("插件 id 不合法: " + id);
    }

    private static void requirePending(Pending pending) {
        if (pending == null || !isPendingToken(pending.token)) {
            throw new IllegalArgumentException("待审核插件无效");
        }
    }

    private static boolean isPendingToken(String token) {
        return token != null && PENDING_TOKEN.matcher(token).matches();
    }

    private static boolean exists(Path path) {
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS);
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

    private static byte[] readLimited(InputStream input, int limit, String label)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (out.size() + count > limit) throw new IOException(label + " 超过大小限制");
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }

    private static byte[] readLimited(Path file, int limit, String label) throws IOException {
        try (InputStream input = Files.newInputStream(file, StandardOpenOption.READ)) {
            return readLimited(input, limit, label);
        }
    }

    private static void writeNew(Path file, byte[] bytes) throws IOException {
        Files.write(file, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private static void deleteTree(Path path) throws IOException {
        if (!exists(path)) return;
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path,
                LinkOption.NOFOLLOW_LINKS)) {
            Files.delete(path);
            return;
        }
        try (DirectoryStream<Path> children = Files.newDirectoryStream(path)) {
            for (Path child : children) deleteTree(child);
        }
        Files.delete(path);
    }

    private static void deleteTreeQuietly(Path path) {
        try {
            deleteTree(path);
        } catch (IOException ignored) {
            // Best effort cleanup after a staging write failure.
        }
    }

    private static IllegalStateException storageFailure(String message, IOException cause) {
        IllegalStateException error = new IllegalStateException(message + ": " + cause.getMessage());
        error.initCause(cause);
        return error;
    }

    private static final class UpgradeInspection {
        final Path pendingDir;
        final Path installedRoot;
        final StagedPackage staged;
        final PluginManifest current;

        UpgradeInspection(Path pendingDir, Path installedRoot, StagedPackage staged,
                PluginManifest current) {
            this.pendingDir = pendingDir;
            this.installedRoot = installedRoot;
            this.staged = staged;
            this.current = current;
        }
    }

    private static final class UpgradeAttempt {
        final PluginManifest manifest;
        final Path transaction;
        final Path target;
        final Path backup;

        UpgradeAttempt(PluginManifest manifest, Path transaction, Path target, Path backup) {
            this.manifest = manifest;
            this.transaction = transaction;
            this.target = target;
            this.backup = backup;
        }
    }

    private static final class StagedPackage {
        final PluginManifest manifest;
        final byte[] manifestBytes;
        final byte[] scriptBytes;
        final byte[] hookBytes;
        final byte[] themeBytes;

        StagedPackage(PluginManifest manifest, byte[] manifestBytes, byte[] scriptBytes,
                byte[] hookBytes, byte[] themeBytes) {
            this.manifest = manifest;
            this.manifestBytes = manifestBytes;
            this.scriptBytes = scriptBytes;
            this.hookBytes = hookBytes;
            this.themeBytes = themeBytes;
        }
    }
}
