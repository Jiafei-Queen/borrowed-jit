# Measured Performance of In-Browser JVM Execution Stacks

> **Date**: 2026-10-31
> **Platform**: Apple M4 Max MacBook Pro (Apple Silicon, arm64)
> **Test browsers**: Firefox 158.0.1 (Gecko; JS/WASM engine SpiderMonkey), Chrome 155.0.8059.86 (Chromium; JS/WASM engine V8), Safari 26.6.2 (21624.5.1.11.3) (WebKit; JS/WASM engine JavaScriptCore)
> **Benchmark**: local HotSpot Temurin 21.0.11; in-browser CheerpJ 4.3 and b-jvm (anematode/b-jvm, cloned 2026-10-30, JDK 23 runtime)

---

## 1. Background and the chain of questions

This experiment originates from a single question: what happens when an application on iOS uses JIT? The answer is that the kernel issues a SIGKILL for a code-signing violation — unless the process holds the `dynamic-codesigning` entitlement, which in practice is granted only to the browser's own JS/WASM engine (JavaScriptCore in the case of Safari).

From this follows the central question of this report: **when the only component permitted to JIT is the browser engine, the JIT compiler of a JVM running in a web page can only take the form of "bytecode → JS/WASM", with the final machine code produced by the host engine. What is the real performance level of that chain?**

Four execution stacks were compared:

| Execution stack | Location | Description |
|---|---|---|
| HotSpot `-Xint` | local | Pure interpreter baseline |
| HotSpot JIT (C1/C2) | local | Native JIT baseline |
| CheerpJ | browser | The JVM's JIT emits JS, which the host engine then compiles; its interpreter is implemented in WASM |
| b-jvm | browser | Pure WASM interpreter (its JIT, `dumb_jit`, is unconditionally disabled by the author in the current source; the measured equality of the jit/interp forms confirms this) |

## 2. Experimental design

A single `Bench.java`, 7 workloads × 2 invocation forms:

**Workload dimensions**:

| Code | Content | What it probes |
|---|---|---|
| MATH | `Math.sin` accumulation | Native-bridging overhead; expected to show the smallest JIT gain |
| INT | int summation loop | Basic code quality; HotSpot performs induction-variable elimination |
| LONG | long summation loop | The cost of representing 64-bit integers on a JS target |
| ARRAY | Array access + predictable branch | Bounds-check elimination capability |
| INVOKE | High-frequency calls to a tiny method | Inlining capability |
| ALLOC | Small object allocated per iteration | Escape analysis / scalar replacement capability |
| VIRTUAL | Monomorphic interface call | Devirtualization + inlining capability |

**Form control** (the only between-group variable):

- interp form: 3 large calls of a single method (millions of iterations each), with the invocation count kept below the tier-up threshold;
- jit form: 800 warm-up calls + 5 rounds × 400 small calls, crossing the invocation-count threshold to trigger JIT compilation.

**Self-verification**: the checksum for each workload must agree across forms and across runtimes (all data points report chk=OK); per-round output makes tier-up and deoptimization directly visible.

**An important methodological lesson**: form control works for HotSpot but is unsafe for CheerpJ — its tiering counters are more aggressive than expected and compilation is triggered asynchronously, so some "interp form" data points were in fact already compiled (see the contamination annotations in Section 3). b-jvm has no JIT, so its two forms are naturally equivalent, which makes it the cleanest interpreter reference group.

## 3. Main data matrix

Units are ns/iteration; values are the mean over the 5 rounds of the jit form. **Bold** marks the fastest value among the four stacks for that workload.

| Workload | HS -Xint | HS JIT | CJ JIT (Gecko) | CJ JIT (Chromium) | CJ JIT (WebKit) | b-jvm (Gecko) | b-jvm (Chromium) | b-jvm (WebKit) |
|---|---|---|---|---|---|---|---|---|
| MATH | 9.61 | 1.96 | 26.2 | **16.6** | 23.1 | 202.5 | 42.4 | **14.5** |
| INT | 3.88 | 0.24 | **0.23** | **0.23** | **0.22** | 87.8 | 27.2 | 6.19 |
| LONG | 3.85 | 0.24 | **1.44** | 17.7 | 18.7 | 95.1 | 21.2 | 6.77 |
| ARRAY | 5.17 | 0.24 | 1.55 | **1.29** | 19.5 | 136.6 | 30.2 | 9.47 |
| INVOKE | 11.05 | 0.02 | **1.60** | 11.1 | 11.7 | 297.1 | 92.6 | 28.2 |
| ALLOC | 29.3 | 0.23 | 1.37 | **1.10** | 18.8 | 584–590 | 171.5 | 59.8 |
| VIRTUAL | 12.9 | 0.02 | **1.36** | 11.2 | 11.7 | 334.7 | 107.0 | 36.1 |

