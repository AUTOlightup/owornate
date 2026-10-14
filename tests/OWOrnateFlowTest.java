package dev.codex.watchworkshop;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * OWOrnate 纯 JVM 行为测试：直接编译并运行 src 中的真实 LatestThemeQueue 与 ModuleCatalog，
 * 不使用替身实现或复制过来的逻辑。
 *
 * <p>用受控 Executor（一次性排队、由测试显式触发的 ManualExecutor；可计数的单线程 Executor）
 * 和受控 Worker（可阻塞在飞写入、可记录回调、可在回调里重入 submit）来验证合并写入语义。
 */
public final class OWOrnateFlowTest {

    private static int passed;
    private static int failed;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        run("queue: one in-flight write, pending replaced by latest, converge",
                OWOrnateFlowTest::oneInFlightLatestWins);
        run("queue: failure clears pending, rejects submit during recovery",
                OWOrnateFlowTest::failureClearsPendingAndRejectsDuringRecovery);
        run("queue: explicit submit after recovery restarts writing",
                OWOrnateFlowTest::explicitSubmitAfterRecoveryRestarts);
        run("queue: submit after idle drains again, isRunning false after exit",
                OWOrnateFlowTest::submitAfterIdleKeepsRunningFlagHonest);
        run("catalog: uninstalled before installed, groups stable, input unchanged",
                OWOrnateFlowTest::catalogGroupsUninstalledFirstStable);
        run("catalog: single item and empty set",
                OWOrnateFlowTest::catalogSingleAndEmpty);
        System.out.println();
        System.out.println("OWOrnateFlowTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            for (String failure : failures) {
                System.out.println("  FAILED: " + failure);
            }
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------
    // LatestThemeQueue：合并写入语义
    // ------------------------------------------------------------------

    private static void oneInFlightLatestWins() throws Exception {
        CountingSingleThreadExecutor exec = new CountingSingleThreadExecutor();
        GatedWorker worker = new GatedWorker();
        LatestThemeQueue<String> queue = new LatestThemeQueue<>(exec, worker);
        try {
            ok("fresh queue is idle", !queue.isRunning());
            ok("submit A accepted", queue.submit("A"));
            eq("in-flight write A begins", "A", worker.started.poll(5, TimeUnit.SECONDS));
            ok("running while a write is in flight", queue.isRunning());

            ok("submit B accepted", queue.submit("B"));
            ok("submit C accepted", queue.submit("C"));
            ok("submit D accepted", queue.submit("D"));

            eq("only one drain chain was scheduled", 1, exec.count());
            eq("never more than one write at a time", 1, worker.maxActive.get());
            eq("in-flight value written once, pending not yet flushed", "[A]", worker.applied.toString());

            worker.gate.release();
            eq("latest pending selection converges", "D", worker.started.poll(5, TimeUnit.SECONDS));
            eq("B and C replaced by D", "[A, D]", worker.applied.toString());

            worker.gate.release();
            ok("queue returns to idle", awaitIdle(queue));
            eq("idle signaled once for the whole chain", 1, worker.idles.get());
            eq("no extra writes after convergence", "[A, D]", worker.applied.toString());
        } finally {
            worker.gate.release(8);
            exec.shutdown();
        }
    }

    private static void failureClearsPendingAndRejectsDuringRecovery() throws Exception {
        ManualExecutor exec = new ManualExecutor();
        ProbeWorker worker = new ProbeWorker();
        LatestThemeQueue<String> queue = new LatestThemeQueue<>(exec, worker);

        final Boolean[] runningDuringWrite = new Boolean[1];
        final Boolean[] acceptedDuringWrite = new Boolean[1];
        final Boolean[] acceptedDuringFailure = new Boolean[1];

        worker.duringApply = () -> {
            runningDuringWrite[0] = queue.isRunning();
            acceptedDuringWrite[0] = queue.submit("B");
        };
        worker.failNext = true;
        worker.duringFailed = () -> acceptedDuringFailure[0] = queue.submit("X");

        ok("submit A accepted", queue.submit("A"));
        eq("one drain scheduled", 1, exec.pending());
        ok("drain executed", exec.runAll());

        eq("running flag true while writing", Boolean.TRUE, runningDuringWrite[0]);
        eq("selection B accepted while writing", Boolean.TRUE, acceptedDuringWrite[0]);
        eq("only the failing value was attempted", "[A]", worker.attempts.toString());
        eq("failure callback fired once", 1, worker.failures.size());
        eq("waiting value B cleared by the failure", "[A]", worker.attempts.toString());
        eq("submit during recovery rejected", Boolean.FALSE, acceptedDuringFailure[0]);
        ok("queue stopped after failure", !queue.isRunning());
        eq("idle signaled once", 1, worker.idles.get());
        eq("rejected value X never written", "[]", worker.applied.toString());
    }

