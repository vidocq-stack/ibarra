# Ibarra — Design plan

> Status: **I1/I2 implemented, I3 in progress**. This document is the design of record for Ibarra, the
> Jakarta Pages 4.0 (formerly JavaServer Pages) implementation of the Vidocq
> ecosystem. The scaffold (I0) and initial runtime (I1) are implemented.
> JSP/JSPX parsing and an immutable page model are implemented (I2). Initial Java
> generation, compiled fixtures and build-time Peano EL exist (I3); remaining translation
> semantics and tags remain planned. Initial packaging/Foy binding and TLD
> listener discovery are implemented.
> Decisions marked **(to validate)** need the maintainer's sign-off before
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
- **Initial generator output: Java source + `javax.tools`.** Scriptlets and
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
  Literal property-specific BeanInfo editor descriptors are read from class files;
  generated String conversions call editor constructors and methods directly.
  Dynamic BeanInfo/PropertyEditorManager metadata, array editors and custom
  descriptor accessors remain unsupported. Editor-using application modules read
  `java.desktop`; the runtime core and compiler retain their existing descriptors.

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
| `ibarra-api` | `io.vidocq.ibarra.api` | runtime | `transitive jakarta.servlet.jsp` | `io.vidocq.ibarra.spi` (`PageOptions`, `PageDescriptor`, `ListenerDescriptor`, `PageIndex`) |
| `ibarra-core` | `io.vidocq.ibarra.core` | runtime | `transitive jakarta.servlet.jsp`, `io.vidocq.ibarra.api` | qualified binding API to `ibarra-foy` only; installs its container `JspFactory`, `uses PageIndex` |
| `ibarra-compiler` | `io.vidocq.ibarra.compiler` | build (+ dev mode planned) | `io.vidocq.ibarra.api`, `java.xml`, `static java.compiler`, `io.vidocq.peano.compiler` | `io.vidocq.ibarra.compiler` |
| `ibarra-maven-plugin` | (Maven plugin, class path) | build | `ibarra-compiler` | — |
| `ibarra-foy` | `io.vidocq.ibarra.foy` | runtime | `io.vidocq.ibarra.api`, `io.vidocq.ibarra.core`, Foy's SPI module | nothing — `provides` Foy's JSP-engine SPI |
| `ibarra-tck` | — | test, `-Ptck` | `ibarra-core` (+ Foy, Arquillian adapter later) | — |
| `ibarra-bench` | — | JMH, not deployed | — | — |

Today `ibarra-api`, `ibarra-core` (I1 runtime), `ibarra-compiler` (I2 parser/model,
initial I3/I4 generation and packaging), `ibarra-maven-plugin`, `ibarra-foy` and `ibarra-tck`
(skeleton) exist. The Maven plugin runs only in Maven's build-time classpath;
application pages and the runtime remain strict named Java modules.

`ibarra-core` does **not** depend on Peano: it gets its `ExpressionFactory`
through `ExpressionFactory.newInstance()`, so any compliant EL implementation
works and Peano is simply the default one shipped by the Vidocq runtime. The
provider's generated-accessor resolver participates through the standard
`ExpressionFactory.getStreamELResolver` hook. Compiled expression classes belong
to the application module, which explicitly requires `io.vidocq.peano.api`.
Core and binding acquire no Peano dependency.

## Integration points

### Foy (Jakarta Servlet 6.1) — the JSP-engine SPI

The initial binding uses an optional service exported by Foy API:

```java
public interface JspEngine {
    Application deploy(ServletContext context, ModuleLayer applicationLayer) throws ServletException;
    interface Application extends AutoCloseable {
        Servlet servlet();
        Servlet servletFor(String jspFile);
        default List<JspEngine.Listener> listeners() { return List.of(); }
        void close();
    }
}
```

- Foy parses `jsp-config` from `web.xml` / fragments into the standard
  `JspConfigDescriptor` and returns it from `getJspConfigDescriptor()`.
  Translation remains build-time; fragment-aware packaging is still pending.
