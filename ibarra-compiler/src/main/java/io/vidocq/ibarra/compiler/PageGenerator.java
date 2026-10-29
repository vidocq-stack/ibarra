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
    private static final Set<String> JSP_ROOT_VERSIONS = Set.of("1.2", "2.0", "2.1", "2.2", "2.3", "3.0", "3.1", "4.0");
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
        private static final String LANGUAGE = "language";
        private static final String EXTENDS = "extends";
        private static final String SESSION = "session";
        private static final String BUFFER = "buffer";
        private static final String AUTO_FLUSH = "autoFlush";
        private static final String CONTENT_TYPE = "contentType";
        private static final String PAGE_ENCODING = "pageEncoding";
        private static final String ERROR_PAGE = "errorPage";
        private static final String IS_ERROR_PAGE = "isErrorPage";
        private static final String IS_EL_IGNORED = "isELIgnored";
        private static final String DEFERRED_SYNTAX_ALLOWED = "deferredSyntaxAllowedAsLiteral";
        private static final String TRIM_DIRECTIVE_WHITESPACES = "trimDirectiveWhitespaces";
        private static final String ERROR_ON_EL_NOT_FOUND = "errorOnELNotFound";
        private static final String EXPRESSION_SUFFIX = "$Expression";
        private static final String NEW = " = new ";
        private static final String FUNCTION_CONTEXT = "_jspxFunctionContext";
        private static final String OUT_WRITE = "out.write(";
        private static final String TAGLIB = "taglib";
        private static final String TRUE = "true";
        private static final String FALSE = "false";
        private static final String OBJECT = "Object ";
        private static final String NO_ARGUMENTS = "();\n";
        private static final String GET_ATTRIBUTE = ") pageContext.getAttribute(";
        private static final String REMOVE_ATTRIBUTE = "pageContext.removeAttribute(";
        private static final String PAGE_SCOPE_ARGUMENT = ", jakarta.servlet.jsp.PageContext.PAGE_SCOPE);\n";
        private static final String TAG_DATA_REQUEST_TIME_VALUE = "jakarta.servlet.jsp.tagext.TagData.REQUEST_TIME_VALUE";
        private static final String ATTRIBUTE_ACTION = "attribute";
        private static final String BODY_ACTION = "body";
        private static final String TEXT_ACTION = "text";
        private static final String JAVA_STRING = "java.lang.String";
        private static final String CLASS_SUFFIX = ".class";
        private static final String JAVA_OBJECT = "java.lang.Object";
        private static final String BYTE = "byte";
        private static final String SHORT = "short";
        private static final String INT = "int";
        private static final String LONG = "long";
        private static final String FLOAT = "float";
        private static final String DOUBLE = "double";
        private static final String BOOLEAN = "boolean";
        private static final String JAVA_BYTE = "java.lang.Byte";
        private static final String JAVA_SHORT = "java.lang.Short";
        private static final String JAVA_INTEGER = "java.lang.Integer";
        private static final String JAVA_LONG = "java.lang.Long";
        private static final String JAVA_FLOAT = "java.lang.Float";
        private static final String JAVA_DOUBLE = "java.lang.Double";
        private static final String JAVA_BOOLEAN = "java.lang.Boolean";
        private static final String JAVA_CHARACTER = "java.lang.Character";
        private static final Set<String> CONVERTIBLE_BOXED_TYPES = Set.of(JAVA_BYTE, JAVA_SHORT, JAVA_INTEGER, JAVA_LONG,
                JAVA_FLOAT, JAVA_DOUBLE, JAVA_BOOLEAN);
        private static final Set<String> LITERAL_METHOD_RETURN_TYPES = Set.of(BYTE, JAVA_BYTE, SHORT, JAVA_SHORT, INT, JAVA_INTEGER,
                LONG, JAVA_LONG, FLOAT, JAVA_FLOAT, DOUBLE, JAVA_DOUBLE, "java.math.BigInteger", "java.math.BigDecimal",
                BOOLEAN, JAVA_BOOLEAN, "char", JAVA_CHARACTER, JAVA_STRING, JAVA_OBJECT);
        private static final TagAttribute DYNAMIC_ATTRIBUTE = new TagAttribute("dynamic", JAVA_OBJECT, false, false, false, JAVA_OBJECT, null);
        private static final String STRING_DECLARATION = "String ";
        private static final String CLOSE_CALL = "));\n";
        private static final String VALUE = "value";
        private static final String PARAM = "param";
        private static final String PROPERTY = "property";
        private static final String SET_DYNAMIC_ATTRIBUTE = ".setDynamicAttribute(null, ";
        private static final Set<String> BOOLEAN_PAGE_ATTRIBUTES = Set.of(SESSION, AUTO_FLUSH, IS_ERROR_PAGE, IS_EL_IGNORED,
                DEFERRED_SYNTAX_ALLOWED, TRIM_DIRECTIVE_WHITESPACES, ERROR_ON_EL_NOT_FOUND);
        private static final Set<String> RESERVED_VARIABLE_NAMES = Set.of(TRUE, FALSE, "null", "_", "request", "response",
                "pageContext", "out", "application", "config", "page", SESSION, "exception");
        private static final Set<String> PAGE_ATTRIBUTES = Set.of(LANGUAGE, "import", EXTENDS, SESSION,
                BUFFER, AUTO_FLUSH, CONTENT_TYPE, PAGE_ENCODING, ERROR_PAGE, IS_ERROR_PAGE, "info",
                IS_EL_IGNORED, DEFERRED_SYNTAX_ALLOWED, TRIM_DIRECTIVE_WHITESPACES, ERROR_ON_EL_NOT_FOUND);
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
    private record ScriptingVariable(String name, String contextName, String type, boolean declare, int scope,
            boolean synchronize) { }
        private final Map<String, ScriptingVariable> pageVariables = new LinkedHashMap<>();
        private final Map<String, String> beans = new LinkedHashMap<>();
        private boolean needsFactory;
        private int serial;
        private int jspIdSerial;
        private int tagValidationSerial;
        private final boolean startupElAllowed;
        private boolean needsBeanNameSupport;

        Generator(Page page, String className, Map<String, String> properties, Map<String, Page.Syntax> sourceSyntaxes, String responseEncoding, GenerationOptions options, TldReferences.Resolved resolved) {
            this.page = page; this.className = className;
            this.sourceSyntaxes = sourceSyntaxes;
            this.responseEncoding = responseEncoding;
            this.startupElAllowed = options.allowStartupEl();
            this.generationOptions = options;
            this.resolvedLibraries = resolved;
            copy(properties, "default-content-type", CONTENT_TYPE);
            copy(properties, BUFFER, BUFFER);
            copy(properties, "page-encoding", PAGE_ENCODING);
            copy(properties, "trim-directive-whitespaces", TRIM_DIRECTIVE_WHITESPACES);
            copy(properties, "scripting-invalid", "scriptingInvalid");
            copy(properties, "el-ignored", IS_EL_IGNORED);
            copy(properties, "deferred-syntax-allowed-as-literal", DEFERRED_SYNTAX_ALLOWED);
            copy(properties, "error-on-el-not-found", ERROR_ON_EL_NOT_FOUND);
            copy(properties, "error-on-undeclared-namespace", "errorOnUndeclaredNamespace");
        }
        private ParserOptions parserOptions() {
            return new ParserOptions(bool(IS_EL_IGNORED, false), bool(DEFERRED_SYNTAX_ALLOWED, false));
        }
        private void copy(Map<String, String> values, String from, String to) {
            if (values.containsKey(from)) settings.put(to, values.get(from));
        }

        GeneratedPage generate() {
            directives(page.nodes());
            boolean xmlDeclaration = XmlDeclaration.collect(page);
            var doctype = XmlDoctype.collect(page);
            requireJavaScripting();
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
            var compiled = expressionFields(result);
            result.add(declarations);
            if (expressionSpecs.stream().anyMatch(spec -> spec.method() != null)) methodScope(result);
            result.add(fragments);
            initializeMethod(result);
            serviceMethod(result, xmlDeclaration, doctype);
            helperMethods(result);
            lifecycleClass(result, simple);
            return new GeneratedPage(className, result.text(), result.locations(), compiled);
        }
        private void requireJavaScripting() {
            if (settings.containsKey(LANGUAGE) && !settings.get(LANGUAGE).equals("java"))
                throw error(page.nodes().isEmpty() ? new SourceLocation(page.source(), 1, 1) : page.nodes().getFirst().location(), "Only Java scripting is supported");
        }
        private List<GeneratedClass> expressionFields(JavaSource result) {
            var compiled = new ArrayList<GeneratedClass>();
            for (int i = 0; i < expressions.size(); i++) {
                if (startupElAllowed) result.add("private jakarta.el.ValueExpression _jspxExpression" + i + ";\n");
                else compiled.add(compiledExpressionField(result, i));
            }
            return compiled;
        }
        private GeneratedClass compiledExpressionField(JavaSource result, int i) {
            var spec = expressionSpecs.get(i);
            String name = className + EXPRESSION_SUFFIX + i;
            GeneratedClass expression;
            try {
                expression = new GeneratedClass(name, expressionBytecode(name, expressions.get(i), spec));
            } catch (jakarta.el.ELException invalid) {
                throw error(expressionLocations.get(i), "Cannot compile EL: " + invalid.getMessage());
            }
            var functions = expressionFunctions.get(i);
            if (!functions.isEmpty()) functionDefinitions(result, i, functions);
            if (!spec.deferred()) result.add("private static final jakarta.el.ValueExpression _jspxExpression" + i + NEW + name + "("
                    + (functions.isEmpty() ? "" : FUNCTION_CONTEXT + i + "(), ") + "Object.class);\n");
            return expression;
        }
        private static byte[] expressionBytecode(String name, String expression, ExpressionSpec spec) {
            if (spec.literal()) return LiteralExpressions.compile(name, expression, spec.method() != null);
            if (spec.method() == null) return io.vidocq.peano.compiler.ExpressionCompiler.compile(name, expression);
            return io.vidocq.peano.compiler.ExpressionCompiler.compileMethod(name, expression);
        }
        private void initializeMethod(JavaSource result) {
            result.add("@Override protected final void _jspxInitialize() {\n");
            if (startupElAllowed && !expressions.isEmpty()) {
                result.add("var factory = jakarta.servlet.jsp.JspFactory.getDefaultFactory().getJspApplicationContext(getServletContext()).getExpressionFactory();\n");
                result.add("var context = new jakarta.el.StandardELContext(factory);\n");
                for (int i = 0; i < expressions.size(); i++)
                    result.add("_jspxExpression" + i + " = factory.createValueExpression(context, " + JavaSource.quote(expressions.get(i)) + ", Object.class);\n");
            }
            result.add("}\n");
        }
        private void serviceMethod(JavaSource result, boolean xmlDeclaration, XmlDoctype doctype) {
            result.add("@Override public final void _jspService(jakarta.servlet.http.HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response) throws jakarta.servlet.ServletException, java.io.IOException {\n");
            result.add("response.setContentType(" + JavaSource.quote(contentType()) + ");\n");
            result.add("var _jspxFactory = jakarta.servlet.jsp.JspFactory.getDefaultFactory();\n");
            result.add("if (_jspxFactory == null) throw new jakarta.servlet.ServletException(\"No JSP factory installed\");\n");
            result.add("var pageContext = _jspxFactory.getPageContext(this, request, response, "
                    + (settings.containsKey(ERROR_PAGE) ? JavaSource.quote(settings.get(ERROR_PAGE)) : "null") + ", "
                    + bool(SESSION, true) + ", " + buffer() + ", " + bool(AUTO_FLUSH, true) + ");\n");
            result.add("if (pageContext == null) throw new jakarta.servlet.ServletException(\"Cannot initialize page context\");\n");
            result.add("var out = pageContext.getOut();\nvar application = getServletContext();\nvar config = getServletConfig();\nObject page = this;\n");
            if (bool(SESSION, true)) result.add("var session = pageContext.getSession();\n");
            if (bool(IS_ERROR_PAGE, false)) result.add("Throwable exception = pageContext.getErrorData().getThrowable();\n");
            result.add("boolean _jspxFailed = false, _jspxHandled = false; boolean[] _jspxForwarded = {false};\ntry {\n");
            result.add(serviceDeclarations);
            if (xmlDeclaration) result.add("out.write(\"<?xml version=\\\"1.0\\\" encoding=\\\"\" + response.getCharacterEncoding() + \"\\\"?>\");\n");
            if (!doctype.declaration().isEmpty()) result.add(OUT_WRITE + JavaSource.quote(doctype.declaration()) + ");\n", doctype.location());
            if (needsFactory) result.add("var _jspxExpressionFactory = _jspxFactory.getJspApplicationContext(application).getExpressionFactory();\n");
            result.add(body);
            result.add("} catch (Throwable failure) { if (!(failure instanceof jakarta.servlet.jsp.SkipPageException)) { _jspxFailed = true; pageContext.handlePageException(failure); _jspxHandled = true; } } finally {\n");
            result.add("""
                    try { if (!_jspxForwarded[0] && !(pageContext instanceof io.vidocq.ibarra.spi.PageDispatchContext _jspxDispatch && _jspxDispatch.isForwarded())\
                     && (!_jspxFailed || (_jspxHandled && out.getRemaining() < out.getBufferSize()))) out.flush(); } finally { _jspxFactory.releasePageContext(pageContext); }
                    }
                    }
                    """);
        }
        private void helperMethods(JavaSource result) {
            result.add("private static String _jspxText(Object value) { return value == null ? \"\" : String.valueOf(value); }\n");
            result.add("private static Object _jspxBean(jakarta.servlet.jsp.PageContext context, String name) throws jakarta.servlet.ServletException { Object bean = context.findAttribute(name); if (bean == null) throw new jakarta.servlet.ServletException(\"No bean named \" + name); return bean; }\n");
            result.add("private static String _jspxAttribute(String value) { return value.replace(\"&\", \"&amp;\").replace(\"\\\"\", \"&quot;\").replace(\"<\", \"&lt;\"); }\n");
            result.add("private static String _jspxXmlName(String value) { if (!value.matches(\"[\\\\p{L}_][\\\\p{L}\\\\p{N}\\\\p{M}_.:-]*\")) throw new IllegalArgumentException(\"Invalid XML name: \" + value); return value; }\n");
            result.add("private static String _jspxParameter(String path, String name, Object value) { return path + (path.contains(\"?\") ? \"&\" : \"?\") + java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8) + \"=\" + java.net.URLEncoder.encode(_jspxText(value), java.nio.charset.StandardCharsets.UTF_8); }\n");
            if (needsBeanNameSupport) result.add("private static Object _jspxInstantiateBean(ClassLoader loader, String name) throws java.io.IOException, jakarta.servlet.ServletException { try { return Class.forName(name, true, loader).getConstructor().newInstance(); } catch (ClassNotFoundException missingClass) { String resource = name.replace('.', '/') + \".ser\"; try (var stream = loader.getResourceAsStream(resource)) { if (stream == null) throw new jakarta.servlet.ServletException(missingClass); try (var input = new java.io.ObjectInputStream(stream) { @Override protected Class<?> resolveClass(java.io.ObjectStreamClass descriptor) throws java.io.IOException, ClassNotFoundException { try { return Class.forName(descriptor.getName(), false, loader); } catch (ClassNotFoundException missingType) { return super.resolveClass(descriptor); } } }) { return input.readObject(); } catch (ClassNotFoundException failure) { throw new jakarta.servlet.ServletException(failure); } } } catch (ReflectiveOperationException failure) { throw new jakarta.servlet.ServletException(failure); } }\n");
        }
        private void lifecycleClass(JavaSource result, String simple) {
            result.add("}\nabstract class " + simple + "$Lifecycle extends " + settings.getOrDefault(EXTENDS, "jakarta.servlet.http.HttpServlet") + " implements jakarta.servlet.jsp.HttpJspPage, io.vidocq.ibarra.spi.PageOptions {\n");
            result.add("@Override public final boolean errorOnELNotFound() { return " + bool(ERROR_ON_EL_NOT_FOUND, false) + "; }\n");
            result.add("""
                    @Override public final boolean usesStaticELImports() { return true; }
                    @SuppressWarnings({"removal","deprecation"}) @Override public final Class<?> resolveELClass(String name) { if (name == null) return null; return switch(name) {
                    """);
            importResolutionCases(result);
            result.add("default -> null; }; }\n");
            result.add("protected abstract void _jspxInitialize();\nprivate final void _jspxValidateTagExtraInfo() {\n");
            result.add(libraryValidations);
            result.add(tagValidations);
            result.add("}\npublic void jspInit() {}\npublic void jspDestroy() {}\n");
            lifecycleOverrides(result);
            if (settings.containsKey("info") && !hasFinalLifecycleMethod("getServletInfo", "()Ljava/lang/String;"))
                result.add("@Override public String getServletInfo() { return " + JavaSource.quote(settings.get("info")) + "; }\n");
            result.add("}\n");
        }
        private void importResolutionCases(JavaSource result) {
            var importClasses = new ImportClasses(importModulePath, applicationModule);
            for (String name : imports) if (!name.endsWith(".*")) importClasses.bindings(List.of(name), importLocations.get(name));
            var bindings = importClasses.bindings(imports, new SourceLocation(page.source(), 1, 1));
            for (var binding : bindings.entrySet()) {
                result.add("case " + JavaSource.quote(binding.getKey()) + " -> ");
                if (binding.getValue().size() == 1) result.add(binding.getValue().iterator().next() + ".class;\n");
                else result.add("throw new jakarta.el.ELException(" + JavaSource.quote("Ambiguous EL import: " + binding.getKey()) + ");\n");
            }
        }
        private void lifecycleOverrides(JavaSource result) {
            if (!hasFinalLifecycleMethod("init", "()V"))
                result.add("@Override public final void init() throws jakarta.servlet.ServletException { super.init(); _jspxInitialize(); _jspxValidateTagExtraInfo(); jspInit(); }\n");
            if (!hasFinalLifecycleMethod("init", "(Ljakarta/servlet/ServletConfig;)V"))
                result.add("@Override public final void init(jakarta.servlet.ServletConfig config) throws jakarta.servlet.ServletException { super.init(config); }\n");
            if (!hasFinalLifecycleMethod("destroy", "()V"))
                result.add("@Override public final void destroy() { try { jspDestroy(); } finally { super.destroy(); } }\n");
            if (!hasFinalLifecycleMethod("service", "(Ljakarta/servlet/http/HttpServletRequest;Ljakarta/servlet/http/HttpServletResponse;)V")) {
                result.add("@Override protected final void service(jakarta.servlet.http.HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response) throws jakarta.servlet.ServletException, java.io.IOException {\n");
                result.add("var _jspxPrecompile = request.getParameterValues(\"jsp_precompile\");\n");
                result.add("if (_jspxPrecompile != null) {\nfor (var value : _jspxPrecompile) {\n");
                result.add("if (!(\"\".equals(value) || \"true\".equals(value) || \"false\".equals(value))) { response.sendError(500, \"Invalid jsp_precompile value\"); return; }\n}\nreturn;\n}\n");
                result.add("_jspService(request, response);\n}\n");
            }
            if (!hasFinalLifecycleMethod("service", "(Ljakarta/servlet/ServletRequest;Ljakarta/servlet/ServletResponse;)V"))
                result.add("@Override public final void service(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) throws jakarta.servlet.ServletException, java.io.IOException { super.service(request, response); }\n");
        }

        private void directives(List<PageNode> nodes) {
            var explicit = new HashMap<String, String>();
            collectDirectives(nodes, explicit);
            settings.putAll(explicit);
            if (settings.containsKey(EXTENDS) && !SourceVersion.isName(settings.get(EXTENDS)))
                throw error(new SourceLocation(page.source(), 1, 1), "Invalid page superclass");
        }
        private boolean hasFinalLifecycleMethod(String name, String descriptor) {
            String superclass = settings.get(EXTENDS);
            return superclass != null && classes.hasFinalMethod(superclass, name, descriptor,
                    new SourceLocation(page.source(), 1, 1));
        }
        private void collectDirectives(List<PageNode> nodes, Map<String, String> explicit) {
            for (var node : nodes) {
                if (node instanceof PageNode.Directive directive) pageDirective(directive, explicit);
                else if (node instanceof PageNode.Element element) collectDirectives(element.children(), explicit);
            }
        }
        private void pageDirective(PageNode.Directive directive, Map<String, String> explicit) {
            if (directive.name().equals(TAGLIB)) return;
            if (!directive.name().equals("page")) throw error(directive.location(), "Unsupported directive: " + directive.name());
            for (var entry : directive.attributes().entrySet()) pageAttribute(directive, entry.getKey(), entry.getValue(), explicit);
        }
        private void pageAttribute(PageNode.Directive directive, String name, String value, Map<String, String> explicit) {
            if (namespaceAttribute(name)) return;
            if (!PAGE_ATTRIBUTES.contains(name)) throw error(directive.location(), "Unsupported page attribute: " + name);
            if (name.equals(PAGE_ENCODING) && !directive.location().source().equals(page.source())) return;
            if (BOOLEAN_PAGE_ATTRIBUTES.contains(name) && !value.equals(TRUE) && !value.equals(FALSE))
                throw error(directive.location(), name + " must be true or false");
            if (name.equals("import")) {
                pageImports(directive, value);
                return;
            }
            String previous = explicit.putIfAbsent(name, value);
            settingLocations.putIfAbsent(name, directive.location());
            if (previous != null && !previous.equals(value))
                throw error(directive.location(), "Conflicting page attribute: " + name);
        }
        private void pageImports(PageNode.Directive directive, String value) {
            for (String declared : value.split(",")) {
                String name = declared.trim();
                if (name.startsWith("static ")) throw error(directive.location(), "Page imports accept types or packages, not static members");
                if (!SourceVersion.isName(name.endsWith(".*") ? name.substring(0, name.length() - 2) : name))
                    throw error(directive.location(), "Invalid page import: " + name);
                imports.add(name);
                importLocations.putIfAbsent(name, directive.location());
            }
        }

        private void emit(List<PageNode> nodes) {
            for (var node : nodes) emit(node);
        }
        private void emit(PageNode node) {
            switch (node) {
                case PageNode.RawText(var value, var location) -> write(value, location);
                case PageNode.Text(var value, var location) -> {
                    if (!bool(TRIM_DIRECTIVE_WHITESPACES, false) || !value.chars().allMatch(c -> c == ' ' || c == '\t' || c == '\r' || c == '\n'))
                        write(value, location);
                }
                case PageNode.Expression(var code, var deferred, var location) -> {
                    if (deferred) throw error(location, "Deferred EL in template text is not supported");
                    body.add("out.write(_jspxText(" + expression("${" + code + "}", location) + CLOSE_CALL, location);
                }
                case PageNode.Script(var kind, var code, var location) -> script(kind, code, location);
                case PageNode.Element element -> element(element);
                case PageNode.Directive directive -> {
                    if (directive.name().equals(TAGLIB)) taglibBindings.put(directive.attributes().get("prefix"), directive);
                }
            }
        }
        private void script(PageNode.ScriptKind kind, String code, SourceLocation location) {
            if (bool("scriptingInvalid", false)) throw error(location, "Scripting is disabled by configuration");
            if (kind == PageNode.ScriptKind.DECLARATION) declarations.add(code + "\n", location);
            else if (kind == PageNode.ScriptKind.EXPRESSION) body.add("out.print(" + code + ");\n", location);
            else body.add(code + "\n", location);
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
            if (!node.namespace().equals(PageParser.JSP_NAMESPACE)) {
                foreignElement(node);
                return;
            }
            String local = node.name().substring(node.name().lastIndexOf(':') + 1);
            switch (local) {
                case "root" -> jspRoot(node);
                case "text" -> emit(node.children());
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
        private void foreignElement(PageNode.Element node) {
            boolean standard = sourceSyntaxes.get(node.location().source()) == Page.Syntax.STANDARD;
            var library = resolvedLibraries.resolve(node.namespace(), tagLibraryOrigin(node, standard), standard);
            if (library != null) {
                customTag(node, library);
                return;
            }
            if (standard || node.namespace().startsWith("urn:jsptag")
                    || node.namespace().startsWith("urn:jsptld") || node.namespace().startsWith("jakarta.tags."))
                throw error(node.location(), "No build-time tag library for " + node.namespace() + ": " + node.name());
            templateElement(node);
        }
        private SourceLocation tagLibraryOrigin(PageNode.Element node, boolean standard) {
            String uri = node.namespace();
            String prefix = node.name().substring(0, Math.max(0, node.name().indexOf(':')));
            var binding = taglibBindings.get(prefix);
            if (standard && binding != null && uri.equals(binding.attributes().get("uri"))) return binding.location();
            var namespace = namespaces.get(prefix);
            if (namespace != null && uri.equals(namespace.uri())) return namespace.location();
            return node.location();
        }
        private void templateElement(PageNode.Element node) {
            write("<" + node.name(), node.location());
            for (var entry : node.attributes().entrySet()) {
                if (namespaceAttribute(entry.getKey()) && entry.getValue().equals(PageParser.JSP_NAMESPACE)) continue;
                write(" " + entry.getKey() + "=\"", node.location());
                body.add("out.write(_jspxText(" + value(entry.getValue(), node.location(), true) + CLOSE_CALL, node.location());
                write("\"", node.location());
            }
            write(node.selfClosing() ? "/>" : ">", node.location());
            if (!node.selfClosing()) {
                emit(node.children());
                write("</" + node.name() + ">", node.location());
            }
        }

        private void jspRoot(PageNode.Element node) {
            allowed(node, Set.of("version"));
            String version = required(node, "version");
            if (!JSP_ROOT_VERSIONS.contains(version))
                throw error(node.location(), "Unsupported jsp:root version: " + version);
            for (var child : node.children()) {
                if (child instanceof PageNode.Element wrapper && wrapper.namespace().equals(PageParser.JSP_NAMESPACE)
                        && wrapper.name().substring(wrapper.name().lastIndexOf(':') + 1).equals("body")) {
                    allowed(wrapper, Set.of());
                    emit(wrapper.children());
                } else emit(List.of(child));
            }
        }


        private void synchronizeVariables(List<ScriptingVariable> variables, int... scopes) {
            for (var variable : variables) {
                for (int scope : scopes) {
                    if (variable.synchronize() && variable.scope() == scope)
                        body.add(variable.name() + " = (" + variable.type() + GET_ATTRIBUTE
                                + JavaSource.quote(variable.name()) + ");\n");
                }
            }
        }

        /** Validates the declared variable name and resolves its class name (carried in {@code type}) at build time. */
        private void addScriptingVariable(List<ScriptingVariable> variables, ScriptingVariable declared, SourceLocation at) {
            String name = declared.name();
            if (!SourceVersion.isIdentifier(name) || SourceVersion.isKeyword(name)
                    || RESERVED_VARIABLE_NAMES.contains(name)
                    || name.startsWith("_jspx"))
                throw error(at, "Invalid or reserved tag scripting variable: " + name);
            String type = classes.expressionType(declared.type(), at);
            var resolved = new ScriptingVariable(name, declared.contextName(), type, declared.declare(), declared.scope(),
                    declared.synchronize());
            variables.add(resolved);
            if (declared.scope() != 0 && declared.declare()) {
                var previous = pageVariables.putIfAbsent(name, resolved);
                if (previous == null) serviceDeclarations.add(type + " " + name + " = null;\n", at);
                else if (!previous.type().equals(type)) throw error(at, "Conflicting tag scripting variable type: " + name);
            }
        }

        private String tagVariableName(String attributeName, PageNode.Element tag, TagInvocation invocation) {
            String value = tag.attributes().get(attributeName);
            var parser = new PageNormalizer(sourceSyntaxes.getOrDefault(tag.location().source(), Page.Syntax.STANDARD),
                    parserOptions(), Map.of());
            if (value != null) {
                if (value.contains("<%"))
                    throw error(tag.location(), "Tag variable name attribute must be a translation-time constant: " + attributeName);
                return templateLiteral(parser.normalizeStructured(List.of(new PageNode.Text(value, tag.location()))));
            }
            var named = invocation.attributes().get(attributeName);
            if (named == null) throw error(tag.location(), "Missing tag variable name attribute: " + attributeName);
            return templateLiteral(parser.normalizeStructured(namedContent(named)));
        }

        /** One custom action being generated: its element, descriptor, handler kind and generated handler variable. */
        private record TagUse(PageNode.Element node, TagDescriptor tag, BeanClasses.Handler kind, String handler,
                List<ScriptingVariable> variables, List<PageNode> children) {
            SourceLocation location() { return node.location(); }
        }

        private void customTag(PageNode.Element node, TagLibraryDescriptor library) {
            String local = node.name().substring(node.name().lastIndexOf(':') + 1);
            var tag = library.tags().get(local);
            if (tag == null) throw error(node.location(), "Unknown tag " + local + " in " + library.resourcePath());
            if (tag.unsupportedMetadata())
                throw error(node.location(), tag.unsupportedDiagnostic());
            var invocation = tagInvocation(node);
            var scriptingVariables = new ArrayList<ScriptingVariable>();
            if (tag.teiClass() != null) {
                for (var variable : classes.tagVariables(tag.teiClass(), node.location()))
                    addScriptingVariable(scriptingVariables, new ScriptingVariable(variable.name(), variable.name(),
                            variable.className(), variable.declare(), variable.scope(), variable.declare()), node.location());
            }
            var kind = handlerKind(tag, node.location());
            validateTagExtraInfo(tag, library, node, invocation);
            var children = invocation.content();
            validateTagContent(tag, node, invocation);
            declaredTagVariables(tag, node, invocation, scriptingVariables);
            var use = new TagUse(node, tag, kind, "_jspxTag" + serial++, scriptingVariables, children);
            body.add("{\n", node.location());
            initializeHandler(use);
            for (var supplied : node.attributes().entrySet()) suppliedAttribute(use, supplied.getKey(), supplied.getValue());
            for (var supplied : invocation.attributes().entrySet()) namedAttribute(use, supplied.getKey(), supplied.getValue());
            String previousParent = parentTag;
            parentTag = use.handler();
            if (kind.simple()) simpleTagInvocation(use);
            else classicTagInvocation(use);
            parentTag = previousParent;
            body.add("}\n");
        }
        private BeanClasses.Handler handlerKind(TagDescriptor tag, SourceLocation at) {
            var kind = tag.tagFile() ? new BeanClasses.Handler(tag.handlerClass(), true, false, false, false, tag.dynamicAttributes(), false)
                    : classes.handler(tag.handlerClass(), at);
            if (kind.simple() && tag.bodyContent().equals("JSP")) throw error(at, "SimpleTag cannot have JSP body-content");
            if (tag.dynamicAttributes() && !kind.dynamic()) throw error(at, "Handler does not implement DynamicAttributes");
            return kind;
        }
        private void validateTagContent(TagDescriptor tag, PageNode.Element node, TagInvocation invocation) {
            if (tag.bodyContent().equals("empty") && !invocation.content().isEmpty()) throw error(node.location(), "Tag requires an empty body");
            if (tag.bodyContent().equals("scriptless")) scriptless(invocation.content());
            for (var attribute : tag.attributes().values())
                if (attribute.required() && !node.attributes().containsKey(attribute.name()) && !invocation.attributes().containsKey(attribute.name()))
                    throw error(node.location(), "Required tag attribute: " + attribute.name());
        }
        private void declaredTagVariables(TagDescriptor tag, PageNode.Element node, TagInvocation invocation,
                List<ScriptingVariable> scriptingVariables) {
            for (var variable : tag.variables()) {
                String name = variable.name() != null ? variable.name()
                        : tagVariableName(variable.nameFromAttribute(), node, invocation);
                String contextName = variable.alias() == null ? name : variable.alias();
                addScriptingVariable(scriptingVariables, new ScriptingVariable(name, contextName, variable.className(),
                        variable.declare(), variable.scope(), variable.declare() || tag.tagFile()), node.location());
            }
        }
        private void initializeHandler(TagUse use) {
            String handler = use.handler();
            var kind = use.kind();
            for (var variable : use.variables())
                if (variable.scope() == TagVariableDescriptor.NESTED && variable.declare()
                        && (!use.tag().tagFile() || !use.children().isEmpty()))
                    body.add(variable.type() + " " + variable.name() + " = null;\n", use.location());
            body.add("var " + handler + NEW + kind.sourceName() + NO_ARGUMENTS, use.location());
            if (!kind.simple()) body.add("try {\n");
            body.add(handler + (kind.simple() ? ".setJspContext(pageContext);\n" : ".setPageContext(pageContext);\n"), use.location());
            if (!kind.simple()) body.add(handler + ".setParent(" + classicParent(parentTag) + ");\n");
            else if (!parentTag.equals("null")) body.add(handler + ".setParent(" + parentTag + ");\n");
            if (kind.jspIdConsumer()) body.add(handler + ".setJspId(" + JavaSource.quote("ibarra_tag_" + jspIdSerial++) + ");\n", use.location());
        }
        /** Rejects or skips an attribute the tag does not declare; {@code true} when it is set as a dynamic attribute. */
        private boolean acceptsDynamicAttribute(TagUse use, String name, SourceLocation at) {
            if (use.kind().jspIdConsumer() && name.equals("jspId")) throw error(at, "Tag attribute cannot override compiler-provided jspId");
            if (use.tag().tagFile() && name.contains(":")) return false;
            if (!use.tag().dynamicAttributes() || name.contains(":")) throw error(at, "Unknown or namespaced tag attribute: " + name);
            return true;
        }
        private BeanClasses.Accessor tagSetter(TagUse use, TagAttribute attribute, DeferredInput deferredValue, SourceLocation at) {
            if (use.tag().tagFile()) return tagFileSetter(attribute);
            String handlerClass = use.tag().handlerClass();
            if (attribute.fragment()) return classes.tagFragmentSetter(handlerClass, attribute.name(), attribute.setterType(), at);
            if (attribute.deferred()) return classes.tagSetter(handlerClass, attribute.name(), deferredSetterType(attribute, deferredValue), at);
            return classes.tagPropertySetter(handlerClass, attribute.name(), at);
        }
        private void suppliedAttribute(TagUse use, String name, String value) {
            if (namespaceAttribute(name)) return;
            var at = use.location();
            var attribute = use.tag().attributes().get(name);
            if (attribute == null) {
                if (acceptsDynamicAttribute(use, name, at))
                    body.add(use.handler() + SET_DYNAMIC_ATTRIBUTE + JavaSource.quote(name) + ", "
                            + dynamicAttributeValue(value, at) + ");\n", at);
                return;
            }
            if (attribute.fragment()) throw error(at, "Fragment attributes require jsp:attribute");
            DeferredInput deferredValue = attribute.deferred() ? attributeInput(value, at) : null;
            var setter = tagSetter(use, attribute, deferredValue, at);
            var target = new TagAttribute(attribute.name(), setter.type(), attribute.required(), attribute.runtimeExpression(), false);
            protectJspIdSetter(use.kind(), setter, at);
            String argument = attribute.deferred() ? deferredAttribute(deferredValue, attribute, at)
                    : tagValue(value, target, at);
            body.add(use.handler() + "." + setter.name() + "((" + setter.type() + ") (" + argument + CLOSE_CALL, at);
        }
        private void namedAttribute(TagUse use, String name, PageNode.Element named) {
            var previousNamespaces = enterNamespaces(named);
            try {
                namedAttributeValue(use, name, named);
            } finally {
                namespaces.clear();
                namespaces.putAll(previousNamespaces);
            }
        }
        private void namedAttributeValue(TagUse use, String name, PageNode.Element named) {
            var at = named.location();
            var attribute = use.tag().attributes().get(name);
            var content = namedContent(named);
            if (attribute == null) {
                if (acceptsDynamicAttribute(use, name, at))
                    body.add(use.handler() + SET_DYNAMIC_ATTRIBUTE + JavaSource.quote(name) + ", "
                            + dynamicAttributeValue(content, at, use.handler()) + ");\n", at);
                return;
            }
            DeferredInput deferredValue = attribute.deferred() ? deferredInput(content, at) : null;
            var setter = tagSetter(use, attribute, deferredValue, at);
            protectJspIdSetter(use.kind(), setter, at);
            String argument;
            if (attribute.fragment()) argument = fragment(content, use.handler());
            else if (attribute.deferred()) argument = deferredAttribute(deferredValue, attribute, at);
            else if (attribute.runtimeExpression()) argument = stringConversion(capture(content, at, use.handler()), setter.type(), at);
            else argument = literalValue(templateLiteral(content), new TagAttribute(attribute.name(), setter.type(), attribute.required(), false, false), at);
            body.add(use.handler() + "." + setter.name() + "((" + setter.type() + ") (" + argument + CLOSE_CALL, at);
        }
        private void simpleTagInvocation(TagUse use) {
            String handler = use.handler();
            if (!use.children().isEmpty()) {
                String bodyFragment = use.tag().tagFile()
                        ? tagFileFragment(use.children(), handler, use.kind().sourceName(), use.variables())
                        : fragment(use.children(), handler);
                body.add(handler + ".setJspBody(" + bodyFragment + ");\n", use.location());
            }
            var nestedTagFileVariableIndexes = use.tag().tagFile() ? saveNestedTagFileVariables(use) : List.<Integer>of();
            if (nestedTagFileVariableIndexes.isEmpty()) body.add(handler + ".doTag();\n", use.location());
            else restoreNestedTagFileVariables(use, nestedTagFileVariableIndexes);
            if (use.tag().tagFile()) synchronizeTagFileVariables(handler, use.variables(), use.location());
            synchronizeVariables(use.variables(), 1, 2);
        }
        private List<Integer> saveNestedTagFileVariables(TagUse use) {
            var indexes = new ArrayList<Integer>();
            var variables = use.variables();
            for (int variableIndex = 0; variableIndex < variables.size(); variableIndex++) {
                var variable = variables.get(variableIndex);
                if (variable.scope() != TagVariableDescriptor.NESTED) continue;
                String state = use.handler() + "_jspxVariable" + variableIndex;
                String name = JavaSource.quote(variable.name());
                body.add("boolean " + state + "Present = pageContext.getAttributesScope(" + name
                        + ") == jakarta.servlet.jsp.PageContext.PAGE_SCOPE;\n"
                        + OBJECT + state + "Value = pageContext.getAttribute(" + name
                        + PAGE_SCOPE_ARGUMENT
                        + REMOVE_ATTRIBUTE + name + PAGE_SCOPE_ARGUMENT,
                        use.location());
                indexes.add(variableIndex);
            }
            return indexes;
        }
        private void restoreNestedTagFileVariables(TagUse use, List<Integer> indexes) {
            body.add("try { " + use.handler() + ".doTag(); } finally {\n", use.location());
            for (int variableIndex : indexes) {
                var variable = use.variables().get(variableIndex);
                String state = use.handler() + "_jspxVariable" + variableIndex;
                String name = JavaSource.quote(variable.name());
                body.add(REMOVE_ATTRIBUTE + name + PAGE_SCOPE_ARGUMENT
                        + "if (" + state + "Present) pageContext.setAttribute(" + name + ", " + state
                        + "Value, jakarta.servlet.jsp.PageContext.PAGE_SCOPE);\n", use.location());
            }
            body.add("}\n");
        }
        private void classicTagInvocation(TagUse use) {
            String handler = use.handler();
            var kind = use.kind();
            if (kind.catchFinally()) body.add("boolean _jspxSetterFailed" + handler + " = false;\n");
            body.add("int _jspxEnd" + handler + " = jakarta.servlet.jsp.tagext.Tag.EVAL_PAGE;\n");
            body.add("try {\nint _jspxStart" + handler + " = " + handler + ".doStartTag();\n", use.location());
            String start = "_jspxStart" + handler;
            body.add("if (" + start + " != 0 && " + start + " != 1" + (kind.buffered() ? " && " + start + " != 2" : "")
                    + ") throw new jakarta.servlet.jsp.JspException(\"Invalid doStartTag result\");\n");
            synchronizeVariables(use.variables(), 1);
            if (!use.children().isEmpty()) classicTagBody(use, start);
            body.add("_jspxEnd" + handler + " = " + handler + ".doEndTag();\n", use.location());
            synchronizeVariables(use.variables(), 1, 2);
            if (kind.catchFinally()) body.add("} catch (Throwable _jspxFailure" + handler + ") { if (_jspxSetterFailed" + handler + ") throw _jspxFailure" + handler + "; "
                    + handler + ".doCatch(_jspxFailure" + handler + "); if (_jspxFailure" + handler + " instanceof jakarta.servlet.jsp.SkipPageException) throw _jspxFailure" + handler + ";"
                    + " } finally { if (!_jspxSetterFailed" + handler + ") " + handler + ".doFinally(); }\n");
            else body.add("} finally { /* Handler action scope. */ }\n");
            body.add("if (_jspxEnd" + handler + " == jakarta.servlet.jsp.tagext.Tag.SKIP_PAGE) throw new jakarta.servlet.jsp.SkipPageException();\n", use.location());
            body.add("} finally { " + handler + ".release(); }\n");
        }
        private void classicTagBody(TagUse use, String start) {
            String handler = use.handler();
            var kind = use.kind();
            var previousBody = body;
            var content = new JavaSource();
            body = content;
            emit(use.children());
            body = previousBody;
            body.add("if (" + start + " != jakarta.servlet.jsp.tagext.Tag.SKIP_BODY) {\n");
            if (kind.buffered()) {
                body.add(bufferedBodyStart(handler, start, kind.catchFinally()));
                synchronizeVariables(use.variables(), 1, 0);
            } else synchronizeVariables(use.variables(), 0);
            if (kind.iteration() && !use.variables().isEmpty()) {
                String afterBody = "_jspxAfterBody" + handler;
                body.add("int " + afterBody + ";\ndo {\n");
                body.add(content);
                body.add(afterBody + " = " + handler + ".doAfterBody();\n");
                synchronizeVariables(use.variables(), 1, 0);
                body.add("} while (" + afterBody + " == jakarta.servlet.jsp.tagext.IterationTag.EVAL_BODY_AGAIN);\n");
            } else {
                body.add("do {\n");
                body.add(content);
                body.add("} while (" + (kind.iteration() ? handler + ".doAfterBody() == jakarta.servlet.jsp.tagext.IterationTag.EVAL_BODY_AGAIN" : FALSE) + ");\n");
            }
            if (kind.buffered()) body.add("} finally { if (_jspxBuffered" + handler + ") out = pageContext.popBody(); }\n");
            body.add("}\n");
        }
        private static String bufferedBodyStart(String handler, String start, boolean catchFinally) {
            String failed = "_jspxSetterFailed" + handler;
            return "boolean _jspxBuffered" + handler + " = " + start + " == jakarta.servlet.jsp.tagext.BodyTag.EVAL_BODY_BUFFERED;\n"
                    + "if (_jspxBuffered" + handler + ") out = pageContext.pushBody();\ntry {\n"
                    + "if (_jspxBuffered" + handler + ") { " + (catchFinally ? failed + " = true; " : "")
                    + handler + ".setBodyContent((jakarta.servlet.jsp.tagext.BodyContent) out); "
                    + (catchFinally ? failed + " = false; " : "") + handler + ".doInitBody(); }\n";
        }

        private void synchronizeTagFileVariables(String handler, List<ScriptingVariable> variables, SourceLocation at) {
            int index = 0;
            for (var variable : variables) {
                if (variable.scope() == TagVariableDescriptor.NESTED) { index++; continue; }
                String value = handler + "_jspxScopeOutput" + index++;
                String name = JavaSource.quote(variable.name());
                String contextName = JavaSource.quote(variable.contextName());
                body.add(OBJECT + value + " = " + handler + "._jspxTagFileContext().getAttribute(" + contextName
                        + PAGE_SCOPE_ARGUMENT
                        + REMOVE_ATTRIBUTE + name + PAGE_SCOPE_ARGUMENT
                        + "if (" + value + " != null) pageContext.setAttribute(" + name + ", " + value
                        + PAGE_SCOPE_ARGUMENT, at);
            }
        }

        private record TagInvocation(Map<String, PageNode.Element> attributes, List<PageNode> content) {}

        private BeanClasses.Accessor tagFileSetter(TagAttribute attribute) {
            String name = "set" + Character.toUpperCase(attribute.name().charAt(0)) + attribute.name().substring(1);
            return new BeanClasses.Accessor(name, attribute.type());
        }

        private String deferredSetterType(TagAttribute attribute, DeferredInput value) {
            return attribute.deferredMethod() != null && (!attribute.runtimeExpression() || value.hash())
                    ? "jakarta.el.MethodExpression" : attribute.setterType();
        }

        private void validateTagExtraInfo(TagDescriptor tag, TagLibraryDescriptor library, PageNode.Element node,
                TagInvocation invocation) {
            if (tag.teiClass() == null) return;
            String suffix = String.valueOf(tagValidationSerial++);
            String extraInfo = "_jspxTagExtraInfo" + suffix;
            String tagLibrary = "_jspxTagLibrary" + suffix;
            String tagInfo = "_jspxTagInfo" + suffix;
            String messages = "_jspxTagValidationMessages" + suffix;
            tagValidations.add("var " + extraInfo + NEW + tag.teiClass().replace('$', '.') + NO_ARGUMENTS, node.location());
            tagValidations.add("var " + tagLibrary + " = " + tagLibraryInfoSource(library, node) + ";\n", node.location());

            String attributes = tag.attributes().values().stream().map(this::tagAttributeInfoSource)
                    .collect(java.util.stream.Collectors.joining(", "));
            String variables = tag.variables().stream().map(this::tagVariableInfoSource)
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
            for (var supplied : invocation.attributes().entrySet())
                values.put(supplied.getKey(), namedTagDataValue(tag.attributes().get(supplied.getKey()), supplied.getValue()));
            String pairs = values.entrySet().stream().map(value -> "new Object[] { " + JavaSource.quote(value.getKey()) + ", "
                    + value.getValue() + " }").collect(java.util.stream.Collectors.joining(", "));
            tagValidations.add("var " + messages + " = " + extraInfo + ".validate(new jakarta.servlet.jsp.tagext.TagData(new Object[][] {"
                    + pairs + "}));\n"
                    + "if (" + messages + " != null && " + messages + ".length != 0) throw new IllegalStateException("
                    + JavaSource.quote("TagExtraInfo validation failed for " + tag.name() + ": ") + " + " + messages + "[0].getMessage());\n",
                    node.location());
        }
        private String namedTagDataValue(TagAttribute attribute, PageNode.Element named) {
            List<PageNode> content = namedContent(named);
            if (attribute != null && attribute.fragment() || !content.stream().allMatch(PageNode.Text.class::isInstance))
                return TAG_DATA_REQUEST_TIME_VALUE;
            return tagDataValue(content.stream().map(PageNode.Text.class::cast).map(PageNode.Text::value)
                    .collect(java.util.stream.Collectors.joining()), attribute, named.location());
        }
        private String tagVariableInfoSource(TagVariableDescriptor variable) {
            return "new jakarta.servlet.jsp.tagext.TagVariableInfo(" + nullableString(variable.name()) + ", "
                    + nullableString(variable.nameFromAttribute()) + ", " + JavaSource.quote(variable.className())
                    + ", " + variable.declare() + ", " + variable.scope() + ")";
        }

        private record TagLibraryUse(String prefix, String uri, TagLibraryValidatorDescriptor validator) { }

        private void validateTagLibraries() {
            var uses = new LinkedHashMap<String, TagLibraryUse>();
            var prefixes = new LinkedHashMap<String, String>();
            collectTagLibraryUses(page.nodes(), uses, prefixes);
            if (uses.isEmpty()) return;
            String xmlView = PageDataXmlView.serialize(page.nodes(), prefixes);
            int validatorIndex = 0;
            for (var use : uses.values()) {
                String suffix = String.valueOf(validatorIndex++);
                String validator = "_jspxTagLibraryValidator" + suffix;
                String xml = "_jspxPageDataXml" + suffix;
                String messages = "_jspxTagLibraryMessages" + suffix;
                libraryValidations.add("var " + validator + NEW + use.validator().className().replace('$', '.') + NO_ARGUMENTS);
                if (!use.validator().initParameters().isEmpty()) {
                    String parameters = use.validator().initParameters().entrySet().stream()
                            .sorted(Map.Entry.comparingByKey())
                            .map(parameter -> "java.util.Map.<String,Object>entry(" + JavaSource.quote(parameter.getKey())
                                    + ", " + JavaSource.quote(parameter.getValue()) + ")")
                            .collect(java.util.stream.Collectors.joining(", "));
                    libraryValidations.add(validator + ".setInitParameters(java.util.Map.<String,Object>ofEntries(" + parameters + CLOSE_CALL);
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
                if (node instanceof PageNode.Directive(var name, var attributes, var location) && name.equals(TAGLIB)) {
                    String prefix = attributes.get("prefix");
                    String uri = attributes.get("uri");
                    if (prefix != null && uri != null) {
                        namespaces.putIfAbsent(prefix, uri);
                        addTagLibraryUse(prefix, uri, location, true, uses);
                    }
                } else if (node instanceof PageNode.Element element) collectTagLibraryUses(element, uses, namespaces);
            }
        }
        private void collectTagLibraryUses(PageNode.Element element, Map<String, TagLibraryUse> uses,
                Map<String, String> namespaces) {
            for (var attribute : element.attributes().entrySet()) {
                String prefix = TldReferences.xmlnsPrefix(attribute.getKey());
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
                    + tagLibraryInfosSource(prefix, node.namespace(), node.location()) + "}; }\n"
                    + "@Override public jakarta.servlet.jsp.tagext.TagLibraryInfo[] getTagLibraryInfos() { "
                    + "return _jspxTagLibraryInfos; }\n}";
        }

        private String tagLibraryInfosSource(String currentPrefix, String currentUri, SourceLocation at) {
            var entries = new ArrayList<String>();
            boolean currentIncluded = false;
            for (var binding : taglibBindings.entrySet()) {
                PageNode.Directive directive = binding.getValue();
                String uri = directive.attributes().get("uri");
                TagLibraryDescriptor imported = uri == null ? null : resolvedLibraries.resolve(uri, directive.location(), true);
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
                    tagDeclarations.append("var ").append(tagExtraInfo).append(NEW)
                            .append(tag.teiClass().replace('$', '.')).append("(); ");
                String attributes = tag.attributes().values().stream().map(this::tagAttributeInfoSource)
                        .collect(java.util.stream.Collectors.joining(", "));
                String variables = tag.variables().stream().map(this::tagVariableInfoSource)
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
            String variables = metadata.variables().stream().map(this::tagVariableInfoSource)
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
                return TAG_DATA_REQUEST_TIME_VALUE;
            var normalized = new PageNormalizer(sourceSyntaxes.getOrDefault(at.source(), Page.Syntax.STANDARD),
                    parserOptions(), Map.of())
                    .normalizeStructured(List.of(new PageNode.Text(raw, at)));
            if (normalized.stream().anyMatch(value -> !(value instanceof PageNode.Text))) return TAG_DATA_REQUEST_TIME_VALUE;
            return JavaSource.quote(normalized.stream().map(PageNode.Text.class::cast).map(PageNode.Text::value)
                    .collect(java.util.stream.Collectors.joining()));
        }

        private String nullableString(String value) {
            return value == null ? "null" : JavaSource.quote(value);
        }

        private boolean action(PageNode node, String name) {
            return node instanceof PageNode.Element element && element.namespace().equals(PageParser.JSP_NAMESPACE)
                    && element.name().substring(element.name().lastIndexOf(':') + 1).equals(name);
        }
        private TagInvocation tagInvocation(PageNode.Element tag) {
            if (tag.children().stream().noneMatch(child -> action(child, ATTRIBUTE_ACTION) || action(child, BODY_ACTION)))
                return new TagInvocation(Map.of(), tag.children());
            var named = new LinkedHashMap<String, PageNode.Element>();
            var content = new ArrayList<PageNode>();
            boolean bodySeen = false;
            for (var child : tag.children()) {
                if (xmlWhitespace(child)) continue;
                if (action(child, ATTRIBUTE_ACTION)) {
                    if (bodySeen) throw error(child.location(), "jsp:attribute must precede jsp:body");
                    namedAttributeAction(tag, (PageNode.Element) child, named);
                } else {
                    explicitBody(child, bodySeen, content);
                    bodySeen = true;
                }
            }
            return new TagInvocation(named, content);
        }
        private boolean xmlWhitespace(PageNode node) {
            return node instanceof PageNode.Text(var value, var _) && value.chars().allMatch(c -> xmlSpace((char) c));
        }
        private void namedAttributeAction(PageNode.Element tag, PageNode.Element attribute, Map<String, PageNode.Element> named) {
            allowed(attribute, Set.of("name", "trim", "omit"));
            String name = required(attribute, "name");
            if (!name.matches("[\\p{L}_][\\p{L}\\p{N}\\p{M}_.-]*(?::[\\p{L}_][\\p{L}\\p{N}\\p{M}_.-]*)?"))
                throw error(attribute.location(), "Invalid named tag attribute: " + name);
            int colon = name.indexOf(':');
            if (colon >= 0) {
                if (!name.substring(0, colon).equals(tag.name().substring(0, tag.name().indexOf(':'))))
                    throw error(attribute.location(), "Unknown or namespaced tag attribute: " + name);
                name = name.substring(colon + 1);
            }
            if (tag.attributes().containsKey(name) || named.putIfAbsent(name, attribute) != null)
                throw error(attribute.location(), "Duplicate tag attribute: " + name);
            if (attribute.attributes().containsKey("omit")) boolValue(attribute.attributes().get("omit"), attribute.location());
        }
        private void explicitBody(PageNode child, boolean bodySeen, List<PageNode> content) {
            if (!action(child, BODY_ACTION)) throw error(child.location(), "Named attributes and explicit bodies require jsp:body around content");
            if (bodySeen) throw error(child.location(), "Duplicate jsp:body");
            var wrapper = (PageNode.Element) child;
            allowed(wrapper, Set.of());
            if (!wrapper.children().isEmpty()) content.add(new PageNode.Element("jsp:text", PageParser.JSP_NAMESPACE,
                    wrapper.attributes(), wrapper.children(), wrapper.selfClosing(), wrapper.location()));
        }
        private List<PageNode> namedContent(PageNode.Element attribute) {
            var children = new ArrayList<>(attribute.children());
            if (boolValue(attribute.attributes().getOrDefault("trim", TRUE), attribute.location()) && !children.isEmpty()) {
                trimNamed(children, true);
                trimNamed(children, false);
            }
            return children;
        }
        /** Trims XML whitespace from one end of the content; {@code true} when every visited node became empty. */
        private boolean trimNamed(List<PageNode> children, boolean leading) {
            int step = leading ? 1 : -1;
            for (int index = leading ? 0 : children.size() - 1; index >= 0 && index < children.size(); index += step) {
                if (!trimNamedNode(children, index, leading)) return false;
            }
            return true;
        }
        private boolean trimNamedNode(List<PageNode> children, int index, boolean leading) {
            var node = children.get(index);
            if (node instanceof PageNode.Text(var value, var location)) {
                int start = leading ? firstNonSpace(value) : 0;
                int end = leading ? value.length() : lastNonSpaceEnd(value);
                children.set(index, new PageNode.Text(value.substring(start, end), location.advance(value.substring(0, start))));
                return start == end;
            }
            if (action(node, TEXT_ACTION)) {
                var element = (PageNode.Element) node;
                var content = new ArrayList<>(element.children());
                boolean exhausted = trimNamed(content, leading);
                children.set(index, new PageNode.Element(element.name(), element.namespace(), element.attributes(), content, element.selfClosing(), element.location()));
                return exhausted;
            }
            return false;
        }
        private int firstNonSpace(String value) {
            int start = 0;
            while (start < value.length() && xmlSpace(value.charAt(start))) start++;
            return start;
        }
        private int lastNonSpaceEnd(String value) {
            int end = value.length();
            while (end > 0 && xmlSpace(value.charAt(end - 1))) end--;
            return end;
        }
        private String templateLiteral(List<PageNode> nodes) {
            var literal = new StringBuilder();
            for (var node : nodes) {
                if (node instanceof PageNode.Text(var value, var _)) literal.append(value);
                else if (action(node, TEXT_ACTION)) literal.append(templateLiteral(((PageNode.Element) node).children()));
                else if (node instanceof PageNode.Element element && templateMarkup(element)) templateMarkupLiteral(literal, element);
                else throw error(node.location(), "Static named attributes accept only template text");
            }
            return literal.toString();
        }
        private boolean templateMarkup(PageNode.Element element) {
            return sourceSyntaxes.get(element.location().source()) == Page.Syntax.XML
                    && !element.namespace().equals(PageParser.JSP_NAMESPACE) && !element.namespace().startsWith("urn:jsp")
                    && !element.namespace().startsWith("jakarta.tags.")
                    && resolvedLibraries.resolve(element.namespace(), element.location(), false) == null;
        }
        private void templateMarkupLiteral(StringBuilder literal, PageNode.Element element) {
            literal.append('<').append(element.name());
            for (var entry : element.attributes().entrySet()) {
                var normalized = new PageNormalizer(Page.Syntax.XML, parserOptions(), Map.of())
                        .normalizeStructured(List.of(new PageNode.Text(entry.getValue(), element.location())));
                String value = templateLiteral(normalized);
                literal.append(' ').append(entry.getKey()).append("=\"")
                        .append(escapeAttribute(value))
                        .append('"');
            }
            literal.append(element.selfClosing() ? "/>" : ">");
            if (!element.selfClosing()) literal.append(templateLiteral(element.children())).append("</").append(element.name()).append('>');
        }
        private String capture(List<PageNode> nodes, SourceLocation at, String parent) {
            String captured = "_jspxAttribute" + serial++;
            String writer = "_jspxBody" + serial++;
            body.add(STRING_DECLARATION + captured + ";\nvar " + writer + " = pageContext.pushBody();\ntry { out = " + writer + ";\n", at);
            String previousParent = parentTag;
            parentTag = parent;
            emit(nodes);
            parentTag = previousParent;
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
            if (kind.jspIdConsumer() && setter.name().equals("setJspId") && setter.type().equals(JAVA_STRING))
                throw error(at, "Tag attribute cannot override compiler-provided jspId");
        }

        private String fragment(List<PageNode> nodes, String parent) {
            return fragment(nodes, parent, null, null, "");
        }

        private String tagFileFragment(List<PageNode> nodes, String parent,
                String handlerType, List<ScriptingVariable> variables) {
            StringBuilder synchronization = new StringBuilder();
            int index = 0;
            String prefix = "_jspxTagFileBody" + serial++;
            for (var variable : variables) {
                if (variable.scope() != TagVariableDescriptor.AT_END) {
                    String value = prefix + "Value" + index;
                    String contextName = JavaSource.quote(variable.contextName());
                    synchronization.append(OBJECT).append(value).append(" = _jspxTagFileHandler._jspxTagFileContext().getAttribute(")
                            .append(contextName).append(PAGE_SCOPE_ARGUMENT)
                            .append(REMOVE_ATTRIBUTE).append(JavaSource.quote(variable.name()))
                            .append(PAGE_SCOPE_ARGUMENT)
                            .append("if (").append(value).append(" != null) pageContext.setAttribute(")
                            .append(JavaSource.quote(variable.name())).append(", ")
                            .append(value).append(PAGE_SCOPE_ARGUMENT);
                }
                index++;
            }
            return fragment(nodes, parent, handlerType, parent, synchronization.toString());
        }

        private String fragment(List<PageNode> nodes, String parent,
                String captureType, String captureExpression, String invocationPrelude) {
            scriptless(nodes);
            String name = "_jspxFragment" + serial++;
            var previousBody = body;
            String previousParent = parentTag;
            boolean previousFactory = needsFactory;
            var content = fragmentBody(nodes);
            boolean factory = needsFactory;
            body = previousBody;
            parentTag = previousParent;
            needsFactory = previousFactory || factory;
            var definition = new JavaSource();
            String captureField = captureType == null ? "" : "private final " + captureType + " _jspxTagFileHandler;\n";
            String captureParameter = captureType == null ? "" : ", " + captureType + " tagFileHandler";
            String captureAssignment = captureType == null ? "" : " _jspxTagFileHandler=tagFileHandler;";
            String captureArgument = captureType == null ? "" : ", " + captureExpression;
            definition.add("private final class " + name + " extends jakarta.servlet.jsp.tagext.JspFragment {\n"
                    + "private final jakarta.servlet.jsp.PageContext _context; private final jakarta.servlet.jsp.tagext.JspTag _jspxParent; private final boolean[] _forwarded;\n"
                    + captureField
                    + name + "(jakarta.servlet.jsp.PageContext context, jakarta.servlet.jsp.tagext.JspTag parent, boolean[] forwarded"
                    + captureParameter + ") { _context=context; _jspxParent=parent; _forwarded=forwarded;" + captureAssignment + " }\n"
                    + "public jakarta.servlet.jsp.JspContext getJspContext() { return _context; }\n"
                    + "public void invoke(java.io.Writer writer) throws jakarta.servlet.jsp.JspException, java.io.IOException {\n"
                    + "var pageContext=_context; var out=pageContext.getOut(); var _jspxForwarded=_forwarded;\n"
                    + "var request=(jakarta.servlet.http.HttpServletRequest) pageContext.getRequest(); var response=(jakarta.servlet.http.HttpServletResponse) pageContext.getResponse();\n"
                    + "var application=pageContext.getServletContext(); var config=pageContext.getServletConfig(); Object page=pageContext.getPage();\n");
            if (bool(SESSION, true)) definition.add("var session=pageContext.getSession();\n");
            if (bool(IS_ERROR_PAGE, false)) definition.add("Throwable exception=pageContext.getErrorData().getThrowable();\n");
            if (factory) definition.add("var _jspxExpressionFactory=jakarta.servlet.jsp.JspFactory.getDefaultFactory().getJspApplicationContext(application).getExpressionFactory();\n");
            definition.add(invocationPrelude);
            definition.add("if (writer != null) out=pageContext.pushBody(writer);\ntry {\n");
            definition.add(content);
            definition.add("""
                    } catch (Throwable failure) { if (failure instanceof jakarta.servlet.jsp.JspException jsp) throw jsp;\
                     if (failure instanceof java.io.IOException io) throw io; if (failure instanceof RuntimeException runtime) throw runtime; if (failure instanceof Error error) throw error;\
                     throw new jakarta.servlet.jsp.JspException(failure); } finally { if (writer != null) pageContext.popBody(); }
                    }
                    }
                    """);
            fragments.add(definition);
            return "new " + name + "(pageContext, " + parent + ", _jspxForwarded" + captureArgument + ")";
        }
        /** Generates a fragment body in a fresh buffer with the fragment's parent variable and expression-factory state. */
        private JavaSource fragmentBody(List<PageNode> nodes) {
            body = new JavaSource();
            parentTag = "_jspxParent";
            needsFactory = false;
            fragmentDepth++;
            emit(nodes);
            fragmentDepth--;
            return body;
        }
        private String tagValue(String raw, TagAttribute attribute, SourceLocation at) {
            if (raw.startsWith("<%=") && raw.endsWith("%>")) {
                if (!attribute.runtimeExpression()) throw error(at, "Attribute does not accept runtime expressions: " + attribute.name());
                return value(raw, at, false);
            }
            var normalized = new PageNormalizer(sourceSyntaxes.getOrDefault(at.source(), Page.Syntax.STANDARD),
                    parserOptions(), Map.of())
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
                    parserOptions(), Map.of())
                    .normalizeStructured(List.of(new PageNode.Text(raw, at)));
            return deferredInput(pieces, at);
        }
        private DeferredInput deferredInput(List<PageNode> pieces, SourceLocation at) {
            var code = new StringBuilder();
            var literal = new StringBuilder();
            var functions = new TreeMap<String, BeanClasses.Function>();
            boolean[] kinds = new boolean[2];
            deferredParts(pieces, at, code, literal, functions, kinds);
            return new DeferredInput(code.toString(), literal.toString(), kinds[0], kinds[1], Map.copyOf(functions));
        }
        private void deferredParts(List<PageNode> pieces, SourceLocation at, StringBuilder code, StringBuilder literal,
                Map<String, BeanClasses.Function> functions, boolean[] kinds) {
            for (var piece : pieces) {
                if (piece instanceof PageNode.Text(var value, var _)) {
                    literal.append(value);
                    code.append(value.replace("\\", "\\\\").replace("${", "\\${").replace("#{", "\\#{"));
                } else if (piece instanceof PageNode.Expression expression) {
                    deferredExpressionPart(expression, code, functions, kinds);
                } else if (action(piece, TEXT_ACTION)) {
                    var wrapper = (PageNode.Element) piece;
                    var previous = enterNamespaces(wrapper);
                    try {
                        deferredParts(wrapper.children(), at, code, literal, functions, kinds);
                    } finally {
                        namespaces.clear();
                        namespaces.putAll(previous);
                    }
                } else throw error(at, "Deferred named attributes require text and EL, without executable body capture");
            }
        }
        private void deferredExpressionPart(PageNode.Expression expression, StringBuilder code,
                Map<String, BeanClasses.Function> functions, boolean[] kinds) {
            kinds[expression.deferred() ? 0 : 1] = true;
            String source = (expression.deferred() ? "#{" : "${") + expression.code() + "}";
            code.append(source);
            for (var entry : functionBindings(source, expression.location()).entrySet()) {
                var previous = functions.putIfAbsent(entry.getKey(), entry.getValue());
                if (previous != null && !previous.equals(entry.getValue()))
                    throw error(expression.location(), "Deferred composite cannot rebind one function name");
            }
        }
        private String deferredAttribute(DeferredInput input, TagAttribute attribute, SourceLocation at) {
            if (input.hash() && input.dollar()) throw error(at, "Attribute cannot mix immediate and deferred expressions");
            if (!input.hash() && attribute.runtimeExpression()) return immediateAttribute(input, attribute, at);
            if (input.dollar()) throw error(at, "Deferred attribute requires #{} syntax: " + attribute.name());
            if (startupElAllowed) throw error(at, "Deferred attributes require build-time compilation");
            var method = deferredMethod(input, attribute, at);
            String expected = method == null ? classes.expressionType(attribute.deferredValueType(), at) : method.returnType();
            var spec = new ExpressionSpec(expected, method, true, !input.hash());
            int index = registerExpression(input.hash() ? input.code() : input.literal(), at, spec, input.hash() ? input.functions() : Map.of());
            String construction = "new " + className + EXPRESSION_SUFFIX + index + "(" + deferredContext(spec, index) + expected + CLASS_SUFFIX
                    + (method == null ? "" : ", new Class<?>[]{" + classLiterals(method.parameters()) + "}") + ")";
            return method == null ? construction : "new _jspxScopedMethodExpression(" + className + CLASS_SUFFIX + ", " + construction + ")";
        }
        /** A non-deferred value given to a deferred attribute that also accepts runtime expressions. */
        private String immediateAttribute(DeferredInput input, TagAttribute attribute, SourceLocation at) {
            var dynamic = new TagAttribute(attribute.name(), attribute.type(), attribute.required(), true, false);
            if (!input.dollar()) return literalValue(input.literal(), dynamic, at);
            if (input.code().startsWith("<%=")) return tagValue(input.code(), dynamic, at);
            if (startupElAllowed) throw error(at, "Mixed deferred attributes require build-time compilation");
            String expected = classes.expressionType(attribute.type(), at);
            int index = registerExpression(input.code(), at, new ExpressionSpec(expected, null, true, false), input.functions());
            String context = input.functions().isEmpty() ? "pageContext.getELContext()"
                    : FUNCTION_CONTEXT + index + "(pageContext.getELContext())";
            return "new " + className + EXPRESSION_SUFFIX + index + "(" + context + ", " + expected + ".class).getValue(pageContext.getELContext())";
        }
        /** The build-time resolved method signature of a deferred-method attribute, or {@code null} for a deferred value. */
        private DeferredMethodSignature deferredMethod(DeferredInput input, TagAttribute attribute, SourceLocation at) {
            var signature = attribute.deferredMethod();
            if (signature == null) return null;
            String expected = classes.expressionType(signature.returnType(), at);
            var method = new DeferredMethodSignature(expected, signature.parameters().stream().map(type -> classes.expressionType(type, at)).toList());
            if (!input.hash()) validateMethodLiteral(input.literal(), expected, at);
            return method;
        }
        private String deferredContext(ExpressionSpec spec, int index) {
            if (spec.literal()) return "";
            if (expressionFunctions.get(index).isEmpty()) return "pageContext.getELContext(), ";
            return FUNCTION_CONTEXT + index + "(pageContext.getELContext()), ";
        }
        private static String classLiterals(List<String> types) {
            return types.stream().map(type -> type + CLASS_SUFFIX).collect(java.util.stream.Collectors.joining(","));
        }
        private String dynamicAttributeValue(String raw, SourceLocation at) {
            var input = attributeInput(raw, at);
            if (input.hash()) return deferredAttribute(input, DYNAMIC_ATTRIBUTE, at);
            return value(raw, at, false);
        }
        private String dynamicAttributeValue(List<PageNode> content, SourceLocation at, String handler) {
            if (hasDeferredExpression(content))
                return deferredAttribute(deferredInput(content, at), DYNAMIC_ATTRIBUTE, at);
            return capture(content, at, handler);
        }
        private boolean hasDeferredExpression(List<PageNode> content) {
            for (var node : content) {
                if (node instanceof PageNode.Expression(var _, var deferred, var _) && deferred) return true;
                if (node instanceof PageNode.Element element && action(element, TEXT_ACTION) && hasDeferredExpression(element.children())) return true;
            }
            return false;
        }
        private void methodScope(JavaSource source) {
            source.add("""
                    private static final class _jspxScopedMethodExpression extends jakarta.el.MethodExpression {
                    private static final long serialVersionUID=1L; private final Class<?> owner; private final jakarta.el.MethodExpression delegate;
                    _jspxScopedMethodExpression(Class<?> owner, jakarta.el.MethodExpression delegate) { this.owner=owner; this.delegate=delegate; }
                    private <T> T scoped(java.util.function.Supplier<T> operation) { var thread=Thread.currentThread(); var previous=thread.getContextClassLoader();
                    try { var loader=owner.getClassLoader(); if(loader!=null) thread.setContextClassLoader(loader); return operation.get(); } finally { thread.setContextClassLoader(previous); } }
                    public Object invoke(jakarta.el.ELContext context,Object[] parameters) { return scoped(()->delegate.invoke(context,parameters)); }
                    public jakarta.el.MethodInfo getMethodInfo(jakarta.el.ELContext context) { return scoped(()->delegate.getMethodInfo(context)); }
                    public jakarta.el.MethodReference getMethodReference(jakarta.el.ELContext context) { return scoped(()->delegate.getMethodReference(context)); }
                    public String getExpressionString() { return delegate.getExpressionString(); }
                    public boolean isLiteralText() { return delegate.isLiteralText(); }
                    public boolean isParametersProvided() { return delegate.isParametersProvided(); }
                    public boolean equals(Object other) { return other instanceof _jspxScopedMethodExpression expression && owner==expression.owner && delegate.equals(expression.delegate); }
                    public int hashCode() { return java.util.Objects.hash(owner,delegate); }
                    }
                    """);
        }
        private void validateMethodLiteral(String literal, String type, SourceLocation at) {
            if (type.equals("void")) throw error(at, "Literal deferred method cannot return void");
            if (!LITERAL_METHOD_RETURN_TYPES.contains(type))
                throw error(at, "Literal deferred method return conversion is unsupported: " + type);
            if (literal.isEmpty()) return;
            try {
                switch (type) {
                    case "java.math.BigInteger" -> new java.math.BigInteger(literal);
                    case "java.math.BigDecimal" -> new java.math.BigDecimal(literal);
                    default -> parseNumericLiteral(literal, type);
                }
            } catch (NumberFormatException _) { throw error(at, "Invalid literal for deferred method return type " + type); }
        }
        private String literalValue(String literal, TagAttribute attribute, SourceLocation at) {
            String type = attribute.type();
            try {
                if (!literal.isEmpty()) parseNumericLiteral(literal, type);
            } catch (IllegalArgumentException _) { throw error(at, "Invalid literal for " + type + " attribute " + attribute.name()); }
            return stringConversion(JavaSource.quote(literal), type, at);
        }
        /** Checks the syntax of a literal for a primitive or boxed numeric type; other types have nothing to check. */
        private static void parseNumericLiteral(String literal, String type) {
            switch (type) {
                case BYTE, JAVA_BYTE -> Byte.valueOf(literal);
                case SHORT, JAVA_SHORT -> Short.valueOf(literal);
                case INT, JAVA_INTEGER -> Integer.valueOf(literal);
                case LONG, JAVA_LONG -> Long.valueOf(literal);
                case FLOAT, JAVA_FLOAT -> Float.valueOf(literal);
                case DOUBLE, JAVA_DOUBLE -> Double.valueOf(literal);
                default -> {
                    // Not a numeric type: boolean, character, string and object literals need no syntax check.
                }
            }
        }
        private String stringConversion(String expression, String type, SourceLocation at) {
            String boxed = switch (type) {
                case BYTE -> JAVA_BYTE;
                case SHORT -> JAVA_SHORT;
                case INT -> JAVA_INTEGER;
                case LONG -> JAVA_LONG;
                case FLOAT -> JAVA_FLOAT;
                case DOUBLE -> JAVA_DOUBLE;
                case BOOLEAN -> JAVA_BOOLEAN;
                default -> type;
            };
            if (type.equals(JAVA_STRING) || type.equals(JAVA_OBJECT)) return expression;
            if (type.equals("char") || type.equals(JAVA_CHARACTER)) return "(" + expression + ".isEmpty() ? '\\0' : " + expression + ".charAt(0))";
            if (!CONVERTIBLE_BOXED_TYPES.contains(boxed))
                throw error(at, "Literal coercion is not supported for tag attribute type " + type);
            return boxed + ".valueOf(" + (boxed.equals(JAVA_BOOLEAN) ? expression : expression + ".isEmpty() ? \"0\" : " + expression) + ")";
        }
        private boolean namespaceAttribute(String name) { return name.equals("xmlns") || name.startsWith("xmlns:"); }

        private void dynamicElement(PageNode.Element node) {
            allowed(node, Set.of("name"));
            String variable = "_jspxElement" + serial++;
            body.add(STRING_DECLARATION + variable + " = _jspxXmlName(_jspxText(" + value(required(node, "name"), node.location(), false) + CLOSE_CALL, node.location());
            body.add("out.write(\"<\" + " + variable + ");\n", node.location());
            var content = new ArrayList<PageNode>();
            var names = new HashSet<String>();
            boolean hasBody = false;
            for (var child : node.children()) {
                if (action(child, ATTRIBUTE_ACTION)) {
                    if (hasBody) throw error(child.location(), "jsp:attribute must precede the element body");
                    dynamicElementAttribute((PageNode.Element) child, names);
                } else if (hasBody || !blankText(child)) {
                    // Blank text between named attributes is formatting, not element body content.
                    hasBody = true;
                    dynamicElementBody(child, content);
                }
            }
            if (content.isEmpty()) body.add("out.write(\"/>\");\n", node.location());
            else {
                write(">", node.location());
                emit(content);
                body.add("out.write(\"</\" + " + variable + " + \">\");\n", node.location());
            }
        }
        private static boolean blankText(PageNode node) {
            return node instanceof PageNode.Text(var value, var _) && value.isBlank();
        }
        private void dynamicElementAttribute(PageNode.Element attribute, Set<String> names) {
            allowed(attribute, Set.of("name", "trim"));
            String name = required(attribute, "name");
            if (!name.matches("[\\p{L}_][\\p{L}\\p{N}\\p{M}_.:-]*") || !names.add(name))
                throw error(attribute.location(), "Invalid or duplicate element attribute: " + name);
            String captured = "_jspxAttribute" + serial++;
            String writer = "_jspxBody" + serial++;
            body.add(STRING_DECLARATION + captured + ";\nvar " + writer + " = pageContext.pushBody();\ntry { out = " + writer + ";\n", attribute.location());
            emit(trimmedAttributeContent(attribute));
            body.add(captured + " = " + writer + ".getString();\n} finally { out = pageContext.popBody(); }\n", attribute.location());
            body.add(OUT_WRITE + JavaSource.quote(" " + name + "=\"") + " + _jspxAttribute(" + captured + ") + \"\\\"\");\n", attribute.location());
        }
        private List<PageNode> trimmedAttributeContent(PageNode.Element attribute) {
            var children = new ArrayList<>(attribute.children());
            if (!boolValue(attribute.attributes().getOrDefault("trim", TRUE), attribute.location()) || children.isEmpty()) return children;
            if (children.getFirst() instanceof PageNode.Text(var value, var location)) {
                int start = firstNonSpace(value);
                children.set(0, new PageNode.Text(value.substring(start), location.advance(value.substring(0, start))));
            }
            if (children.getLast() instanceof PageNode.Text(var value, var location))
                children.set(children.size() - 1, new PageNode.Text(value.substring(0, lastNonSpaceEnd(value)), location));
            return children;
        }
        private void dynamicElementBody(PageNode child, List<PageNode> content) {
            if (action(child, BODY_ACTION)) {
                var wrapper = (PageNode.Element) child;
                allowed(wrapper, Set.of());
                content.addAll(wrapper.children());
            } else content.add(child);
        }

        private void dispatch(PageNode.Element node, boolean forward) {
            allowed(node, forward ? Set.of("page") : Set.of("page", "flush"));
            String variable = "_jspxPath" + serial++;
            body.add(STRING_DECLARATION + variable + " = _jspxText(" + value(required(node, "page"), node.location(), false) + ");\n", node.location());
            for (var child : node.children()) {
                if (blankText(child)) continue;
                if (!(child instanceof PageNode.Element param) || !param.namespace().equals(PageParser.JSP_NAMESPACE)
                        || !param.name().endsWith(":param")) throw error(child.location(), "Dispatch body accepts only jsp:param");
                dispatchParameter(param, variable);
            }
            String exit = fragmentDepth > 0 ? "throw new jakarta.servlet.jsp.SkipPageException();" : "return;";
            if (forward) body.add("pageContext.forward(" + variable + "); _jspxForwarded[0] = true; if (true) "
                    + exit + "\n", node.location());
            else body.add("pageContext.include(" + variable + ", " + boolValue(node.attributes().getOrDefault("flush", FALSE), node.location()) + ");\n"
                    + "if (pageContext instanceof io.vidocq.ibarra.spi.PageDispatchContext _jspxDispatch && _jspxDispatch.isForwarded()) { _jspxForwarded[0] = true; "
                    + exit + " }\n", node.location());
        }
        private void dispatchParameter(PageNode.Element param, String variable) {
            TagInvocation invocation = tagInvocation(param);
            allowed(param, Set.of("name", VALUE));
            String name = dispatchParameterName(param, invocation);
            String parameterValue;
            if (param.attributes().containsKey(VALUE)) {
                parameterValue = value(param.attributes().get(VALUE), param.location(), false);
            } else {
                PageNode.Element namedValue = invocation.attributes().get(VALUE);
                if (namedValue == null) throw error(param.location(), "jsp:param requires value");
                parameterValue = capture(namedContent(namedValue), namedValue.location(), parentTag);
            }
            body.add(variable + " = _jspxParameter(" + variable + ", " + JavaSource.quote(name) + ", "
                    + parameterValue + ");\n", param.location());
        }
        private String dispatchParameterName(PageNode.Element param, TagInvocation invocation) {
            String name = param.attributes().get("name");
            PageNode.Element namedName = invocation.attributes().get("name");
            if (name == null && namedName != null) name = templateLiteral(namedContent(namedName));
            if (name == null || name.isBlank()) throw error(param.location(), "jsp:param requires name");
            if (name.contains("${") || name.contains("<%=")) throw error(param.location(), "Parameter name must be literal");
            return name;
        }

        private void bean(PageNode.Element node) {
            allowed(node, Set.of("id", "class", "type", "scope", "beanName"));
            String id = required(node, "id");
            if (!SourceVersion.isIdentifier(id) || SourceVersion.isKeyword(id)) throw error(node.location(), "Invalid bean id");
            String implementation = node.attributes().get("class");
            String beanName = node.attributes().get("beanName");
            String type = declaredBeanType(node, implementation, beanName);
            if (beans.putIfAbsent(id, type) != null) throw error(node.location(), "Duplicate bean id: " + id);
            int scope = scope(node.attributes().getOrDefault("scope", "page"), node.location());
            if (implementation != null) classes.constructor(implementation, node.location());
            String instantiation = implementation == null ? null : "new " + implementation + "()";
            if (beanName != null) instantiation = beanNameInstantiation(node, type, beanName);
            if (scope == 3 || scope == 4) sharedScopeBean(node, id, type, scope, instantiation);
            else localScopeBean(node, id, type, scope, instantiation);
        }
        private String declaredBeanType(PageNode.Element node, String implementation, String beanName) {
            if (implementation != null && beanName != null)
                throw error(node.location(), "jsp:useBean cannot specify both class and beanName");
            String type = node.attributes().getOrDefault("type", implementation);
            if (type == null || !SourceVersion.isName(type) || (implementation != null && !SourceVersion.isName(implementation)))
                throw error(node.location(), "Bean requires a Java type");
            return type;
        }
        private String beanNameInstantiation(PageNode.Element node, String type, String beanName) {
            if (beanName.isBlank()) throw error(node.location(), "jsp:useBean beanName must not be empty");
            if (directBeanConstructor(beanName, node.location())) return "(" + type + ") new " + beanName + "()";
            needsBeanNameSupport = true;
            return "(" + type + ") _jspxInstantiateBean(getClass().getClassLoader(), java.lang.String.valueOf("
                    + value(beanName, node.location(), false) + "))";
        }
        private boolean directBeanConstructor(String beanName, SourceLocation at) {
            if (beanName.contains("${") || beanName.contains("<%=") || !SourceVersion.isName(beanName)) return false;
            try {
                classes.constructor(beanName, at);
                return true;
            } catch (PageParseException _) {
                // A missing class name can identify a serialized bean resource instead.
                return false;
            }
        }
        private void sharedScopeBean(PageNode.Element node, String id, String type, int scope, String instantiation) {
            String suffix = Integer.toString(serial++);
            String lock = "_jspxBeanLock" + suffix;
            String created = "_jspxBeanCreated" + suffix;
            body.add(type + " " + id + ";\n"
                    + (instantiation == null ? "" : "boolean " + created + " = false;\n")
                    + "java.util.concurrent.locks.Lock " + lock + " = ((io.vidocq.ibarra.spi.BeanScopeContext) pageContext).beanScopeLock(" + scope + ");\n"
                    + lock + ".lock();\ntry {\n"
                    + id + " = (" + type + GET_ATTRIBUTE + JavaSource.quote(id) + ", " + scope + ");\n"
                    + "if (" + id + " == null) {\n", node.location());
            if (instantiation == null) body.add(absentBean(id), node.location());
            else body.add(id + " = " + instantiation + ";\npageContext.setAttribute(" + JavaSource.quote(id) + ", " + id + ", " + scope + ");\n"
                    + created + " = true;\n", node.location());
            body.add("}\n} finally { " + lock + ".unlock(); }\n");
            if (instantiation != null) {
                body.add("if (" + created + ") {\n");
                emit(node.children());
                body.add("}\n");
            }
        }
        private void localScopeBean(PageNode.Element node, String id, String type, int scope, String instantiation) {
            body.add(type + " " + id + " = (" + type + GET_ATTRIBUTE + JavaSource.quote(id) + ", " + scope + ");\nif (" + id + " == null) {\n", node.location());
            if (instantiation == null) body.add(absentBean(id), node.location());
            else {
                body.add(id + " = " + instantiation + ";\npageContext.setAttribute(" + JavaSource.quote(id) + ", " + id + ", " + scope + ");\n", node.location());
                emit(node.children());
            }
            body.add("}\n");
        }
        private static String absentBean(String id) {
            return "throw new java.lang.InstantiationException(\"Required bean is absent: " + id + "\");\n";
        }
        private String beanType(PageNode.Element node) {
            String name = required(node, "name");
            var type = beans.get(name);
            if (type == null) throw error(node.location(), "Bean must be declared before property access: " + name);
            return type;
        }
        private void getProperty(PageNode.Element node) {
            allowed(node, Set.of("name", PROPERTY));
            String type = beanType(node);
            var accessor = classes.getter(type, required(node, PROPERTY), node.location());
            body.add("out.print(((" + type + ") _jspxBean(pageContext, " + JavaSource.quote(node.attributes().get("name")) + "))." + accessor.name() + "());\n", node.location());
        }
        private void setProperty(PageNode.Element node) {
            allowed(node, Set.of("name", PROPERTY, PARAM, VALUE));
            String property = required(node, PROPERTY);
            if (property.equals("*")) {
                wildcardSetProperty(node);
                return;
            }
            var setter = classes.setter(beanType(node), property, node.location());
            String supplied = suppliedPropertyValue(node, setter, property);
            if (node.attributes().containsKey(PARAM) && node.attributes().containsKey(VALUE))
                throw error(node.location(), "setProperty cannot combine value and param");
            String temporary = "_jspxValue" + serial++;
            body.add(OBJECT + temporary + " = " + supplied + ";\n", node.location());
            boolean fromRequest = !node.attributes().containsKey(VALUE);
            if (fromRequest) body.add("if (" + temporary + " != null && !\"\".equals(" + temporary + ")) {\n");
            String converted = convertedPropertyValue(node, setter, property, temporary);
            body.add("((" + beanType(node) + ") _jspxBean(pageContext, " + JavaSource.quote(node.attributes().get("name")) + "))."
                    + setter.name() + "(" + converted + ");\n", node.location());
            if (fromRequest) body.add("}\n");
        }
        private void wildcardSetProperty(PageNode.Element node) {
            if (node.attributes().containsKey(PARAM) || node.attributes().containsKey(VALUE))
                throw error(node.location(), "Wildcard setProperty accepts neither param nor value");
            for (String name : classes.writableProperties(beanType(node), node.location())) {
                var attributes = new LinkedHashMap<>(node.attributes());
                attributes.put(PROPERTY, name);
                setProperty(new PageNode.Element(node.name(), node.namespace(), attributes, List.of(), true, node.location()));
            }
        }
        private String suppliedPropertyValue(PageNode.Element node, BeanClasses.Accessor setter, String property) {
            if (node.attributes().containsKey(VALUE)) return value(node.attributes().get(VALUE), node.location(), false);
            String parameter = node.attributes().getOrDefault(PARAM, property);
            String getter = setter.type().endsWith("[]") ? "getParameterValues" : "getParameter";
            return "request." + getter + "(" + JavaSource.quote(parameter) + ")";
        }
        private String convertedPropertyValue(PageNode.Element node, BeanClasses.Accessor setter, String property, String temporary) {
            String raw = node.attributes().get(VALUE);
            boolean scriptlet = raw != null && raw.startsWith("<%=") && raw.endsWith("%>");
            boolean el = raw != null && !scriptlet && new PageNormalizer(sourceSyntaxes.getOrDefault(node.location().source(), Page.Syntax.STANDARD),
                    parserOptions(), Map.of())
                    .normalizeStructured(List.of(new PageNode.Text(raw, node.location()))).stream().anyMatch(PageNode.Expression.class::isInstance);
            String editor = scriptlet || el ? null : classes.propertyEditor(beanType(node), property, node.location());
            if (editor != null) return editorConversion(node, setter, editor, temporary);
            if (scriptlet) return "(" + setter.type() + ") " + temporary;
            needsFactory = true;
            return "_jspxExpressionFactory.coerceToType(" + temporary + ", " + setter.type() + CLASS_SUFFIX + ")";
        }
        private String editorConversion(PageNode.Element node, BeanClasses.Accessor setter, String editor, String temporary) {
            if (setter.type().endsWith("[]")) throw error(node.location(), "Array property editors are not supported at build time");
            String converted = "_jspxConverted" + serial++;
            String local = "_jspxEditor" + serial++;
            body.add(OBJECT + converted + ";\ntry {\njava.beans.PropertyEditor " + local + NEW + editor + NO_ARGUMENTS
                    + local + ".setAsText((String) " + temporary + ");\n" + converted + " = " + local + ".getValue();\n"
                    + "} catch (Exception _jspxConversionFailure) {\nif (!\"\".equals(" + temporary + ")) throw _jspxConversionFailure;\n"
                    + converted + " = null;\n}\n", node.location());
            return "(" + setter.type() + ") " + converted;
        }

        private String expression(String code, SourceLocation at) {
            int index = registerExpression(code, at, new ExpressionSpec(JAVA_OBJECT, null, false, false), null);
            return "_jspxExpression" + index + ".getValue(pageContext.getELContext())";
        }
        private int registerExpression(String code, SourceLocation at, ExpressionSpec spec, Map<String, BeanClasses.Function> supplied) {
            var functions = supplied == null ? functionBindings(code, at) : supplied;
            int index = expressions.size();
            expressions.add(code);
            expressionLocations.add(at);
            expressionFunctions.add(Map.copyOf(functions));
            expressionSpecs.add(spec);
            return index;
        }
        private Map<String, BeanClasses.Function> functionBindings(String code, SourceLocation at) {
            var functions = new TreeMap<String, BeanClasses.Function>();
            List<io.vidocq.peano.compiler.FunctionReference> references;
            try { references = io.vidocq.peano.compiler.ExpressionCompiler.functions(code); }
            catch (jakarta.el.ELException invalid) { throw error(at, "Cannot compile EL: " + invalid.getMessage()); }
            for (var reference : references) {
                if (startupElAllowed) throw error(at, "TLD functions require build-time compilation, not interpreter preview");
                functions.put(reference.prefix() + ":" + reference.name(), function(reference, at));
            }
            return Map.copyOf(functions);
        }
        private BeanClasses.Function function(io.vidocq.peano.compiler.FunctionReference reference, SourceLocation at) {
            var namespace = namespaces.get(reference.prefix());
            var directive = taglibBindings.get(reference.prefix());
            String uri;
            SourceLocation origin;
            if (namespace != null) {
                uri = namespace.uri();
                origin = namespace.location();
            } else if (directive != null) {
                uri = directive.attributes().get("uri");
                origin = directive.location();
            } else {
                uri = null;
                origin = at;
            }
            if (uri == null) throw error(at, "Unknown function prefix: " + reference.prefix());
            var library = resolvedLibraries.resolve(uri, origin, namespace == null && directive != null);
            if (library == null) throw error(at, "No build-time function library for " + uri);
            var declaration = library.functions().get(reference.name());
            if (declaration == null) throw error(at, "Unknown TLD function: " + reference.prefix() + ":" + reference.name());
            var function = classes.function(declaration, at);
            if (reference.argumentCount() != function.parameters().size()) throw error(at, "Function argument count does not match " + declaration.signature());
            return function;
        }
        private void functionDefinitions(JavaSource source, int index, Map<String, BeanClasses.Function> functions) {
            var names = new TreeMap<String, String>();
            int ordinal = 0;
            for (var entry : new TreeMap<>(functions).entrySet()) {
                var function = entry.getValue();
                String name = "_jspxFunction" + index + "_" + ordinal++;
                names.put(entry.getKey(), name);
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
                        + classLiterals(function.parameters())
                        + "}, false, true, new java.lang.annotation.Annotation[0]), new " + names.get(entry.getKey()) + "());\n");
            }
            source.add("default -> null; }; }\n}; }\n}; }\n");
        }
        private String value(String raw, SourceLocation at, boolean xmlLiteral) {
            if (sourceSyntaxes.getOrDefault(at.source(), Page.Syntax.STANDARD) == Page.Syntax.XML
                    && raw.startsWith("%=") && raw.endsWith("%"))
                return "(" + raw.substring(2, raw.length() - 1) + ")";
            if (raw.startsWith("<%=") && raw.endsWith("%>")) {
                return "(" + raw.substring(3, raw.length() - 2) + ")";
            }
            var temporary = new PageNode.Text(raw, at);
            var parser = new PageNormalizer(sourceSyntaxes.getOrDefault(at.source(), Page.Syntax.STANDARD), parserOptions(), Map.of());
            var pieces = parser.normalizeStructured(List.of(temporary));
            if (pieces.size() == 1 && pieces.getFirst() instanceof PageNode.Expression el) return immediateExpression(el, at);
            var parts = new ArrayList<String>();
            for (var piece : pieces) {
                if (piece instanceof PageNode.Text(var text, var _)) parts.add(JavaSource.quote(xmlLiteral ? escapeAttribute(text) : text));
                else if (piece instanceof PageNode.Expression el) parts.add("_jspxText(" + immediateExpression(el, at) + ")");
            }
            return parts.isEmpty() ? "\"\"" : String.join(" + ", parts);
        }
        private String immediateExpression(PageNode.Expression el, SourceLocation at) {
            if (el.deferred()) throw error(at, "Deferred action attributes require tag-handler support");
            return expression("${" + el.code() + "}", at);
        }

        private void write(String value, SourceLocation at) {
            int start = 0;
            while (start < value.length()) {
                int end = Math.min(value.length(), start + 8192);
                if (end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))) end--;
                body.add(OUT_WRITE + JavaSource.quote(value.substring(start, end)) + ");\n", at);
                start = end;
            }
        }
        private SourceLocation settingLocation(String name) { return settingLocations.getOrDefault(name, new SourceLocation(page.source(), 1, 1)); }
        private boolean bool(String name, boolean fallback) { return boolValue(settings.getOrDefault(name, String.valueOf(fallback)), settingLocation(name)); }
        private boolean boolValue(String value, SourceLocation at) {
            if (!value.equals(TRUE) && !value.equals(FALSE)) throw error(at, "Expected true or false: " + value);
            return Boolean.parseBoolean(value);
        }
        private String contentType() {
            String contentType = settings.getOrDefault(CONTENT_TYPE, page.syntax() == Page.Syntax.XML ? "text/xml" : "text/html");
            if (SourceDecoder.contentCharset(contentType) == null) {
                String encoding;
                if (page.syntax() == Page.Syntax.XML) encoding = "UTF-8";
                else encoding = responseEncoding != null ? responseEncoding : settings.get(PAGE_ENCODING);
                if (encoding != null) contentType += ";charset=" + Charset.forName(encoding).name();
            }
            return contentType;
        }
        private int buffer() {
            String value = settings.getOrDefault(BUFFER, "8kb");
            if (value.equals("none")) {
                if (!bool(AUTO_FLUSH, true)) throw error(settingLocation(BUFFER), "Unbuffered pages require autoFlush");
                return 0;
            }
            return TranslationUnit.bufferCapacity(value, settingLocation(BUFFER));
        }
        private int scope(String scope, SourceLocation at) {
            if (scope.equals(SESSION) && !bool(SESSION, true)) throw error(at, "Session-scoped beans require session participation");
            return switch (scope) { case "page" -> 1; case "request" -> 2; case SESSION -> 3; case "application" -> 4;
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
