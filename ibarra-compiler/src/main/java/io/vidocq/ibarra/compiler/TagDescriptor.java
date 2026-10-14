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

import java.util.*;

/** Immutable handler metadata; unsupported semantics must be rejected before generation. */
public record TagDescriptor(String name, String handlerClass, String bodyContent, Map<String, TagAttribute> attributes,
        boolean dynamicAttributes, boolean unsupportedMetadata, boolean tagFile, String unsupportedReason,
        boolean emitsXmlDeclaration) {
    public TagDescriptor(String name, String handlerClass, String bodyContent, Map<String, TagAttribute> attributes,
            boolean dynamicAttributes, boolean unsupportedMetadata) {
        this(name, handlerClass, bodyContent, attributes, dynamicAttributes, unsupportedMetadata, false, null, false);
    }
    public TagDescriptor(String name, String handlerClass, String bodyContent, Map<String, TagAttribute> attributes,
            boolean dynamicAttributes, boolean unsupportedMetadata, boolean tagFile) {
        this(name, handlerClass, bodyContent, attributes, dynamicAttributes, unsupportedMetadata, tagFile, null, false);
    }
    public TagDescriptor(String name, String handlerClass, String bodyContent, Map<String, TagAttribute> attributes,
            boolean dynamicAttributes, boolean unsupportedMetadata, boolean tagFile, String unsupportedReason) {
        this(name, handlerClass, bodyContent, attributes, dynamicAttributes, unsupportedMetadata, tagFile, unsupportedReason, false);
    }
    public TagDescriptor {
        Objects.requireNonNull(name); Objects.requireNonNull(bodyContent);
        if (handlerClass == null && !unsupportedMetadata) throw new IllegalArgumentException("A handler class is required");
        if (unsupportedReason != null && !unsupportedMetadata) throw new IllegalArgumentException("An unsupported reason requires unsupported metadata");
        attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }
}
