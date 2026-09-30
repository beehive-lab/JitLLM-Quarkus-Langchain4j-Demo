# Quarkus + LangChain4j + JitLLM Demos

A collection of Quarkus applications that drive [JitLLM](https://github.com/beehive-lab/jitllm) via the
[quarkus-langchain4j](https://github.com/quarkiverse/quarkus-langchain4j) `jitllm` provider. Each demo lives under
`demos/` as its own Maven module and can be built once at the repo root and run independently.

Inspired by: <https://docs.quarkiverse.io/quarkus-langchain4j/dev/quickstart-summarization.html>

## Demos at a glance

| Module | What it shows | Model | Default prompt |
|---|---|---|---|
| [`demos/chat-summarization`](demos/chat-summarization) | Blocking chat-model summarization service | Llama 3.2 1B F16 | `SampleTextToSummarize.txt` |
| [`demos/streaming-summarization`](demos/streaming-summarization) | Token-streaming summarization service | Llama 3.2 1B F16 | `SampleTextToSummarize.txt` |
| [`demos/tool-demo-ls`](demos/tool-demo-ls) | Tool calling — LLM invokes a `listDirectory` tool | Qwen3 4B Q8_0 | `Show me what is inside /tmp` |
| [`demos/java-coder-demo`](demos/java-coder-demo) | Code generation, then `writeFile` / `buildAndRun` tool calls | Qwen3 4B Q8_0 | `Write a Java class to print HelloWorld` |
| [`demos/java-coder-iterative`](demos/java-coder-iterative) | Generate → compile → ask LLM to fix on error (up to 3 attempts) | Qwen3 4B Q8_0 | `Write a matrix multiplication Java program` |
| [`demos/gh-pr-code-reviewer`](demos/gh-pr-code-reviewer) | Code review of a real GitHub pull request, with streamed reasoning | Qwen3.8 27B Q4_0 | a pull request link (required) |

The other demos reserve 8 GB of device memory (`device-memory`); `gh-pr-code-reviewer` reserves 22 GB and needs a
24 GB GPU: its model file alone is 16 GB.

## 1. Prerequisites

### Java 25

```bash
sdk install java 25.0.2-open
sdk use     java 25.0.2-open
java -version   # openjdk 25 ...
```

### TornadoVM 7.0.1

```bash
sdk install tornadovm 7.0.1-jdk22plus-cuda   # or 7.0.1-jdk22plus-opencl
sdk use     tornadovm 7.0.1-jdk22plus-cuda
echo "$TORNADOVM_HOME"   # must be set; SDKMAN sets it automatically
tornado --devices
```

JitLLM is published in two lines, each paired with the TornadoVM SDK for the same JDK line. These demos
build on JDK 25, so they use the `jdk22plus` line of both.

> **`sdk use` vs `sdk default`:** `$TORNADOVM_HOME/tornado-argfile` hardcodes paths under
> `.../tornadovm/current/`, i.e. the SDKMAN *default* install — so `sdk use` alone can leave the
> demos running on a different TornadoVM (or backend) than `$TORNADOVM_HOME` names. The run scripts
> avoid this by expanding `tornado-argfile.template` via [`scripts/tornado-args.sh`](scripts/tornado-args.sh);
> pass the same argfile to the build (see below) or use `sdk default`.

### quarkus-langchain4j with the `jitllm` provider (local build)

The `jitllm` provider — the JitLLM 1.0.2 port and the rename of the former `gpu-llama3` extension — is in
review upstream as [quarkiverse/quarkus-langchain4j#2888](https://github.com/quarkiverse/quarkus-langchain4j/pull/2888)
and not released yet. Until it is, build that PR's branch and install it as `999-SNAPSHOT`:

```bash
git clone https://github.com/quarkiverse/quarkus-langchain4j.git
cd quarkus-langchain4j
git fetch origin pull/2888/head:jitllm-provider
git checkout jitllm-provider
mvn install -DskipTests -pl model-providers/jitllm/deployment -am
```

Once a quarkus-langchain4j release contains the provider, set `<quarkus-langchain4j.version>` in the root
[`pom.xml`](pom.xml) to it and skip this step.

### Versions

| Component | Version | Source |
|---|---|---|
| Quarkus | 3.33.3.1 | Maven Central |
| quarkus-langchain4j (`jitllm` provider) | 999-SNAPSHOT | local build of PR #2888 |
| `io.github.beehive-lab:jitllm` | 1.0.2-jdk22plus | Maven Central |
| TornadoVM | 7.0.1-jdk22plus (CUDA or OpenCL) | SDKMAN |

All are pinned in the root [`pom.xml`](pom.xml). `jitllm` is pinned there explicitly: the extension picks
its JitLLM line through a JDK profile in its own parent, which is not reliable to inherit. A locally installed
jar that reuses one of these coordinates (e.g. a `jitllm` built from JitLLM `main`) shadows the published
one and surfaces as a runtime `NoSuchMethodError` / `ClassNotFoundException`, not a build failure.

## 2. Build all demos

From the repo root:

```bash
./mvnw clean install -Dtornado.argfile="$(scripts/tornado-args.sh)"
```

This produces a runnable `target/quarkus-app/quarkus-run.jar` inside each `demos/<demo>/` directory.

> **Models:** each demo downloads its GGUF model to `~/.langchain4j/models/` on first launch. To reuse a
> GGUF you already have, put it (or a symlink to it) where the cache expects it, next to an empty
> `.finished` marker, e.g. `~/.langchain4j/models/ggml-org_Qwen3-4B-GGUF/Qwen3-4B-Q8_0.gguf`. Model name
> and quantization are fixed at build time, so rebuild after changing them.

## 3. Run a demo

Two equivalent options for every demo: the helper script under `scripts/`, or `java @tornado-argfile ... -jar ...` directly.

### chat-summarization (blocking)

```bash
scripts/run-chat-summarization.sh
```

With batched prefill-decode (runtime properties, no rebuild needed):

```bash
java "@$(scripts/tornado-args.sh)" \
    --add-modules jdk.incubator.vector \
    -Dquarkus.langchain4j.jitllm.chat-model.prefill-decode=true \
    -Dquarkus.langchain4j.jitllm.chat-model.prefill-batch-size=32 \
    -jar demos/chat-summarization/target/quarkus-app/quarkus-run.jar
```

### streaming-summarization (token-streamed)

```bash
scripts/run-streaming-summarization.sh
```

### tool-demo-ls (tool calling)

```bash
scripts/run-tool-demo-ls.sh                          # default: "Show me what is inside /tmp"
scripts/run-tool-demo-ls.sh "Show me what is inside $HOME/Desktop"
```

The prompt is forwarded to the demo. `log-requests` / `log-responses` are enabled in this demo's
`application.properties`, so the full tool-call round-trip is visible in the Quarkus log. The tool
returns at most 100 entries, so that the listing fits in the model's context.

### java-coder-demo (generate + write + run)

```bash
scripts/run-java-coder-demo.sh                       # default: "Write a Java class to print HelloWorld"
scripts/run-java-coder-demo.sh "Write a matmul in Java"
```

### java-coder-iterative (generate → compile → fix loop)

```bash
scripts/run-java-coder-iterative.sh                  # default: matrix multiplication
scripts/run-java-coder-iterative.sh "Write a quicksort in Java"
```

### gh-pr-code-reviewer (review a GitHub pull request)

```bash
scripts/run-gh-pr-code-reviewer.sh https://github.com/beehive-lab/TornadoVM/pull/1132
scripts/run-gh-pr-code-reviewer.sh https://github.com/owner/repo/pull/123 --max-diff-chars 30000
```

Fetches the pull request and its diff from the GitHub REST API, then streams the model's reasoning
(dimmed) and a Markdown review: summary, findings with severity and `path:line`, verdict. Set
`GITHUB_TOKEN` for private repositories or to lift GitHub's unauthenticated rate limit. Expect minutes,
not seconds, per review; see [its README](demos/gh-pr-code-reviewer/README.md).

## 4. Dev mode (no need to repackage)

Each module can also be run via `quarkus:dev`, which is handy when iterating on a single demo:

```bash
cd demos/tool-demo-ls
../../mvnw quarkus:dev -Dtornado.argfile="$(../../scripts/tornado-args.sh)" -Dquarkus.args="Show me what is inside /tmp"
```

## 5. Tuning

Each demo has its own `src/main/resources/application.properties`. Common knobs:

- `quarkus.langchain4j.jitllm.chat-model.model-name` — Hugging Face GGUF repo (e.g. `ggml-org/Qwen3-4B-GGUF`)
- `quarkus.langchain4j.jitllm.chat-model.quantization` — `Q8_0`, `F16`, ...
- `quarkus.langchain4j.jitllm.chat-model.temperature`, `.top-p`
- `quarkus.langchain4j.jitllm.chat-model.max-tokens` — also the context length: prompt, tool results and
  answer must fit in it together. When they do not, the model returns an empty answer.
- `quarkus.langchain4j.jitllm.chat-model.device-memory` — TornadoVM heap, tune to your GPU. The extension
  sets this itself, so the `-Dtornado.device.memory` JVM flag is ignored (its 4GB default is too small
  for the F16 and 4B models used here).
- `quarkus.langchain4j.jitllm.chat-model.prefill-decode=true` and `.prefill-batch-size=<N>` — enable
  (batched) prefill-decode.
- `quarkus.langchain4j.jitllm.chat-model.enable-thinking` — Qwen3 reasoning phase, off by default.

### Choosing a model for the tool-calling demos

The tool demos default to Qwen3-4B Q8_0, which on JitLLM 1.0.2 calls the tool once, reads the result and
answers from it. Smaller models do not complete the round trip reliably:

| Model | Behaviour in the tool demos |
|---|---|
| Qwen3-4B Q8_0 | Correct tool call and a correct final answer in all three demos |
| Qwen3-1.7B Q8_0 | Correct tool calls; answers are thinner (e.g. reports the class name as the program output) |
| Qwen3-0.6B F16 | Round trip works in `tool-demo-ls`; the coder demos generate broken code or skip `buildAndRun` |
| Llama 3.2 3B Q8_0 | Calls the tool once, then describes the call instead of reporting the result |
| Llama 3.2 1B Q8_0/F16 | Keeps re-calling the tool until LangChain4j stops it: `exceeded 10 sequential tool executions` |

The two summarization demos use no tools and run well on Llama 3.2 1B F16.

## 6. Tested with

CUDA backend, RTX 5090 Laptop GPU (24 GB): JDK 25.0.2, TornadoVM 7.0.1-jdk22plus-cuda, JitLLM
1.0.2-jdk22plus, quarkus-langchain4j PR #2888 (`595955d02`), Quarkus 3.33.3.1. All demos with the
committed configuration, plus chat-summarization with batched prefill-decode (batch 32). `gh-pr-code-reviewer`
on pull requests from 2 to 46 changed files.
