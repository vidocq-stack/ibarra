# Ibarra — Design plan

> Status: **in design**. This document is the design of record for Ibarra, the
> Jakarta Pages 4.0 (formerly JavaServer Pages) implementation of the Vidocq
> ecosystem. Nothing below is implemented yet beyond the scaffold (milestone
> I0). Decisions marked **(to validate)** need the maintainer's sign-off before
> the milestone that depends on them starts.

## Vision

Ibarra translates Jakarta Pages (`.jsp`, `.jspx`, `.tag`, `.tagx`) into servlets
**at build time**, and runs them inside Foy (Jakarta Servlet 6.1) with Peano
(Jakarta Expression Language 6.0) evaluating `${}` / `#{}`:

- **zero third-party dependency** at run time: `jakarta.servlet.jsp` (which
  brings `jakarta.servlet` and `jakarta.el`) only — no Jasper, no Eclipse JDT;
- **strict Java Modules**: one `module-info.java` per artefact, no `opens`;
- **static code generation first**: pages, tag files and tag-handler wiring are
  generated and compiled during the application build; translating a page in a
  running server is a documented, degraded development mode;
- **container-neutral core**: the page runtime talks to `jakarta.servlet` only;
  the Foy binding lives in a dedicated module behind a JSP-engine SPI that Foy
  will expose.

The name: Joaquín Ibarra y Marín (1725–1785), royal printer in Madrid, famous for
the 1780 Real Academia edition of *Don Quixote* — a nod to `cervantes`, the
MicroProfile JWT brick. Composing the pages from a model once, then printing
them on demand, is exactly what a precompiled page engine does.

```
   build time (application build)                      run time (Foy)
 ┌──────────────────────────────────┐          ┌───────────────────────────────┐
 │ ibarra-maven-plugin              │          │ ibarra-foy                    │
 │  scan src/main/webapp, TLDs      │          │  implements Foy's JSP SPI     │
 │  ibarra-compiler:                │          │  *.jsp mapping, addJspFile,   │
 │   parse → page model → Java src  │          │  jsp-config, TLD listeners    │
 │   → javax.tools (build JVM)      │          └──────────────┬────────────────┘
 │  peano-compiler: page EL → class │                         │
 │  emits page index (ServiceLoader)│ ──────────────────────► │
 └──────────────────────────────────┘   generated classes     ▼
                                        + page index  ┌───────────────────────────┐
                                                      │ ibarra-core               │
                                                      │  JspFactory, PageContext, │
                                                      │  JspWriter, tag lifecycle,│
                                                      │  JspApplicationContext,   │
                                                      │  Pages EL resolver chain  │
                                                      └─────────────┬─────────────┘
                                                                    │ jakarta.el
                                                                    ▼
                                                                  Peano
```

## Specification scope (Jakarta Pages 4.0)

In scope — what the 4.0 specification and its TCK require:

