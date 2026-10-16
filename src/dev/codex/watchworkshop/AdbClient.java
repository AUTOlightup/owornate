package dev.codex.watchworkshop;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.PrivateKey;
import java.security.Signature;

/**
 * 最小 ADB 客户端。
 *
 * 只实现 CNXN / AUTH / OPEN / OKAY / WRTE / CLSE 六种报文和 shell 服务，
 * 目的是让设备上的普通应用能向本机 root adbd（127.0.0.1:5555）发一条 root shell 命令。
 * 不实现 push/pull、不实现 shell v2 帧、不做端口转发。
 */
final class AdbClient implements Closeable {

    static final String HOST = "127.0.0.1";
    static final int PORT = 5555;

    private static final int CMD_CNXN = 0x4e584e43; // "CNXN"
    private static final int CMD_AUTH = 0x48545541; // "AUTH"
    private static final int CMD_OPEN = 0x4e45504f; // "OPEN"
    private static final int CMD_OKAY = 0x59414b4f; // "OKAY"
    private static final int CMD_CLSE = 0x45534c43; // "CLSE"
    private static final int CMD_WRTE = 0x45545257; // "WRTE"

    private static final int VERSION = 0x01000001;
    private static final int MAX_DATA = 256 * 1024;
    private static final int AUTH_TOKEN = 1;
    private static final int AUTH_SIGNATURE = 2;
    private static final int AUTH_RSAPUBLICKEY = 3;
    private static final int MAX_AUTH_ATTEMPTS = 3;
    private static final String RC_MARKER = "__WORKSHOP_RC=";
    // adbd passes the 20-byte AUTH token to RSA_sign(NID_sha1) as an already
    // computed digest. SHA1withRSA would hash the token a second time.
    private static final byte[] SHA1_DIGEST_INFO = new byte[] {
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
            0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    };

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final PrivateKey key;
    private final String publicKeyLine;
    private int exitCode = -1;
    private int nextLocalId = 1;

    private AdbClient(Socket socket, PrivateKey key, String publicKeyLine) throws IOException {
        this.socket = socket;
        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();
        this.key = key;
        this.publicKeyLine = publicKeyLine;
    }

    static AdbClient open(int connectTimeoutMs, int readTimeoutMs, PrivateKey key, String publicKeyLine)
            throws IOException {
        return open(HOST, PORT, connectTimeoutMs, readTimeoutMs, key, publicKeyLine);
    }

