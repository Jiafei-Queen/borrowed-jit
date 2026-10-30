# borrowed-jit

### Measuring JVM execution stacks in the browser, where the browser does the JIT

A benchmark harness for JVM execution stacks running **inside a web browser**, plus the
measured report produced from it.

The central question: when the only component allowed to JIT is the browser engine itself,
what is the real performance of a JVM whose JIT can only emit "bytecode → JS/WASM"?

In that setup the JIT is **borrowed**. b-jvm runs as a WASM interpreter whose output the
browser re-optimizes; CheerpJ's JIT emits JS that the browser then recompiles. Neither JVM
owns the tiering that makes their code fast — the host engine does. This project measures
what that arrangement actually costs, and where it quietly wins.

<p align="center">
    Reports:
    <a href="./docs/report.md">English report</a> |
    <a href="./docs/report_zh-cn.md">中文报告</a>
</p>

<p align="center">
    READMEs:
    <a href="./README.md">English README</a> |
    <a href="./docs/README_zh-cn.md">中文自述文件</a>
</p>

---

## What is measured

Four execution stacks are compared, using a single `Bench.java` with 7 workloads × 2 invocation forms:

| Execution stack | Location | Role |
|---|---|---|
| HotSpot `-Xint` | local | Pure interpreter baseline |
| HotSpot JIT (C1/C2) | local | Native JIT baseline |
| CheerpJ 4.3 | browser | JVM JIT emits JS, host engine recompiles it |
| b-jvm | browser | Pure WASM interpreter (its JIT is disabled upstream) |

Workloads: `MATH` (native bridge), `INT` (tight loop), `LONG` (64-bit cost), `ARRAY` (bounds checks), `INVOKE` (inlining), `ALLOC` (escape analysis), `VIRTUAL` (devirtualization).

Metric: **ns per iteration**. A per-workload checksum (`chk=OK`) proves that all forms and runtimes compute identical results.

## Headline results

- Compact int loops under CheerpJ JIT reach **0.22–0.23 ns/iter on all three engines** — on par with desktop HotSpot C2 (0.24).
- The engine ranking **inverts** by workload class: WebKit is fastest for the WASM interpreter (b-jvm), SpiderMonkey is best for CheerpJ-generated JS.
- On Safari/iOS, the interpreter route (b-jvm) can beat the JIT route (CheerpJ) for call-intensive code.

Full data matrices and per-dimension analysis are in [`docs/report.md`](docs/report.md).

---

## Repository layout

```
.
├── README.md              # this file (English)
├── LICENSE                # MIT
├── .gitignore
├── Bench.java             # the benchmark source (7 workloads × 2 forms)
├── bench.jar              # compiled benchmark, loaded by both browser harnesses
├── bjvm-bench.html        # b-jvm harness (browser, pure interpreter baseline)
├── cheerpj-bench.html     # CheerpJ harness (browser, interpreted vs JIT forms)
├── docs/
│   ├── README_zh-cn.md    # 中文版说明
│   ├── report.md          # measured report (English)
│   └── report_zh-cn.md    # 测量报告（中文）
└── bjvm-assets/           # b-jvm WASM build + JDK 23 runtime (~54 MB)
    ├── bjvm_main.js
    ├── bjvm_main.wasm
    ├── jdk23.jar
    └── jdk23/
        ├── conf/security/{java.security,java.policy}
        └── lib/{modules,security/default.policy}
```

## Requirements

- A JDK to rebuild `bench.jar` (Temurin 21 was used; any JDK 17+ should work)
- A modern browser (Firefox / Chromium / Safari); CheerpJ loads its runtime from the network on first use
- Python 3 (or any static file server) to serve the pages

## Usage

### 1. Serve the directory

Both harnesses load WASM, JARs and class-library files via `fetch`, so they must be served over HTTP — opening the files directly from disk (`file://`) will fail on CORS.

```bash
cd borrowed-jit
python3 -m http.server 8765
```

### 2. Run a browser harness

Open the corresponding page and click **Run benchmark**:

- <http://localhost:8765/bjvm-bench.html> — b-jvm (pure interpreter baseline)
- <http://localhost:8765/cheerpj-bench.html> — CheerpJ (interpreted vs JIT forms)

> The b-jvm page downloads roughly 54 MB of JDK 23 runtime on first load; subsequent visits are much faster once the HTTP cache is warm.

### 3. Run the local HotSpot baselines

```bash
# pure interpreter
java -Xint -cp bench.jar Bench

# default JIT
java -cp bench.jar Bench
```

Output is CSV: `wl,form,round,ns_per_iter`, with a `chk=OK` marker on each interp-form line.

### 4. Rebuild `bench.jar` (optional)

```bash
javac -d build Bench.java
jar cfe bench.jar Bench -C build .
```

## Reading the output

Each workload emits lines of the form:

```
INT,jit,round0,0.59
INT,jit,round1,0.59
...
INT,jit,round4,0.59
INT,interp,main,0.59,chk=OK
INT,jit,avg,0.59,best=0.59,postJIT_bigN_ms=5.93
```

- `jit,roundN` — per-round measurements after 800 warm-up calls
- `interp,main` — the single measured large call (below the tier-up threshold), emitted after the jit rounds
- `jit,avg` — mean over the 5 rounds, plus the best round and the post-JIT large-call timing
- `chk=OK` — the checksum from the jit form matches the interp form

## Methodological caveats

- **Form control works for HotSpot but is unsafe for CheerpJ.** Its tiering counters are more aggressive than expected and compilation is triggered asynchronously, so some "interp form" data points are in fact already compiled. Those points are annotated in the report.
- **b-jvm has no JIT** (`dumb_jit` / `attempt_jit` are unconditionally disabled upstream), so its two forms are naturally equivalent — which makes it the cleanest interpreter reference group.
- **Cross-engine comparison requires the same browser build.** Numbers in the report were taken on Firefox 158.0.1, Chrome 155.0.8059.86 and Safari 26.6.2 (macOS on Apple M4 Max, arm64).
- All figures come from **synthetic microbenchmarks** and must not be extrapolated directly to real application workloads.

## License

Released under the [MIT License](LICENSE).
