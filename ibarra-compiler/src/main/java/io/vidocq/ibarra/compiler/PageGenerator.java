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

import io.vidocq.ibarra.compiler.internal.*;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.*;
import javax.lang.model.SourceVersion;

/** Generates build-time servlet source. No generated class is loaded by this API. */
public final class PageGenerator {
    private final BeanClasses classes;
    private final List<Path> importModulePath;
    private final java.lang.module.ModuleDescriptor applicationModule;
    public PageGenerator(List<Path> beanModulePath) { this(beanModulePath, null); }
    /** Uses the application descriptor to filter imports by readability and exports. */
    public PageGenerator(List<Path> beanModulePath, java.lang.module.ModuleDescriptor applicationModule) {
        classes = new BeanClasses(beanModulePath);
        importModulePath = List.copyOf(beanModulePath); this.applicationModule = applicationModule;
    }

    public GeneratedPage generate(String path, String source, String className, GenerationOptions options) {
        if (!SourceVersion.isName(className) || !className.contains("."))
            throw new IllegalArgumentException("Generated class needs a qualified Java name: " + className);
        var unit = new TranslationUnit(options).read(path, source);
        return generate(unit, className, options);
    }

    /** Detects and strictly decodes the requested file's byte encoding before translation. */
    public GeneratedPage generate(String path, byte[] source, String className, GenerationOptions options) {
        if (!SourceVersion.isName(className) || !className.contains("."))
            throw new IllegalArgumentException("Generated class needs a qualified Java name: " + className);
        var unit = new TranslationUnit(options).read(path, source);
        return generate(unit, className, options);
    }

    GeneratedPage generate(TranslationUnit.Result unit, String className, GenerationOptions options) {
        return generate(unit, className, options, TldReferences.discover(List.of(unit), options));
    }
    GeneratedPage generate(TranslationUnit.Result unit, String className, GenerationOptions options, TldReferences.Resolved resolved) {
        return new Generator(unit.page(), className, unit.properties(), unit.sourceSyntaxes(), unit.responseEncoding(), options, resolved).generate();
    }

    private final class Generator {
        private static final Set<String> PAGE_ATTRIBUTES = Set.of("language", "import", "extends", "session",
                "buffer", "autoFlush", "contentType", "pageEncoding", "errorPage", "isErrorPage", "info",
                "isELIgnored", "deferredSyntaxAllowedAsLiteral", "trimDirectiveWhitespaces", "errorOnELNotFound");
        private final Page page;
        private final String className;
        private final Map<String, String> settings = new LinkedHashMap<>();
        private final Map<String, SourceLocation> settingLocations = new HashMap<>();
        private final Map<String, Page.Syntax> sourceSyntaxes;
        private final String responseEncoding;
        private final List<String> imports = new ArrayList<>();
        private final Map<String, SourceLocation> importLocations = new HashMap<>();
        private final JavaSource declarations = new JavaSource();
        private final JavaSource serviceDeclarations = new JavaSource();
        private final JavaSource libraryValidations = new JavaSource();
        private final JavaSource tagValidations = new JavaSource();
        private JavaSource body = new JavaSource();
        private final JavaSource fragments = new JavaSource();
        private final TldReferences.Resolved resolvedLibraries;
        private final GenerationOptions generationOptions;
        private final Map<String, PageNode.Directive> taglibBindings = new LinkedHashMap<>();
        private record NamespaceBinding(String uri, SourceLocation location) {}
        private final Map<String, NamespaceBinding> namespaces = new HashMap<>();
        private String parentTag = "null";
        private int fragmentDepth;
        private final List<String> expressions = new ArrayList<>();
        private final List<SourceLocation> expressionLocations = new ArrayList<>();
        private final List<Map<String, BeanClasses.Function>> expressionFunctions = new ArrayList<>();
        private record ExpressionSpec(String expectedType, DeferredMethodSignature method, boolean deferred, boolean literal) { }
        private final List<ExpressionSpec> expressionSpecs = new ArrayList<>();
        private record ScriptingVariable(String name, String type, boolean declare, int scope) { }
        private final Map<String, ScriptingVariable> pageVariables = new LinkedHashMap<>();
        private final Map<String, String> beans = new LinkedHashMap<>();
        private boolean needsFactory;
        private int serial;
        private int jspIdSerial;
        private int tagValidationSerial;
        private final boolean startupElAllowed;

        Generator(Page page, String className, Map<String, String> properties, Map<String, Page.Syntax> sourceSyntaxes, String responseEncoding, GenerationOptions options, TldReferences.Resolved resolved) {
            this.page = page; this.className = className;
            this.sourceSyntaxes = sourceSyntaxes;
            this.responseEncoding = responseEncoding;
            this.startupElAllowed = options.allowStartupEl();
            this.generationOptions = options;
            this.resolvedLibraries = resolved;
            copy(properties, "default-content-type", "contentType");
            copy(properties, "buffer", "buffer");
            copy(properties, "page-encoding", "pageEncoding");
            copy(properties, "trim-directive-whitespaces", "trimDirectiveWhitespaces");
            copy(properties, "scripting-invalid", "scriptingInvalid");
            copy(properties, "el-ignored", "isELIgnored");
            copy(properties, "deferred-syntax-allowed-as-literal", "deferredSyntaxAllowedAsLiteral");
            copy(properties, "error-on-el-not-found", "errorOnELNotFound");
            copy(properties, "error-on-undeclared-namespace", "errorOnUndeclaredNamespace");
        }
        private void copy(Map<String, String> values, String from, String to) {
            if (values.containsKey(from)) settings.put(to, values.get(from));
        }

        GeneratedPage generate() {
            directives(page.nodes());
            boolean xmlDeclaration = XmlDeclaration.collect(page);
            var doctype = XmlDoctype.collect(page);
            if (settings.containsKey("language") && !settings.get("language").equals("java"))
                throw error(page.nodes().isEmpty() ? new SourceLocation(page.source(), 1, 1) : page.nodes().getFirst().location(), "Only Java scripting is supported");
            emit(page.nodes());
            validateTagLibraries();
            int dot = className.lastIndexOf('.');
            String packageName = className.substring(0, dot);
            String simple = className.substring(dot + 1);
            var result = new JavaSource();
            result.add("package " + packageName + ";\n");
            result.add("import jakarta.servlet.*;\nimport jakarta.servlet.http.*;\nimport jakarta.servlet.jsp.*;\n");
            for (String name : imports) result.add("import " + name + ";\n");
            result.add("public final class " + simple + " extends " + simple + "$Lifecycle {\n");
            var compiled = new ArrayList<GeneratedClass>();
            for (int i = 0; i < expressions.size(); i++) {
                var spec = expressionSpecs.get(i);
                if (startupElAllowed) result.add("private jakarta.el.ValueExpression _jspxExpression" + i + ";\n");
                else {
                    String name = className + "$Expression" + i;
                    try {
                        compiled.add(new GeneratedClass(name, spec.literal() ? LiteralExpressions.compile(name, expressions.get(i), spec.method() != null)
                                : spec.method() == null ? io.vidocq.peano.compiler.ExpressionCompiler.compile(name, expressions.get(i))
                                : io.vidocq.peano.compiler.ExpressionCompiler.compileMethod(name, expressions.get(i))));
                    } catch (jakarta.el.ELException invalid) {
                        throw error(expressionLocations.get(i), "Cannot compile EL: " + invalid.getMessage());
                    }
                    var functions = expressionFunctions.get(i);
                    if (!functions.isEmpty()) functionDefinitions(result, i, functions);
                    if (!spec.deferred()) result.add("private static final jakarta.el.ValueExpression _jspxExpression" + i + " = new " + name + "("
                            + (functions.isEmpty() ? "" : "_jspxFunctionContext" + i + "(), ") + "Object.class);\n");
                }
            }
            result.add(declarations);
            if (expressionSpecs.stream().anyMatch(spec -> spec.method() != null)) methodScope(result);
            result.add(fragments);
            result.add("@Override protected final void _jspxInitialize() {\n");
            if (startupElAllowed && !expressions.isEmpty()) {
                result.add("var factory = jakarta.servlet.jsp.JspFactory.getDefaultFactory().getJspApplicationContext(getServletContext()).getExpressionFactory();\n");
                result.add("var context = new jakarta.el.StandardELContext(factory);\n");
                for (int i = 0; i < expressions.size(); i++)
                    result.add("_jspxExpression" + i + " = factory.createValueExpression(context, " + JavaSource.quote(expressions.get(i)) + ", Object.class);\n");
            }
            result.add("}\n");
            result.add("@Override public final void _jspService(jakarta.servlet.http.HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response) throws jakarta.servlet.ServletException, java.io.IOException {\n");
            result.add("response.setContentType(" + JavaSource.quote(contentType()) + ");\n");
            result.add("var _jspxFactory = jakarta.servlet.jsp.JspFactory.getDefaultFactory();\n");
            result.add("if (_jspxFactory == null) throw new jakarta.servlet.ServletException(\"No JSP factory installed\");\n");
            result.add("var pageContext = _jspxFactory.getPageContext(this, request, response, "
                    + (settings.containsKey("errorPage") ? JavaSource.quote(settings.get("errorPage")) : "null") + ", "
                    + bool("session", true) + ", " + buffer() + ", " + bool("autoFlush", true) + ");\n");
            result.add("if (pageContext == null) throw new jakarta.servlet.ServletException(\"Cannot initialize page context\");\n");
            result.add("var out = pageContext.getOut();\nvar application = getServletContext();\nvar config = getServletConfig();\nObject page = this;\n");
            if (bool("session", true)) result.add("var session = pageContext.getSession();\n");
            if (bool("isErrorPage", false)) result.add("Throwable exception = pageContext.getErrorData().getThrowable();\n");
            result.add("boolean _jspxFailed = false, _jspxHandled = false; boolean[] _jspxForwarded = {false};\ntry {\n");
            result.add(serviceDeclarations);
            if (xmlDeclaration) result.add("out.write(\"<?xml version=\\\"1.0\\\" encoding=\\\"\" + response.getCharacterEncoding() + \"\\\"?>\");\n");
            if (!doctype.declaration().isEmpty()) result.add("out.write(" + JavaSource.quote(doctype.declaration()) + ");\n", doctype.location());
            if (needsFactory) result.add("var _jspxExpressionFactory = _jspxFactory.getJspApplicationContext(application).getExpressionFactory();\n");
            result.add(body);
            result.add("} catch (Throwable failure) { if (!(failure instanceof jakarta.servlet.jsp.SkipPageException)) { _jspxFailed = true; pageContext.handlePageException(failure); _jspxHandled = true; } } finally {\n");
            result.add("try { if (!_jspxForwarded[0] && !(pageContext instanceof io.vidocq.ibarra.spi.PageDispatchContext _jspxDispatch && _jspxDispatch.isForwarded())"
                    + " && (!_jspxFailed || (_jspxHandled && out.getRemaining() < out.getBufferSize()))) out.flush(); } finally { _jspxFactory.releasePageContext(pageContext); }\n}\n}\n");
            result.add("private static String _jspxText(Object value) { return value == null ? \"\" : String.valueOf(value); }\n");
            result.add("private static Object _jspxBean(jakarta.servlet.jsp.PageContext context, String name) throws jakarta.servlet.ServletException { Object bean = context.findAttribute(name); if (bean == null) throw new jakarta.servlet.ServletException(\"No bean named \" + name); return bean; }\n");
            result.add("private static String _jspxAttribute(String value) { return value.replace(\"&\", \"&amp;\").replace(\"\\\"\", \"&quot;\").replace(\"<\", \"&lt;\"); }\n");
            result.add("private static String _jspxXmlName(String value) { if (!value.matches(\"[\\\\p{L}_][\\\\p{L}\\\\p{N}\\\\p{M}_.:-]*\")) throw new IllegalArgumentException(\"Invalid XML name: \" + value); return value; }\n");
            result.add("private static String _jspxParameter(String path, String name, Object value) { return path + (path.contains(\"?\") ? \"&\" : \"?\") + java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8) + \"=\" + java.net.URLEncoder.encode(_jspxText(value), java.nio.charset.StandardCharsets.UTF_8); }\n");
            result.add("}\nabstract class " + simple + "$Lifecycle extends " + settings.getOrDefault("extends", "jakarta.servlet.http.HttpServlet") + " implements jakarta.servlet.jsp.HttpJspPage, io.vidocq.ibarra.spi.PageOptions {\n");
            result.add("@Override public final boolean errorOnELNotFound() { return " + bool("errorOnELNotFound", false) + "; }\n");
            result.add("@Override public final boolean usesStaticELImports() { return true; }\n"
                    + "@SuppressWarnings({\"removal\",\"deprecation\"}) @Override public final Class<?> resolveELClass(String name) { if (name == null) return null; return switch(name) {\n");
            var importClasses = new ImportClasses(importModulePath, applicationModule);
            for (String name : imports) if (!name.endsWith(".*")) importClasses.bindings(List.of(name), importLocations.get(name));
            var bindings = importClasses.bindings(imports, new SourceLocation(page.source(), 1, 1));
            for (var binding : bindings.entrySet()) {
                result.add("case " + JavaSource.quote(binding.getKey()) + " -> ");
                if (binding.getValue().size() == 1) result.add(binding.getValue().iterator().next() + ".class;\n");
                else result.add("throw new jakarta.el.ELException(" + JavaSource.quote("Ambiguous EL import: " + binding.getKey()) + ");\n");
            }
            result.add("default -> null; }; }\n");
            result.add("protected abstract void _jspxInitialize();\nprivate final void _jspxValidateTagExtraInfo() {\n");
            result.add(libraryValidations);
            result.add(tagValidations);
            result.add("}\npublic void jspInit() {}\npublic void jspDestroy() {}\n");
            result.add("@Override public final void init() throws jakarta.servlet.ServletException { super.init(); _jspxInitialize(); _jspxValidateTagExtraInfo(); jspInit(); }\n");
            result.add("@Override public final void init(jakarta.servlet.ServletConfig config) throws jakarta.servlet.ServletException { super.init(config); }\n");
            result.add("@Override public final void destroy() { try { jspDestroy(); } finally { super.destroy(); } }\n");
            result.add("@Override protected final void service(jakarta.servlet.http.HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response) throws jakarta.servlet.ServletException, java.io.IOException {\n");
            result.add("var _jspxPrecompile = request.getParameterValues(\"jsp_precompile\");\n");
            result.add("if (_jspxPrecompile != null) {\nfor (var value : _jspxPrecompile) {\n");
            result.add("if (!(\"\".equals(value) || \"true\".equals(value) || \"false\".equals(value))) { response.sendError(500, \"Invalid jsp_precompile value\"); return; }\n}\nreturn;\n}\n");
            result.add("_jspService(request, response);\n}\n");
            result.add("@Override public final void service(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) throws jakarta.servlet.ServletException, java.io.IOException { super.service(request, response); }\n");
            if (settings.containsKey("info")) result.add("@Override public String getServletInfo() { return " + JavaSource.quote(settings.get("info")) + "; }\n");
            result.add("}\n");
            return new GeneratedPage(className, result.text(), result.locations(), compiled);
        }

