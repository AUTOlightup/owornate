package dev.codex.watchworkshop;

import java.io.File;
import java.io.FileInputStream;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPrivateCrtKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RsaKeyStore 的离线并发验证：纯 Java main，无 JUnit、无 Android、不连设备、不写 src。
 *
 * <p>场景对应真机启动时的真实竞争：RuntimeSupport 的启动检查和 UI 的密钥准备
 * （Activity 里 RsaKeyStore 的预热）会同时创建各自的 RsaKeyStore 实例，指向同一个
 * {@code files/adb} 目录。如果缺一把跨实例的锁，两边都会发现"文件不存在"并各自生成密钥，
 * 于是 app 返回的公钥、注入时用的私钥、以及设备 adb_keys 里已被授权的那一份可能不是同一把。
 *
 * <p>断言口径：
 * <ul>
 *   <li>16 个线程各自 new 一个 RsaKeyStore，同一临时目录，同时调用 publicKeyLine() 与 privateKey()；
 *   <li>16 份公钥行必须完全一致，且每个实例私钥都能推导出这一份公钥行（private 与 public 匹配）；
 *   <li>目录里只能有一份公钥文件，落盘的公钥与私钥必须互相匹配；
 *   <li>风暴结束后再 new 新实例读取，公钥与私钥都必须保持不变（没有二次生成）。
 * </ul>
 *
 * <p>输出只包含计数、公钥指纹前缀与目录路径，不打印任何私钥内容，也不构造占位密钥。
 */
public final class RsaKeyStoreTest {

    private static final int PARALLEL = 16;
    private static final long READY_TIMEOUT_MS = 120000L;
    private static final long JOIN_TIMEOUT_MS = 300000L;
    private static final String PRIVATE_NAME = "adbkey.der";
    private static final String PUBLIC_NAME = "adbkey.pub";
    private static final Charset ASCII = Charset.forName("US-ASCII");

