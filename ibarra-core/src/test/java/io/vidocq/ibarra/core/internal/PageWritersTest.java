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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;

import jakarta.servlet.jsp.JspWriter;
import org.junit.jupiter.api.Test;

class PageWritersTest {

    @Test
    void buffersUntilFlushedAndAcquiresTheResponseWriterLazily() throws Exception {
        var sink = new Sink();
        var out = new BufferedJspWriter(sink::acquire, 4, true);
        out.write("abcd");
        assertEquals(0, out.getRemaining());
        assertEquals(0, sink.acquisitions);
        out.flush();
        assertEquals("abcd", sink.text.toString());
        assertEquals(1, sink.acquisitions);
        assertEquals(1, sink.flushes);
        assertEquals(4, out.getRemaining());
    }

    @Test
    void automaticallyFlushesOnOverflowAndPreservesOrderAcrossLargeWrites() throws Exception {
        var sink = new Sink();
        var out = new BufferedJspWriter(sink::acquire, 4, true);
        out.write("abc");
        out.write("defghijkl");
        out.flush();
        assertEquals("abcdefghijkl", sink.text.toString());
        assertThrows(IOException.class, out::clear);
    }

    @Test
    void rejectsOverflowWithoutAutoflushAndKeepsPendingContent() throws Exception {
        var sink = new Sink();
        var out = new BufferedJspWriter(sink::acquire, 4, false);
        out.write("abc");
        assertThrows(IOException.class, () -> out.write("de"));
        assertEquals(0, sink.acquisitions);
        assertEquals(1, out.getRemaining());
        out.flush();
        assertEquals("abc", sink.text.toString());
    }

    @Test
    void clearsBeforeFlushAndClearBufferDiscardsOnlyPendingOutputAfterFlush() throws Exception {
        var sink = new Sink();
        var out = new BufferedJspWriter(sink::acquire, 8, true);
        out.print("discard");
        out.clear();
        assertEquals(0, sink.acquisitions);
        out.print("keep");
        out.flush();
        out.print("discard");
        assertThrows(IOException.class, out::clear);
        out.clearBuffer();
        out.print("!");
        out.flush();
        assertEquals("keep!", sink.text.toString());
    }

    @Test
    void unbufferedOutputIsImmediateAndCannotBeCleared() throws Exception {
        var sink = new Sink();
        var out = new BufferedJspWriter(sink::acquire, JspWriter.NO_BUFFER, true);
        out.write("now");
        assertEquals("now", sink.text.toString());
        assertEquals(0, out.getRemaining());
        assertThrows(IOException.class, out::clear);
        assertThrows(IOException.class, out::clearBuffer);
    }

    @Test
    void resolvesDefaultBufferAndRejectsUnsupportedSizes() {
        var sink = new Sink();
        var out = new BufferedJspWriter(sink::acquire, JspWriter.DEFAULT_BUFFER, true);
        assertTrue(out.getBufferSize() >= 8192);
        assertEquals(out.getBufferSize(), out.getRemaining());
        assertThrows(IllegalArgumentException.class,
                () -> new BufferedJspWriter(sink::acquire, JspWriter.UNBOUNDED_BUFFER, true));
        assertThrows(IllegalArgumentException.class,
                () -> new BufferedJspWriter(sink::acquire, -3, true));
    }

    @Test
    void closingFlushesAndClosesOnceAndPreventsFurtherOutput() throws Exception {
        var sink = new Sink();
        var out = new BufferedJspWriter(sink::acquire, 8, true);
        out.print("end");
        out.close();
        out.close();
        assertEquals("end", sink.text.toString());
        assertEquals(1, sink.closes);
        assertThrows(IOException.class, () -> out.write('x'));
        assertThrows(IOException.class, () -> out.write(""));
        assertThrows(IOException.class, out::flush);
        assertThrows(IOException.class, out::clearBuffer);
    }

    @Test
    void propagatesAcquisitionAndWriteFailures() throws Exception {
        var failure = new IOException("response unavailable");
        var out = new BufferedJspWriter(() -> { throw failure; }, 8, true);
        out.write("pending");
        assertSame(failure, assertThrows(IOException.class, out::flush));
        var broken = new BufferedJspWriter(() -> new Writer() {
            @Override public void write(char[] chars, int offset, int length) throws IOException {
                throw failure;
            }
            @Override public void flush() { }
            @Override public void close() { }
        }, 0, true);
        assertSame(failure, assertThrows(IOException.class, () -> broken.print("test")));
    }

    @Test
    void validatesWriteRangesBeforeChangingOutput() throws Exception {
        var sink = new Sink();
        var out = new BufferedJspWriter(sink::acquire, 4, true);
        assertThrows(IndexOutOfBoundsException.class, () -> out.write("abc", -1, 2));
        assertThrows(IndexOutOfBoundsException.class, () -> out.write(new char[2], 1, 2));
        assertThrows(NullPointerException.class, () -> out.write((char[]) null, 0, 0));
        out.write("abc", 3, 0);
        out.write(new char[] {'a', 'b', 'c'}, 1, 2);
        out.write('!');
        out.flush();
        assertEquals("bc!", sink.text.toString());
    }

    @Test
    void printAndPrintlnUseJavaConversionsAndPlatformLineSeparator() throws Exception {
        var sink = new Sink();
        var out = new BufferedJspWriter(sink::acquire, 256, true);
        assertPrintConversions(out);
        out.flush();
        assertEquals(expectedConversions(), sink.text.toString());
    }

