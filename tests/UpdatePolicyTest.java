package dev.codex.watchworkshop;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class UpdatePolicyTest {
    static int checks;
    static void verify(boolean pass, String id, int version, List<String> oldSigners, List<String> nextSigners) throws Exception {
        try {
            UpdatePolicy.requireApk("dev.codex.watchworkshop", 26, oldSigners, id, version, nextSigners);
            if (!pass) throw new AssertionError("Accepted invalid APK: " + id + "/" + version);
        } catch (IOException expected) { if (pass) throw expected; }
        checks++;
    }
    public static void main(String[] args) throws Exception {
        List<String> one = Arrays.asList("original");
        verify(true, "dev.codex.watchworkshop", 27, one, one);
        verify(false, "other.package", 27, one, one);
        verify(false, "dev.codex.watchworkshop", 26, one, one);
        verify(false, "dev.codex.watchworkshop", 25, one, one);
        verify(false, "dev.codex.watchworkshop", 27, one, Arrays.asList("foreign"));
        verify(false, "dev.codex.watchworkshop", 27, one, Collections.emptyList());
        verify(false, "dev.codex.watchworkshop", 27, Collections.emptyList(), Collections.emptyList());
        verify(false, "dev.codex.watchworkshop", 27, one, null);
        verify(true, "dev.codex.watchworkshop", 27, Arrays.asList("a", "b"), Arrays.asList("b", "a"));
        verify(false, "dev.codex.watchworkshop", 27, Arrays.asList("a", "b"), Arrays.asList("a", "a"));
        System.out.println("UpdatePolicyTest: " + checks + " checks passed");
    }
}