### CheerpJ interpreted-form reference (with contamination annotations)

| Workload | Gecko | Chromium | WebKit | Note |
|---|---|---|---|---|
| MATH | 30.0 | 22.6 | 38.0 | Not contaminated (interp and jit values differ) |
| INT | 0.60* | 4.32 | 0.30* | *Contaminated by compilation; 0.6 ns/iter is below the physical floor for interpretation |
| LONG | 11.2 | 10.5 | 18.8 | Most likely genuinely interpreted |
| ARRAY | 4.20 | 3.74 | 19.0 | FF/Ch most likely genuinely interpreted |
| INVOKE | 4.20 | 3.62 | 12.0 | Medium confidence |
| ALLOC | 10.7 | 9.17 | 21.0 | Agrees with the 11.2 ns of an independent experiment; most trustworthy |
| VIRTUAL | 4.00 | 3.13 | 12.3 | Medium confidence |

## 4. Per-dimension analysis

### 4.1 Compact integer loops: all three engines reach HotSpot C2 levels

Under CheerpJ JIT, INT measures 0.22–0.23 ns/iter on all three engines, exactly on par with HotSpot JIT (0.24). That is roughly one clock cycle per iteration, which means the JS emitted by CheerpJ is optimized by V8, SpiderMonkey, and JSC alike into an extremely tight loop. **The ceiling of the "compile to JS" route is not capped by the engine — at least for the simplest numeric code.**

### 4.2 The 64-bit tax: corrected from "an inherent cost of the route" to "a pathological engine combination"

The three-engine data for LONG (Gecko/SpiderMonkey 1.44 / Chromium/V8 17.7 / WebKit/JavaScriptCore 18.7) overturns the intermediate conclusion formed before this round, that the long tax was specific to V8: **JSC carries the tax as well; only SpiderMonkey does not.** The b-jvm control group (native WASM i64) shows an LONG/INT ratio ≤ 1.2 on all three engines, which proves the WASM route incurs no such tax. The conclusion: the long tax is a compound property of "the JS representation of long in CheerpJ's output × the specific engine's optimization path". When choosing the "compile to JS" route, it is an **engine risk** rather than a fixed cost.

### 4.3 Call-intensive workloads: a pathological point shared by V8 and JSC

INVOKE/VIRTUAL show a clear polarization: 1.6 ns on SpiderMonkey versus 11.1–11.7 ns on both V8 and JSC (a factor of 7). Moreover, on JSC this value is nearly identical to CheerpJ's own interpreted state (12.0) — **in Safari, the net benefit of CheerpJ's JIT for call-intensive code is approximately zero** (INVOKE: interpreted 12.0 → compiled 11.7). V8 and JSC cannot effectively inline the generated JS for this class of "tiny method body, extremely high call frequency", which is the dimension with the largest cross-engine divergence.

### 4.4 Allocation and arrays: the bimodal behaviour of JSC

ALLOC and ARRAY measure 1.1–1.6 ns on Gecko/Chromium but land at 18.8–19.5 ns on WebKit. JSC exhibits clear **bimodality**: either it optimizes to the extreme (INT 0.22), or it stalls at a ~19 ns plateau (LONG/ARRAY/ALLOC are almost identical values). A reasonable inference (unconfirmed): JSC applies only baseline-tier optimizations to these patterns in CheerpJ's output and never enters the optimal tier, or its property/array inline caches are unfavourable to the shape of the generated code. The fact that three distinct workloads share the same 19 ns plateau itself supports the "stuck at the same non-optimized tier" explanation.

### 4.5 MATH: the only dimension where cross-stack "loss" is visible

CheerpJ JIT measures 16.6–26.2 ns across engines against 1.96 ns for HotSpot. The gap lies not in `sin` itself but in the native bridge crossed on every call. It is independent of JIT quality (which is precisely why this dimension was designed as the "smallest JIT gain" control), and it yields a general signal: **when the bottleneck of a workload lies in host runtime services rather than in bytecode execution, the improvement headroom of the "JVM JIT" chain is structurally capped.**

