# borrowed-jit

### 借来的 JIT —— 当浏览器内核自己承担 JIT 时，浏览器内 JVM 执行栈的真实性能

用于测量 **运行在浏览器内** 的 JVM 执行栈性能的基准测试工具，以及由它产出的实测报告。

核心问题：当唯一允许执行 JIT 的组件是浏览器内核自身时，一个只能走 "字节码 → JS/WASM" 路线的
JVM，其真实性能究竟如何？

在这种架构下，JIT 是**借来的**。b-jvm 作为 WASM 解释器运行，其产物由浏览器重新优化；
CheerpJ 的 JIT 生成 JS，再由浏览器二次编译。两者都不掌握让自己变快的分层编译——那是宿主内核的
事情。本项目测量的正是这种安排的真实代价，以及它在哪里反而占优。

<p align="center">
    Reports:
    <a href="./report_zh-cn.md">中文报告</a> |
    <a href="./report.md">English report</a>
</p>

<p align="center">
    READMEs:
    <a href="./README_zh-cn.md">中文自述文件</a> |
    <a href="../README.md">English README</a>
</p>

---

## 测量内容

使用单一的 `Bench.java`，7 个负载 × 2 种调用形态，对比四条执行栈：

| 执行栈 | 位置 | 角色 |
|---|---|---|
| HotSpot `-Xint` | 本地 | 纯解释器基线 |
| HotSpot JIT (C1/C2) | 本地 | 原生 JIT 基线 |
| CheerpJ 4.3 | 浏览器 | JVM 的 JIT 生成 JS，由宿主内核再编译 |
| b-jvm | 浏览器 | 纯 WASM 解释器（其 JIT 在上游被禁用） |

负载：`MATH`（native 桥接）、`INT`（紧凑循环）、`LONG`（64 位成本）、`ARRAY`（边界检查）、`INVOKE`（内联）、`ALLOC`（逃逸分析）、`VIRTUAL`（去虚化）。

度量指标为 **每次迭代的纳秒数**。每个负载附带校验和（`chk=OK`），用以证明所有形态与运行时计算结果完全一致。

## 核心结论

- CheerpJ JIT 下的紧凑 int 循环在三个引擎上均达到 **0.22–0.23 ns/迭代**，与桌面 HotSpot C2（0.24）持平。
- 引擎排名 **随负载类型完全颠倒**：WASM 解释器（b-jvm）以 WebKit 最快，CheerpJ 生成的 JS 则以 SpiderMonkey 最优。
- 在 Safari / iOS 上，对于调用密集型代码，解释器路线（b-jvm）可能反而优于 JIT 路线（CheerpJ）。

完整数据矩阵与逐维度分析见 [`report_zh-cn.md`](report_zh-cn.md)。

---

## 目录结构

```
.
├── README.md              # 本文件（英文）
├── LICENSE                # MIT
├── .gitignore
├── Bench.java             # 基准测试源码（7 负载 × 2 形态）
├── bench.jar              # 编译后的基准测试，两个浏览器 harness 均加载此文件
├── bjvm-bench.html        # b-jvm harness（浏览器，纯解释器基线）
├── cheerpj-bench.html     # CheerpJ harness（浏览器，解释形态 vs JIT 形态）
├── docs/
│   ├── README_zh-cn.md    # 中文版说明
│   ├── report.md          # 测量报告（英文）
│   └── report_zh-cn.md    # 测量报告（中文）
└── bjvm-assets/           # b-jvm WASM 构建产物 + JDK 23 运行时（约 54 MB）
    ├── bjvm_main.js
    ├── bjvm_main.wasm
    ├── jdk23.jar
    └── jdk23/
        ├── conf/security/{java.security,java.policy}
        └── lib/{modules,security/default.policy}
```

## 环境要求

- 用于重新编译 `bench.jar` 的 JDK（实测使用 Temurin 21，JDK 17+ 应当均可）
- 主流浏览器（Firefox / Chromium / Safari）；CheerpJ 首次使用时会联网加载运行时
- Python 3（或任意静态文件服务器）用于托管页面

## 使用方法

### 1. 启动静态服务

两个 harness 都通过 `fetch` 加载 WASM、JAR 与类库文件，因此必须经 HTTP 访问——直接以 `file://` 打开会因 CORS 失败。

```bash
cd borrowed-jit
python3 -m http.server 8765
```

### 2. 运行浏览器 harness

打开对应页面并点击 **Run benchmark**：

- <http://localhost:8765/bjvm-bench.html> — b-jvm（纯解释器基线）
- <http://localhost:8765/cheerpj-bench.html> — CheerpJ（解释形态 vs JIT 形态）

harness 页面为英文；本文件与 [`report_zh-cn.md`](report_zh-cn.md) 提供中文说明。

> b-jvm 页面首次加载需下载约 54 MB 的 JDK 23 运行时；HTTP 缓存生效后重访会快很多。

### 3. 运行本地 HotSpot 基线

```bash
# 纯解释器
java -Xint -cp bench.jar Bench

# 默认 JIT
java -cp bench.jar Bench
```

输出为 CSV：`wl,form,round,ns_per_iter`，每条 interp 形态行附带 `chk=OK` 标记。

### 4. 重新编译 `bench.jar`（可选）

```bash
javac -d build Bench.java
jar cfe bench.jar Bench -C build .
```

## 输出解读

每个负载输出形如：

```
INT,jit,round0,0.59
INT,jit,round1,0.59
...
INT,jit,round4,0.59
INT,interp,main,0.59,chk=OK
INT,jit,avg,0.59,best=0.59,postJIT_bigN_ms=5.93
```

- `jit,roundN` — 800 次预热后的逐轮测量；**逐轮输出至关重要**，因为它能让 tier-up 与去优化直接可见（某轮耗时跳高且不再回落的，表现为去优化而非噪声）
- `interp,main` — 单次测量的调用（低于分层编译阈值），在 jit 各轮之后输出
- `jit,avg` — 5 轮均值，另附最佳轮次与 JIT 化后的大调用耗时
- `chk=OK` — JIT 形态的校验和与解释形态一致

## 方法学注意事项

- **形态控制对 HotSpot 有效，但对 CheerpJ 不安全。** 其分层计数比预期激进且编译触发异步，导致部分 "interp 形态" 数据点实际已被编译，这些点在报告中有明确标注。
- **b-jvm 没有 JIT**（上游的 `dumb_jit` / `attempt_jit` 被无条件禁用），两种形态天然等价，因而构成最干净的解释器参考组。
- **跨引擎比较要求浏览器版本一致。** 报告数据取自 Firefox 158.0.1、Chrome 155.0.8059.86、Safari 26.6.2（macOS，Apple M4 Max，arm64）。
- 全部数字均来自 **合成微基准**，不可直接外推到真实应用负载。

## 许可证

基于 [MIT 许可证](../LICENSE) 发布。