- Foy discovers the engine with `ServiceLoader` (`uses JspEngine`); with no
  engine present `*.jsp` stays unmapped and `addJspFile` keeps its current
  behaviour — Foy keeps working without Ibarra.
- `ibarra-foy` is the only Ibarra module that depends on Foy.
- Page indexes belong to the supplied application layer, with parent providers
  excluded before instantiation. Extension pages initialize once; named JSP
  registrations receive fresh instances. Stop/drain HTTP before undeployment.
- TLD listener metadata is discovered/validated at build time; generated indexes
  expose direct constructor factories. Foy deduplicates classes and applies the
  restricted initialization context to TLD listeners. Initial I6 classic/simple
  handlers use direct construction/setters and generated body fragments.
  See the Antora
  `modules/foy-binding` reference for bootstrap ownership and test tooling.

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

- Runner: `ibarra-tck`, in the reactor only under `-Ptck`; its modular WAR
  adapter is implemented and regression-tested. The official suite is resolved
  only under `-Ptck,tck-official`.
- The Pages 4.0 TCK moved from the Platform TCK into the `jakartaee/pages`
  repository and was rewritten to **Arquillian + JUnit 5**. Distribution
  (checked 2026-10-10): **not on Maven Central** — it ships as
  `jakarta-pages-tck-4.0.0.zip` on `download.eclipse.org/jakartaee/pages/4.0/`,
  whose artefacts are installed into the local Maven repository, as for the
  Servlet TCK of `foy-tck`. Central only has the Platform-mode
  `jakarta.tck:pages-platform-tck` 11.0.x. Coordinates verified on 2026-10-12:
  `jakarta.tck:jakarta-pages-tck:4.0.0`, one jar, with the embedded upstream POM
  preserved. The archive omits its JavaTest 5.0 dependency; `prepare-tck.py`
  obtains that exact tool from an earlier Eclipse distribution, with SHA-256
  verification. It does not run or replace any Pages 4.0 tests with older tests.
- The TCK deploys WARs that contain **JSP sources**, so it exercises translation
  at deployment time. The implemented adapter precompiles each WAR with
  `ibarra-compiler` before launching Foy in a separate process. Neither compiler
  nor `java.compiler` is present in that runtime. Explicit deployment precompilation
  retains real per-page parse/generation/javac failures as compiled error servlets,
  allowing mixed positive/negative fixtures to deploy. Normal packaging still
  rejects page errors; global metadata/module failures remain fatal. Root JSP
  property groups now reach the compiler through a build-side descriptor adapter.
  Explicit root web.xml Servlet/filter/listener classes in WEB-INF/classes receive
  Class-File-generated Foy WebComponent providers in the same private application
  module. Ordinary legacy WAR libraries now assemble into that same module,
  preserving shared packages, original JAR resources and separate TLD metadata.
  Explicit modular/multi-release libraries, fragments, classpath services,
  annotations and initializer providers remain unsupported.
  Legacy private WAR bean properties receive Class-File-generated Peano accessor
  services, with module visibility checks and reflection denial preserved. Public
  java.base collections allocated by compiled WAR/page bytecode also receive
  method-only tables after page compilation, retaining existing module services
  and native property resolvers. Varargs/runtime-annotated method names receive
  explicit omission diagnostics. General methods/static members remain separate
  capabilities. The TEI fixture lifecycle
  constraint and remaining metadata design are recorded in
  [studies/tag-extra-info-aot.md](studies/tag-extra-info-aot.md).
  Degraded runtime translation is not used as a fallback.
- Script `run-official-tck-pages-4.0.sh` (`smoke` / `all` / `-Dtest=`), modelled
  on `foy/run-official-tck-servlet6.1.sh`.
- Gate: once green, 100 % PASS is a merge gate; challenges go to a `TCK.md`.

## Milestones (TDD — test first, every milestone)