        private void directives(List<PageNode> nodes) {
            var explicit = new HashMap<String, String>();
            collectDirectives(nodes, explicit);
            settings.putAll(explicit);
            if (settings.containsKey("extends") && !SourceVersion.isName(settings.get("extends")))
                throw error(new SourceLocation(page.source(), 1, 1), "Invalid page superclass");
        }
        private void collectDirectives(List<PageNode> nodes, Map<String, String> explicit) {
            for (var node : nodes) {
                if (node instanceof PageNode.Directive directive) {
                    if (directive.name().equals("taglib")) continue;
                    if (!directive.name().equals("page")) throw error(directive.location(), "Unsupported directive: " + directive.name());
                    for (var entry : directive.attributes().entrySet()) {
                        if (namespaceAttribute(entry.getKey())) continue;
                        if (!PAGE_ATTRIBUTES.contains(entry.getKey())) throw error(directive.location(), "Unsupported page attribute: " + entry.getKey());
                        if (entry.getKey().equals("pageEncoding") && !directive.location().source().equals(page.source())) continue;
                        if (Set.of("session", "autoFlush", "isErrorPage", "isELIgnored",
                                "deferredSyntaxAllowedAsLiteral", "trimDirectiveWhitespaces", "errorOnELNotFound").contains(entry.getKey())
                                && !entry.getValue().equals("true") && !entry.getValue().equals("false"))
                            throw error(directive.location(), entry.getKey() + " must be true or false");
                        if (entry.getKey().equals("import")) {
                            for (String name : entry.getValue().split(",")) {
                                name = name.trim();
                                if (name.startsWith("static ")) throw error(directive.location(), "Page imports accept types or packages, not static members");
                                String base = name.startsWith("static ") ? name.substring(7) : name;
                                if (!SourceVersion.isName(base.endsWith(".*") ? base.substring(0, base.length() - 2) : base))
                                    throw error(directive.location(), "Invalid page import: " + name);
                                imports.add(name);
                                importLocations.putIfAbsent(name, directive.location());
                            }
                        } else {
                            String previous = explicit.putIfAbsent(entry.getKey(), entry.getValue());
                            settingLocations.putIfAbsent(entry.getKey(), directive.location());
                            if (previous != null && !previous.equals(entry.getValue()))
                                throw error(directive.location(), "Conflicting page attribute: " + entry.getKey());
                        }
                    }
                } else if (node instanceof PageNode.Element element) collectDirectives(element.children(), explicit);
            }
        }

        private void emit(List<PageNode> nodes) {
            for (var node : nodes) {
                if (node instanceof PageNode.RawText text) write(text.value(), text.location());
                else if (node instanceof PageNode.Text text) {
                    if (!bool("trimDirectiveWhitespaces", false) || !text.value().chars().allMatch(c -> c == ' ' || c == '\t' || c == '\r' || c == '\n'))
                        write(text.value(), text.location());
                } else if (node instanceof PageNode.Expression expression) {
                    if (expression.deferred()) throw error(expression.location(), "Deferred EL in template text is not supported");
                    body.add("out.write(_jspxText(" + expression("${" + expression.code() + "}", expression.location()) + "));\n", expression.location());
                } else if (node instanceof PageNode.Script script) {
                    if (bool("scriptingInvalid", false)) throw error(script.location(), "Scripting is disabled by configuration");
                    if (script.kind() == PageNode.ScriptKind.DECLARATION) declarations.add(script.code() + "\n", script.location());
                    else if (script.kind() == PageNode.ScriptKind.EXPRESSION) body.add("out.print(" + script.code() + ");\n", script.location());
                    else body.add(script.code() + "\n", script.location());
                } else if (node instanceof PageNode.Element element) element(element);
                else if (node instanceof PageNode.Directive directive && directive.name().equals("taglib"))
                    taglibBindings.put(directive.attributes().get("prefix"), directive);
            }
        }

        private void element(PageNode.Element node) {
            var previous = enterNamespaces(node);
            try { elementContent(node); }
            finally { namespaces.clear(); namespaces.putAll(previous); }
        }
        private Map<String, NamespaceBinding> enterNamespaces(PageNode.Element node) {
            var previous = new HashMap<>(namespaces);
            for (var attribute : node.attributes().entrySet()) {
                if (attribute.getKey().startsWith("xmlns:"))
                    namespaces.put(attribute.getKey().substring(6), new NamespaceBinding(attribute.getValue(), node.location()));
                else if (attribute.getKey().equals("xmlns"))
                    namespaces.put("", new NamespaceBinding(attribute.getValue(), node.location()));
            }
            return previous;
        }
        private void elementContent(PageNode.Element node) {
            String local = node.name().substring(node.name().lastIndexOf(':') + 1);
            if (!node.namespace().equals(PageParser.JSP_NAMESPACE)) {
                String uri = node.namespace();
                var binding = taglibBindings.get(node.name().substring(0, Math.max(0, node.name().indexOf(':'))));
                boolean standard = sourceSyntaxes.get(node.location().source()) == Page.Syntax.STANDARD;
                var namespace = namespaces.get(node.name().substring(0, Math.max(0, node.name().indexOf(':'))));
                SourceLocation origin = standard && binding != null && uri.equals(binding.attributes().get("uri")) ? binding.location()
                        : namespace != null && uri.equals(namespace.uri()) ? namespace.location() : node.location();
                var library = resolvedLibraries.resolve(uri, origin, standard);
                if (library != null) { customTag(node, library); return; }
                if (sourceSyntaxes.get(node.location().source()) == Page.Syntax.STANDARD || node.namespace().startsWith("urn:jsptag")
                        || node.namespace().startsWith("urn:jsptld") || node.namespace().startsWith("jakarta.tags."))
                    throw error(node.location(), "No build-time tag library for " + node.namespace() + ": " + node.name());
                write("<" + node.name(), node.location());
                for (var entry : node.attributes().entrySet()) {
                    if (namespaceAttribute(entry.getKey()) && entry.getValue().equals(PageParser.JSP_NAMESPACE)) continue;
                    write(" " + entry.getKey() + "=\"", node.location());
                    body.add("out.write(_jspxText(" + value(entry.getValue(), node.location(), true) + "));\n", node.location());
                    write("\"", node.location());
                }
                write(node.selfClosing() ? "/>" : ">", node.location());
                if (!node.selfClosing()) { emit(node.children()); write("</" + node.name() + ">", node.location()); }
                return;
            }
            switch (local) {
                case "root", "text" -> emit(node.children());
                case "element" -> dynamicElement(node);
                case "include", "forward" -> dispatch(node, local.equals("forward"));
                case "useBean" -> bean(node);
                case "getProperty" -> getProperty(node);
                case "setProperty" -> setProperty(node);
                case "output" -> {
                    // Prolog metadata is collected for the whole translation unit before body generation.
                }
                default -> throw error(node.location(), "Unsupported standard action: " + node.name());
            }
        }


        private void synchronizeVariables(List<ScriptingVariable> variables, int... scopes) {
            for (var variable : variables) for (int scope : scopes) if (variable.declare() && variable.scope() == scope)
                body.add(variable.name() + " = (" + variable.type() + ") pageContext.getAttribute("
                        + JavaSource.quote(variable.name()) + ");\n");
        }

        private void addScriptingVariable(List<ScriptingVariable> variables, String name, String className,
                boolean declare, int scope, SourceLocation at) {
            if (!SourceVersion.isIdentifier(name) || SourceVersion.isKeyword(name)
                    || Set.of("true", "false", "null", "_", "request", "response", "pageContext", "out",
                            "application", "config", "page", "session", "exception").contains(name)
                    || name.startsWith("_jspx"))
                throw error(at, "Invalid or reserved tag scripting variable: " + name);
            String type = classes.expressionType(className, at);
            var resolved = new ScriptingVariable(name, type, declare, scope);
            variables.add(resolved);
            if (scope != 0 && declare) {
                var previous = pageVariables.putIfAbsent(name, resolved);
                if (previous == null) serviceDeclarations.add(type + " " + name + " = null;\n", at);
                else if (!previous.type().equals(type)) throw error(at, "Conflicting tag scripting variable type: " + name);
            }
        }

        private String tagVariableName(String attributeName, PageNode.Element tag, TagInvocation invocation) {
            String value = tag.attributes().get(attributeName);
            var parser = new PageNormalizer(sourceSyntaxes.getOrDefault(tag.location().source(), Page.Syntax.STANDARD),
                    new ParserOptions(bool("isELIgnored", false), bool("deferredSyntaxAllowedAsLiteral", false)), Map.of());
            if (value != null) {
                if (value.contains("<%"))
                    throw error(tag.location(), "Tag variable name attribute must be a translation-time constant: " + attributeName);
                return templateLiteral(parser.normalizeStructured(List.of(new PageNode.Text(value, tag.location()))));
            }
            var named = invocation.attributes().get(attributeName);
            if (named == null) throw error(tag.location(), "Missing tag variable name attribute: " + attributeName);
            return templateLiteral(parser.normalizeStructured(namedContent(named)));
        }

