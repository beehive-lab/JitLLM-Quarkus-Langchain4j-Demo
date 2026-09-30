# tool-demo-ls

Demonstrates tool calling with JitLLM via the Quarkus LangChain4j `jitllm` provider.
The AI service is given a `listDirectory` tool and must call it to answer questions about directory contents.

## What it does

1. The LLM receives the user prompt and a `listDirectory` tool definition.
2. It emits a `<tool_call>` response naming the tool and the path argument.
3. Quarkus LangChain4j executes `DirectoryTools.listDirectory` (Java NIO, no shell). The listing is
   capped at 100 entries so that it fits in the model's context.
4. The result is fed back to the LLM, which produces a plain-text answer.

## Prerequisites and build

See the [root README](../../README.md): JDK 25, TornadoVM 7.0.1-jdk22plus, the local build of the
quarkus-langchain4j `jitllm` provider, then build all demos from the repo root.

## Configuration

Edit `src/main/resources/application.properties` to switch the model or tune sampling:

```properties
quarkus.langchain4j.jitllm.chat-model.model-name=ggml-org/Qwen3-4B-GGUF
quarkus.langchain4j.jitllm.chat-model.quantization=Q8_0
quarkus.langchain4j.jitllm.chat-model.temperature=0.6
quarkus.langchain4j.jitllm.chat-model.top-p=0.95
quarkus.langchain4j.jitllm.chat-model.max-tokens=8192
```

Qwen3-4B completes the tool round trip reliably; Llama 3.2 1B keeps re-calling the tool. See
"Choosing a model for the tool-calling demos" in the root README.

`max-tokens` is also the context length: the tool result and the answer, which echoes it, have to fit in it.
Model name and quantization are fixed at build time, so rebuild after changing them.

## Run

From the repo root:

```bash
scripts/run-tool-demo-ls.sh "Show me what is inside /tmp"
```

or directly:

```bash
java "@$(scripts/tornado-args.sh)" \
    --add-modules jdk.incubator.vector \
    -jar demos/tool-demo-ls/target/quarkus-app/quarkus-run.jar \
    "Show me what is inside /tmp"
```

The prompt is passed as command-line arguments after the jar. Without arguments it defaults to `"Show me what is inside /tmp"`.

### With batched prefill-decode

```bash
java "@$(scripts/tornado-args.sh)" \
    --add-modules jdk.incubator.vector \
    -Dquarkus.langchain4j.jitllm.chat-model.prefill-decode=true \
    -Dquarkus.langchain4j.jitllm.chat-model.prefill-batch-size=32 \
    -jar demos/tool-demo-ls/target/quarkus-app/quarkus-run.jar \
    "Show me what is inside $HOME/Desktop"
```

## Expected output

```
Tool Calling Demo — listDirectory
==================================
Prompt: Show me what is inside /tmp

Contents of /tmp:
dir:  .ICE-unix
file: .X0-lock, 11 bytes
...
```

`log-requests` and `log-responses` are enabled in `application.properties`, so the full tool-call round-trip
(`<tool_call>` response, tool execution, final answer) is visible in the Quarkus log output.