| Area | Content |
|---|---|
| Syntax | Standard syntax and XML document syntax (`.jspx`, `jsp:root`), directives (`page`, `include`, `taglib`, `tag`, `attribute`, `variable`), scripting elements (declarations, scriptlets, expressions), comments, template text and escaping, EL `${}` / `#{}` with `isELIgnored` / `deferredSyntaxAllowedAsLiteral` |
| Translation | Page → Java source of an `HttpJspPage` servlet: `_jspService`, buffering and `autoFlush`, `contentType` / `pageEncoding` detection (BOM, XML prolog, property groups), `errorPage` / `isErrorPage`, `session`, `import`, `extends`, `trimDirectiveWhitespaces`, translation-time includes, SMAP / JSR-045 debug info for stack traces (to validate) |
| Standard actions | `jsp:include`, `jsp:forward`, `jsp:param`, `jsp:useBean` / `getProperty` / `setProperty`, `jsp:element`, `jsp:attribute`, `jsp:body`, `jsp:invoke`, `jsp:doBody`, `jsp:text`, `jsp:output`. `jsp:plugin` / `jsp:params` / `jsp:fallback` were removed from Pages 4.0 (to verify) |
| Runtime API | `JspFactory`, `JspEngineInfo`, `PageContext` / `JspContext` (four scopes, `findAttribute`, `include`, `forward`, `handlePageException`, `pushBody`/`popBody`), `JspWriter` / `BodyContent`, `JspApplicationContext` (`addELResolver`, `getExpressionFactory`, `addELContextListener`), `ErrorData`, `HttpJspPage` |
| EL integration | The Pages resolver chain (implicit objects, imports, scoped attributes, resolvers added by the application, the standard Map/ResourceBundle/List/Array/Record/Optional/Bean resolvers), `ELContext` per page, `errorOnELNotFound` behaviour (new in 4.0, to verify), deferred expressions passed to tag attributes as `ValueExpression` / `MethodExpression` |
| Custom tags | Classic handlers (`Tag`, `IterationTag`, `BodyTag`, `TryCatchFinally`), simple handlers (`SimpleTag`, `JspFragment`), `DynamicAttributes`, `TagExtraInfo` / `TagLibraryValidator`, scripting variables (`AT_BEGIN`, `NESTED`, `AT_END`) |
| Tag files | `.tag` / `.tagx` under `WEB-INF/tags` and in JARs, implicit TLDs, `jsp:invoke` / `jsp:doBody`, fragments |
| TLDs | Parsing `WEB-INF/**/*.tld` and `META-INF/**/*.tld` of libraries (module resources under Java Modules), URI → TLD map, TLD-declared listeners (handed to the container) |
| Configuration | `jsp-config` from `web.xml` / fragments: `taglib`, `jsp-property-group` (url-pattern, el-ignored, page-encoding, scripting-invalid, is-xml, include-prelude / include-coda, deferred-syntax-allowed-as-literal, trim-directive-whitespaces, default-content-type, buffer, error-on-undeclared-namespace, error-on-el-not-found) |
| Dispatch | `jsp-file` servlets, `ServletContext.addJspFile`, `*.jsp` / `*.jspx` default mapping, welcome files |

Out of scope: **JSTL** (Jakarta Standard Tag Library — a separate specification
and, if ever wanted, a separate brick), Jakarta Faces, and the `jsp:plugin`
family. `jsp:useBean beanName="..."` relies on `java.beans.Beans.instantiate`
(serialized beans, `java.desktop`); it is an **open question** whether to support
it at all.

## Codegen doctrine applied

### Pages are compiled during the build

- **`ibarra-compiler`** holds the parser, the page model and the generator. It is
  a plain library used by the Maven plugin (build JVM) and, in development mode
  only, by the runtime.
- **Generator output: Java source + `javax.tools` (to validate).** Scriptlets and
  declarations *are* Java source fragments, so a Java compiler is needed for any
  page that uses them; generating Java source keeps one pipeline for all pages
  and gives readable output for debugging. **Alternative kept open:** emit
  scriptless pages (the large majority in modern code, and every tag file
  without scripting) directly with the **Class-File API** (JEP 484), skipping
  `javac` entirely — a later optimisation once the source generator is
  TCK-green.
- **`ibarra-maven-plugin`** (goal `precompile`, phase `process-classes`)
  translates every page and tag file of `src/main/webapp`, compiles the result
  into the application module, and emits a **page index**: a generated class
  `provides io.vidocq.ibarra.spi.PageIndex` mapping each page path to a
  `Supplier<HttpJspPage>` that calls `new` directly. At run time no page class is
  ever loaded by name.
- **EL in pages:** the plugin calls `peano-compiler` so that each page's
  expressions are compiled classes held in `static final` fields (resolution
  through the `ELResolver` chain stays dynamic, as the spec requires).
- **Tag handlers:** the TLD is read at translation time, so attribute setters
  are known: generated code calls `new FooTag()` and `setBar(...)` directly — no
  reflection, no introspection. No tag-handler pooling (to validate): with
  virtual threads and modern allocators, reuse adds state bugs for no measured
  gain; any decision gets a `BENCH.md` entry.
- **`jsp:useBean` / `getProperty` / `setProperty`:** the bean class is known at
  translation time — generated `new` and direct getter/setter calls; `property="*"`
  becomes a generated parameter-to-setter table.

### Degraded mode: runtime translation

When a page has no precompiled class (development, or a WAR deployed without the
plugin), `ibarra-foy` can translate it on first request through
`ibarra-compiler` + `javax.tools`. This requires the `java.compiler` module (a
full JDK or a jlink image including it), is **off by default in production**,
logs a warning per page, and can be forbidden (`ibarra.runtime-translation=deny`,
name to validate). It is the same "documented degraded path" pattern as Peano's
reflective fallback and Vauban's runtime fallbacks.

## Java Modules layout