| # | Content | Done when |
|---|---|---|
| I0 | Scaffold: reactor, `ibarra-core` module, `ibarra-tck` skeleton, docs, this plan | `./mvnw -ntp install` green (this commit) |
| I1 | Runtime API: `JspFactory`, `PageContext`, `JspWriter` / `BodyContent` buffering, scopes, error pages — driven by hand-written `HttpJspPage` fixtures | implemented: unit tests against Foy-free servlet doubles, clean install green |
| I2 | `ibarra-compiler` parser: standard + XML syntax, directives, scripting, template text, page model | implemented: 36 parser corpus tests, clean install green |
| I3 | Generator: Java source for pages, standard actions, compiled EL and `jsp-config` property groups | in progress: compiled EL/accessors/imports/functions, atomic shared beans and typed deferred tag attributes render; runtime import changes and broader validation remain pending |
| I4 | `ibarra-maven-plugin` precompile goal + page index; extend `ibarra-api` SPI | initial implementation: independent application JAR precompiled and rendered; web fragments and broader configuration pending |
| I5 | `ibarra-foy` + Foy's JSP-engine SPI (Foy-side PR), `*.jsp` mapping, `addJspFile`, `getJspConfigDescriptor`, TLD listeners | initial HTTP implementation with build-generated TLD listeners; broader deployment/TCK coverage pending |
| I6 | Custom tags: TLD parsing, classic + simple handlers, scripting variables, validators | initial rendering: Tag/IterationTag/BodyTag/TryCatchFinally, SimpleTag bodies, named scalar/fragment attributes, explicit bodies and unqualified dynamic attributes; variables/validators/full metadata pending |
| I7 | Tag files, fragments, `jsp:invoke` / `jsp:doBody`; Peano compiled expressions in pages | |
| I8 | Official TCK through `ibarra-tck` (Arquillian adapter on Foy) | in progress: initial adapter and smoke delivered, full diagnostic baseline failing; acceptance remains 100 % PASS recorded in docs `tck` page |
| I9 | Degraded runtime translation polish, Class-File API path for scriptless pages, `ibarra-bench` (vs Jasper), Vidocq Runtime extension | numbers in `BENCH.md` only |

## Implementation progress

I1 implementation (2026-10-10): page/body writers, `IbarraPageContext`,
`IbarraJspFactory` and `IbarraApplicationContext` are implemented. Tests cover
buffering, scope lookup/storage, session opt-in, implicit objects, body writers,
includes/forwards, error-page dispatch, release/reinitialization and rendering
through an installed factory with a hand-written `HttpJspPage`.

Forwarding correction (2026-10-13): base JSP writer state also gates dispatch,
independently of Servlet commitment. Unbuffered writes and JSP flushes reject
forwarding; unwritten/zero-length pages still forward, and pending buffered output
is discarded. Partial write failures retain possible output. Error pages include
after emitted output and retain the original exception. All seven official forward
scenarios pass; the full measured baseline is recorded in ibarra-tck/BASELINE.md.

Application resolvers freeze atomically on first use. The EL provider is created
once, lazily, through `ExpressionFactory.newInstance()`. Context listeners can
retrieve the page's context during their callback. Tests use an EL factory
double; real Peano evaluation, the full Pages implicit-object/import chain and
generated-page EL behaviour remain I3 work.

Proof: `./mvnw -o -ntp clean install` on Temurin Java 25/Maven 3.9.16 passes
38 tests. The default page buffer is 8192 characters. No official TCK has been
run, no JSP sources are translated, and no Foy deployment exists yet. The future
container binding must drain requests before releasing an application context.

### I2 parser

I2 implementation (2026-10-10): `PageParser` accepts decoded source strings and
returns immutable text, EL, directive, scripting and element nodes. Standard
syntax preserves ordinary markup and HTML comments, discards JSP comments and
resolves taglib prefixes across the translation unit. XML syntax uses StAX,
recognizes the JSP namespace by URI, maps XML scripting/directive elements,
decodes built-in/numeric references, and preserves whitespace within `jsp:text`.
EL settings apply to the whole source; quotes and balanced braces delimit expressions.
Locations refer to the original source, including entity/CDATA decoding, with
one-based lines and UTF-16 columns. Errors contain the source path and location.

The compiler module requires only `java.xml`; it exports the parser/model API
and keeps implementation packages hidden. Module and dependency reviews pass.
`./mvnw -o -ntp clean install` under Temurin 25 passes 74 tests: 38 runtime and
36 parser tests. Parser regressions are tracked as fixed in `BUG.md`.

