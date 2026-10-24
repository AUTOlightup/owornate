package dev.codex.watchworkshop;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;

/** Exercises the real exit-marker decoding path without opening an ADB connection. */
public final class AdbClientDecodeTest {
    private static final String MARKER = "__WORKSHOP_RC=";
    private final AdbClient client;
    private final Method decode;

    private AdbClientDecodeTest() throws Exception {
        Constructor<AdbClient> constructor = AdbClient.class.getDeclaredConstructor(
                Socket.class, PrivateKey.class, String.class);
        constructor.setAccessible(true);
        client = constructor.newInstance(new TestSocket(), null, null);
        decode = AdbClient.class.getDeclaredMethod("decode", byte[].class);
        decode.setAccessible(true);
    }

    private void check(String payload, String expectedText, int expectedCode) throws Exception {
        String actual = (String) decode.invoke(client, (Object) payload.getBytes(StandardCharsets.UTF_8));
        if (!expectedText.equals(actual)) {
            throw new AssertionError("text: expected <" + expectedText + "> but got <" + actual + ">");
        }
        if (client.exitCode() != expectedCode) {
            throw new AssertionError("exit code: expected " + expectedCode + " but got " + client.exitCode());
        }
    }

    public static void main(String[] args) throws Exception {
        AdbClientDecodeTest test = new AdbClientDecodeTest();
        try {
            test.check("{\"version\":2,\"error\":\"failure\"}" + MARKER + "0\n",
                    "{\"version\":2,\"error\":\"failure\"}", 0);
            test.check("single line" + MARKER + "7\n", "single line", 7);
            test.check("first\nlast" + MARKER + "3\n", "first\nlast", 3);
            test.check("first\nlast\n" + MARKER + "4\n", "first\nlast", 4);
            test.check(MARKER + "0\n", "", 0);
            test.check("failed" + MARKER + "12\n", "failed", 12);
            System.out.println("PASS AdbClientDecodeTest: 6 cases");
        } finally {
            test.client.close();
        }
    }

    private static final class TestSocket extends Socket {
        private final InputStream input = new ByteArrayInputStream(new byte[0]);
        private final OutputStream output = new ByteArrayOutputStream();

        @Override public InputStream getInputStream() { return input; }
        @Override public OutputStream getOutputStream() { return output; }
        @Override public void close() {}
    }
}