### 4.6 ALLOC and GC: a snapshot of each engine

- b-jvm (FF) ALLOC round0 = 3336 ns, subsequently stable at 584 ns: the first collection of its single-threaded compacting GC was captured in full, with the pause approximately 5.7× the steady state;
- b-jvm's steady-state allocation (Safari 59.8 ns) is still 2× slower than HotSpot `-Xint` (29.3 ns), but its amplification factor relative to its own other workloads is the smallest — bump allocation plus a compacting GC is b-jvm's strongest relative dimension.

## 5. Cross-engine findings

### 5.1 The engine sensitivity of an interpreter far exceeds that of generated code

b-jvm (a WASM tail-call interpreter) spans a factor of **3.4–4.8×** across engines; CheerpJ's JIT output spans typically < 2× across engines (outside the pathological dimensions). **An interpreter's dependence on the quality of the host's WASM engine is far greater than generated code's dependence on the quality of the host's JS engine** — the latter because the generated code is simple and regular enough that every vendor optimizes it well, whereas the former is determined directly by the quality of the tail-call implementation.

### 5.2 The engine ranking inverts entirely depending on the workload class

- WASM interpreter (b-jvm): **WebKit < Chromium < Gecko** (WebKit fastest at 6.2 vs 27.2 vs 87.8 ns; Gecko 14× slower). WebKit's WASM tail-call implementation is the youngest (the stack-argument-form tail call was only fixed in 2024-08) yet is the fastest of the three — the most unexpected result of this round, and it refuted the pre-measurement prediction that "the new WebKit implementation is most likely on the slow side".
- CheerpJ-generated JS: **SpiderMonkey is best overall** (no pathological dimension across the seven workloads); V8 and JSC each have pathological dimensions (long, call-intensive; JSC additionally alloc/array).

No single engine leads on both classes of workload. **"Which browser engine runs Java fastest" has no single answer — it depends on whether the execution stack is an interpreter or JIT-generated code.**

### 5.3 The real risk of deoptimization (Chrome, previous round)

On Chrome, CheerpJ INT once exhibited a complete deoptimization lifecycle: rounds 0–2 at 0.23 ns, then rounds 3–4 jumping back to 4.24 ns and never recovering. Generated code is not a terminal state; the host may reclaim optimizations at any time — this is an inherent risk of the architecture, and measurement must emit per-round output to capture it.

## 6. Returning to the original question: JVMs on iOS

macOS Safari and iOS share the same browser engine, WebKit (both the JS and WASM engines being JavaScriptCore), so Safari/WebKit can be read as the iOS column (bar differences in resources such as memory):

1. **Running a JVM in a web page on iOS is entirely feasible**, and on its best dimension (compact int loops) it matches the per-iteration cycle count of desktop HotSpot C2.
2. CheerpJ (the JIT route) is weak on JSC for long, arrays, allocation, and method calls (a ~19 ns plateau and ~11.7 ns call overhead); its overall level is roughly "1–2× the speed of the HotSpot interpreter", far better than any WASM interpreter on the same engine (6.2–59.8 ns).
3. b-jvm (the interpretation route) is in fact the fastest of the three engines on JSC, with INT at 6.19 ns — only 1.6× slower than the local HotSpot interpreter. If the characteristics of an application cannot be effectively covered by CheerpJ's JIT (for example, call-intensive workloads), the optimal solution on iOS may be precisely an interpreter.
4. For native iOS apps (as opposed to web pages), the conclusion of the first round stands: generating machine code by oneself results in a SIGKILL from the kernel; delegating code generation to JSC (via WKWebView / the JS API) is the only legal path whose performance this experiment has verified to be acceptable.

## 7. Limitations and unconfirmed items

- Whether CheerpJ's compiled ALLOC performs scalar replacement cannot be distinguished (1.1–1.4 ns equals the floor of a loop without allocation); the origin of the JSC 19 ns plateau (the baseline-tier hypothesis) is an inference.
- Note on the removal of single-round outliers: Chrome b-jvm INT round2 = 52.3 ns was an isolated anomaly (neighbouring rounds 19–26) and does not affect the mean-based conclusions; FF b-jvm ALLOC round0 is a GC snapshot.
- All figures are confined to synthetic microbenchmarks and cannot be extrapolated directly to real application workloads; real Java programs with dense method calls will lean significantly towards the pathological dimensions identified in this report on V8/JSC.
