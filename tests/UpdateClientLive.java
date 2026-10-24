package dev.codex.watchworkshop;

import java.io.IOException;
import javax.net.ssl.SSLException;

/** Real-network smoke entry. Defaults match the manager release check used during development. */
public final class UpdateClientLive {
    private static final int DEFAULT_VERSION = 26;

    private UpdateClientLive() {}

    public static void main(String[] args) {
        int exitCode = run(args);
        if (exitCode != 0) System.exit(exitCode);
    }

    private static int run(String[] args) {
        final String source;
        final String id;
        final int version;
        try {
            if (args.length == 0) {
                source = UpdateClient.DEFAULT_SOURCE;
                id = UpdateClient.MANAGER_ID;
                version = DEFAULT_VERSION;
            } else if (args.length == 3) {
                source = args[0];
                id = args[1];
                version = Integer.parseInt(args[2]);
            } else {
                System.out.println("Usage: UpdateClientLive [source id version]");
                return 64;
            }
        } catch (NumberFormatException e) {
            System.out.println("RESULT: INVALID_ARGUMENT");
            System.out.println("version must be a decimal integer: " + args[2]);
            return 64;
        }

        System.out.println("LIVE: source=" + source);
        System.out.println("LIVE: id=" + id);
        System.out.println("LIVE: currentVersion=" + version);
        try {
            UpdateClient.Release release = new UpdateClient().check(source, id, version);
            if (release == null) {
                System.out.println("RESULT: CURRENT_OR_OLDER");
                return 0;
            }
            System.out.println("RESULT: UPDATE_AVAILABLE");
            System.out.println("release.id=" + release.id);
            System.out.println("release.versionCode=" + release.versionCode);
            System.out.println("release.versionName=" + release.versionName);
            System.out.println("release.url=" + release.url);
            System.out.println("release.sha256=" + release.sha256);
            System.out.println("release.notes=" + release.notes);
            return 0;
        } catch (IOException e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            if (message.contains("无更新清单")
                    || message.contains("尚未发布更新清单")) {
                System.out.println("RESULT: EXPECTED_NO_UPDATE_CATALOG");
                System.out.println("EXPECTED: 无更新清单");
                System.out.println("OBSERVED: IOException: " + message);
                return 0;
            }
            System.out.println("RESULT: FAIL_IO");
            if (hasCause(e, SSLException.class)) {
                System.out.println("FAILURE_CLASS: TLS");
            } else {
                System.out.println("FAILURE_CLASS: NETWORK_OR_IO");
            }
            e.printStackTrace(System.out);
            return 2;
        } catch (Exception e) {
            System.out.println("RESULT: FAIL_UNEXPECTED");
            e.printStackTrace(System.out);
            return 3;
        }
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        Throwable current = error;
        while (current != null) {
            if (type.isInstance(current)) return true;
            current = current.getCause();
        }
        return false;
    }
}