        private void customTag(PageNode.Element node, TagLibraryDescriptor library) {
            String local = node.name().substring(node.name().lastIndexOf(':') + 1);
            var tag = library.tags().get(local);
            if (tag == null) throw error(node.location(), "Unknown tag " + local + " in " + library.resourcePath());
            if (tag.unsupportedMetadata())
                throw error(node.location(), tag.unsupportedReason() == null
                        ? "Tag validators, scripting variables or tag metadata are not supported yet" : tag.unsupportedReason());
            var invocation = tagInvocation(node);
            var scriptingVariables = new ArrayList<ScriptingVariable>();
            if (tag.teiClass() != null) for (var variable : classes.tagVariables(tag.teiClass(), node.location()))
                addScriptingVariable(scriptingVariables, variable.name(), variable.className(), variable.declare(), variable.scope(), node.location());
            var kind = tag.tagFile() ? new BeanClasses.Handler(tag.handlerClass(), true, false, false, false, tag.dynamicAttributes(), false)
                    : classes.handler(tag.handlerClass(), node.location());
            if (kind.simple() && tag.bodyContent().equals("JSP")) throw error(node.location(), "SimpleTag cannot have JSP body-content");
            if (tag.dynamicAttributes() && !kind.dynamic()) throw error(node.location(), "Handler does not implement DynamicAttributes");
            validateTagExtraInfo(tag, library, node, invocation);
            var children = invocation.content();
            if (tag.bodyContent().equals("empty") && !children.isEmpty()) throw error(node.location(), "Tag requires an empty body");
            if (tag.bodyContent().equals("scriptless")) scriptless(children);
            for (var attribute : tag.attributes().values())
                if (attribute.required() && !node.attributes().containsKey(attribute.name()) && !invocation.attributes().containsKey(attribute.name()))
                    throw error(node.location(), "Required tag attribute: " + attribute.name());
            for (var variable : tag.variables()) {
                String name = variable.name() != null ? variable.name()
                        : tagVariableName(variable.nameFromAttribute(), node, invocation);
                addScriptingVariable(scriptingVariables, name, variable.className(), variable.declare(), variable.scope(), node.location());
            }
            String handler = "_jspxTag" + serial++;
            body.add("{\n", node.location());
            for (var variable : scriptingVariables)
                if (variable.scope() == TagVariableDescriptor.NESTED && variable.declare()
                        && (!tag.tagFile() || !children.isEmpty()))
                    body.add(variable.type() + " " + variable.name() + " = null;\n", node.location());
            body.add("var " + handler + " = new " + kind.sourceName() + "();\n", node.location());
            if (!kind.simple()) body.add("try {\n");
            body.add(handler + (kind.simple() ? ".setJspContext(pageContext);\n" : ".setPageContext(pageContext);\n"), node.location());
            if (!kind.simple()) body.add(handler + ".setParent(" + classicParent(parentTag) + ");\n");
            else if (!parentTag.equals("null")) body.add(handler + ".setParent(" + parentTag + ");\n");
            if (kind.jspIdConsumer()) body.add(handler + ".setJspId(" + JavaSource.quote("ibarra_tag_" + jspIdSerial++) + ");\n", node.location());
            for (var supplied : node.attributes().entrySet()) {
                if (namespaceAttribute(supplied.getKey())) continue;
                var attribute = tag.attributes().get(supplied.getKey());
                if (attribute == null) {
                    if (kind.jspIdConsumer() && supplied.getKey().equals("jspId")) throw error(node.location(), "Tag attribute cannot override compiler-provided jspId");
                    if (!tag.dynamicAttributes() || supplied.getKey().contains(":")) throw error(node.location(), "Unknown or namespaced tag attribute: " + supplied.getKey());
                    body.add(handler + ".setDynamicAttribute(null, " + JavaSource.quote(supplied.getKey()) + ", " + value(supplied.getValue(), node.location(), false) + ");\n", node.location());
                } else {
                    if (attribute.fragment()) throw error(node.location(), "Fragment attributes require jsp:attribute");
                    var setter = tag.tagFile() ? tagFileSetter(attribute)
                            : attribute.fragment()
                            ? classes.tagFragmentSetter(tag.handlerClass(), attribute.name(), attribute.setterType(), node.location())
                            : attribute.deferred() ? classes.tagSetter(tag.handlerClass(), attribute.name(), attribute.setterType(), node.location())
                            : classes.tagPropertySetter(tag.handlerClass(), attribute.name(), node.location());
                    var target = new TagAttribute(attribute.name(), setter.type(), attribute.required(), attribute.runtimeExpression(), false);
                    protectJspIdSetter(kind, setter, node.location());
                    String argument = attribute.deferred() ? deferredAttribute(attributeInput(supplied.getValue(), node.location()), attribute, node.location())
                            : tagValue(supplied.getValue(), target, node.location());
                    body.add(handler + "." + setter.name() + "((" + setter.type() + ") (" + argument + "));\n", node.location());
                }
            }
            for (var supplied : invocation.attributes().entrySet()) {
                var named = supplied.getValue();
                var previousNamespaces = enterNamespaces(named);
                try {
                var attribute = tag.attributes().get(supplied.getKey());
                var content = namedContent(named);
                if (attribute == null) {
                    if (kind.jspIdConsumer() && supplied.getKey().equals("jspId")) throw error(named.location(), "Tag attribute cannot override compiler-provided jspId");
                    if (!tag.dynamicAttributes() || supplied.getKey().contains(":")) throw error(named.location(), "Unknown or namespaced tag attribute: " + supplied.getKey());
                    body.add(handler + ".setDynamicAttribute(null, " + JavaSource.quote(supplied.getKey()) + ", " + capture(content, named.location(), handler) + ");\n", named.location());
                } else {
                    var setter = tag.tagFile() ? tagFileSetter(attribute)
                            : attribute.fragment()
                            ? classes.tagFragmentSetter(tag.handlerClass(), attribute.name(), attribute.setterType(), named.location())
                            : attribute.deferred() ? classes.tagSetter(tag.handlerClass(), attribute.name(), attribute.setterType(), named.location())
                            : classes.tagPropertySetter(tag.handlerClass(), attribute.name(), named.location());
                    protectJspIdSetter(kind, setter, named.location());
                    String argument;
                    if (attribute.fragment()) argument = fragment(content, named.location(), handler);
                    else if (attribute.deferred()) argument = deferredAttribute(deferredInput(content, named.location()), attribute, named.location());
                    else if (attribute.runtimeExpression()) argument = stringConversion(capture(content, named.location(), handler), setter.type(), named.location());
                    else {
                        argument = literalValue(templateLiteral(content), new TagAttribute(attribute.name(), setter.type(), attribute.required(), false, false), named.location());
                    }
                    body.add(handler + "." + setter.name() + "((" + setter.type() + ") (" + argument + "));\n", named.location());
                }
                } finally { namespaces.clear(); namespaces.putAll(previousNamespaces); }
            }
            String previousParent = parentTag; parentTag = handler;
            if (kind.simple()) {
                if (!children.isEmpty()) body.add(handler + ".setJspBody(" + fragment(children, node.location(), handler) + ");\n", node.location());
                var nestedTagFileVariableIndexes = new ArrayList<Integer>();
                if (tag.tagFile()) {
                    int variableIndex = 0;
                    for (var variable : scriptingVariables) {
                        String state = handler + "_jspxVariable" + variableIndex;
                        String name = JavaSource.quote(variable.name());
                        body.add("boolean " + state + "Present = pageContext.getAttributesScope(" + name
                                + ") == jakarta.servlet.jsp.PageContext.PAGE_SCOPE;\n"
                                + "Object " + state + "Value = pageContext.getAttribute(" + name
                                + ", jakarta.servlet.jsp.PageContext.PAGE_SCOPE);\n"
                                + "pageContext.removeAttribute(" + name + ", jakarta.servlet.jsp.PageContext.PAGE_SCOPE);\n",
                                node.location());
                        if (variable.scope() == TagVariableDescriptor.NESTED) nestedTagFileVariableIndexes.add(variableIndex);
                        variableIndex++;
                    }
                }
                if (nestedTagFileVariableIndexes.isEmpty()) body.add(handler + ".doTag();\n", node.location());
                else {
                    body.add("try { " + handler + ".doTag(); } finally {\n", node.location());
                    for (int variableIndex : nestedTagFileVariableIndexes) {
                        var variable = scriptingVariables.get(variableIndex);
                        String state = handler + "_jspxVariable" + variableIndex;
                        String name = JavaSource.quote(variable.name());
                        body.add("pageContext.removeAttribute(" + name + ", jakarta.servlet.jsp.PageContext.PAGE_SCOPE);\n"
                                + "if (" + state + "Present) pageContext.setAttribute(" + name + ", " + state
                                + "Value, jakarta.servlet.jsp.PageContext.PAGE_SCOPE);\n", node.location());
                    }
                    body.add("}\n");
                }
                if (tag.tagFile()) synchronizeTagFileVariables(handler, scriptingVariables, node.location());
                synchronizeVariables(scriptingVariables, 1, 2);
            } else {
                if (kind.catchFinally()) body.add("boolean _jspxSetterFailed" + handler + " = false;\n");
                body.add("int _jspxEnd" + handler + " = jakarta.servlet.jsp.tagext.Tag.EVAL_PAGE;\n");
                body.add("try {\nint _jspxStart" + handler + " = " + handler + ".doStartTag();\n", node.location());
                String start = "_jspxStart" + handler;
                body.add("if (" + start + " != 0 && " + start + " != 1" + (kind.buffered() ? " && " + start + " != 2" : "")
                        + ") throw new jakarta.servlet.jsp.JspException(\"Invalid doStartTag result\");\n");
                synchronizeVariables(scriptingVariables, 1);
                if (!children.isEmpty()) {
                    var previousBody = body; var content = new JavaSource(); body = content; emit(children); body = previousBody;
                    body.add("if (" + start + " != jakarta.servlet.jsp.tagext.Tag.SKIP_BODY) {\n");
                    if (kind.buffered()) body.add("boolean _jspxBuffered" + handler + " = " + start + " == jakarta.servlet.jsp.tagext.BodyTag.EVAL_BODY_BUFFERED;\n"
                            + "if (_jspxBuffered" + handler + ") out = pageContext.pushBody();\ntry {\n"
                            + "if (_jspxBuffered" + handler + ") { " + (kind.catchFinally() ? "_jspxSetterFailed" + handler + " = true; " : "")
                            + handler + ".setBodyContent((jakarta.servlet.jsp.tagext.BodyContent) out); "
                            + (kind.catchFinally() ? "_jspxSetterFailed" + handler + " = false; " : "") + handler + ".doInitBody(); }\n");
                    if (kind.buffered()) synchronizeVariables(scriptingVariables, 1, 0);
                    else synchronizeVariables(scriptingVariables, 0);
                    if (kind.iteration() && !scriptingVariables.isEmpty()) {
                        String afterBody = "_jspxAfterBody" + handler;
                        body.add("int " + afterBody + ";\ndo {\n"); body.add(content);
                        body.add(afterBody + " = " + handler + ".doAfterBody();\n");
                        synchronizeVariables(scriptingVariables, 1, 0);
                        body.add("} while (" + afterBody + " == jakarta.servlet.jsp.tagext.IterationTag.EVAL_BODY_AGAIN);\n");
                    } else {
                        body.add("do {\n"); body.add(content);
                        body.add("} while (" + (kind.iteration() ? handler + ".doAfterBody() == jakarta.servlet.jsp.tagext.IterationTag.EVAL_BODY_AGAIN" : "false") + ");\n");
                    }
                    if (kind.buffered()) body.add("} finally { if (_jspxBuffered" + handler + ") out = pageContext.popBody(); }\n");
                    body.add("}\n");
                }
                body.add("_jspxEnd" + handler + " = " + handler + ".doEndTag();\n", node.location());
                synchronizeVariables(scriptingVariables, 1, 2);
                if (kind.catchFinally()) body.add("} catch (Throwable _jspxFailure" + handler + ") { if (_jspxSetterFailed" + handler + ") throw _jspxFailure" + handler + "; "
                        + handler + ".doCatch(_jspxFailure" + handler + "); if (_jspxFailure" + handler + " instanceof jakarta.servlet.jsp.SkipPageException) throw _jspxFailure" + handler + ";"
                        + " } finally { if (!_jspxSetterFailed" + handler + ") " + handler + ".doFinally(); }\n");
                else body.add("} finally { /* Handler action scope. */ }\n");
                body.add("if (_jspxEnd" + handler + " == jakarta.servlet.jsp.tagext.Tag.SKIP_PAGE) throw new jakarta.servlet.jsp.SkipPageException();\n", node.location());
                body.add("} finally { " + handler + ".release(); }\n");
            }
            parentTag = previousParent; body.add("}\n");
        }

