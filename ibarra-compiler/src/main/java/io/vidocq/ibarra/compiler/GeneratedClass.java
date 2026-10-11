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

import java.lang.classfile.ClassFile;
import java.util.Arrays;
import java.util.Objects;
import javax.lang.model.SourceVersion;

/** Immutable application-owned class file produced by a build-time compiler. */
public record GeneratedClass(String className, byte[] bytes) {
    public GeneratedClass {
        Objects.requireNonNull(className);
        bytes = Objects.requireNonNull(bytes).clone();
        if (!className.contains(".") || !SourceVersion.isName(className))
            throw new IllegalArgumentException("Invalid generated class name: " + className);
        var model = ClassFile.of().parse(bytes);
        if (!model.thisClass().asInternalName().replace('/', '.').equals(className))
            throw new IllegalArgumentException("Class-file name does not match " + className);
    }
    @Override public byte[] bytes() { return bytes.clone(); }
    @Override public boolean equals(Object other) {
        return other instanceof GeneratedClass generated && className.equals(generated.className)
                && Arrays.equals(bytes, generated.bytes);
    }
    @Override public int hashCode() { return 31 * className.hashCode() + Arrays.hashCode(bytes); }
}
