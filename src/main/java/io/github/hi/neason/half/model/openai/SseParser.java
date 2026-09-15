package io.github.hi.neason.half.model.openai;

import java.util.Optional;

/** Compatibility bridge to the shared SSE parser. */
final class SseParser {
    private final io.github.hi.neason.half.model.http.SseParser delegate = new io.github.hi.neason.half.model.http.SseParser();
    Optional<String> accept(String line) { return delegate.accept(line); }
}
