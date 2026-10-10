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
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.util.Objects;

import jakarta.servlet.jsp.JspWriter;
import jakarta.servlet.jsp.tagext.BodyContent;

/** Request-owned, unbounded tag body capture with no backing response stream. */
final class BufferedBodyContent extends BodyContent {

    private final StringBuilder content = new StringBuilder();
    private boolean closed;

    BufferedBodyContent(JspWriter enclosing) {
        super(Objects.requireNonNull(enclosing, "enclosing"));
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("Body writer is closed");
        }
    }

    @Override
    public void write(int value) throws IOException {
        ensureOpen();
        content.append((char) value);
    }

    @Override
    public void write(char[] chars, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, chars.length);
        ensureOpen();
        content.append(chars, offset, length);
    }

    @Override
    public void write(String text, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, text.length());
        ensureOpen();
        content.append(text, offset, offset + length);
    }

    @Override
    public void clear() throws IOException {
        ensureOpen();
        content.setLength(0);
    }

    @Override
    public void clearBuffer() throws IOException {
        clear();
    }

    @Override
    public void clearBody() {
        content.setLength(0);
    }

    @Override
    public Reader getReader() {
        return new StringReader(getString());
    }

    @Override
    public String getString() {
        return content.toString();
    }

    @Override
    public void writeOut(Writer writer) throws IOException {
        writer.write(getString());
    }

    @Override
    public int getRemaining() {
        return Integer.MAX_VALUE - content.length();
    }

    @Override
    public void close() {
        closed = true;
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