    static AdbClient open(String host, int port, int connectTimeoutMs, int readTimeoutMs,
            PrivateKey key, String publicKeyLine) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(readTimeoutMs);
        } catch (IOException e) {
            try { socket.close(); } catch (IOException ignored) {}
            throw e;
        }
        AdbClient client = new AdbClient(socket, key, publicKeyLine);
        try {
            client.handshake(readTimeoutMs);
        } catch (IOException e) {
            client.close();
            throw e;
        }
        return client;
    }

    int exitCode() {
        return exitCode;
    }

    /**
     * 在设备上跑一条 shell 命令，返回合并后的输出（不含退出码标记行）。
     *
     * <p>同一个连接可以连续调用多次：每条命令用一个新的本地流编号，属于先前流的迟到报文
     * （比如 adbd 对已结束流补发的 CLSE）按编号忽略。这样一次操作只握手、签名一次。
     * 等待只受 totalTimeoutMs 约束：命令中途安静几秒（比如重启 Launcher 后等待加载）不算超时。
     */
    String shell(String command, long totalTimeoutMs) throws IOException {
        String wrapped = "(" + command + "); echo \"" + RC_MARKER + "$?\"";
        int local = nextLocalId++;
        int remote = 0;
        exitCode = -1;
        send(CMD_OPEN, local, 0, asciiNul("shell:" + wrapped));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        long deadline = System.currentTimeMillis() + totalTimeoutMs;
        while (true) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) throw new SocketTimeoutException("shell timeout");
            Message message = readMessage((int) Math.min(remaining, Integer.MAX_VALUE));
            if (message.arg1 != local) continue;
            if (message.command == CMD_OKAY) {
                remote = message.arg0;
                continue;
            }
            if (message.command == CMD_WRTE) {
                remote = message.arg0;
                stdout.write(message.data);
                send(CMD_OKAY, local, remote, null);
                // Android 8.1 adbd may keep the shell channel open for a
                // background child even after the command has returned.
                // The exit marker is emitted last by our wrapper, so it is
                // enough to finish without waiting for a remote CLSE; closing
                // our side lets the next command reuse this connection.
                if (hasCompleteExitMarker(stdout.toByteArray())) {
                    send(CMD_CLSE, local, remote, null);
                    return decode(stdout.toByteArray());
                }
                continue;
            }
            if (message.command == CMD_CLSE) {
                send(CMD_CLSE, local, message.arg0, null);
                return decode(stdout.toByteArray());
            }
            throw new IOException("unexpected message " + name(message.command));
        }
    }

    @Override
    public void close() {
        try { socket.close(); } catch (IOException ignored) {}
    }

    private void handshake(int readTimeoutMs) throws IOException {
        send(CMD_CNXN, VERSION, MAX_DATA, asciiNul("host::features=cmd,stat"));
        int attempts = 0;
        while (true) {
            Message message = readMessage(readTimeoutMs);
            if (message.command == CMD_CNXN) return;
            if (message.command == CMD_AUTH) {
                if (message.arg0 == AUTH_RSAPUBLICKEY) {
                    send(CMD_AUTH, AUTH_RSAPUBLICKEY, 0, asciiNul(publicKeyLine));
                    continue;
                }
                if (++attempts > MAX_AUTH_ATTEMPTS) {
                    throw new IOException("授权失败：" + AdbClient.HOST + ":" + AdbClient.PORT
                            + " 未接受本应用的公钥。先在电脑侧执行安装脚本，把 "
                            + RsaKeyStore.COMMENT + " 公钥写入 /data/misc/adb/adb_keys 并重启 adbd。");
                }
                send(CMD_AUTH, AUTH_SIGNATURE, 0, sign(message.data));
                continue;
            }
            throw new IOException("握手异常：" + name(message.command));
        }
    }

    private byte[] sign(byte[] token) throws IOException {
        try {
            Signature signature = Signature.getInstance("NONEwithRSA");
            signature.initSign(key);
            signature.update(SHA1_DIGEST_INFO);
            signature.update(token);
            return signature.sign();
        } catch (Exception e) {
            throw new IOException("签名失败：" + e);
        }
    }

    private String decode(byte[] payload) throws IOException {
        String text = new String(payload, "UTF-8");
        int index = text.lastIndexOf(RC_MARKER);
        if (index >= 0) {
            int start = index + RC_MARKER.length();
            int end = start;
            while (end < text.length() && text.charAt(end) >= '0' && text.charAt(end) <= '9') end++;
            try {
                exitCode = Integer.parseInt(text.substring(start, end));
            } catch (NumberFormatException ignored) {
                exitCode = -1;
            }
            int lineStart = text.lastIndexOf('\n', index);
            text = text.substring(0, lineStart < 0 ? 0 : lineStart);
        }
        return text;
    }

    private static boolean hasCompleteExitMarker(byte[] payload) throws IOException {
        String text = new String(payload, "UTF-8");
        int index = text.lastIndexOf(RC_MARKER);
        if (index < 0) return false;
        int start = index + RC_MARKER.length();
        int end = start;
        while (end < text.length() && text.charAt(end) >= '0' && text.charAt(end) <= '9') end++;
        return end > start && end < text.length() &&
                (text.charAt(end) == '\n' || text.charAt(end) == '\r');
    }

    private void send(int command, int arg0, int arg1, byte[] data) throws IOException {
        byte[] header = new byte[24];
        putInt(header, 0, command);
        putInt(header, 4, arg0);
        putInt(header, 8, arg1);
        putInt(header, 12, data == null ? 0 : data.length);
        putInt(header, 16, data == null ? 0 : checksum(data));
        putInt(header, 20, command ^ 0xffffffff);
        out.write(header);
        if (data != null && data.length > 0) out.write(data);
        out.flush();
    }

    private Message readMessage(int timeoutMs) throws IOException {
        socket.setSoTimeout(timeoutMs);
        byte[] header = new byte[24];
        DataInputStream stream = new DataInputStream(in);
        stream.readFully(header);
        Message message = new Message();
        message.command = getInt(header, 0);
        message.arg0 = getInt(header, 4);
        message.arg1 = getInt(header, 8);
        int length = getInt(header, 12);
        int magic = getInt(header, 20);
        if (magic != (message.command ^ 0xffffffff)) {
            throw new IOException("报文校验失败");
        }
        if (length < 0 || length > MAX_DATA * 2) {
            throw new IOException("报文长度异常：" + length);
        }
        message.data = new byte[length];
        if (length > 0) stream.readFully(message.data);
        return message;
    }

    private static int checksum(byte[] data) {
        int sum = 0;
        for (byte value : data) sum += value & 0xff;
        return sum;
    }

    private static void putInt(byte[] target, int offset, int value) {
        target[offset] = (byte) (value & 0xff);
        target[offset + 1] = (byte) ((value >>> 8) & 0xff);
        target[offset + 2] = (byte) ((value >>> 16) & 0xff);
        target[offset + 3] = (byte) ((value >>> 24) & 0xff);
    }

    private static int getInt(byte[] source, int offset) {
        return (source[offset] & 0xff)
                | ((source[offset + 1] & 0xff) << 8)
                | ((source[offset + 2] & 0xff) << 16)
                | ((source[offset + 3] & 0xff) << 24);
    }

    private static byte[] ascii(String value) throws IOException {
        return value.getBytes("US-ASCII");
    }

    private static byte[] asciiNul(String value) throws IOException {
        byte[] text = ascii(value);
        byte[] result = new byte[text.length + 1];
        System.arraycopy(text, 0, result, 0, text.length);
        return result;
    }

    private static String name(int command) {
        return new String(new byte[] {
                (byte) (command & 0xff),
                (byte) ((command >>> 8) & 0xff),
                (byte) ((command >>> 16) & 0xff),
                (byte) ((command >>> 24) & 0xff)
        }, java.nio.charset.Charset.forName("US-ASCII"));
    }

    private static final class Message {
        int command;
        int arg0;
        int arg1;
        byte[] data;
    }
}
