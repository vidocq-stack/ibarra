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

package io.vidocq.ibarra.compiler;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SourceLocationTest {
    private static final String PAGE = "/index.jsp";

    @ParameterizedTest
    @CsvSource({
            "'ab', 1, 3",
            "'a\nb', 2, 2",
            "'a\rb', 2, 2",
            "'a\r\nb', 2, 2",
            "'\r\r\n', 3, 1",
            "'\n\r', 3, 1",
            "'\r\n\n', 3, 1",
            "'', 1, 1"
    })
    void advanceCountsEachLineTerminatorOnceAndCrLfAsOne(String text, int line, int column) {
        var location = new SourceLocation(PAGE, 1, 1).advance(text);
        assertEquals(line, location.line());
        assertEquals(column, location.column());
        assertEquals(PAGE, location.source());
    }

    @Test
    void advanceContinuesFromTheCurrentPosition() {
        assertEquals(new SourceLocation(PAGE, 4, 1), new SourceLocation(PAGE, 3, 7).advance("x\r\n"));
        assertEquals("/index.jsp:3:9", new SourceLocation(PAGE, 3, 7).advance("xy").toString());
    }

    @Test
    void parseExceptionsKeepTheirLocationAcrossSerialization() throws IOException, ClassNotFoundException {
        var failure = new PageParseException(new SourceLocation(PAGE, 2, 5), "Broken directive");
        var bytes = new ByteArrayOutputStream();
        try (var output = new ObjectOutputStream(bytes)) {
            output.writeObject(failure);
        }
        Object restored;
        try (var input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            restored = input.readObject();
        }
        var copy = assertInstanceOf(PageParseException.class, restored);
        assertEquals(new SourceLocation(PAGE, 2, 5), copy.location());
        assertEquals(failure.getMessage(), copy.getMessage());
    }
}