        private void synchronizeTagFileVariables(String handler, List<ScriptingVariable> variables, SourceLocation at) {
            int index = 0;
            for (var variable : variables) {
                if (variable.scope() == TagVariableDescriptor.NESTED) { index++; continue; }
                String value = handler + "_jspxScopeOutput" + index++;
                String name = JavaSource.quote(variable.name());
                body.add("Object " + value + " = " + handler + "._jspxTagFileContext().getAttribute(" + name
                        + ", jakarta.servlet.jsp.PageContext.PAGE_SCOPE);\n"
                        + "pageContext.removeAttribute(" + name + ", jakarta.servlet.jsp.PageContext.PAGE_SCOPE);\n"
                        + "if (" + value + " != null) pageContext.setAttribute(" + name + ", " + value
                        + ", jakarta.servlet.jsp.PageContext.PAGE_SCOPE);\n", at);
            }
        }

        private record TagInvocation(Map<String, PageNode.Element> attributes, List<PageNode> content) {}

        private BeanClasses.Accessor tagFileSetter(TagAttribute attribute) {
            String name = "set" + Character.toUpperCase(attribute.name().charAt(0)) + attribute.name().substring(1);
            return new BeanClasses.Accessor(name, attribute.type());
        }

        private void validateTagExtraInfo(TagDescriptor tag, TagLibraryDescriptor library, PageNode.Element node,
                TagInvocation invocation) {
            if (tag.teiClass() == null) return;
            String suffix = String.valueOf(tagValidationSerial++);
            String extraInfo = "_jspxTagExtraInfo" + suffix;
            String tagLibrary = "_jspxTagLibrary" + suffix;
            String tagInfo = "_jspxTagInfo" + suffix;
            String messages = "_jspxTagValidationMessages" + suffix;
            tagValidations.add("var " + extraInfo + " = new " + tag.teiClass().replace('$', '.') + "();\n", node.location());
            tagValidations.add("var " + tagLibrary + " = " + tagLibraryInfoSource(library, node) + ";\n", node.location());

            String attributes = tag.attributes().values().stream().map(this::tagAttributeInfoSource)
                    .collect(java.util.stream.Collectors.joining(", "));
            String variables = tag.variables().stream().map(variable ->
                    "new jakarta.servlet.jsp.tagext.TagVariableInfo(" + (variable.name() == null ? "null" : JavaSource.quote(variable.name())) + ", "
                            + (variable.nameFromAttribute() == null ? "null" : JavaSource.quote(variable.nameFromAttribute())) + ", "
                            + JavaSource.quote(variable.className()) + ", " + variable.declare() + ", " + variable.scope() + ")")
                    .collect(java.util.stream.Collectors.joining(", "));
            tagValidations.add("var " + tagInfo + " = new jakarta.servlet.jsp.tagext.TagInfo("
                    + JavaSource.quote(tag.name()) + ", " + JavaSource.quote(tag.handlerClass()) + ", "
                    + JavaSource.quote(tag.bodyContent()) + ", " + nullableString(tag.infoString()) + ", " + tagLibrary + ", " + extraInfo
                    + ", new jakarta.servlet.jsp.tagext.TagAttributeInfo[] {" + attributes + "}, " + nullableString(tag.displayName())
                    + ", " + nullableString(tag.smallIcon()) + ", " + nullableString(tag.largeIcon()) + ", "
                    + "new jakarta.servlet.jsp.tagext.TagVariableInfo[] {" + variables + "}, " + tag.dynamicAttributes() + ");\n"
                    + extraInfo + ".setTagInfo(" + tagInfo + ");\n", node.location());

            var values = new LinkedHashMap<String, String>();
            for (var supplied : node.attributes().entrySet()) {
                if (namespaceAttribute(supplied.getKey())) continue;
                values.put(supplied.getKey(), tagDataValue(supplied.getValue(), tag.attributes().get(supplied.getKey()), node.location()));
            }
            for (var supplied : invocation.attributes().entrySet()) {
                TagAttribute attribute = tag.attributes().get(supplied.getKey());
                List<PageNode> content = namedContent(supplied.getValue());
                String value = attribute != null && attribute.fragment() ? tagDataRequestTimeValue()
                        : content.stream().allMatch(PageNode.Text.class::isInstance)
                                ? tagDataValue(content.stream().map(PageNode.Text.class::cast).map(PageNode.Text::value)
                                        .collect(java.util.stream.Collectors.joining()), attribute, supplied.getValue().location())
                                : tagDataRequestTimeValue();
                values.put(supplied.getKey(), value);
            }
            String pairs = values.entrySet().stream().map(value -> "new Object[] { " + JavaSource.quote(value.getKey()) + ", "
                    + value.getValue() + " }").collect(java.util.stream.Collectors.joining(", "));
            tagValidations.add("var " + messages + " = " + extraInfo + ".validate(new jakarta.servlet.jsp.tagext.TagData(new Object[][] {"
                    + pairs + "}));\n"
                    + "if (" + messages + " != null && " + messages + ".length != 0) throw new IllegalStateException("
                    + JavaSource.quote("TagExtraInfo validation failed for " + tag.name() + ": ") + " + " + messages + "[0].getMessage());\n",
                    node.location());
        }

        private record TagLibraryUse(String prefix, String uri, TagLibraryValidatorDescriptor validator) { }

        private void validateTagLibraries() {
            var uses = new LinkedHashMap<String, TagLibraryUse>();
            var namespaces = new LinkedHashMap<String, String>();
            collectTagLibraryUses(page.nodes(), uses, namespaces);
            if (uses.isEmpty()) return;
            String xmlView = PageDataXmlView.serialize(page.nodes(), namespaces);
            int serial = 0;
            for (var use : uses.values()) {
                String suffix = String.valueOf(serial++);
                String validator = "_jspxTagLibraryValidator" + suffix;
                String xml = "_jspxPageDataXml" + suffix;
                String messages = "_jspxTagLibraryMessages" + suffix;
                libraryValidations.add("var " + validator + " = new " + use.validator().className().replace('$', '.') + "();\n");
                if (!use.validator().initParameters().isEmpty()) {
                    String parameters = use.validator().initParameters().entrySet().stream()
                            .sorted(Map.Entry.comparingByKey())
                            .map(parameter -> "java.util.Map.<String,Object>entry(" + JavaSource.quote(parameter.getKey())
                                    + ", " + JavaSource.quote(parameter.getValue()) + ")")
                            .collect(java.util.stream.Collectors.joining(", "));
                    libraryValidations.add(validator + ".setInitParameters(java.util.Map.<String,Object>ofEntries(" + parameters + "));\n");
                }
                libraryValidations.add("var " + xml + " = " + JavaSource.quote(xmlView) + ";\n"
                        + "try {\nvar " + messages + " = " + validator + ".validate(" + JavaSource.quote(use.prefix()) + ", "
                        + JavaSource.quote(use.uri()) + ", new jakarta.servlet.jsp.tagext.PageData() {\n"
                        + "@Override public java.io.InputStream getInputStream() { return new java.io.ByteArrayInputStream("
                        + xml + ".getBytes(java.nio.charset.StandardCharsets.UTF_8)); }\n});\n"
                        + "if (" + messages + " != null && " + messages + ".length != 0) throw new IllegalStateException("
                        + JavaSource.quote("TagLibraryValidator validation failed for " + use.uri() + ": ")
                        + " + " + messages + "[0].getMessage());\n"
                        + "} finally { " + validator + ".release(); }\n");
            }
        }

        private void collectTagLibraryUses(List<PageNode> nodes, Map<String, TagLibraryUse> uses,
                Map<String, String> namespaces) {
            for (var node : nodes) {
                if (node instanceof PageNode.Directive directive && directive.name().equals("taglib")) {
                    String prefix = directive.attributes().get("prefix");
                    String uri = directive.attributes().get("uri");
                    if (prefix != null && uri != null) {
                        namespaces.putIfAbsent(prefix, uri);
                        addTagLibraryUse(prefix, uri, directive.location(), true, uses);
                    }
                } else if (node instanceof PageNode.Element element) {
                    for (var attribute : element.attributes().entrySet()) {
                        String prefix = attribute.getKey().startsWith("xmlns:") ? attribute.getKey().substring(6)
                                : attribute.getKey().equals("xmlns") ? "" : null;
                        if (prefix != null) {
                            namespaces.putIfAbsent(prefix, attribute.getValue());
                            addTagLibraryUse(prefix, attribute.getValue(), element.location(), false, uses);
                        }
                    }
                    if (!element.namespace().isEmpty() && !element.namespace().equals(PageParser.JSP_NAMESPACE)) {
                        String prefix = element.name().contains(":") ? element.name().substring(0, element.name().indexOf(':')) : "";
                        namespaces.putIfAbsent(prefix, element.namespace());
                        addTagLibraryUse(prefix, element.namespace(), element.location(),
                                sourceSyntaxes.get(element.location().source()) == Page.Syntax.STANDARD, uses);
                    }
                    collectTagLibraryUses(element.children(), uses, namespaces);
                }
            }
        }

        private void addTagLibraryUse(String prefix, String uri, SourceLocation at, boolean standard,
                Map<String, TagLibraryUse> uses) {
            if (uri.isBlank() || uri.equals(PageParser.JSP_NAMESPACE)) return;
            var library = resolvedLibraries.resolve(uri, at, standard);
            if (library == null || library.validator() == null) return;
            uses.putIfAbsent(uri, new TagLibraryUse(prefix, uri, library.validator()));
        }

        private String tagLibraryInfoSource(TagLibraryDescriptor library, PageNode.Element node) {
            int separator = node.name().indexOf(':');
            String prefix = separator < 0 ? "" : node.name().substring(0, separator);
            return "new jakarta.servlet.jsp.tagext.TagLibraryInfo(" + JavaSource.quote(prefix) + ", "
                    + JavaSource.quote(node.namespace()) + ") {\n"
                    + "private jakarta.servlet.jsp.tagext.TagLibraryInfo[] _jspxTagLibraryInfos;\n"
                    + "{ " + tagLibraryMetadataSource(library, node.location())
                    + "_jspxTagLibraryInfos = new jakarta.servlet.jsp.tagext.TagLibraryInfo[] {"
                    + tagLibraryInfosSource(library, prefix, node.namespace(), node.location()) + "}; }\n"
                    + "@Override public jakarta.servlet.jsp.tagext.TagLibraryInfo[] getTagLibraryInfos() { "
                    + "return _jspxTagLibraryInfos; }\n}";
        }

        private String tagLibraryInfosSource(TagLibraryDescriptor current, String currentPrefix, String currentUri,
                SourceLocation at) {
            var entries = new ArrayList<String>();
            boolean currentIncluded = false;
            for (var binding : taglibBindings.entrySet()) {
                PageNode.Directive directive = binding.getValue();
                String uri = directive.attributes().get("uri");
                if (uri == null) continue;
                TagLibraryDescriptor imported = resolvedLibraries.resolve(uri, directive.location(), true);
                if (imported == null) continue;
                if (binding.getKey().equals(currentPrefix) && uri.equals(currentUri)) {
                    entries.add("this");
                    currentIncluded = true;
                } else entries.add(tagLibraryInfoEntrySource(binding.getKey(), uri, imported, at));
            }
            if (!currentIncluded) entries.add("this");
            return String.join(", ", entries);
        }

        private String tagLibraryInfoEntrySource(String prefix, String uri, TagLibraryDescriptor library, SourceLocation at) {
            return "new jakarta.servlet.jsp.tagext.TagLibraryInfo(" + JavaSource.quote(prefix) + ", "
                    + JavaSource.quote(uri) + ") {\n"
                    + "{ " + tagLibraryMetadataSource(library, at) + "}\n"
                    + "@Override public jakarta.servlet.jsp.tagext.TagLibraryInfo[] getTagLibraryInfos() { "
                    + "return _jspxTagLibraryInfos; }\n}";
        }