Parsing is separate from translation semantics. Include expansion, encoding
detection, full directive/action/TLD validation, Java generation and EL evaluation
remain later work. Attribute values are retained for later interpretation.
DOCTYPE declarations and external entities are explicitly unsupported by this
initial XML parser; it performs no external document lookup. The official TCK
has not been run. Generator back-end questions below remain open.

### Initial I3 generator (2026-10-10)

`PageGenerator` produces `GeneratedPage` source and a source-location map.
`PageCompiler` emits Java 25 classes with `javax.tools` at build time and keeps
the generated `.ibarra-sources` for inspection. No generated classes are loaded
by the compiler API. Java source is the initial back end for scripting pages;
the later Class-File path for scriptless pages remains open.

Supported fixtures cover text/scripting, lifecycle hooks, content type/buffering,
static includes, preludes/codas, property-group matching, include/forward/param,
useBean/getProperty/setProperty (including wildcard tables), and dynamic
element/attribute/body generation. Bean metadata is read with the Class-File API
and calls are direct. Property actions resolve the current named scoped bean.
Javac enforces target-module access; inspection paths must supply non-base JDK
class metadata when inheritance needs it.

The runtime adds Pages scope, parameter, header, cookie and initialization-parameter
implicit objects. Peano compiler is now a build dependency of ibarra-compiler.
Default expression fields are static final and directly construct compiled,
application-owned classes. Evaluation uses each request's EL context; there is
no runtime expression creation or parsing fallback. The old interpreter remains
an explicit `withStartupEl(true)` preview, rejected by production packaging.
The Pages chain retains provider resolvers, discovering application accessor
tables under the page's defining loader and restoring the caller loader even
on failure. Private application beans work without exports/opens and with
Peano's reflection fallback denied. Applications generate/register their tables
through Peano's processor or the public AccessorProvider contract.

Proof: `./mvnw -o -ntp clean install` under Temurin 25 passes 166 tests:
48 runtime, 36 parser, 13 byte-decoding, 54 generator, 11 packaging and 4 plugin tests. Fixtures compile and run on
the module path, construct pages directly and patch the core only to install
its internal factory. I4 additionally compiles a separate application module,
packages it as a JAR and discovers its unexported constructor-based page index
with compiler modules excluded. Initial Foy deployment is available in I5. Module, dependency, codegen and
concurrency reviews have run; fixed regressions are recorded in `BUG.md`.

Strict policies are implemented: generated immutable `PageOptions` carry
`errorOnELNotFound` into each request EL context before listener callbacks;
resolved null values retain normal EL behavior. Standard pages enforce configured
undeclared-prefix errors and taglib declaration order across includes/preludes.
Boolean settings fail with source diagnostics even on otherwise literal pages.

Mixed standard/XML includes, preludes and codas parse each decoded resource in
its own syntax, then apply the unit-wide EL policy. Whitespace, escaping and
ordinary/custom element classification retain source syntax. Encodings are
validated per file (one `pageEncoding` directive per file); included encodings
do not change response defaults. XML response defaults are `text/xml` and UTF-8.
Unknown property-group keys and invalid buffers have translation diagnostics.

`PageGenerator` also accepts byte arrays, with included resources supplied through
`GenerationOptions.withByteSources`. Standard-page decoding uses BOM, configured
encoding, page directive, content-type charset or ISO-8859-1. XML decoding uses
byte signatures and the named prolog encoding (UTF-8 by default); the derived
encoding is distinct from configuration declarations. UTF-8/16/32 BOMs are
removed, malformed bytes fail without replacement, and named encoding conflicts
retain resource coordinates. Source snapshots and options equality are by value.

TLD functions are bound at build time from immutable catalogue metadata. Peano
AST references retain each call's arity; Ibarra validates public static signatures
from class files and emits direct invokers using the request EL context for
coercion. Standard directives, relative included declarations and lexically scoped
JSPX namespaces share the same binding path. Named-module HTTP tests render
functions in template and fragment/tag attributes with reflection denied and both
compilers excluded. Local reactor proof: 263 tests; the independent Maven example
also exercises a TLD function. Peano's prerequisite passes 303 tests and all
361 official EL TCK tests; those results do not certify Jakarta Pages.

