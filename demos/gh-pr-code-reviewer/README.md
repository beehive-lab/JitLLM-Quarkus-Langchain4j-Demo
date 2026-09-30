# gh-pr-code-reviewer

Reviews a real GitHub pull request with Qwen3.8 27B running locally on the GPU through JitLLM and the
Quarkus LangChain4j `jitllm` provider.

```bash
scripts/run-gh-pr-code-reviewer.sh https://github.com/beehive-lab/TornadoVM/pull/1132
```

## What it does

1. Parses the pull request link (`https://github.com/<owner>/<repo>/pull/<number>`, with or without a
   trailing `/files`).
2. Fetches the pull request and its unified diff through a Quarkus REST client
   ([`GitHubClient`](src/main/java/demo/GitHubClient.java)).
3. Prepares the diff ([`DiffFormatter`](src/main/java/demo/DiffFormatter.java)):
   - prefixes every added and unchanged line with its line number in the new file, so findings can cite
     `path:line` without the model counting from hunk headers;
   - drops lock files, binaries and generated assets;
   - includes whole files until the diff budget (`gh-pr-code-reviewer.max-diff-chars`, default 60000, or
     `--max-diff-chars`) runs out, and lists the rest as not reviewed. The model is told which files it
     cannot see.
4. Streams the review from an AI service ([`CodeReviewer`](src/main/java/demo/CodeReviewer.java))
   that returns `Multi<ChatEvent>`: the model's reasoning is printed dimmed as it is generated, then the
   review in Markdown — **Summary**, **Findings** (severity, `path:line`, problem and fix) and **Verdict**.

The command line is a `quarkus-picocli` command ([`ReviewCommand`](src/main/java/demo/ReviewCommand.java)).
Its own settings are a `@ConfigMapping` ([`CodeReviewerConfig`](src/main/java/demo/CodeReviewerConfig.java),
prefix `gh-pr-code-reviewer`). The same code is proposed upstream as the quarkus-langchain4j `gh-pr-code-reviewer`
sample.

## Prerequisites

- The setup in the [root README](../../README.md): JDK 25, TornadoVM 7.0.1-jdk22plus, the local build of
  the quarkus-langchain4j `jitllm` provider.
- A GPU with 24 GB. The model file is 16 GB; the demo reserves 22 GB of device memory
  (`device-memory`). A review of a 46-file pull request peaked at 19.9 GB.
- The model, `unsloth/Qwen3.8-27B-GGUF` / `Qwen3.8-27B-Q4_0.gguf`, is downloaded on first launch to
  `~/.langchain4j/models/unsloth_Qwen3.8-27B-GGUF/`. To reuse a copy you already have:

  ```bash
  mkdir -p ~/.langchain4j/models/unsloth_Qwen3.8-27B-GGUF
  ln -s /path/to/Qwen3.8-27B-Q4_0.gguf ~/.langchain4j/models/unsloth_Qwen3.8-27B-GGUF/
  touch ~/.langchain4j/models/unsloth_Qwen3.8-27B-GGUF/.finished
  ```

- Optional: `GITHUB_TOKEN`, for private repositories or to lift GitHub's unauthenticated limit of
  60 requests per hour (each review makes two). With the GitHub CLI: `export GITHUB_TOKEN=$(gh auth token)`.

## Run

```bash
scripts/run-gh-pr-code-reviewer.sh <pull-request-url> [--max-diff-chars N]
```

| Option | Effect |
|---|---|
| `--max-diff-chars N` | Diff budget in characters; with the review it must fit in `max-tokens` (32768) |

Exit codes: `0` review printed, `1` GitHub or model error, `2` not a pull request link.

## How long it takes

Measured on an RTX 5090 Laptop GPU (CUDA), including model loading and the first run's kernel compilation:

