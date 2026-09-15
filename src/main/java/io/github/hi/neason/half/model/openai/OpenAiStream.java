package io.github.hi.neason.half.model.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hi.neason.half.model.ModelEvent;
import io.github.hi.neason.half.model.http.ModelStream;
import java.util.concurrent.Flow;
import java.util.function.Consumer;

/** OpenAI 解码器与共享订阅传输的连接。 */
final class OpenAiStream extends ModelStream {
    OpenAiStream(ObjectMapper json, Flow.Subscriber<? super ModelEvent> downstream,
                 Consumer<OpenAiStream> starter, Consumer<OpenAiStream> release) {
        super(new OpenAiEventDecoder(json)::accept, downstream,
                value -> starter.accept((OpenAiStream) value), value -> release.accept((OpenAiStream) value));
    }
}
