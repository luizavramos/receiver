package br.com.motorfraude.receiver.adapters.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Optional;

public class RequestBodyReader {

    public static final int DEFAULT_MAX_BYTES = 16 * 1024;

    private final int maxBytes;

    public RequestBodyReader() {
        this(DEFAULT_MAX_BYTES);
    }

    public RequestBodyReader(int maxBytes) {
        this.maxBytes = maxBytes;
    }

    public int maxBytes() {
        return maxBytes;
    }

    public Optional<byte[]> read(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > maxBytes) {
            return Optional.empty();
        }
        var bytes = request.getInputStream().readNBytes(maxBytes + 1);
        return bytes.length > maxBytes ? Optional.empty() : Optional.of(bytes);
    }
}
