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
package io.vidocq.ibarra.compiler.internal;

import jakarta.servlet.jsp.tagext.TagData;
import jakarta.servlet.jsp.tagext.TagExtraInfo;
import jakarta.servlet.jsp.tagext.VariableInfo;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TagExtraInfoVariablesTest {
    public static final class LiteralTei extends TagExtraInfo {
        @Override public VariableInfo[] getVariableInfo(TagData data) {
            return new VariableInfo[] {
                    new VariableInfo("begin", "java.lang.Integer", true, VariableInfo.AT_BEGIN),
                    new VariableInfo("nested", "java.lang.Integer", true, VariableInfo.NESTED),
                    new VariableInfo("end", "java.lang.Integer", true, VariableInfo.AT_END)
            };
        }
    }

    public static final class DynamicTei extends TagExtraInfo {
        @Override public VariableInfo[] getVariableInfo(TagData data) { return variables(); }
        private VariableInfo[] variables() { return new VariableInfo[0]; }
    }

    @Test
    void readsLiteralVariableInfoWithoutLoadingTheTeiClass() throws IOException {
        var model = model(LiteralTei.class.getName());
        assertEquals(List.of(
                new TagExtraInfoVariables.Variable("begin", "java.lang.Integer", true, VariableInfo.AT_BEGIN),
                new TagExtraInfoVariables.Variable("nested", "java.lang.Integer", true, VariableInfo.NESTED),
                new TagExtraInfoVariables.Variable("end", "java.lang.Integer", true, VariableInfo.AT_END)),
                TagExtraInfoVariables.read(model).orElseThrow());
    }

    @Test
    void inheritedTagExtraInfoDefaultDeclaresNoVariables() throws IOException {
        try (var stream = TagExtraInfo.class.getResourceAsStream("TagExtraInfo.class")) {
            assertNotNull(stream);
            var model = ClassFile.of().parse(stream.readAllBytes());
            assertEquals(List.of(), TagExtraInfoVariables.read(model).orElseThrow());
        }
    }

    @Test
    void rejectsDynamicVariableMetadataWithoutExecutingTheTei() throws IOException {
        var model = model(DynamicTei.class.getName());
        assertThrows(IllegalArgumentException.class, () -> TagExtraInfoVariables.read(model));
    }

    private ClassModel model(String type) throws IOException {
        String resource = "/" + type.replace('.', '/') + ".class";
        try (var stream = getClass().getResourceAsStream(resource)) {
            assertNotNull(stream, resource);
            return ClassFile.of().parse(stream.readAllBytes());
        }
    }
}
