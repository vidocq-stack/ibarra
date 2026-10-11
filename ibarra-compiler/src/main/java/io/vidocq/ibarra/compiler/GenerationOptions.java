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
import java.util.List;
import java.util.Map;

/** Deployment defaults and immutable source snapshots for translation-time inclusion. */
public record GenerationOptions(List<JspPropertyGroup> propertyGroups, Map<String, String> sources, boolean allowStartupEl,
        Map<String, byte[]> byteSources, Map<String, TagLibraryDescriptor> tagLibraries) {
    public static final GenerationOptions DEFAULT = new GenerationOptions(List.of(), Map.of());
    public GenerationOptions(List<JspPropertyGroup> propertyGroups, Map<String, String> sources) { this(propertyGroups, sources, false); }
    public GenerationOptions(List<JspPropertyGroup> propertyGroups, Map<String, String> sources, boolean allowStartupEl) {
        this(propertyGroups, sources, allowStartupEl, Map.of());
    }
    public GenerationOptions(List<JspPropertyGroup> propertyGroups, Map<String, String> sources, boolean allowStartupEl, Map<String, byte[]> byteSources) {
        this(propertyGroups, sources, allowStartupEl, byteSources, Map.of());
    }
    public GenerationOptions {
        propertyGroups = List.copyOf(propertyGroups); sources = Map.copyOf(sources); byteSources = snapshot(byteSources); tagLibraries = Map.copyOf(tagLibraries);
        for (String path : sources.keySet()) if (byteSources.containsKey(path))
            throw new IllegalArgumentException("Source supplied as both text and bytes: " + path);
    }
    private static Map<String, byte[]> snapshot(Map<String, byte[]> values) {
        var copy = new java.util.LinkedHashMap<String, byte[]>();
        values.forEach((path, bytes) -> copy.put(path, bytes.clone()));
        return Map.copyOf(copy);
    }
    @Override public Map<String, byte[]> byteSources() { return snapshot(byteSources); }
    @Override public boolean equals(Object other) {
        if (!(other instanceof GenerationOptions value) || allowStartupEl != value.allowStartupEl
                || !propertyGroups.equals(value.propertyGroups) || !sources.equals(value.sources)
                || !byteSources.keySet().equals(value.byteSources.keySet()) || !tagLibraries.equals(value.tagLibraries)) return false;
        for (var entry : byteSources.entrySet())
            if (!java.util.Arrays.equals(entry.getValue(), value.byteSources.get(entry.getKey()))) return false;
        return true;
    }
    @Override public int hashCode() {
        int bytesHash = 0;
        for (var entry : byteSources.entrySet()) bytesHash += entry.getKey().hashCode() ^ java.util.Arrays.hashCode(entry.getValue());
        return java.util.Objects.hash(propertyGroups, sources, allowStartupEl, bytesHash, tagLibraries);
    }
    /** Returns a defensive copy of one included resource, or null if not supplied. */
    public byte[] byteSource(String path) { var bytes = byteSources.get(path); return bytes == null ? null : bytes.clone(); }
    /** Supplies byte resources; each file's encoding is detected independently. */
    public GenerationOptions withByteSources(Map<String, byte[]> values) { return new GenerationOptions(propertyGroups, sources, allowStartupEl, values, tagLibraries); }
    /** Explicit interpreter preview; production packaging always compiles EL at build time. */
    public GenerationOptions withStartupEl(boolean allowed) { return new GenerationOptions(propertyGroups, sources, allowed, byteSources, tagLibraries); }
    /** Supplies an immutable build-time URI/resource map; runtime parsing is never needed. */
    public GenerationOptions withTagLibraries(Map<String, TagLibraryDescriptor> values) { return new GenerationOptions(propertyGroups, sources, allowStartupEl, byteSources, values); }
}