        private String tagLibraryMetadataSource(TagLibraryDescriptor library, SourceLocation at) {
            String functions = library.functions().values().stream()
                    .sorted(java.util.Comparator.comparing(TldFunction::name))
                    .map(function -> "new jakarta.servlet.jsp.tagext.FunctionInfo(" + JavaSource.quote(function.name()) + ", "
                            + JavaSource.quote(function.functionClass()) + ", " + JavaSource.quote(function.signature()) + ")")
                    .collect(java.util.stream.Collectors.joining(", "));
            String tagFiles = library.tags().values().stream()
                    .filter(tag -> tag.tagFile() && tag.tagFilePath() != null)
                    .sorted(java.util.Comparator.comparing(TagDescriptor::name))
                    .map(tag -> tagFileInfoSource(tag, at))
                    .collect(java.util.stream.Collectors.joining(", "));
            var tagDeclarations = new StringBuilder();
            var tagNames = new ArrayList<String>();
            int tagIndex = 0;
            for (var tag : library.tags().values()) {
                if (tag.tagFile()) continue;
                String tagInfo = "_jspxTldTagInfo" + tagIndex;
                String tagExtraInfo = "_jspxTldTagExtraInfo" + tagIndex;
                if (tag.teiClass() != null)
                    tagDeclarations.append("var ").append(tagExtraInfo).append(" = new ")
                            .append(tag.teiClass().replace('$', '.')).append("(); ");
                String attributes = tag.attributes().values().stream().map(this::tagAttributeInfoSource)
                        .collect(java.util.stream.Collectors.joining(", "));
                String variables = tag.variables().stream().map(variable ->
                        "new jakarta.servlet.jsp.tagext.TagVariableInfo(" + nullableString(variable.name()) + ", "
                                + nullableString(variable.nameFromAttribute()) + ", " + JavaSource.quote(variable.className())
                                + ", " + variable.declare() + ", " + variable.scope() + ")")
                        .collect(java.util.stream.Collectors.joining(", "));
                tagDeclarations.append("var ").append(tagInfo).append(" = new jakarta.servlet.jsp.tagext.TagInfo(")
                        .append(JavaSource.quote(tag.name())).append(", ").append(nullableString(tag.handlerClass())).append(", ")
                        .append(JavaSource.quote(tag.bodyContent())).append(", ").append(nullableString(tag.infoString())).append(", this, ")
                        .append(tag.teiClass() == null ? "null" : tagExtraInfo).append(", ")
                        .append("new jakarta.servlet.jsp.tagext.TagAttributeInfo[] {").append(attributes).append("}, ")
                        .append(nullableString(tag.displayName())).append(", ").append(nullableString(tag.smallIcon())).append(", ")
                        .append(nullableString(tag.largeIcon())).append(", new jakarta.servlet.jsp.tagext.TagVariableInfo[] {")
                        .append(variables).append("}, ").append(tag.dynamicAttributes()).append("); ");
                if (tag.teiClass() != null) tagDeclarations.append(tagExtraInfo).append(".setTagInfo(").append(tagInfo).append("); ");
                tagNames.add(tagInfo);
                tagIndex++;
            }
            return "shortname = " + nullableString(library.shortName()) + "; info = " + nullableString(library.infoString())
                    + "; tlibversion = " + nullableString(library.tlibVersion()) + "; jspversion = "
                    + nullableString(library.requiredVersion()) + "; urn = " + nullableString(library.uri()) + "; "
                    + tagDeclarations + "tags = new jakarta.servlet.jsp.tagext.TagInfo[] {" + String.join(", ", tagNames)
                    + "}; functions = new jakarta.servlet.jsp.tagext.FunctionInfo[] {" + functions + "}; "
                    + "tagFiles = new jakarta.servlet.jsp.tagext.TagFileInfo[] {" + tagFiles + "}; ";
        }

        private String tagFileInfoSource(TagDescriptor tagFile, SourceLocation at) {
            String path = tagFile.tagFilePath();
            byte[] source = generationOptions.byteSource(path);
            if (source == null) throw new PageParseException(at, "Tag-file resource is absent: " + path);
            var unit = new TranslationUnit(generationOptions).read(path, source);
            var metadata = TagFileCompiler.tagInfoMetadata(unit);
            String attributes = metadata.attributes().values().stream().map(this::tagAttributeInfoSource)
                    .collect(java.util.stream.Collectors.joining(", "));
            String variables = metadata.variables().stream().map(variable ->
                    "new jakarta.servlet.jsp.tagext.TagVariableInfo(" + nullableString(variable.name()) + ", "
                            + nullableString(variable.nameFromAttribute()) + ", " + JavaSource.quote(variable.className())
                            + ", " + variable.declare() + ", " + variable.scope() + ")")
                    .collect(java.util.stream.Collectors.joining(", "));
            return "new jakarta.servlet.jsp.tagext.TagFileInfo(" + JavaSource.quote(tagFile.name()) + ", "
                    + JavaSource.quote(path) + ", new jakarta.servlet.jsp.tagext.TagInfo(" + JavaSource.quote(tagFile.name())
                    + ", null, " + JavaSource.quote(metadata.bodyContent()) + ", " + nullableString(metadata.infoString())
                    + ", this, null, new jakarta.servlet.jsp.tagext.TagAttributeInfo[] {" + attributes + "}, "
                    + nullableString(metadata.displayName()) + ", " + nullableString(metadata.smallIcon()) + ", "
                    + nullableString(metadata.largeIcon()) + ", new jakarta.servlet.jsp.tagext.TagVariableInfo[] {"
                    + variables + "}, " + metadata.dynamicAttributes() + "))";
        }

        private String tagAttributeInfoSource(TagAttribute attribute) {
            String arguments = JavaSource.quote(attribute.name()) + ", " + attribute.required() + ", "
                    + JavaSource.quote(attribute.type()) + ", " + attribute.runtimeExpression() + ", " + attribute.fragment();
            if (!attribute.deferred())
                return "new jakarta.servlet.jsp.tagext.TagAttributeInfo(" + arguments + ")";
            String methodSignature = attribute.deferredMethod() == null ? null
                    : attribute.deferredMethod().returnType() + " method(" + String.join(", ", attribute.deferredMethod().parameters()) + ")";
            return "new jakarta.servlet.jsp.tagext.TagAttributeInfo(" + arguments + ", null, "
                    + (attribute.deferredValueType() != null) + ", " + (attribute.deferredMethod() != null) + ", "
                    + nullableString(attribute.deferredValueType()) + ", " + nullableString(methodSignature) + ")";
        }

        private String tagDataValue(String raw, TagAttribute attribute, SourceLocation at) {
            if (attribute != null && attribute.fragment() || raw.startsWith("<%=") && raw.endsWith("%>"))
                return tagDataRequestTimeValue();
            var normalized = new PageNormalizer(sourceSyntaxes.getOrDefault(at.source(), Page.Syntax.STANDARD),
                    new ParserOptions(bool("isELIgnored", false), bool("deferredSyntaxAllowedAsLiteral", false)), Map.of())
                    .normalizeStructured(List.of(new PageNode.Text(raw, at)));
            if (normalized.stream().anyMatch(value -> !(value instanceof PageNode.Text))) return tagDataRequestTimeValue();
            return JavaSource.quote(normalized.stream().map(PageNode.Text.class::cast).map(PageNode.Text::value)
                    .collect(java.util.stream.Collectors.joining()));
        }

        private String tagDataRequestTimeValue() {
            return "jakarta.servlet.jsp.tagext.TagData.REQUEST_TIME_VALUE";
        }

        private String nullableString(String value) {
            return value == null ? "null" : JavaSource.quote(value);
        }