| Pull request | Size | Prompt | First output | Total |
|---|---|---|---|---|
| [TornadoVM#1132](https://github.com/beehive-lab/TornadoVM/pull/1132) | 2 files, +86 | 4k tokens | 22 s | 1.7 min |
| [quarkus-langchain4j#2888](https://github.com/quarkiverse/quarkus-langchain4j/pull/2888) (16 of 46 files, budget) | +3453 −2812 | 22k tokens | 47 s | 4.3 min |

Before the first output: reading the model file (about 3 s), compiling its kernels, uploading the weights
and prefilling the prompt. After it: the reasoning and the review, at 25-32 tokens/s. Measured with
`prefill-batch-size=512`.

## Configuration

`src/main/resources/application.properties`:

- `enable-thinking=true`: the model reasons before answering. It makes a review take minutes longer, but
  without it the model misreads diffs more often. On quarkus-langchain4j#2888, for example, it reported
  a documentation include as broken because it read the removed line and missed the added one. Set it to
  `false` for faster, shallower reviews. It is a runtime property, so no rebuild is needed:
  `-Dquarkus.langchain4j.jitllm.chat-model.enable-thinking=false`.
- `temperature=0.2`: a review should stay close to the diff.
- `max-tokens=32768`: also the context length. The prompt, the reasoning and the review must fit in it
  together.
- `prefill-decode=true`, `prefill-batch-size=512`: batched prefill, 512 prompt tokens at a time. The
  prompt is a whole diff: on quarkus-langchain4j#2888 (22k tokens) width 512 reached the first token in
  47 s against 149 s at width 32, and sequential prefill was slower still (2.4× slower than width 32 on
  TornadoVM#1132).

`application.properties` is packaged into the jar, so rebuild after editing it — or override a runtime
property on the command line without rebuilding, e.g. `-Dquarkus.langchain4j.jitllm.chat-model.prefill-batch-size=256`.
Model name and quantization are build-time only.

## Logging

At the default `INFO` level the log shows each step before the review starts:

```
INFO  [demo.ReviewCommand] Fetching quarkiverse/quarkus-langchain4j#2888 from the GitHub API
INFO  [demo.ReviewCommand] Prepared 59,761 characters of annotated diff from 16 of 46 file(s) (budget 60,000)
INFO  [demo.ReviewCommand] Starting the review. The model reads the whole prompt before its first token; ...
INFO  [io.quarkiverse.langchain4j.jitllm.JitLLMModelHolder] JitLLM model initialization {modelPath=..., prefillBatchSize=512, ...}
INFO  [org.beehive.jitllm.model.loader.AbstractModelLoader] Loaded model weights in TornadoVM format (Q4_0 -> Q4_0)
INFO  [io.quarkiverse.langchain4j.jitllm.JitLLMModelHolder] JitLLM model initialization complete! (QWEN_3_5: ...)
INFO  [org.beehive.jitllm.backend.tornado.TornadoVMMasterPlanBatchPrefillDecode] Starting TornadoVM initialization...
INFO  [org.beehive.jitllm.backend.tornado.TornadoVMMasterPlanBatchPrefillDecode] TornadoVM device: NVIDIA CUDA (cuda) capabilities ...
INFO  [org.beehive.jitllm.backend.tornado.TornadoVMMasterPlanBatchPrefillDecode] qwen35 batched prefill: width 512: ...
INFO  [demo.ReviewCommand] ... 15 s, no output yet
INFO  [demo.ReviewCommand] ... 30 s, no output yet
...
INFO  [demo.ReviewCommand] Prompt: 22,126 tokens. Generated: 4,420 tokens (reasoning and review). First output after 46.2 s, review completed in 223.2 s
```

`demo.ReviewCommand` lines come from the demo, `io.quarkiverse.langchain4j.jitllm` from the extension and
`org.beehive.jitllm` from the engine. The engine's lines about weight loading and TornadoVM
initialization need `-Djitllm.EnableTimingForTornadoVMInit=true`, which `scripts/run-gh-pr-code-reviewer.sh`
passes. For more, raise a category, e.g. `-Dquarkus.log.category.\"org.beehive.jitllm\".level=DEBUG`. The
progress interval is `gh-pr-code-reviewer.progress-interval` (default `15s`).

## Limitations

- The model sees the diff only: the changed hunks, not the rest of each file or of the repository. It is
  told not to report code outside the hunks as missing, but a finding can still be wrong. Treat the review
  as a first pass, not as a verdict.
- Pull requests larger than the diff budget are reviewed in part, in diff order. For very large pull
  requests GitHub refuses to produce a diff at all, and the demo says so.