Shared-scope bean creation now coordinates lookup, direct construction and
publication through the BeanScopeContext SPI and one bounded application-owned
reentrant lock. The creator-only body executes after unlocking. Virtual-thread
and named-module HTTP tests cover one application bean across JSP/JSPX pages,
session cookie reuse/isolation, constructor/type/body failures and scope reuse.
No lock registry, hidden session attributes or new module dependency is introduced.

Deferred-value and deferred-method tag attributes retain typed immutable metadata.
Generated classes are constructed per invocation and capture the current
VariableMapper and build-known function descriptors without retaining their
creation context. Pure deferred literals use Class-File API literal protocol
classes; mixed attributes defer only active hash expressions. Method metadata and
invocation use a serializable application-class anchor with loader restoration,
including failures/concurrent evaluation outside the original page call.
Literal protocols are checked against the interpreter; HTTP JSP/JSPX private-bean
methods run with reflection denied and compilers excluded. Initial named deferred
attributes accept text/EL only; custom literal method return coercion remains
unsupported. IBARRA-076 through IBARRA-079 are fixed.

Remaining I3 work: runtime ImportHandler mutations,
broader output/attribute/translation validation,
broader namespace/XML-view validation. Unsupported constructs
fail explicitly. The official TCK has not been run.

Direct TLD discovery is implemented from immutable raw-parse references, retaining
include/prelude/coda origins and namespace declarations removed by normalization.
URI aliases and physical resources are separate: standard declarations retain
alias precedence, forced XML paths bypass aliases, and plain XML namespaces do
not gain bindings from another page's direct resource. Explicit files may be
extensionless; JARs use META-INF/taglib.tld. Referenced listeners join the index
without initialization. Named-attribute/text wrappers retain local bindings and
source trimming, including explicit-body wrappers. IBARRA-068 and IBARRA-071
through IBARRA-075 are fixed.

## Initial I4 implementation

Initial I4 proof (2026-10-10): `examples/precompiled-pages` builds with the real
Maven goal after reactor installation. Its unexported application module contains
compiled pages and a constructor-based `PageIndex` service. The packaging fixture
discovers/renders a separate application JAR with compiler modules excluded.
The goal handles `.jsp`/`.jspx`, root web.xml property groups, stable generated
names and content-verified incremental cleanup. Default packages derive from the
application module name. Web-fragment merging, TLD/taglib configuration, custom
extension mappings and Foy deployment are verified by the initial I5 HTTP fixture. Packaging never enables
startup EL interpretation. Maven's provided/build dependencies are named in the
Antora Maven plugin reference; none enter application/runtime artifacts.

## Open questions

1. **Generator back end** — the initial I3 implementation uses Java source +
   `javax.tools`; when should the Class-File path for scriptless pages be added
   after the source path is TCK-green?
2. **TCK deployment model** — runtime translation vs precompiling Arquillian
   adapter (see "TCK plan").
3. **Foy SPI shape and module** — resolved for initial I5: Foy API exports the
   application-scoped service above, Foy parses `jsp-config`, Ibarra owns page
   catalogues and direct TLD listener factories; Foy owns listener lifecycle.
4. **Peano coupling** — resolved through standard API hooks: core stays on
   `jakarta.el`; the build module requires Peano compiler and generated application
   classes require Peano API. No separate coupling module or optional core read
   is needed for the implemented resolver/compiled-expression path.
5. **`jsp:useBean beanName`** (serialized beans via `java.beans.Beans`) — support,
   or document as unsupported (pulls `java.desktop`)?
6. **SMAP / JSR-045** line mapping for stack traces — needed for 1.0?
7. **TCK coordinates** — confirm on Maven Central.
8. **Docs aggregation** — the Codefloe repository exists; confirm the
   `vidocq-docs` playbook entry and workspace project listing.
