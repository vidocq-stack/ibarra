/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.ibarra.core.internal;

import java.io.IOException;
import java.io.Writer;
import java.util.Objects;

import jakarta.servlet.jsp.JspWriter;

/**
 * Request-owned page output, with lazy acquisition of the response writer.
 * Buffer capacity and remaining space are measured in characters.
 */
final class BufferedJspWriter extends JspWriter {

    private static final int DEFAULT_CAPACITY = 8192;

    @FunctionalInterface
    interface WriterSource {
        Writer acquire() throws IOException;
    }

    private final WriterSource source;
    private final char[] buffer;
    private Writer destination;
    private int position;
    private boolean flushed;
    private boolean closed;

    BufferedJspWriter(WriterSource source, int size, boolean autoFlush) {
        super(capacity(size), autoFlush);
        this.source = Objects.requireNonNull(source, "source");
        this.buffer = new char[bufferSize];
    }

    private static int capacity(int size) {
        if (size == DEFAULT_BUFFER) {
            return DEFAULT_CAPACITY;
        }
        if (size < 0) {
            throw new IllegalArgumentException("Page buffer size must be non-negative or DEFAULT_BUFFER");
        }
        return size;
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("Page writer is closed");
        }
    }

    private Writer destination() throws IOException {
        if (destination == null) {
            destination = Objects.requireNonNull(source.acquire(), "response writer");
        }
        return destination;
    }

    private void checkCapacity(int length) throws IOException {
        if (bufferSize > 0 && !autoFlush && length > getRemaining()) {
            throw new IOException("Page output buffer overflow");
        }
    }

    @Override
    public void write(int value) throws IOException {
        ensureOpen();
        checkCapacity(1);
        if (bufferSize == NO_BUFFER) {
            Writer writer = destination();
            flushed = true;
            writer.write(value);
            return;
        }
        if (position == bufferSize) {
            flush();
        }
        buffer[position++] = (char) value;
    }

    @Override
    public void write(char[] chars, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, chars.length);
        ensureOpen();
        checkCapacity(length);
        if (length == 0) {
            return;
        }
        if (bufferSize == NO_BUFFER) {
            Writer writer = destination();
            flushed = true;
            writer.write(chars, offset, length);
            return;
        }
        while (length > 0) {
            if (position == bufferSize) {
                flush();
            }
            int count = Math.min(length, getRemaining());
            System.arraycopy(chars, offset, buffer, position, count);
            position += count;
            offset += count;
            length -= count;
        }
    }

    @Override
    public void write(String text, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, text.length());
        ensureOpen();
        checkCapacity(length);
        if (length == 0) {
            return;
        }
        if (bufferSize == NO_BUFFER) {
            Writer writer = destination();
            flushed = true;
            writer.write(text, offset, length);
            return;
        }
        while (length > 0) {
            if (position == bufferSize) {
                flush();
            }
            int count = Math.min(length, getRemaining());
            text.getChars(offset, offset + count, buffer, position);
            position += count;
            offset += count;
            length -= count;
        }
    }

    @Override
    public void clear() throws IOException {
        ensureOpen();
        if (flushed) {
            throw new IOException("Page output has already been flushed");
        }
        clearBuffer();
    }

    @Override
    public void clearBuffer() throws IOException {
        ensureOpen();
        if (bufferSize == NO_BUFFER) {
            throw new IOException("Page output is unbuffered");
        }
        position = 0;
    }

    @Override
    public void flush() throws IOException {
        ensureOpen();
        Writer writer = destination();
        // A failed write may still have sent part of the output to the client.
        flushed = true;
        if (position > 0) {
            writer.write(buffer, 0, position);
            position = 0;
        }
        writer.flush();
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        try {
            flush();
        } finally {
            closed = true;
            if (destination != null) {
                destination.close();
            }
        }
    }

    @Override
    public int getRemaining() {
        return bufferSize - position;
    }

    /** Includes unbuffered writes that may have sent partial output before failing. */
    boolean hasFlushed() {
        return flushed;
    }

    @Override
    public void newLine() throws IOException {
        write(System.lineSeparator());
    }

    @Override
    public void print(boolean value) throws IOException {
        write(String.valueOf(value));
    }

    @Override
    public void print(char value) throws IOException {
        write(value);
    }

    @Override
    public void print(int value) throws IOException {
        write(String.valueOf(value));
    }

    @Override
    public void print(long value) throws IOException {
        write(String.valueOf(value));
    }

    @Override
    public void print(float value) throws IOException {
        write(String.valueOf(value));
    }

    @Override
    public void print(double value) throws IOException {
        write(String.valueOf(value));
    }

    @Override
    public void print(char[] value) throws IOException {
        write(value);
    }

    @Override
    public void print(String value) throws IOException {
        write(value == null ? "null" : value);
    }

    @Override
    public void print(Object value) throws IOException {
        write(String.valueOf(value));
    }

    @Override
    public void println() throws IOException {
        newLine();
    }

    @Override
    public void println(boolean value) throws IOException {
        print(value);
        newLine();
    }

    @Override
    public void println(char value) throws IOException {
        print(value);
        newLine();
    }

    @Override
    public void println(int value) throws IOException {
        print(value);
        newLine();
    }

    @Override
    public void println(long value) throws IOException {
        print(value);
        newLine();
    }

    @Override
    public void println(float value) throws IOException {
        print(value);
        newLine();
    }

    @Override
    public void println(double value) throws IOException {
        print(value);
        newLine();
    }

    @Override
    public void println(char[] value) throws IOException {
        print(value);
        newLine();
    }

    @Override
    public void println(String value) throws IOException {
        print(value);
        newLine();
    }

    @Override
    public void println(Object value) throws IOException {
        print(value);
        newLine();
    }
}

