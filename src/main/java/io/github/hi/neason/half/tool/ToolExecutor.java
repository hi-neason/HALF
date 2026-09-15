package io.github.hi.neason.half.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hi.neason.half.model.ContentBlock;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 顺序执行显式注册的工具；不请求模型、不订阅参数分片、不自行重试。 */
public final class ToolExecutor {
    private static final int MAX_ARGUMENT_CHARACTERS = 1024 * 1024;
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();
    private final ToolRegistry registry;
    private final int maxOutputCharacters;

    public ToolExecutor(ToolRegistry registry) {
        this(registry, 16_000);
    }

    public ToolExecutor(ToolRegistry registry, int maxOutputCharacters) {
        this.registry = Objects.requireNonNull(registry, "registry");
        if (maxOutputCharacters < 64) throw new IllegalArgumentException("Output limit must be at least 64 characters");
        this.maxOutputCharacters = maxOutputCharacters;
    }

    /** 调用方只在整轮模型响应成功完成后传入完整调用；重复调用本方法会再次执行。 */
    public ToolResult execute(ContentBlock.ToolCall call) throws InterruptedException {
        return execute(call, () -> false, ignored -> { });
    }

    public ToolResult execute(ContentBlock.ToolCall call, BooleanSupplier cancelled,
                              Consumer<ToolProgress> onProgress) throws InterruptedException {
        Objects.requireNonNull(call, "call");
        var context = new ToolContext(call.id(), call.name(), cancelled, onProgress, maxOutputCharacters);
        ToolResult result;
        try {
            result = execute(call, context);
        } finally {
            context.finish();
        }
        context.rethrowObserverFailure();
        return result;
    }

    private ToolResult execute(ContentBlock.ToolCall call, ToolContext context) throws InterruptedException {
        context.checkCancelled();
        var tool = registry.find(call.name());
        if (tool.isEmpty()) return failure(call, ToolResult.Status.UNKNOWN_TOOL);
        ObjectNode arguments;
        try {
            arguments = parseArguments(call.arguments());
        } catch (JsonProcessingException | ToolArgumentsException error) {
            context.checkCancelled();
            return failure(call, ToolResult.Status.INVALID_ARGUMENTS);
        }
        ToolOutput output;
        try {
            context.checkCancelled();
            output = tool.get().execute(arguments, context);
        } catch (InterruptedException | CancellationException error) {
            throw error;
        } catch (ToolArgumentsException error) {
            context.rethrowObserverFailure();
            context.checkCancelled();
            return failure(call, ToolResult.Status.INVALID_ARGUMENTS);
        } catch (Exception error) {
            context.rethrowObserverFailure();
            context.checkCancelled();
            return failure(call, ToolResult.Status.EXECUTION_FAILED);
        }
        context.rethrowObserverFailure();
        context.checkCancelled();
        if (output == null) return failure(call, ToolResult.Status.EXECUTION_FAILED);
        return new ToolResult(call.id(), call.name(), ToolResult.Status.SUCCESS,
                ToolOutput.limit(output.text(), maxOutputCharacters), output.details(),
                output.text().length() > maxOutputCharacters);
    }

    /** 同批调用 ID 必须唯一；错误结果不中止后续工具，中断和取消则立即向上传播。 */
    public List<ToolResult> executeAll(List<ContentBlock.ToolCall> calls) throws InterruptedException {
        return executeAll(calls, () -> false, ignored -> { });
    }

    public List<ToolResult> executeAll(List<ContentBlock.ToolCall> calls, BooleanSupplier cancelled,
                                     Consumer<ToolProgress> onProgress) throws InterruptedException {
        var batch = List.copyOf(calls);
        Objects.requireNonNull(cancelled, "cancelled");
        Objects.requireNonNull(onProgress, "onProgress");
        checkInterrupted();
        if (cancelled.getAsBoolean()) throw new CancellationException("Tool execution was cancelled");
        var ids = new HashSet<String>();
        for (var call : batch) {
            if (!ids.add(call.id())) throw new IllegalArgumentException("Duplicate tool call id");
        }
        var results = new ArrayList<ToolResult>();
        for (var call : batch) results.add(execute(call, cancelled, onProgress));
        return List.copyOf(results);
    }

    private static ObjectNode parseArguments(String arguments) throws JsonProcessingException, ToolArgumentsException {
        if (arguments.length() > MAX_ARGUMENT_CHARACTERS) throw new ToolArgumentsException();
        var value = JSON.readTree(arguments);
        if (!(value instanceof ObjectNode object)) throw new ToolArgumentsException();
        return object;
    }

    private static ToolResult failure(ContentBlock.ToolCall call, ToolResult.Status status) {
        String message = switch (status) {
            case UNKNOWN_TOOL -> "Tool is not registered";
            case INVALID_ARGUMENTS -> "Arguments do not meet the tool's requirements";
            case EXECUTION_FAILED -> "Tool execution failed";
            case SUCCESS -> throw new IllegalArgumentException("Expected a failure status");
        };
        return new ToolResult(call.id(), call.name(), status, message);
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.interrupted()) throw new InterruptedException("Tool execution was interrupted");
    }
}