    private static int checks = 0;
    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        File base = new File(args.length > 0 ? args[0] : System.getProperty("user.dir", "."), "work");
        File dir = new File(base, "rsakeystore-test-"
                + new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(new Date()));
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IllegalStateException("cannot create test dir " + dir);
        }

        Storm storm = runParallelStorm(dir);
        verifyStorm(dir, storm);
        verifyStableReread(dir, storm);

        System.out.println("RsaKeyStoreTest: " + (checks - failures) + "/" + checks + " checks passed");
        System.out.println("temp dir: " + dir.getAbsolutePath());
        if (failures > 0) {
            System.out.println("RESULT: FAIL");
            System.exit(1);
        }
        System.out.println("RESULT: OK");
    }

    // ---- 并行风暴：16 个独立实例，同一目录 ------------------------------------

    private static final class Storm {
        final List<String> lines = Collections.synchronizedList(new ArrayList<String>());
        final List<String> derived = Collections.synchronizedList(new ArrayList<String>());
        final List<String> keyHashes = Collections.synchronizedList(new ArrayList<String>());
        final List<String> errors = Collections.synchronizedList(new ArrayList<String>());
        int started;
    }

    private static Storm runParallelStorm(final File dir) throws Exception {
        final Storm storm = new Storm();
        final CountDownLatch ready = new CountDownLatch(PARALLEL);
        final CountDownLatch start = new CountDownLatch(1);
        final AtomicInteger started = new AtomicInteger();
        Thread[] workers = new Thread[PARALLEL];
        for (int i = 0; i < PARALLEL; i++) {
            workers[i] = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        RsaKeyStore store = new RsaKeyStore(dir); // 每个线程自己的实例
                        started.incrementAndGet();
                        ready.countDown();
                        start.await();
                        String line = store.publicKeyLine();
                        PrivateKey privateKey = store.privateKey();
                        storm.lines.add(line);
                        storm.derived.add(derivePublicLine(privateKey));
                        storm.keyHashes.add(sha256Hex(privateKey.getEncoded()));
                    } catch (Throwable failure) {
                        storm.errors.add(failure.getClass().getSimpleName() + ": " + failure.getMessage());
                    }
                }
            }, "rsakeystore-worker-" + i);
            workers[i].setDaemon(true);
            workers[i].start();
        }
        if (!ready.await(READY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("workers did not reach the start line");
        }
        start.countDown();
        for (Thread worker : workers) {
            worker.join(JOIN_TIMEOUT_MS);
        }
        storm.started = started.get();
        return storm;
    }

    private static void verifyStorm(File dir, Storm storm) throws Exception {
        check("16 个实例都已启动", storm.started == PARALLEL);
        check("16 个实例都返回了结果", storm.lines.size() == PARALLEL && storm.derived.size() == PARALLEL);
        check("没有线程抛异常", storm.errors.isEmpty());
        if (!storm.errors.isEmpty()) {
            System.out.println("worker error: " + storm.errors.get(0));
        }

        String line = storm.lines.isEmpty() ? "" : storm.lines.get(0);
        check("返回的公钥行非空", line.length() > 0);
        check("返回的公钥行是 ADB 格式（524 字节 blob + 注释）", isAdbPublicLine(line));

        boolean identicalLine = true;
        for (String candidate : storm.lines) {
            if (!line.equals(candidate)) {
                identicalLine = false;
            }
        }
        check("只有一份公钥：16 个实例的公钥行完全一致", identicalLine);

        boolean privateMatchesPublic = true;
        for (String derivedLine : storm.derived) {
            if (!line.equals(derivedLine)) {
                privateMatchesPublic = false;
            }
        }
        check("private 与 public 匹配：每个实例的私钥都能推导出同一公钥行", privateMatchesPublic);
        check("只有一份私钥：16 个实例的私钥编码哈希一致", distinctCount(storm.keyHashes) == 1);

        List<String> names = listNames(dir);
        check("目录里只有密钥对两个文件", names.size() == 2
                && names.contains(PRIVATE_NAME) && names.contains(PUBLIC_NAME));

        String publicFile = readText(new File(dir, PUBLIC_NAME)).trim();
        check("落盘公钥与返回值一致", publicFile.equals(line));
        check("落盘公钥只有一行", readText(new File(dir, PUBLIC_NAME)).trim().indexOf('\n') < 0);

        PrivateKey onDisk = readPrivate(new File(dir, PRIVATE_NAME));
        check("落盘的 private 与落盘的 public 互相匹配", line.equals(derivePublicLine(onDisk)));

        System.out.println("public key: sha256:" + sha256Hex(line.getBytes(ASCII)).substring(0, 12)
                + "... length " + line.length() + ", distinct private keys " + distinctCount(storm.keyHashes));
    }

    // ---- 稳定性：风暴之后新实例再读 ------------------------------------------

    private static void verifyStableReread(File dir, Storm storm) throws Exception {
        String expected = storm.lines.isEmpty() ? "" : storm.lines.get(0);
        byte[] publicBefore = Files.readAllBytes(new File(dir, PUBLIC_NAME).toPath());
        byte[] privateBefore = Files.readAllBytes(new File(dir, PRIVATE_NAME).toPath());
        String privateHashBefore = sha256Hex(privateBefore);

        RsaKeyStore fresh = new RsaKeyStore(dir);
        check("新实例的 publicKeyFile 指向目录内 " + PUBLIC_NAME,
                fresh.publicKeyFile().getAbsolutePath().equals(new File(dir, PUBLIC_NAME).getAbsolutePath()));
        check("新实例的 privateKeyFile 指向目录内 " + PRIVATE_NAME,
                fresh.privateKeyFile().getAbsolutePath().equals(new File(dir, PRIVATE_NAME).getAbsolutePath()));

        String line = fresh.publicKeyLine();
        PrivateKey privateKey = fresh.privateKey();
        check("新实例读到同一份公钥", line.equals(expected));
        check("新实例的 private 与 public 匹配", line.equals(derivePublicLine(privateKey)));
        check("新实例私钥与风暴期私钥相同（没有重新生成）",
                sha256Hex(privateKey.getEncoded()).equals(privateHashBefore));
        check("新实例没有改写公钥文件",
                Arrays.equals(publicBefore, Files.readAllBytes(new File(dir, PUBLIC_NAME).toPath())));
        check("新实例没有改写私钥文件",
                Arrays.equals(privateBefore, Files.readAllBytes(new File(dir, PRIVATE_NAME).toPath())));

        RsaKeyStore second = new RsaKeyStore(dir);
        check("第二个新实例公钥稳定", expected.equals(second.publicKeyLine()));
        check("第二个新实例私钥稳定",
                sha256Hex(second.privateKey().getEncoded()).equals(privateHashBefore));

        check("私钥是 2048 位 RSA", modulusBits(privateKey) == 2048);
        check("私钥仍可解析为 PKCS#8 RSA",
                readPrivate(new File(dir, PRIVATE_NAME)).getAlgorithm().equals("RSA"));
        check("目录仍只有密钥对两个文件", listNames(dir).size() == 2);
    }

    // ---- 工具 ---------------------------------------------------------------

    /** 由私钥推导 ADB 公钥行，用于验证 private 与 public 属于同一密钥对。 */
    private static String derivePublicLine(PrivateKey privateKey) throws Exception {
        BigInteger modulus;
        BigInteger publicExponent;
        if (privateKey instanceof RSAPrivateCrtKey) {
            RSAPrivateCrtKey crt = (RSAPrivateCrtKey) privateKey;
            modulus = crt.getModulus();
            publicExponent = crt.getPublicExponent();
        } else {
            RSAPrivateCrtKeySpec spec = (RSAPrivateCrtKeySpec) KeyFactory.getInstance("RSA")
                    .getKeySpec(privateKey, RSAPrivateCrtKeySpec.class);
            modulus = spec.getModulus();
            publicExponent = spec.getPublicExponent();
        }
        PublicKey publicKey = KeyFactory.getInstance("RSA")
                .generatePublic(new RSAPublicKeySpec(modulus, publicExponent));
        return RsaKeyStore.encode(publicKey, RsaKeyStore.COMMENT);
    }

    private static int modulusBits(PrivateKey privateKey) throws Exception {
        if (privateKey instanceof RSAPrivateCrtKey) {
            return ((RSAPrivateCrtKey) privateKey).getModulus().bitLength();
        }
        RSAPrivateCrtKeySpec spec = (RSAPrivateCrtKeySpec) KeyFactory.getInstance("RSA")
                .getKeySpec(privateKey, RSAPrivateCrtKeySpec.class);
        return spec.getModulus().bitLength();
    }

    private static boolean isAdbPublicLine(String line) {
        if (line == null || !line.endsWith(" " + RsaKeyStore.COMMENT)) {
            return false;
        }
        String[] parts = line.split(" ");
        if (parts.length < 2 || parts[0].length() == 0) {
            return false;
        }
        try {
            return Base64.getDecoder().decode(parts[0]).length == 524;
        } catch (IllegalArgumentException notBase64) {
            return false;
        }
    }

    private static PrivateKey readPrivate(File file) throws Exception {
        return KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(file.toPath())));
    }

    private static String readText(File file) throws Exception {
        FileInputStream in = new FileInputStream(file);
        try {
            byte[] buffer = new byte[(int) file.length()];
            int offset = 0;
            while (offset < buffer.length) {
                int read = in.read(buffer, offset, buffer.length - offset);
                if (read < 0) {
                    break;
                }
                offset += read;
            }
            if (offset != buffer.length) {
                throw new IllegalStateException("short read " + file);
            }
            return new String(buffer, ASCII);
        } finally {
            in.close();
        }
    }

    private static List<String> listNames(File dir) {
        String[] names = dir.list();
        return names == null ? new ArrayList<String>() : Arrays.asList(names);
    }

    private static int distinctCount(List<String> values) {
        List<String> seen = new ArrayList<String>();
        for (String value : values) {
            if (!seen.contains(value)) {
                seen.add(value);
            }
        }
        return seen.size();
    }

    private static String sha256Hex(byte[] value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(value);
        StringBuilder text = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            text.append(Character.forDigit((b >> 4) & 0xf, 16));
            text.append(Character.forDigit(b & 0xf, 16));
        }
        return text.toString();
    }

    private static void check(String name, boolean condition) {
        checks++;
        if (!condition) {
            failures++;
            System.out.println("FAIL: " + name);
        }
    }
}
