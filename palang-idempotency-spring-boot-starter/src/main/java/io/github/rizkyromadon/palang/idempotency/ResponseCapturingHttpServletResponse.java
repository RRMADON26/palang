package io.github.rizkyromadon.palang.idempotency;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Captures the response body while still writing it to the client, so a successful
 * response can be stored for later replay.
 */
final class ResponseCapturingHttpServletResponse extends HttpServletResponseWrapper {

    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    private final HttpServletResponse delegate;
    private ServletOutputStream outputStream;
    private PrintWriter writer;

    ResponseCapturingHttpServletResponse(HttpServletResponse response) {
        super(response);
        this.delegate = response;
    }

    byte[] capturedBody() throws IOException {
        if (writer != null) {
            writer.flush();
        }
        return captured.toByteArray();
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        if (writer != null) {
            throw new IllegalStateException("getWriter() has already been called on this response");
        }
        if (outputStream == null) {
            ServletOutputStream target = delegate.getOutputStream();
            outputStream = new ServletOutputStream() {
                @Override
                public boolean isReady() {
                    return target.isReady();
                }

                @Override
                public void setWriteListener(WriteListener writeListener) {
                    target.setWriteListener(writeListener);
                }

                @Override
                public void write(int b) throws IOException {
                    captured.write(b);
                    target.write(b);
                }
            };
        }
        return outputStream;
    }

    @Override
    public PrintWriter getWriter() throws IOException {
        if (outputStream != null) {
            throw new IllegalStateException("getOutputStream() has already been called on this response");
        }
        if (writer == null) {
            Charset charset = getCharacterEncoding() != null
                    ? Charset.forName(getCharacterEncoding())
                    : StandardCharsets.UTF_8;
            writer = new PrintWriter(new java.io.OutputStreamWriter(getOutputStreamInternal(), charset), true);
        }
        return writer;
    }

    private ServletOutputStream getOutputStreamInternal() throws IOException {
        ServletOutputStream target = delegate.getOutputStream();
        return new ServletOutputStream() {
            @Override
            public boolean isReady() {
                return target.isReady();
            }

            @Override
            public void setWriteListener(WriteListener writeListener) {
                target.setWriteListener(writeListener);
            }

            @Override
            public void write(int b) throws IOException {
                captured.write(b);
                target.write(b);
            }
        };
    }

    @Override
    public void flushBuffer() throws IOException {
        if (writer != null) {
            writer.flush();
        }
        super.flushBuffer();
    }
}
