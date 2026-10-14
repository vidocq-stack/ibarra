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

import io.vidocq.ibarra.compiler.*;
import io.vidocq.ibarra.spi.PageDescriptor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Build-time catalog and compiler for the initial static XML tag-file subset. */
public final class TagFileCompiler {
    private static final String TAGDIR_PREFIX = "urn:jsptagdir:";
    private static final String JSP_NAMESPACE = PageParser.JSP_NAMESPACE;

    public record Result(Map<String, TagLibraryDescriptor> libraries, List<GeneratedPage> sources) {
        public Result { libraries = Map.copyOf(libraries); sources = List.copyOf(sources); }
    }
    private record Directory(String path, SourceLocation location) { }

    private TagFileCompiler() { }

    public static boolean referencesDirectory(TranslationUnit.Result unit, String directory) {
        for (var reference : unit.tagLibraries()) if (reference.uri().startsWith(TAGDIR_PREFIX))
            try {
                if (directory(reference.uri().substring(TAGDIR_PREFIX.length()), reference.location()).equals(directory)) return true;
            } catch (PageParseException ignored) { }
        return false;
    }

    public static Result compile(Collection<TranslationUnit.Result> units, GenerationOptions options, String packageName) {
        var directories = new LinkedHashMap<String, Directory>();
        var aliases = new LinkedHashMap<String, String>();
        var referencedFiles = new HashSet<String>();
        for (var unit : units) for (var reference : unit.tagLibraries()) {
            if (!reference.uri().startsWith(TAGDIR_PREFIX)) continue;
            String path = directory(reference.uri().substring(TAGDIR_PREFIX.length()), reference.location());
            directories.putIfAbsent(path, new Directory(path, reference.location()));
            aliases.put(reference.uri(), path);
        }
        for (var unit : units) referencedFiles(unit.page().nodes(), referencedFiles);

        var generated = new ArrayList<GeneratedPage>();
        var byDirectory = new HashMap<String, Map<String, TagDescriptor>>();
        for (var directory : directories.values()) {
            var tags = new TreeMap<String, TagDescriptor>();
            var kinds = new HashMap<String, String>();
            String prefix = directory.path() + "/";
            for (var resource : new TreeMap<>(options.byteSources()).entrySet()) {
                String path = resource.getKey();
                if (!path.startsWith(prefix) || path.substring(prefix.length()).contains("/")) continue;
                String suffix = path.endsWith(".tagx") ? ".tagx" : path.endsWith(".tag") ? ".tag" : null;
                if (suffix == null) continue;
                String name = path.substring(prefix.length(), path.length() - suffix.length());
                if (name.isBlank() || name.contains("."))
                    throw new PageParseException(directory.location(), "Invalid tag-file name: " + path);
                String previousKind = kinds.putIfAbsent(name, suffix);
                if (previousKind != null)
                    throw new PageParseException(directory.location(), "Conflicting .tag and .tagx files for tag " + name);
                PageDescriptor.validatePath(path);
                if (suffix.equals(".tag")) {
                    tags.put(name, new TagDescriptor(name, null, "empty", Map.of(), false, true));
                    continue;
                }
                String handler = packageName + ".TagFile_" + hash(path);
                if (referencedFiles.contains(path)) {
                    try {
                        var unit = new TranslationUnit(options).read(path, resource.getValue());
                        boolean declaration = XmlDeclaration.collectTagFile(unit.page());
                        String markup = staticMarkup(unit.page().nodes());
                        generated.add(new GeneratedPage(handler, source(handler, declaration, markup), Map.of()));
                        tags.put(name, new TagDescriptor(name, handler, "empty", Map.of(), false, false, true, null, declaration));
                    } catch (PageParseException failure) {
                        tags.put(name, new TagDescriptor(name, null, "empty", Map.of(), false, true, false, failure.getMessage()));
                        continue;
                    }
                }
                tags.putIfAbsent(name, new TagDescriptor(name, handler, "empty", Map.of(), false, false, true));
            }
            if (tags.isEmpty()) throw new PageParseException(directory.location(), "Tag directory contains no .tag or .tagx files: " + directory.path());
            byDirectory.put(directory.path(), Map.copyOf(tags));
        }

        var libraries = new LinkedHashMap<String, TagLibraryDescriptor>();
        for (var alias : aliases.entrySet()) {
            String path = alias.getValue();
            libraries.put(alias.getKey(), new TagLibraryDescriptor(path, alias.getKey(), List.of(), byDirectory.get(path), false));
        }
        return new Result(libraries, generated);
    }