| Module (artefact) | Java module | Kind | Requires | Exports |
|---|---|---|---|---|
| `ibarra-api` | `io.vidocq.ibarra.api` | runtime | `transitive jakarta.servlet.jsp` | `io.vidocq.ibarra.spi` (generated-code SPI: `PageIndex`, page descriptor) |
| `ibarra-core` | `io.vidocq.ibarra.core` | runtime | `transitive jakarta.servlet.jsp`, `io.vidocq.ibarra.api` | nothing — installs its `JspFactory` with `JspFactory.setDefaultFactory` at deployment (the spec defines no service lookup for it), `uses PageIndex` |
| `ibarra-compiler` | `io.vidocq.ibarra.compiler` | build (+ dev mode) | `io.vidocq.ibarra.api`, `java.compiler` (static), `io.vidocq.peano.compiler` | `io.vidocq.ibarra.compiler` |
| `ibarra-maven-plugin` | (Maven plugin, class path) | build | `ibarra-compiler` | — |
| `ibarra-foy` | `io.vidocq.ibarra.foy` | runtime | `io.vidocq.ibarra.core`, Foy's SPI module | nothing — `provides` Foy's JSP-engine SPI |
| `ibarra-tck` | — | test, `-Ptck` | `ibarra-core` (+ Foy, Arquillian adapter later) | — |
| `ibarra-bench` | — | JMH, not deployed | — | — |

