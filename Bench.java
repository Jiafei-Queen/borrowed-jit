// ============================================================
// Bench: single-file benchmark comparing
//   1. HotSpot -Xint   (local, pure interpreter)
//   2. HotSpot JIT     (local, default)
//   3. CheerpJ JIT     (browser, jit form below)
//   4. b-jvm           (browser, interpreter reference)
//
// Forms (controlled by the harness, not the workloads):
//   interp form: 3 calls of a huge loop   -> per-iteration ns,
//                below HotSpot tier-up threshold; used for local -Xint
//                and as the b-jvm / interpreter-reference path
//   jit form:    800 warmup + 5 rounds x 400 small calls -> crosses
//                tier-up threshold; used for HotSpot JIT and CheerpJ JIT
//
// Metric: ns per iteration. Checksum (chk=OK) proves both forms and
// all runtimes compute identical results for identical n.
// ============================================================

public class Bench {

    // ---------- 1. Scalar replacement target ----------
    static class Point {
        int x, y;
        Point(int x, int y) { this.x = x; this.y = y; }
    }
    static long wAlloc(int n) {
        long sum = 0;
        for (int i = 0; i < n; i++) {
            Point p = new Point(i, i * 2);
            sum += p.x + p.y;
        }
        return sum;
    }

    // ---------- 2. Devirtualization / inlining target ----------
    interface Op { long apply(long v); }
    static class Add implements Op { public long apply(long v) { return v + 3; } }
    static class Mul implements Op { public long apply(long v) { return v * 3 + 1; } }
    static Op theOp = new Add();
    static long wVirtual(int n) {
        Op op = theOp;
        long sum = 0;
        for (int i = 0; i < n; i++) {
            sum = op.apply(sum);
        }
        return sum;
    }

    // ---------- 3. int arithmetic ----------
    static long wInt(int n) {
        int sum = 0;
        for (int i = 0; i < n; i++) {
            sum += i;
        }
        return sum;
    }

    // ---------- 4. long arithmetic (64-bit handling) ----------
    static long wLong(int n) {
        long sum = 0L;
        for (int i = 0; i < n; i++) {
            sum += i;
        }
        return sum;
    }

    // ---------- 5. Array access + predictable branch ----------
    static int[] arr = new int[256];
    static {
        for (int i = 0; i < arr.length; i++) arr[i] = i;
    }
    static long wArray(int n) {
        long sum = 0;
        for (int i = 0; i < n; i++) {
            sum += arr[i & 255];
        }
        return sum;
    }

    // ---------- 6. Pure invoke overhead ----------
    static long tiny(long v) { return v + 1; }
    static long wInvoke(int n) {
        long sum = 0;
        for (int i = 0; i < n; i++) {
            sum = tiny(sum);
        }
        return sum;
    }

    // ---------- 7. Math.sin: expected UNCHANGED between forms ----------
    static long wMath(int n) {
        double sum = 0;
        for (int i = 0; i < n; i++) {
            sum += Math.sin(i * 0.0001);
        }
        return (long) (sum * 1000);
    }

    // ---------- harness ----------
    static long now() { return System.nanoTime() / 1_000; } // microseconds
    static String f(double v) { return String.format("%.2f", v); }

    interface W { long run(int n); }

    static void bench(String name, W w, int bigN, int smallN) {
        // --- interp form: 2 warmup + 1 measured big call ---
        w.run(bigN);
        w.run(bigN);
        long a = now();
        long rInterp = w.run(bigN);
        long b = now();
        double interpNs = (b - a) * 1000.0 / bigN;

        // --- jit form: 800 warmup small calls, then 5 rounds x 400 calls ---
        for (int i = 0; i < 800; i++) w.run(smallN);
        double best = Double.MAX_VALUE, sum = 0;
        for (int r = 0; r < 5; r++) {
            long t0 = now();
            for (int i = 0; i < 400; i++) w.run(smallN);
            long t1 = now();
            double ns = (t1 - t0) * 1000.0 / (400.0 * smallN);
            sum += ns;
            if (ns < best) best = ns;
            System.out.println(name + ",jit,round" + r + "," + f(ns));
        }
        // post-JIT checksum: same n as interp form -> must equal rInterp
        long c0 = now();
        long rJit = w.run(bigN);
        long c1 = now();
        String ok = (rInterp == rJit) ? "chk=OK" : "chk=MISMATCH";
        System.out.println(name + ",interp,main," + f(interpNs) + "," + ok);
        System.out.println(name + ",jit,avg," + f(sum / 5) + ",best=" + f(best)
                + ",postJIT_bigN_ms=" + f((c1 - c0) / 1000.0));
    }

    // No-arg entry for embedding hosts (b-jvm browser glue); main() kept for JVMs.
    public static void run() {
        main(null);
    }

    public static void main(String[] args) {
        System.out.println("env java.version=" + System.getProperty("java.version"));
        System.out.println("env java.vm.name=" + System.getProperty("java.vm.name"));
        System.out.println("wl,form,round,ns_per_iter");

        bench("MATH",    Bench::wMath,    1_000_000,  20_000);
        bench("INT",     Bench::wInt,   10_000_000, 200_000);
        bench("LONG",    Bench::wLong,  10_000_000, 200_000);
        bench("ARRAY",   Bench::wArray, 10_000_000, 200_000);
        bench("INVOKE",  Bench::wInvoke, 5_000_000, 100_000);
        bench("ALLOC",   Bench::wAlloc,  3_000_000,  50_000);
        bench("VIRTUAL", Bench::wVirtual,3_000_000,  50_000);

        System.out.println("Bench done");
    }
}