    @Test
    void bodyCaptureIsUnboundedAndDoesNotWriteToItsEnclosingWriter() throws Exception {
        var sink = new Sink();
        var enclosing = new BufferedJspWriter(sink::acquire, 4, true);
        var body = new BufferedBodyContent(enclosing);
        String text = "body".repeat(10_000);
        body.write(text);
        assertSame(enclosing, body.getEnclosingWriter());
        assertEquals(JspWriter.UNBOUNDED_BUFFER, body.getBufferSize());
        assertFalse(body.isAutoFlush());
        assertEquals(text, body.getString());
        var read = new StringWriter();
        body.getReader().transferTo(read);
        assertEquals(text, read.toString());
        assertEquals(0, sink.acquisitions);
    }

    @Test
    void bodyCanBeCopiedRepeatedlyAndClearedForAnotherIteration() throws Exception {
        var body = new BufferedBodyContent(new BufferedJspWriter(StringWriter::new, 8, true));
        body.print("first");
        var destination = new StringWriter();
        body.writeOut(destination);
        body.writeOut(destination);
        assertEquals("firstfirst", destination.toString());
        body.clearBody();
        assertEquals("", body.getString());
        body.print("second");
        body.clearBuffer();
        body.print("third");
        body.clear();
        assertEquals("", body.getString());
    }

    @Test
    void nestedBodiesRemainIndependentAndFlushIsForbidden() throws Exception {
        var sink = new Sink();
        var outer = new BufferedBodyContent(new BufferedJspWriter(sink::acquire, 8, true));
        var inner = new BufferedBodyContent(outer);
        outer.print("before");
        inner.print("inside");
        assertThrows(IOException.class, inner::flush);
        inner.writeOut(outer);
        outer.print("after");
        assertEquals("beforeinsideafter", outer.getString());
        assertEquals(0, sink.acquisitions);
    }

    @Test
    void closingBodyNeverClosesOrFlushesTheEnclosingWriter() throws Exception {
        var sink = new Sink();
        var out = new BufferedJspWriter(sink::acquire, 8, true);
        var body = new BufferedBodyContent(out);
        body.print("captured");
        body.close();
        assertEquals("captured", body.getString());
        assertEquals(0, sink.acquisitions);
        assertThrows(IOException.class, () -> body.print("later"));
        out.print("open");
        out.flush();
        assertEquals("open", sink.text.toString());
        assertEquals(0, sink.closes);
    }

    @Test
    void bodySupportsTheSamePrintConversionsAndCheckedWriteRanges() throws Exception {
        var body = new BufferedBodyContent(new BufferedJspWriter(StringWriter::new, 8, true));
        assertPrintConversions(body);
        assertEquals(expectedConversions(), body.getString());
        assertThrows(IndexOutOfBoundsException.class, () -> body.write("abc", 2, 2));
        assertThrows(IndexOutOfBoundsException.class, () -> body.write(new char[1], 0, 2));
    }

    @Test
    void appendAndUnicodeOutputWorkAcrossBufferBoundaries() throws Exception {
        var sink = new Sink();
        var out = new BufferedJspWriter(sink::acquire, 1, true);
        out.append("é😀").append('!').append("xyz", 1, 3).append(null);
        out.flush();
        assertEquals("é😀!yznull", sink.text.toString());
        var body = new BufferedBodyContent(out);
        body.append("é😀").append('!').append("xyz", 1, 3).append(null);
        assertEquals("é😀!yznull", body.getString());
    }

    @Test
    void bodyReaderKeepsItsSnapshotAcrossFurtherIterations() throws Exception {
        var body = new BufferedBodyContent(new BufferedJspWriter(StringWriter::new, 8, true));
        body.write("first");
        var reader = body.getReader();
        body.clearBody();
        body.write("second");
        var snapshot = new StringWriter();
        reader.transferTo(snapshot);
        assertEquals("first", snapshot.toString());
        assertEquals("second", body.getString());
    }

    @Test
    void failedCloseLeavesTheWriterClosed() throws Exception {
        var failure = new IOException("flush failed");
        var out = new BufferedJspWriter(() -> new Writer() {
            @Override public void write(char[] chars, int offset, int length) { }
            @Override public void flush() throws IOException { throw failure; }
            @Override public void close() { }
        }, 8, true);
        out.write("pending");
        assertSame(failure, assertThrows(IOException.class, out::close));
        assertThrows(IOException.class, () -> out.print("later"));
        out.close();
    }

    private static void assertPrintConversions(JspWriter out) throws IOException {
        out.print(true); out.print('!'); out.print(12); out.print(34L);
        out.print(1.5f); out.print(2.5d); out.print(new char[] {'a', 'b'});
        out.print((String) null); out.print((Object) null);
        out.println(); out.println(false); out.println('x'); out.println(1);
        out.println(2L); out.println(3.5f); out.println(4.5d);
        out.println(new char[] {'c'}); out.println("text"); out.println((Object) 9);
        out.newLine();
    }

    private static String expectedConversions() {
        String nl = System.lineSeparator();
        return "true!12341.52.5abnullnull" + nl
                + String.join(nl, "false", "x", "1", "2", "3.5", "4.5", "c", "text", "9", "", "");
    }

    private static final class Sink extends Writer {
        private final StringWriter text = new StringWriter();
        private int acquisitions;
        private int flushes;
        private int closes;

        Writer acquire() { acquisitions++; return this; }
        @Override public void write(char[] chars, int offset, int length) {
            text.write(chars, offset, length);
        }
        @Override public void flush() { flushes++; }
        @Override public void close() { closes++; }
    }
}