    private static void explicitSubmitAfterRecoveryRestarts() throws Exception {
        ManualExecutor exec = new ManualExecutor();
        ProbeWorker worker = new ProbeWorker();
        LatestThemeQueue<String> queue = new LatestThemeQueue<>(exec, worker);
        worker.failNext = true;

        ok("submit A accepted", queue.submit("A"));
        ok("first drain executed", exec.runAll());
        ok("queue stopped after failure", !queue.isRunning());

        ok("explicit submit C accepted after recovery", queue.submit("C"));
        ok("drain executed again", exec.runAll());
        eq("writing restarted with C", "[C]", worker.applied.toString());
        ok("queue idle after restart", !queue.isRunning());
        eq("idle signaled for both cycles", 2, worker.idles.get());
    }

    private static void submitAfterIdleKeepsRunningFlagHonest() throws Exception {
        ManualExecutor exec = new ManualExecutor();
        ProbeWorker worker = new ProbeWorker();
        LatestThemeQueue<String> queue = new LatestThemeQueue<>(exec, worker);

        ok("new queue idle", !queue.isRunning());
        ok("submit A accepted", queue.submit("A"));
        ok("running right after submit", queue.isRunning());
        ok("drain executed", exec.runAll());
        eq("A written", "[A]", worker.applied.toString());
        ok("isRunning false after queue exit", !queue.isRunning());

        ok("submit B accepted after idle", queue.submit("B"));
        ok("running again", queue.isRunning());
        ok("drain executed again", exec.runAll());
        eq("B written after idle", "[A, B]", worker.applied.toString());
        ok("isRunning false after second exit", !queue.isRunning());
        eq("idle signaled per cycle", 2, worker.idles.get());
    }

    // ------------------------------------------------------------------
    // ModuleCatalog：排序语义
    // ------------------------------------------------------------------

    private static void catalogGroupsUninstalledFirstStable() {
        ModuleCatalog.Entry a = new ModuleCatalog.Entry("a", "A", false);
        ModuleCatalog.Entry b = new ModuleCatalog.Entry("b", "B", true);
        ModuleCatalog.Entry c = new ModuleCatalog.Entry("c", "C", false);
        ModuleCatalog.Entry d = new ModuleCatalog.Entry("d", "D", true);
        ModuleCatalog.Entry e = new ModuleCatalog.Entry("e", "E", false);
        List<ModuleCatalog.Entry> input = new ArrayList<>(Arrays.asList(a, b, c, d, e));

        List<ModuleCatalog.Entry> out = ModuleCatalog.sorted(input);

        ok("sorted returns a new list", out != input);
        eq("uninstalled first, groups keep catalog order", "[a, c, e, b, d]", ids(out));
        eq("input list content unchanged", "[a, b, c, d, e]", ids(input));
        ok("input holds the same elements in the same order",
                input.get(0) == a && input.get(1) == b && input.get(2) == c
                        && input.get(3) == d && input.get(4) == e);

        List<ModuleCatalog.Entry> again =
                ModuleCatalog.sorted(new ArrayList<>(Arrays.asList(a, c, e, b, d)));
        eq("already grouped order is stable", "[a, c, e, b, d]", ids(again));
    }

    private static void catalogSingleAndEmpty() {
        List<ModuleCatalog.Entry> none = ModuleCatalog.sorted(new ArrayList<>());
        ok("empty input yields empty result", none != null && none.isEmpty());

        ModuleCatalog.Entry solo = new ModuleCatalog.Entry("solo", "Solo", true);
        List<ModuleCatalog.Entry> one = ModuleCatalog.sorted(Collections.singletonList(solo));
        eq("single installed item preserved", "[solo]", ids(one));
        ok("single item is the same element", one.size() == 1 && one.get(0) == solo);

        ModuleCatalog.Entry solo2 = new ModuleCatalog.Entry("solo2", "Solo2", false);
        List<ModuleCatalog.Entry> one2 = ModuleCatalog.sorted(Collections.singletonList(solo2));
        eq("single uninstalled item preserved", "[solo2]", ids(one2));
    }