    private static void referencedFiles(List<PageNode> nodes, Set<String> result) {
        for (var node : nodes) if (node instanceof PageNode.Element element) {
            String uri = element.namespace();
            if (uri.startsWith(TAGDIR_PREFIX)) {
                String name = element.name().substring(element.name().lastIndexOf(':') + 1);
                String directory = directory(uri.substring(TAGDIR_PREFIX.length()), element.location());
                result.add(directory + "/" + name + ".tag");
                result.add(directory + "/" + name + ".tagx");
            }
            referencedFiles(element.children(), result);
        }
    }

    private static String directory(String token, SourceLocation at) {
        if (token.isBlank()) throw new PageParseException(at, "tagdir must name a directory");
        String path = token.startsWith("/") ? java.nio.file.Path.of(token).normalize().toString().replace('\\', '/')
                : TranslationUnit.resolve(at.source(), token);
        if (path.equals("/")) throw new PageParseException(at, "tagdir must name a directory below the web root");
        try { PageDescriptor.validatePath(path + "/_tagdir_validation.tagx"); }
        catch (IllegalArgumentException invalid) { throw new PageParseException(at, "Invalid tagdir: " + token, invalid); }
        return path;
    }

    private static String source(String className, boolean declaration, String markup) {
        int split = className.lastIndexOf('.');
        String source = "package " + className.substring(0, split) + ";\n"
                + "public final class " + className.substring(split + 1)
                + " extends jakarta.servlet.jsp.tagext.SimpleTagSupport {\n"
                + "public void doTag() throws java.io.IOException {\n";
        if (declaration) source += "getJspContext().getOut().write(\"<?xml version=\\\"1.0\\\" encoding=\\\"UTF-8\\\"?>\");\n";
        source += "getJspContext().getOut().write(" + JavaSource.quote(markup) + ");\n} }\n";
        return source;
    }

    private static String staticMarkup(List<PageNode> nodes) {
        var result = new StringBuilder();
        append(nodes, result);
        return result.toString();
    }

    private static void append(List<PageNode> nodes, StringBuilder result) {
        for (var node : nodes) {
            if (node instanceof PageNode.Text text) result.append(escapeText(text.value()));
            else if (node instanceof PageNode.RawText text) result.append(text.value());
            else if (node instanceof PageNode.Directive directive)
                throw new PageParseException(directive.location(), "Directives in XML tag files are not supported yet");
            else if (node instanceof PageNode.Expression expression)
                throw new PageParseException(expression.location(), "Expressions in XML tag files are not supported yet");
            else if (node instanceof PageNode.Script script)
                throw new PageParseException(script.location(), "Scripting in XML tag files is not supported yet");
            else if (node instanceof PageNode.Element element) element(element, result);
        }
    }

    private static void element(PageNode.Element element, StringBuilder result) {
        String local = element.name().substring(element.name().lastIndexOf(':') + 1);
        if (element.namespace().equals(JSP_NAMESPACE)) {
            if (local.equals("output")) return;
            if (local.equals("root") || local.equals("text")) { append(element.children(), result); return; }
            throw new PageParseException(element.location(), "Action in XML tag files is not supported yet: " + element.name());
        }
        result.append('<').append(element.name());
        for (var attribute : element.attributes().entrySet()) {
            if (attribute.getKey().startsWith("xmlns") && attribute.getValue().equals(JSP_NAMESPACE)) continue;
            if (attribute.getValue().contains("${") || attribute.getValue().contains("#{"))
                throw new PageParseException(element.location(), "Expressions in XML tag-file attributes are not supported yet");
            result.append(' ').append(attribute.getKey()).append("=\"").append(escapeAttribute(attribute.getValue())).append('"');
        }
        if (element.selfClosing()) result.append("/>");
        else {
            result.append('>'); append(element.children(), result); result.append("</").append(element.name()).append('>');
        }
    }

    private static String escapeText(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
    private static String escapeAttribute(String text) {
        return escapeText(text).replace("\"", "&quot;");
    }
    private static String hash(String path) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(path.getBytes(StandardCharsets.UTF_8))).substring(0, 24); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
