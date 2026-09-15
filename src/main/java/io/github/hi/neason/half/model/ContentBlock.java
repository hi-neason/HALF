package io.github.hi.neason.half.model;

import java.util.Objects;
import java.util.List;
import java.net.URI;

/** 不可变内容块；工具参数仍是不可信输入，执行前由宿主校验。 */
public sealed interface ContentBlock {
    record Text(String text) implements ContentBlock {
        public Text { Objects.requireNonNull(text, "text"); }
    }

    /** image URL 或 data:image/...；适配器只传输引用，不下载文件。 */
    record Image(String url, String detail) implements ContentBlock {
        public Image {
            Objects.requireNonNull(url, "url");
            URI uri = URI.create(url);
            boolean remote = ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null;
            if (!remote && !url.startsWith("data:image/")) throw new IllegalArgumentException("Expected an HTTP(S) image URL or image data URL");
            if (detail != null && !List.of("auto", "low", "high", "original").contains(detail)) throw new IllegalArgumentException("Invalid image detail");
        }
        public Image(String url) { this(url, null); }
    }

    /** fileId 与 filename + fileData 两种输入互斥；不调用 Files 上传 API。 */
    record File(String fileId, String filename, String fileData) implements ContentBlock {
        public File {
            boolean byId = fileId != null && !fileId.isBlank() && filename == null && fileData == null;
            boolean byData = fileId == null && filename != null && !filename.isBlank() && fileData != null && !fileData.isBlank();
            if (!byId && !byData) throw new IllegalArgumentException("Use fileId or filename and fileData");
        }
        public static File byId(String id) { return new File(id, null, null); }
        public static File byData(String filename, String data) { return new File(null, filename, data); }
    }

    record Refusal(String text) implements ContentBlock {
        public Refusal { Objects.requireNonNull(text, "text"); }
    }

    /** Responses 推理输出；加密内容作为不透明字符串保留和回传。 */
    record Reasoning(String id, List<String> summary, List<String> content, String encryptedContent) implements ContentBlock {
        public Reasoning {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("Reasoning item id is required");
            summary = List.copyOf(summary);
            content = List.copyOf(content);
        }
    }

    record ToolCall(String id, String name, String arguments) implements ContentBlock {
        public ToolCall {
            if (id == null || id.isBlank() || name == null || name.isBlank()) {
                throw new IllegalArgumentException("Tool call id and name must not be blank");
            }
            Objects.requireNonNull(arguments, "arguments");
        }
    }
}