    // ------------------------------------------------------------------
    // 断言与工具
    // ------------------------------------------------------------------

    private interface Body {
        void run() throws Exception;
    }

    private static void run(String name, Body body) {
        try {
            body.run();
            passed++;
            System.out.println("PASS  " + name);
        } catch (Throwable t) {
            failed++;
            failures.add(name + " :: " + t);
            System.out.println("FAIL  " + name + " :: " + t);
        }
    }

    private static void ok(String what, boolean condition) {
        if (!condition) {
            throw new AssertionError(what);
        }
    }

    private static void eq(String what, Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError(what + " (expected=" + expected + ", actual=" + actual + ")");
        }
    }

    private static String ids(List<ModuleCatalog.Entry> entries) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(entries.get(i).id);
        }
        return sb.append("]").toString();
    }

    private static boolean awaitIdle(LatestThemeQueue<?> queue) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (!queue.isRunning()) {
                return true;
            }
            Thread.sleep(5);
        }
        return !queue.isRunning();
    }

    /** 受控 Executor：任务只在测试显式 runAll() 时按队列顺序在当前线程执行。 */
    private static final class ManualExecutor implements Executor {
        private final Deque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public synchronized void execute(Runnable command) {
            tasks.add(command);
        }

        boolean runAll() {
            boolean ran = false;
            while (true) {
                Runnable task;
                synchronized (this) {
                    task = tasks.poll();
                }
                if (task == null) {
                    return ran;
                }
                ran = true;
                task.run();
            }
        }

        synchronized int pending() {
            return tasks.size();
        }
    }

    /** 受控单线程 Executor：可计数 execute() 次数，用于证明只调度了一条写入链。 */
    private static final class CountingSingleThreadExecutor implements Executor {
        private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "owornate-drain");
            thread.setDaemon(true);
            return thread;
        });
        private final AtomicInteger count = new AtomicInteger();

        @Override
        public void execute(Runnable command) {
            count.incrementAndGet();
            pool.execute(command);
        }

        int count() {
            return count.get();
        }

        void shutdown() {
            pool.shutdownNow();
        }
    }

    /** 写在飞会阻塞在 gate 上，直到测试放行；记录并发峰值与回调。 */
    private static final class GatedWorker implements LatestThemeQueue.Worker<String> {
        final List<String> applied = new CopyOnWriteArrayList<>();
        final LinkedBlockingQueue<String> started = new LinkedBlockingQueue<>();
        final Semaphore gate = new Semaphore(0);
        final AtomicInteger active = new AtomicInteger();
        final AtomicInteger maxActive = new AtomicInteger();
        final AtomicInteger idles = new AtomicInteger();

        @Override
        public void apply(String value) throws Exception {
            int now = active.incrementAndGet();
            maxActive.accumulateAndGet(now, Math::max);
            applied.add(value);
            started.add(value);
            gate.acquire();
            active.decrementAndGet();
        }

        @Override
        public void failed(Exception error) {
            // 本用例不触发失败路径。
        }

        @Override
        public void idle() {
            idles.incrementAndGet();
        }
    }

    /** 受控 Worker：可在 apply/failed 回调里重入 submit，可一次性失败。 */
    private static final class ProbeWorker implements LatestThemeQueue.Worker<String> {
        final List<String> attempts = Collections.synchronizedList(new ArrayList<>());
        final List<String> applied = Collections.synchronizedList(new ArrayList<>());
        final List<Exception> failures = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger idles = new AtomicInteger();
        Runnable duringApply;
        Runnable duringFailed;
        boolean failNext;

        @Override
        public void apply(String value) throws Exception {
            Runnable hook = duringApply;
            if (hook != null) {
                duringApply = null;
                hook.run();
            }
            attempts.add(value);
            if (failNext) {
                failNext = false;
                throw new IOException("boom:" + value);
            }
            applied.add(value);
        }

        @Override
        public void failed(Exception error) {
            Runnable hook = duringFailed;
            if (hook != null) {
                duringFailed = null;
                hook.run();
            }
            failures.add(error);
        }

        @Override
        public void idle() {
            idles.incrementAndGet();
        }
    }
}