        private boolean action(PageNode node, String name) {
            return node instanceof PageNode.Element element && element.namespace().equals(PageParser.JSP_NAMESPACE)
                    && element.name().substring(element.name().lastIndexOf(':') + 1).equals(name);
        }
        private TagInvocation tagInvocation(PageNode.Element tag) {
            if (tag.children().stream().noneMatch(child -> action(child, "attribute") || action(child, "body")))
                return new TagInvocation(Map.of(), tag.children());
            var named = new LinkedHashMap<String, PageNode.Element>();
            var content = new ArrayList<PageNode>();
            boolean bodySeen = false;
            for (var child : tag.children()) {
                if (child instanceof PageNode.Text text && text.value().chars().allMatch(c -> xmlSpace((char) c))) continue;
                if (action(child, "attribute")) {
                    var attribute = (PageNode.Element) child;
                    if (bodySeen) throw error(child.location(), "jsp:attribute must precede jsp:body");
                    allowed(attribute, Set.of("name", "trim", "omit"));
                    String name = required(attribute, "name");
                    if (!name.matches("[\\p{L}_][\\p{L}\\p{N}\\p{M}_.-]*(?::[\\p{L}_][\\p{L}\\p{N}\\p{M}_.-]*)?"))
                        throw error(child.location(), "Invalid named tag attribute: " + name);
                    int colon = name.indexOf(':');
                    if (colon >= 0) {
                        if (!name.substring(0, colon).equals(tag.name().substring(0, tag.name().indexOf(':'))))
                            throw error(child.location(), "Unknown or namespaced tag attribute: " + name);
                        name = name.substring(colon + 1);
                    }
                    if (tag.attributes().containsKey(name) || named.putIfAbsent(name, attribute) != null)
                        throw error(child.location(), "Duplicate tag attribute: " + name);
                    if (attribute.attributes().containsKey("omit")) boolValue(attribute.attributes().get("omit"), attribute.location());
                } else if (action(child, "body")) {
                    if (bodySeen) throw error(child.location(), "Duplicate jsp:body");
                    bodySeen = true;
                    var wrapper = (PageNode.Element) child;
                    allowed(wrapper, Set.of());
                    if (!wrapper.children().isEmpty()) content.add(new PageNode.Element("jsp:text", PageParser.JSP_NAMESPACE,
                            wrapper.attributes(), wrapper.children(), wrapper.selfClosing(), wrapper.location()));
                } else throw error(child.location(), "Named attributes and explicit bodies require jsp:body around content");
            }
            return new TagInvocation(named, content);
        }
        private List<PageNode> namedContent(PageNode.Element attribute) {
            var children = new ArrayList<>(attribute.children());
            if (boolValue(attribute.attributes().getOrDefault("trim", "true"), attribute.location()) && !children.isEmpty()) {
                trimNamed(children, true); trimNamed(children, false);
            }
            return children;
        }
        private boolean trimNamed(List<PageNode> children, boolean leading) {
            for (int index = leading ? 0 : children.size() - 1; index >= 0 && index < children.size(); index += leading ? 1 : -1) {
                var node = children.get(index);
                if (node instanceof PageNode.Text text) {
                    int start = 0, end = text.value().length();
                    if (leading) while (start < end && xmlSpace(text.value().charAt(start))) start++;
                    else while (end > start && xmlSpace(text.value().charAt(end - 1))) end--;
                    children.set(index, new PageNode.Text(text.value().substring(start, end), text.location().advance(text.value().substring(0, start))));
                    if (start != end) return false;
                } else if (action(node, "text")) {
                    var element = (PageNode.Element) node;
                    var content = new ArrayList<>(element.children());
                    boolean exhausted = trimNamed(content, leading);
                    children.set(index, new PageNode.Element(element.name(), element.namespace(), element.attributes(), content, element.selfClosing(), element.location()));
                    if (!exhausted) return false;
                } else return false;
            }
            return true;
        }
        private String templateLiteral(List<PageNode> nodes) {
            var literal = new StringBuilder();
            for (var node : nodes) {
                if (node instanceof PageNode.Text text) literal.append(text.value());
                else if (action(node, "text")) literal.append(templateLiteral(((PageNode.Element) node).children()));
                else if (node instanceof PageNode.Element element && sourceSyntaxes.get(element.location().source()) == Page.Syntax.XML
                        && !element.namespace().equals(PageParser.JSP_NAMESPACE) && !element.namespace().startsWith("urn:jsp")
                        && !element.namespace().startsWith("jakarta.tags.")
                        && resolvedLibraries.resolve(element.namespace(), element.location(), false) == null) {
                    literal.append('<').append(element.name());
                    for (var entry : element.attributes().entrySet()) {
                        var normalized = new PageNormalizer(Page.Syntax.XML,
                                new ParserOptions(bool("isELIgnored", false), bool("deferredSyntaxAllowedAsLiteral", false)), Map.of())
                                .normalizeStructured(List.of(new PageNode.Text(entry.getValue(), element.location())));
                        String value = templateLiteral(normalized);
                        literal.append(' ').append(entry.getKey()).append("=\"")
                                .append(value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;"))
                                .append('"');
                    }
                    literal.append(element.selfClosing() ? "/>" : ">");
                    if (!element.selfClosing()) literal.append(templateLiteral(element.children())).append("</").append(element.name()).append('>');
                } else throw error(node.location(), "Static named attributes accept only template text");
            }
            return literal.toString();
        }
        private String capture(List<PageNode> nodes, SourceLocation at, String parent) {
            String captured = "_jspxAttribute" + serial++;
            String writer = "_jspxBody" + serial++;
            body.add("String " + captured + ";\nvar " + writer + " = pageContext.pushBody();\ntry { out = " + writer + ";\n", at);
            String previousParent = parentTag; parentTag = parent;
            emit(nodes); parentTag = previousParent;
            body.add(captured + " = " + writer + ".getString();\n} finally { out = pageContext.popBody(); }\n", at);
            return captured;
        }

        private String classicParent(String parent) {
            if (parent.equals("null")) return "null";
            return "(" + parent + " instanceof jakarta.servlet.jsp.tagext.Tag ? (jakarta.servlet.jsp.tagext.Tag) (jakarta.servlet.jsp.tagext.JspTag) "
                    + parent + " : new jakarta.servlet.jsp.tagext.TagAdapter((jakarta.servlet.jsp.tagext.SimpleTag) (jakarta.servlet.jsp.tagext.JspTag) " + parent + "))";
        }
        private void scriptless(List<PageNode> nodes) {
            for (var node : nodes) {
                if (node instanceof PageNode.Script) throw error(node.location(), "Scripting is forbidden in a scriptless tag body");
                if (node instanceof PageNode.Element element) {
                    for (String value : element.attributes().values()) if (value.startsWith("<%=")) throw error(node.location(), "Scripting attributes are forbidden in a scriptless body");
                    scriptless(element.children());
                }
            }
        }
        private void protectJspIdSetter(BeanClasses.Handler kind, BeanClasses.Accessor setter, SourceLocation at) {
            if (kind.jspIdConsumer() && setter.name().equals("setJspId") && setter.type().equals("java.lang.String"))
                throw error(at, "Tag attribute cannot override compiler-provided jspId");
        }

        private String fragment(List<PageNode> nodes, SourceLocation at, String parent) {
            scriptless(nodes);
            String name = "_jspxFragment" + serial++;
            var previousBody = body; String previousParent = parentTag; boolean previousFactory = needsFactory;
            body = new JavaSource(); parentTag = "_jspxParent"; needsFactory = false; fragmentDepth++;
            emit(nodes);
            var content = body; boolean factory = needsFactory;
            fragmentDepth--; body = previousBody; parentTag = previousParent; needsFactory = previousFactory || factory;
            var definition = new JavaSource();
            definition.add("private final class " + name + " extends jakarta.servlet.jsp.tagext.JspFragment {\n"
                    + "private final jakarta.servlet.jsp.PageContext _context; private final jakarta.servlet.jsp.tagext.JspTag _jspxParent; private final boolean[] _forwarded;\n"
                    + name + "(jakarta.servlet.jsp.PageContext context, jakarta.servlet.jsp.tagext.JspTag parent, boolean[] forwarded) { _context=context; _jspxParent=parent; _forwarded=forwarded; }\n"
                    + "public jakarta.servlet.jsp.JspContext getJspContext() { return _context; }\n"
                    + "public void invoke(java.io.Writer writer) throws jakarta.servlet.jsp.JspException, java.io.IOException {\n"
                    + "var pageContext=_context; var out=pageContext.getOut(); var _jspxForwarded=_forwarded;\n"
                    + "var request=(jakarta.servlet.http.HttpServletRequest) pageContext.getRequest(); var response=(jakarta.servlet.http.HttpServletResponse) pageContext.getResponse();\n"
                    + "var application=pageContext.getServletContext(); var config=pageContext.getServletConfig(); Object page=pageContext.getPage();\n");
            if (bool("session", true)) definition.add("var session=pageContext.getSession();\n");
            if (bool("isErrorPage", false)) definition.add("Throwable exception=pageContext.getErrorData().getThrowable();\n");
            if (factory) definition.add("var _jspxExpressionFactory=jakarta.servlet.jsp.JspFactory.getDefaultFactory().getJspApplicationContext(application).getExpressionFactory();\n");
            definition.add("if (writer != null) out=pageContext.pushBody(writer);\ntry {\n"); definition.add(content);
            definition.add("} catch (Throwable failure) { if (failure instanceof jakarta.servlet.jsp.JspException jsp) throw jsp;"
                    + " if (failure instanceof java.io.IOException io) throw io; if (failure instanceof RuntimeException runtime) throw runtime; if (failure instanceof Error error) throw error;"
                    + " throw new jakarta.servlet.jsp.JspException(failure); } finally { if (writer != null) pageContext.popBody(); }\n}\n}\n");
            fragments.add(definition);
            return "new " + name + "(pageContext, " + parent + ", _jspxForwarded)";
        }
        private String tagValue(String raw, TagAttribute attribute, SourceLocation at) {
            if (raw.startsWith("<%=") && raw.endsWith("%>")) {
                if (!attribute.runtimeExpression()) throw error(at, "Attribute does not accept runtime expressions: " + attribute.name());
                return value(raw, at, false);
            }
            var normalized = new PageNormalizer(sourceSyntaxes.getOrDefault(at.source(), Page.Syntax.STANDARD),
                    new ParserOptions(bool("isELIgnored", false), bool("deferredSyntaxAllowedAsLiteral", false)), Map.of())
                    .normalizeStructured(List.of(new PageNode.Text(raw, at)));
            boolean dynamic = normalized.stream().anyMatch(node -> node instanceof PageNode.Expression);
            if (dynamic) {
                if (!attribute.runtimeExpression()) throw error(at, "Attribute does not accept runtime expressions: " + attribute.name());
                needsFactory = true;
                return "_jspxExpressionFactory.coerceToType(" + value(raw, at, false) + ", " + attribute.type().replace('$', '.') + ".class)";
            }
            String literal = normalized.stream().map(node -> ((PageNode.Text) node).value()).collect(java.util.stream.Collectors.joining());
            return literalValue(literal, attribute, at);
        }
        private record DeferredInput(String code, String literal, boolean hash, boolean dollar,
                Map<String, BeanClasses.Function> functions) { }
        private DeferredInput attributeInput(String raw, SourceLocation at) {
            if (raw.startsWith("<%=") && raw.endsWith("%>"))
                return new DeferredInput(raw, null, false, true, Map.of());
            var pieces = new PageNormalizer(sourceSyntaxes.getOrDefault(at.source(), Page.Syntax.STANDARD),
                    new ParserOptions(bool("isELIgnored", false), bool("deferredSyntaxAllowedAsLiteral", false)), Map.of())
                    .normalizeStructured(List.of(new PageNode.Text(raw, at)));
            return deferredInput(pieces, at);
        }
        private DeferredInput deferredInput(List<PageNode> pieces, SourceLocation at) {
            var code = new StringBuilder(); var literal = new StringBuilder();
            var functions = new TreeMap<String, BeanClasses.Function>(); boolean[] kinds = new boolean[2];
            deferredParts(pieces, at, code, literal, functions, kinds);
            return new DeferredInput(code.toString(), literal.toString(), kinds[0], kinds[1], Map.copyOf(functions));
        }
        private void deferredParts(List<PageNode> pieces, SourceLocation at, StringBuilder code, StringBuilder literal,
                Map<String, BeanClasses.Function> functions, boolean[] kinds) {
            for (var piece : pieces) {
                if (piece instanceof PageNode.Text text) {
                    literal.append(text.value());
                    code.append(text.value().replace("\\", "\\\\").replace("${", "\\${").replace("#{", "\\#{"));
                } else if (piece instanceof PageNode.Expression expression) {
                    kinds[expression.deferred() ? 0 : 1] = true;
                    String source = (expression.deferred() ? "#{" : "${") + expression.code() + "}";
                    code.append(source);
                    for (var entry : functionBindings(source, expression.location()).entrySet()) {
                        var previous = functions.putIfAbsent(entry.getKey(), entry.getValue());
                        if (previous != null && !previous.equals(entry.getValue()))
                            throw error(expression.location(), "Deferred composite cannot rebind one function name");
                    }
                } else if (action(piece, "text")) {
                    var wrapper = (PageNode.Element) piece; var previous = enterNamespaces(wrapper);
                    try { deferredParts(wrapper.children(), at, code, literal, functions, kinds); }
                    finally { namespaces.clear(); namespaces.putAll(previous); }
                } else throw error(at, "Deferred named attributes require text and EL, without executable body capture");
            }
        }
        private String deferredAttribute(DeferredInput input, TagAttribute attribute, SourceLocation at) {
            if (input.hash() && input.dollar()) throw error(at, "Attribute cannot mix immediate and deferred expressions");
            if (!input.hash() && attribute.runtimeExpression()) {
                var dynamic = new TagAttribute(attribute.name(), attribute.type(), attribute.required(), true, false);
                if (!input.dollar()) return literalValue(input.literal(), dynamic, at);
                if (input.code().startsWith("<%=")) return tagValue(input.code(), dynamic, at);
                if (startupElAllowed) throw error(at, "Mixed deferred attributes require build-time compilation");
                String expected = classes.expressionType(attribute.type(), at);
                int index = registerExpression(input.code(), at, new ExpressionSpec(expected, null, true, false), input.functions());
                String context = input.functions().isEmpty() ? "pageContext.getELContext()"
                        : "_jspxFunctionContext" + index + "(pageContext.getELContext())";
                return "new " + className + "$Expression" + index + "(" + context + ", " + expected + ".class).getValue(pageContext.getELContext())";
            }
            if (input.dollar()) throw error(at, "Deferred attribute requires #{} syntax: " + attribute.name());
            if (startupElAllowed) throw error(at, "Deferred attributes require build-time compilation");
            String expected; DeferredMethodSignature method = null;
            if (attribute.deferredMethod() != null) {
                var signature = attribute.deferredMethod();
                expected = classes.expressionType(signature.returnType(), at);
                method = new DeferredMethodSignature(expected, signature.parameters().stream().map(type -> classes.expressionType(type, at)).toList());
                if (!input.hash()) validateMethodLiteral(input.literal(), expected, at);
            } else expected = classes.expressionType(attribute.deferredValueType(), at);
            var spec = new ExpressionSpec(expected, method, true, !input.hash());
            int index = registerExpression(input.hash() ? input.code() : input.literal(), at, spec, input.hash() ? input.functions() : Map.of());
            String context = spec.literal() ? "" : expressionFunctions.get(index).isEmpty() ? "pageContext.getELContext(), "
                    : "_jspxFunctionContext" + index + "(pageContext.getELContext()), ";
            String construction = "new " + className + "$Expression" + index + "(" + context + expected + ".class"
                    + (method == null ? "" : ", new Class<?>[]{" + method.parameters().stream().map(type -> type + ".class").collect(java.util.stream.Collectors.joining(",")) + "}") + ")";
            return method == null ? construction : "new _jspxScopedMethodExpression(" + className + ".class, " + construction + ")";
        }
        private void methodScope(JavaSource source) {
            source.add("private static final class _jspxScopedMethodExpression extends jakarta.el.MethodExpression {\n"
                    + "private static final long serialVersionUID=1L; private final Class<?> owner; private final jakarta.el.MethodExpression delegate;\n"
                    + "_jspxScopedMethodExpression(Class<?> owner, jakarta.el.MethodExpression delegate) { this.owner=owner; this.delegate=delegate; }\n"
                    + "private <T> T scoped(java.util.function.Supplier<T> operation) { var thread=Thread.currentThread(); var previous=thread.getContextClassLoader();\n"
                    + "try { var loader=owner.getClassLoader(); if(loader!=null) thread.setContextClassLoader(loader); return operation.get(); } finally { thread.setContextClassLoader(previous); } }\n"
                    + "public Object invoke(jakarta.el.ELContext context,Object[] parameters) { return scoped(()->delegate.invoke(context,parameters)); }\n"
                    + "public jakarta.el.MethodInfo getMethodInfo(jakarta.el.ELContext context) { return scoped(()->delegate.getMethodInfo(context)); }\n"
                    + "public jakarta.el.MethodReference getMethodReference(jakarta.el.ELContext context) { return scoped(()->delegate.getMethodReference(context)); }\n"
                    + "public String getExpressionString() { return delegate.getExpressionString(); }\n"
                    + "public boolean isLiteralText() { return delegate.isLiteralText(); }\n"
                    + "public boolean isParametersProvided() { return delegate.isParametersProvided(); }\n"
                    + "public boolean equals(Object other) { return other instanceof _jspxScopedMethodExpression expression && owner==expression.owner && delegate.equals(expression.delegate); }\n"
                    + "public int hashCode() { return java.util.Objects.hash(owner,delegate); }\n}\n");
        }
        private void validateMethodLiteral(String literal, String type, SourceLocation at) {
            if (type.equals("void")) throw error(at, "Literal deferred method cannot return void");
            if (!Set.of("byte", "java.lang.Byte", "short", "java.lang.Short", "int", "java.lang.Integer", "long", "java.lang.Long",
                    "float", "java.lang.Float", "double", "java.lang.Double", "java.math.BigInteger", "java.math.BigDecimal",
                    "boolean", "java.lang.Boolean", "char", "java.lang.Character", "java.lang.String", "java.lang.Object").contains(type))
                throw error(at, "Literal deferred method return conversion is unsupported: " + type);
            try {
                if (!literal.isEmpty()) switch (type) {
                    case "byte", "java.lang.Byte" -> Byte.valueOf(literal);
                    case "short", "java.lang.Short" -> Short.valueOf(literal);
                    case "int", "java.lang.Integer" -> Integer.valueOf(literal);
                    case "long", "java.lang.Long" -> Long.valueOf(literal);
                    case "float", "java.lang.Float" -> Float.valueOf(literal);
                    case "double", "java.lang.Double" -> Double.valueOf(literal);
                    case "java.math.BigInteger" -> new java.math.BigInteger(literal);
                    case "java.math.BigDecimal" -> new java.math.BigDecimal(literal);
                    case "boolean", "java.lang.Boolean", "char", "java.lang.Character", "java.lang.String", "java.lang.Object" -> { }
                    default -> throw error(at, "Literal deferred method return conversion is unsupported: " + type);
                }
            } catch (NumberFormatException invalid) { throw error(at, "Invalid literal for deferred method return type " + type); }
        }
        private String literalValue(String literal, TagAttribute attribute, SourceLocation at) {
            String type = attribute.type();
            try {
                if (!literal.isEmpty()) switch (type) {
                    case "byte", "java.lang.Byte" -> Byte.valueOf(literal);
                    case "short", "java.lang.Short" -> Short.valueOf(literal);
                    case "int", "java.lang.Integer" -> Integer.valueOf(literal);
                    case "long", "java.lang.Long" -> Long.valueOf(literal);
                    case "float", "java.lang.Float" -> Float.valueOf(literal);
                    case "double", "java.lang.Double" -> Double.valueOf(literal);
                }
            } catch (IllegalArgumentException invalid) { throw error(at, "Invalid literal for " + type + " attribute " + attribute.name()); }
            return stringConversion(JavaSource.quote(literal), type, at);
        }
        private String stringConversion(String expression, String type, SourceLocation at) {
            String boxed = switch (type) {
                case "byte" -> "java.lang.Byte"; case "short" -> "java.lang.Short"; case "int" -> "java.lang.Integer";
                case "long" -> "java.lang.Long"; case "float" -> "java.lang.Float"; case "double" -> "java.lang.Double"; case "boolean" -> "java.lang.Boolean";
                default -> type;
            };
            if (type.equals("java.lang.String") || type.equals("java.lang.Object")) return expression;
            if (type.equals("char") || type.equals("java.lang.Character")) return "(" + expression + ".isEmpty() ? '\\0' : " + expression + ".charAt(0))";
            if (!Set.of("java.lang.Byte","java.lang.Short","java.lang.Integer","java.lang.Long","java.lang.Float","java.lang.Double","java.lang.Boolean").contains(boxed))
                throw error(at, "Literal coercion is not supported for tag attribute type " + type);
            return boxed + ".valueOf(" + (boxed.equals("java.lang.Boolean") ? expression : expression + ".isEmpty() ? \"0\" : " + expression) + ")";
        }
        private boolean namespaceAttribute(String name) { return name.equals("xmlns") || name.startsWith("xmlns:"); }

        private void dynamicElement(PageNode.Element node) {
            allowed(node, Set.of("name"));
            String variable = "_jspxElement" + serial++;
            body.add("String " + variable + " = _jspxXmlName(_jspxText(" + value(required(node, "name"), node.location(), false) + "));\n", node.location());
            body.add("out.write(\"<\" + " + variable + ");\n", node.location());
            var content = new ArrayList<PageNode>();
            var names = new HashSet<String>();
            boolean hasBody = false;
            for (var child : node.children()) {
                if (child instanceof PageNode.Element attribute && attribute.namespace().equals(PageParser.JSP_NAMESPACE)
                        && attribute.name().substring(attribute.name().lastIndexOf(':') + 1).equals("attribute")) {
                    if (hasBody) throw error(attribute.location(), "jsp:attribute must precede the element body");
                    allowed(attribute, Set.of("name", "trim"));
                    String name = required(attribute, "name");
                    if (!name.matches("[\\p{L}_][\\p{L}\\p{N}\\p{M}_.:-]*") || !names.add(name))
                        throw error(attribute.location(), "Invalid or duplicate element attribute: " + name);
                    String captured = "_jspxAttribute" + serial++;
                    String writer = "_jspxBody" + serial++;
                    body.add("String " + captured + ";\nvar " + writer + " = pageContext.pushBody();\ntry { out = " + writer + ";\n", attribute.location());
                    var children = new ArrayList<>(attribute.children());
                    if (boolValue(attribute.attributes().getOrDefault("trim", "true"), attribute.location()) && !children.isEmpty()) {
                        if (children.getFirst() instanceof PageNode.Text text) {
                            int start = 0;
                            while (start < text.value().length() && xmlSpace(text.value().charAt(start))) start++;
                            children.set(0, new PageNode.Text(text.value().substring(start), text.location().advance(text.value().substring(0, start))));
                        }
                        if (children.getLast() instanceof PageNode.Text text) {
                            int end = text.value().length();
                            while (end > 0 && xmlSpace(text.value().charAt(end - 1))) end--;
                            children.set(children.size() - 1, new PageNode.Text(text.value().substring(0, end), text.location()));
                        }
                    }
                    emit(children);
                    body.add(captured + " = " + writer + ".getString();\n} finally { out = pageContext.popBody(); }\n", attribute.location());
                    body.add("out.write(" + JavaSource.quote(" " + name + "=\"") + " + _jspxAttribute(" + captured + ") + \"\\\"\");\n", attribute.location());
                } else if (child instanceof PageNode.Text text && !hasBody && text.value().isBlank()) {
                    // Formatting between named attributes is not element body content.
                } else {
                    hasBody = true;
                    if (child instanceof PageNode.Element wrapper && wrapper.namespace().equals(PageParser.JSP_NAMESPACE)
                            && wrapper.name().substring(wrapper.name().lastIndexOf(':') + 1).equals("body")) {
                        allowed(wrapper, Set.of()); content.addAll(wrapper.children());
                    } else content.add(child);
                }
            }
            if (content.isEmpty()) body.add("out.write(\"/>\");\n", node.location());
            else {
                write(">", node.location()); emit(content);
                body.add("out.write(\"</\" + " + variable + " + \">\");\n", node.location());
            }
        }

        private void dispatch(PageNode.Element node, boolean forward) {
            allowed(node, forward ? Set.of("page") : Set.of("page", "flush"));
            String variable = "_jspxPath" + serial++;
            body.add("String " + variable + " = _jspxText(" + value(required(node, "page"), node.location(), false) + ");\n", node.location());
            for (var child : node.children()) {
                if (child instanceof PageNode.Text text && text.value().isBlank()) continue;
                if (!(child instanceof PageNode.Element param) || !param.namespace().equals(PageParser.JSP_NAMESPACE)
                        || !param.name().endsWith(":param")) throw error(child.location(), "Dispatch body accepts only jsp:param");
                allowed(param, Set.of("name", "value"));
                String name = required(param, "name");
                if (name.contains("${") || name.contains("<%=")) throw error(param.location(), "Parameter name must be literal");
                body.add(variable + " = _jspxParameter(" + variable + ", " + JavaSource.quote(name) + ", "
                        + value(required(param, "value"), param.location(), false) + ");\n", param.location());
            }
            if (forward) body.add("pageContext.forward(" + variable + "); _jspxForwarded[0] = true; if (true) "
                    + (fragmentDepth > 0 ? "throw new jakarta.servlet.jsp.SkipPageException();" : "return;") + "\n", node.location());
            else body.add("pageContext.include(" + variable + ", " + boolValue(node.attributes().getOrDefault("flush", "false"), node.location()) + ");\n"
                    + "if (pageContext instanceof io.vidocq.ibarra.spi.PageDispatchContext _jspxDispatch && _jspxDispatch.isForwarded()) { _jspxForwarded[0] = true; "
                    + (fragmentDepth > 0 ? "throw new jakarta.servlet.jsp.SkipPageException();" : "return;") + " }\n", node.location());
        }

        private void bean(PageNode.Element node) {
            allowed(node, Set.of("id", "class", "type", "scope"));
            String id = required(node, "id");
            if (!SourceVersion.isIdentifier(id) || SourceVersion.isKeyword(id)) throw error(node.location(), "Invalid bean id");
            String implementation = node.attributes().get("class");
            String type = node.attributes().getOrDefault("type", implementation);
            if (type == null || !SourceVersion.isName(type) || (implementation != null && !SourceVersion.isName(implementation)))
                throw error(node.location(), "Bean requires a Java type");
            if (beans.putIfAbsent(id, type) != null) throw error(node.location(), "Duplicate bean id: " + id);
            int scope = scope(node.attributes().getOrDefault("scope", "page"), node.location());
            if (implementation != null) classes.constructor(implementation, node.location());
            if (scope == 3 || scope == 4) {
                String suffix = Integer.toString(serial++);
                String lock = "_jspxBeanLock" + suffix;
                String created = "_jspxBeanCreated" + suffix;
                body.add(type + " " + id + ";\n"
                        + (implementation == null ? "" : "boolean " + created + " = false;\n")
                        + "java.util.concurrent.locks.Lock " + lock + " = ((io.vidocq.ibarra.spi.BeanScopeContext) pageContext).beanScopeLock(" + scope + ");\n"
                        + lock + ".lock();\ntry {\n"
                        + id + " = (" + type + ") pageContext.getAttribute(" + JavaSource.quote(id) + ", " + scope + ");\n"
                        + "if (" + id + " == null) {\n", node.location());
                if (implementation == null) body.add("throw new java.lang.InstantiationException(\"Required bean is absent: " + id + "\");\n", node.location());
                else body.add(id + " = new " + implementation + "();\npageContext.setAttribute(" + JavaSource.quote(id) + ", " + id + ", " + scope + ");\n"
                        + created + " = true;\n", node.location());
                body.add("}\n} finally { " + lock + ".unlock(); }\n");
                if (implementation != null) { body.add("if (" + created + ") {\n"); emit(node.children()); body.add("}\n"); }
                return;
            }
            body.add(type + " " + id + " = (" + type + ") pageContext.getAttribute(" + JavaSource.quote(id) + ", " + scope + ");\nif (" + id + " == null) {\n", node.location());
            if (implementation == null) body.add("throw new java.lang.InstantiationException(\"Required bean is absent: " + id + "\");\n", node.location());
            else {
                body.add(id + " = new " + implementation + "();\npageContext.setAttribute(" + JavaSource.quote(id) + ", " + id + ", " + scope + ");\n", node.location());
                emit(node.children());
            }
            body.add("}\n");
        }
        private String beanType(PageNode.Element node) {
            String name = required(node, "name");
            var type = beans.get(name);
            if (type == null) throw error(node.location(), "Bean must be declared before property access: " + name);
            return type;
        }
        private void getProperty(PageNode.Element node) {
            allowed(node, Set.of("name", "property"));
            String type = beanType(node);
            var accessor = classes.getter(type, required(node, "property"), node.location());
            body.add("out.print(((" + type + ") _jspxBean(pageContext, " + JavaSource.quote(node.attributes().get("name")) + "))." + accessor.name() + "());\n", node.location());
        }
        private void setProperty(PageNode.Element node) {
            allowed(node, Set.of("name", "property", "param", "value"));
            String property = required(node, "property");
            if (property.equals("*")) {
                if (node.attributes().containsKey("param") || node.attributes().containsKey("value"))
                    throw error(node.location(), "Wildcard setProperty accepts neither param nor value");
                for (String name : classes.writableProperties(beanType(node), node.location())) {
                    var attributes = new LinkedHashMap<>(node.attributes());
                    attributes.put("property", name);
                    setProperty(new PageNode.Element(node.name(), node.namespace(), attributes, List.of(), true, node.location()));
                }
                return;
            }
            var setter = classes.setter(beanType(node), property, node.location());
            String parameter = node.attributes().getOrDefault("param", property);
            String supplied = node.attributes().containsKey("value") ? value(node.attributes().get("value"), node.location(), false)
                    : "request." + (setter.type().endsWith("[]") ? "getParameterValues" : "getParameter")
                            + "(" + JavaSource.quote(parameter) + ")";
            if (node.attributes().containsKey("param") && node.attributes().containsKey("value"))
                throw error(node.location(), "setProperty cannot combine value and param");
            String temporary = "_jspxValue" + serial++;
            body.add("Object " + temporary + " = " + supplied + ";\n", node.location());
            if (!node.attributes().containsKey("value")) body.add("if (" + temporary + " != null && !\"\".equals(" + temporary + ")) {\n");
            String raw = node.attributes().get("value");
            boolean scriptlet = raw != null && raw.startsWith("<%=") && raw.endsWith("%>");
            boolean el = raw != null && !scriptlet && new PageNormalizer(sourceSyntaxes.getOrDefault(node.location().source(), Page.Syntax.STANDARD),
                    new ParserOptions(bool("isELIgnored", false), bool("deferredSyntaxAllowedAsLiteral", false)), Map.of())
                    .normalizeStructured(List.of(new PageNode.Text(raw, node.location()))).stream().anyMatch(PageNode.Expression.class::isInstance);
            String editor = scriptlet || el ? null : classes.propertyEditor(beanType(node), property, node.location());
            String converted;
            if (editor != null) {
                if (setter.type().endsWith("[]")) throw error(node.location(), "Array property editors are not supported at build time");
                converted = "_jspxConverted" + serial++;
                String local = "_jspxEditor" + serial++;
                body.add("Object " + converted + ";\ntry {\njava.beans.PropertyEditor " + local + " = new " + editor + "();\n"
                        + local + ".setAsText((String) " + temporary + ");\n" + converted + " = " + local + ".getValue();\n"
                        + "} catch (Exception _jspxConversionFailure) {\nif (!\"\".equals(" + temporary + ")) throw _jspxConversionFailure;\n"
                        + converted + " = null;\n}\n", node.location());
                converted = "(" + setter.type() + ") " + converted;
            } else if (scriptlet) converted = "(" + setter.type() + ") " + temporary;
            else {
                needsFactory = true;
                converted = "_jspxExpressionFactory.coerceToType(" + temporary + ", " + setter.type() + ".class)";
            }
            body.add("((" + beanType(node) + ") _jspxBean(pageContext, " + JavaSource.quote(node.attributes().get("name")) + "))."
                    + setter.name() + "(" + converted + ");\n", node.location());
            if (!node.attributes().containsKey("value")) body.add("}\n");
        }

        private String expression(String code, SourceLocation at) {
            int index = registerExpression(code, at, new ExpressionSpec("java.lang.Object", null, false, false), null);
            return "_jspxExpression" + index + ".getValue(pageContext.getELContext())";
        }
        private int registerExpression(String code, SourceLocation at, ExpressionSpec spec, Map<String, BeanClasses.Function> supplied) {
            var functions = supplied == null ? functionBindings(code, at) : supplied;
            int index = expressions.size(); expressions.add(code); expressionLocations.add(at); expressionFunctions.add(Map.copyOf(functions)); expressionSpecs.add(spec);
            return index;
        }
        private Map<String, BeanClasses.Function> functionBindings(String code, SourceLocation at) {
            var functions = new TreeMap<String, BeanClasses.Function>();
            List<io.vidocq.peano.compiler.FunctionReference> references;
            try { references = io.vidocq.peano.compiler.ExpressionCompiler.functions(code); }
            catch (jakarta.el.ELException invalid) { throw error(at, "Cannot compile EL: " + invalid.getMessage()); }
            for (var reference : references) {
                if (startupElAllowed) throw error(at, "TLD functions require build-time compilation, not interpreter preview");
                var namespace = namespaces.get(reference.prefix()); var directive = taglibBindings.get(reference.prefix());
                String uri = namespace != null ? namespace.uri() : directive == null ? null : directive.attributes().get("uri");
                SourceLocation origin = namespace != null ? namespace.location() : directive == null ? at : directive.location();
                if (uri == null) throw error(at, "Unknown function prefix: " + reference.prefix());
                var library = resolvedLibraries.resolve(uri, origin, namespace == null && directive != null);
                if (library == null) throw error(at, "No build-time function library for " + uri);
                var declaration = library.functions().get(reference.name());
                if (declaration == null) throw error(at, "Unknown TLD function: " + reference.prefix() + ":" + reference.name());
                var function = classes.function(declaration, at);
                if (reference.argumentCount() != function.parameters().size()) throw error(at, "Function argument count does not match " + declaration.signature());
                functions.put(reference.prefix() + ":" + reference.name(), function);
            }
            return Map.copyOf(functions);
        }
        private void functionDefinitions(JavaSource source, int index, Map<String, BeanClasses.Function> functions) {
            var names = new TreeMap<String, String>(); int ordinal = 0;
            for (var entry : new TreeMap<>(functions).entrySet()) {
                var function = entry.getValue(); String name = "_jspxFunction" + index + "_" + ordinal++; names.put(entry.getKey(), name);
                var arguments = new ArrayList<String>();
                for (int parameter = 0; parameter < function.parameters().size(); parameter++) {
                    String type = function.parameters().get(parameter);
                    arguments.add("(" + type + ") context.convertToType(arguments[" + parameter + "], " + type + ".class)");
                }
                source.add("private static final class " + name + " implements io.vidocq.peano.spi.CompiledFunctionInvoker {\n"
                        + "private static final long serialVersionUID=1L;\npublic Object invoke(jakarta.el.ELContext context, Object[] arguments) throws Throwable {\n"
                        + "if (arguments.length != " + function.parameters().size() + ") throw new jakarta.el.ELException(\"Invalid function argument count\");\n"
                        + (function.returnType().equals("void") ? "" : "return ") + function.owner() + "." + function.method() + "(" + String.join(", ", arguments) + ");\n"
                        + (function.returnType().equals("void") ? "return null;\n" : "") + "}\n}\n");
            }
            source.add("private static jakarta.el.ELContext _jspxFunctionContext" + index + "() { return _jspxFunctionContext" + index + "(null); }\n"
                    + "private static jakarta.el.ELContext _jspxFunctionContext" + index + "(jakarta.el.ELContext delegate) { return new jakarta.el.ELContext() {\n"
                    + "public jakarta.el.ELResolver getELResolver() { return delegate == null ? new jakarta.el.CompositeELResolver() : delegate.getELResolver(); }\n"
                    + "public jakarta.el.VariableMapper getVariableMapper() { return delegate == null ? null : delegate.getVariableMapper(); }\n"
                    + "public jakarta.el.FunctionMapper getFunctionMapper() { return new io.vidocq.peano.spi.CompiledFunctionMapper() {\n"
                    + "public io.vidocq.peano.spi.FunctionDescriptor resolveCompiledFunction(String prefix, String name) { return switch(prefix + \":\" + name) {\n");
            for (var entry : new TreeMap<>(functions).entrySet()) {
                var function = entry.getValue();
                source.add("case " + JavaSource.quote(entry.getKey()) + " -> new io.vidocq.peano.spi.FunctionDescriptor(" + function.owner() + ".class, "
                        + "new io.vidocq.peano.spi.MethodDescriptor(" + JavaSource.quote(function.method()) + ", " + function.returnType() + ".class, new Class<?>[]{"
                        + function.parameters().stream().map(type -> type + ".class").collect(java.util.stream.Collectors.joining(","))
                        + "}, false, true, new java.lang.annotation.Annotation[0]), new " + names.get(entry.getKey()) + "());\n");
            }
            source.add("default -> null; }; }\n}; }\n}; }\n");
        }
        private String value(String raw, SourceLocation at, boolean xmlLiteral) {
            if (raw.startsWith("<%=") && raw.endsWith("%>")) {
                if (bool("scriptingInvalid", false)) throw error(at, "Scripting attributes are disabled");
                return "(" + raw.substring(3, raw.length() - 2) + ")";
            }
            var temporary = new PageNode.Text(raw, at);
            var parser = new PageNormalizer(sourceSyntaxes.getOrDefault(at.source(), Page.Syntax.STANDARD), new ParserOptions(bool("isELIgnored", false),
                    bool("deferredSyntaxAllowedAsLiteral", false)), Map.of());
            var pieces = parser.normalizeStructured(List.of(temporary));
            if (pieces.size() == 1 && pieces.getFirst() instanceof PageNode.Expression el) {
                if (el.deferred()) throw error(at, "Deferred action attributes require tag-handler support");
                return expression("${" + el.code() + "}", at);
            }
            var parts = new ArrayList<String>();
            for (var piece : pieces) {
                if (piece instanceof PageNode.Text text) parts.add(JavaSource.quote(xmlLiteral ? escapeAttribute(text.value()) : text.value()));
                else if (piece instanceof PageNode.Expression el) {
                    if (el.deferred()) throw error(at, "Deferred action attributes require tag-handler support");
                    parts.add("_jspxText(" + expression("${" + el.code() + "}", at) + ")");
                }
            }
            return parts.isEmpty() ? "\"\"" : String.join(" + ", parts);
        }

        private void write(String value, SourceLocation at) {
            for (int start = 0; start < value.length();) {
                int end = Math.min(value.length(), start + 8192);
                if (end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))) end--;
                body.add("out.write(" + JavaSource.quote(value.substring(start, end)) + ");\n", at);
                start = end;
            }
        }
        private SourceLocation settingLocation(String name) { return settingLocations.getOrDefault(name, new SourceLocation(page.source(), 1, 1)); }
        private boolean bool(String name, boolean fallback) { return boolValue(settings.getOrDefault(name, String.valueOf(fallback)), settingLocation(name)); }
        private boolean boolValue(String value, SourceLocation at) {
            if (!value.equals("true") && !value.equals("false")) throw error(at, "Expected true or false: " + value);
            return Boolean.parseBoolean(value);
        }
        private String contentType() {
            String contentType = settings.getOrDefault("contentType", page.syntax() == Page.Syntax.XML ? "text/xml" : "text/html");
            if (SourceDecoder.contentCharset(contentType) == null) {
                String encoding = page.syntax() == Page.Syntax.XML ? "UTF-8"
                        : responseEncoding != null ? responseEncoding : settings.get("pageEncoding");
                if (encoding != null) contentType += ";charset=" + Charset.forName(encoding).name();
            }
            return contentType;
        }
        private int buffer() {
            String value = settings.getOrDefault("buffer", "8kb");
            if (value.equals("none")) {
                if (!bool("autoFlush", true)) throw error(settingLocation("buffer"), "Unbuffered pages require autoFlush");
                return 0;
            }
            return TranslationUnit.bufferCapacity(value, settingLocation("buffer"));
        }
        private int scope(String scope, SourceLocation at) {
            if (scope.equals("session") && !bool("session", true)) throw error(at, "Session-scoped beans require session participation");
            return switch (scope) { case "page" -> 1; case "request" -> 2; case "session" -> 3; case "application" -> 4;
                default -> throw error(at, "Invalid bean scope: " + scope); };
        }
        private String required(PageNode.Element node, String name) {
            var value = node.attributes().get(name);
            if (value == null) throw error(node.location(), node.name() + " requires " + name);
            return value;
        }
        private void allowed(PageNode.Element node, Set<String> attributes) {
            for (String name : node.attributes().keySet())
                if (!namespaceAttribute(name) && !attributes.contains(name)) throw error(node.location(), "Unsupported action attribute: " + name);
        }
        private String escapeAttribute(String text) { return text.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;"); }
        private boolean xmlSpace(char value) { return value == ' ' || value == '\t' || value == '\r' || value == '\n'; }
        private PageParseException error(SourceLocation location, String message) { return new PageParseException(location, message); }
    }
}