Today only `ibarra-core` (scaffold) and `ibarra-tck` (skeleton) exist. The
precompiler plugin is **declared in this design only**: the sibling bricks'
initial scaffolds (Erasmus's first commit) carried no plugin module — plugin
placeholders appeared with the first implementation milestone — so
`ibarra-maven-plugin` is created by milestone I4.

`ibarra-core` does **not** depend on Peano: it gets its `ExpressionFactory`
through `ExpressionFactory.newInstance()`, so any compliant EL implementation
works and Peano is simply the default one shipped by the Vidocq runtime. The
generated-accessor resolver and compiled expressions are added when Peano is
present (soft integration, `requires static`, to validate).

## Integration points

### Foy (Jakarta Servlet 6.1) — the JSP-engine SPI

Foy currently has stubs: `VidocqServletContext.addJspFile(...)` registers
nothing useful and `getJspConfigDescriptor()` returns `null`. Foy will expose an
SPI (to be designed **in Foy**; this is Ibarra's proposal):

```java
// in a Foy SPI module — sketch, names to be agreed with Foy
public interface JspEngine {
    /** Called once per web application, before servlets are initialised. */
    void deploy(ServletContext context, JspConfigDescriptor jspConfig);
    /** Servlet mapped by the container to *.jsp / *.jspx (servlet name "jsp"). */
    Servlet jspServlet();
    /** Backs ServletContext.addJspFile(name, jspFile) and <jsp-file> servlets. */
    Servlet servletFor(String jspFile);
    /** Listener class names declared in TLDs, registered by the container (Servlet 6.1, TLD listeners). */
    List<String> tldListeners();
}
```

- Foy parses `jsp-config` from `web.xml` / fragments into the standard
  `JspConfigDescriptor` and returns it from `getJspConfigDescriptor()`; Ibarra
  consumes it.
- Foy discovers the engine with `ServiceLoader` (`uses JspEngine`); with no
  engine present `*.jsp` stays unmapped and `addJspFile` keeps its current
  behaviour — Foy keeps working without Ibarra.
- `ibarra-foy` is the only Ibarra module that depends on Foy.

### Peano (Jakarta Expression Language 6.0)

Run time: standard `jakarta.el` API only (`ExpressionFactory.newInstance()`),
plus Peano's `GeneratedAccessorELResolver` placed before `BeanELResolver` in the
Pages chain. Build time: `peano-compiler` compiles each page's expressions.

### Vauban (CDI 4.1)

`@Named` beans in pages come from a future `vauban-el` module (CDI 4.1
`cdi-el-api`, `ELAwareBeanManager`) adding its resolver through
`JspApplicationContext.addELResolver` — not an Ibarra concern. Tag handlers and
page servlets are not CDI beans (the spec does not require injection into
them; to validate against the Pages TCK).

### Vidocq Runtime

An extension wiring Ibarra + Foy + Peano and running `ibarra-maven-plugin` from
the runtime's packaging goal, after I8.

## TCK plan

- Runner: `ibarra-tck`, in the reactor only under `-Ptck` (present now as a
  skeleton); the official suite is resolved only under `-Ptck,tck-official`.
- The Pages 4.0 TCK moved from the Platform TCK into the `jakartaee/pages`
  repository and was rewritten to **Arquillian + JUnit 5**. Distribution
  (checked 2026-10-10): **not on Maven Central** — it ships as
  `jakarta-pages-tck-4.0.0.zip` on `download.eclipse.org/jakartaee/pages/4.0/`,
  whose artefacts are installed into the local Maven repository, as for the
  Servlet TCK of `foy-tck`. Central only has the Platform-mode
  `jakarta.tck:pages-platform-tck` 11.0.x. The Maven coordinates are **to
  confirm** against the zip (placeholder `jakarta.tck:jakarta-pages-tck:4.0.0`
  in the POM), including a possible split like the Servlet TCK
  (`servlet-tck-runtime` + `servlet-tck-util`).
- The TCK deploys WARs that contain **JSP sources**, so it exercises translation
  at deployment time. Two ways to run it (**open question**): (a) the degraded
  runtime-translation mode, which must then be TCK-complete; (b) an Arquillian
  adapter that precompiles each WAR with `ibarra-compiler` before deploying it
  into Foy — closer to the production path. Recommended: (b), with (a) covered by
  a smaller smoke subset.
- Script `run-official-tck-pages-4.0.sh` (`smoke` / `all` / `-Dtest=`), modelled
  on `foy/run-official-tck-servlet6.1.sh`.
- Gate: once green, 100 % PASS is a merge gate; challenges go to a `TCK.md`.

## Milestones (TDD — test first, every milestone)

| # | Content | Done when |
|---|---|---|
| I0 | Scaffold: reactor, `ibarra-core` module, `ibarra-tck` skeleton, docs, this plan | `./mvnw -ntp install` green (this commit) |
| I1 | Runtime API: `JspFactory`, `PageContext`, `JspWriter` / `BodyContent` buffering, scopes, error pages — driven by hand-written `HttpJspPage` fixtures | unit tests against Foy-free servlet doubles |
| I2 | `ibarra-compiler` parser: standard + XML syntax, directives, scripting, template text, page model | parser corpus tests |
| I3 | Generator: Java source for pages, standard actions, EL via `jakarta.el` (Peano in tests), `jsp-config` property groups | compiled fixture pages render expected output |
| I4 | `ibarra-maven-plugin` precompile goal + page index; `ibarra-api` SPI | example webapp precompiled, no runtime translation |
| I5 | `ibarra-foy` + Foy's JSP-engine SPI (Foy-side PR), `*.jsp` mapping, `addJspFile`, `getJspConfigDescriptor`, TLD listeners | end-to-end test on Foy/Chappe |
| I6 | Custom tags: TLD parsing, classic + simple handlers, scripting variables, validators | |
| I7 | Tag files, fragments, `jsp:invoke` / `jsp:doBody`; Peano compiled expressions in pages | |
| I8 | Official TCK through `ibarra-tck` (Arquillian adapter on Foy) | 100 % PASS recorded in docs `tck` page |
| I9 | Degraded runtime translation polish, Class-File API path for scriptless pages, `ibarra-bench` (vs Jasper), Vidocq Runtime extension | numbers in `BENCH.md` only |

## Open questions

1. **Generator back end** — Java source + `javax.tools` for everything (simple,
   one path) vs Class-File API for scriptless pages from the start?
2. **TCK deployment model** — runtime translation vs precompiling Arquillian
   adapter (see "TCK plan").
3. **Foy SPI shape and module** — the `JspEngine` sketch above, which Foy module
   exports it, and who parses `jsp-config` (Foy, recommended).
4. **Peano coupling** — `requires static io.vidocq.peano.*` soft integration in
   `ibarra-core`, or keep the core strictly on `jakarta.el` and put the Peano
   accelerations in a separate `ibarra-peano` module?
5. **`jsp:useBean beanName`** (serialized beans via `java.beans.Beans`) — support,
   or document as unsupported (pulls `java.desktop`)?
6. **SMAP / JSR-045** line mapping for stack traces — needed for 1.0?
7. **TCK coordinates** — confirm on Maven Central.
8. **Remote repository and docs site** — `codefloe.com/Vidocq/ibarra` is assumed
   in the POM `<scm>`; the repository, the `vidocq-docs` playbook entry and the
   workspace `CLAUDE.md` listing are not created yet.
