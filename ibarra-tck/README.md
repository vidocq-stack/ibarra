# Official Jakarta Pages 4.0 TCK runner

The runner uses the unmodified `jakarta.tck:jakarta-pages-tck:4.0.0` artifact
from the [official Eclipse distribution](https://download.eclipse.org/jakartaee/pages/4.0/jakarta-pages-tck-4.0.0.zip).
The first smoke subset passes; the full suite does **not** pass. See
[BASELINE.md](BASELINE.md) for measured results and deployment limitations.

## Run

Use Java 25, Maven 3.9.16 and Python 3 from the repository checkout:

```bash
./run-official-tck-pages-4.0.sh smoke
./run-official-tck-pages-4.0.sh all
./run-official-tck-pages-4.0.sh -Dtest=ee.jakarta.tck.pages.api.jakarta_servlet.jsp.jspwriter.URLClientIT
```

The script verifies/downloads the official artifacts, installs them locally,
builds the current Ibarra reactor with `clean install`, runs twenty-six adapter
regression tests, then runs the requested official classes. The default smoke
selects three `JspFactory` tests, one `JspEngineInfo` test and fourteen descriptor
character-sequence/EL/encoding tests (18 total). CI runs this smoke
on pull requests and `main`; it does not claim full-suite certification.

`--no-install` skips the reactor build only when its current artifacts have
already been installed. `--no-prepare` skips artifact preparation. Ordinary
Maven properties are forwarded to the official run. Run modes are processed
left to right; the last `smoke`/`all`/`-Dtest=` selection takes precedence.

```bash
./mvnw -Ptck -pl ibarra-tck clean test
./mvnw -Ptck,tck-official -pl ibarra-tck test \
  -Dtest=ee.jakarta.tck.pages.api.jakarta_servlet.jsp.jspfactory.URLClientIT
```

Normal reactor builds never resolve official TCK artifacts. `-Ptck` adds only
the unpublished harness; `tck-official` enables dependency scanning of `*IT`
classes. Clients retain the upstream Arquillian/JUnit dependencies. The runner
sets `porting.ts.url.class.1` to the Pages distribution's `SunRIURL` implementation;
the upstream common runtime otherwise defaults to a missing REST implementation.

## Artifact preparation

`prepare-tck.py` uses Python's standard library and the Maven wrapper. It
preserves the official jar's embedded Maven POM, including the upstream common
runtime version `11.0.0-M1`. The standalone 4.0 archive omits its non-Central
`javatest:javatest:5.0` dependency. Eclipse's earlier Pages 3.1 distribution
contains that exact JavaTest version (`META-INF/buildInfo.txt`); only the
JavaTest jar is extracted. No older test classes are installed or executed.

Verified SHA-256 fingerprints:

| Artifact | SHA-256 |
|---|---|
| Pages 4.0 distribution | `5446aa866601a7c4c425f74054590cfc7f4fc05592a572d399ecc8694e265489` |
| Pages 3.1 distribution supplying JavaTest | `e9f33d463ca35bdf8a9985cc8871858d7e6ea8a6e90adb00a91a001b1e43e025` |
| JavaTest 5.0 jar | `73d0c322e76839c405f2000b568cc8906977b430ba23e83f20d420345e8a13a4` |

Set `TCK_PAGES_ZIP` and `TCK_JAVATEST_ZIP` to local copies to avoid downloading
the distributions. Their checksums are still mandatory. Maven may separately
need network access to resolve Central dependencies. Downloaded files are
cached under `target/pages-tck-distribution`; `clean` removes that cache.

## Deployment model and limits

Arquillian clients and ShrinkWrap use a test-only classpath, as in `foy-tck`.
The adapter copies WAR bytecode into a named `tck.application` module, compiles
its descriptor and pages on the build machine, then launches a named `tck.host`
with Foy and Chappe. Generated page indexes construct pages directly. The server
asserts that both compilers and `java.compiler` are absent, and starts with
`peano.reflection=deny`. Application packages receive no exports or opens.

Each archive has its own process, application layer, port and resource root.
Multiple Arquillian deployments can coexist. Undeployment drains/stops the
server and releases the application; forced cleanup waits for termination and
preserves interruption. Work directories and `server.log` remain under
`ibarra-tck/target/pages-deployments` for diagnosis, until the next runner clean.
Surefire XML/text reports are under `ibarra-tck/target/surefire-reports`.

`JspConfigurationParser` adapts root web.xml property groups to immutable compiler
options, preserving patterns, scalar settings, preludes/codas and legacy EL defaults.
Malformed global settings abort deployment even if no page matches the group.

Servlets, filters and listeners explicitly declared in root web.xml are adapted
when their bytecode is in WEB-INF/classes. The build-side Class-File API emits
Foy `WebComponent` providers with direct class literals and constructor calls;
the application descriptor registers them with `provides` and explicitly requires
`io.vidocq.foy.api`. Providers share the application module, so component packages
stay private. Metadata discovery does not initialize component classes. Components
must be public concrete classes with public no-arg constructors; missing classes,
invalid constructors and generated-name collisions abort deployment before launch.
Mappings, init parameters and lifecycle remain handled by Foy's descriptor merger.
Generated factories preserve runtime exceptions/errors and wrap checked constructor
failures with their cause, including constructors declared to throw Throwable.

The runner calls explicit `PagePrecompiler.precompileDeployment`. Genuine per-page
parse/generation/javac failures become compiled error servlets that throw the retained
translation error on request, including precompile requests. Valid pages remain
available. `translation-errors.txt` retains the complete build diagnostics;
error servlet messages are bounded safely. Each javac invocation uses isolated
outputs and a clean application view excluding previous generated classes.
The ordinary precompile entry point and Maven plugin continue to reject page errors.

Legacy WEB-INF/lib JARs are assembled into the same private application module.
This preserves the WAR's shared package/class visibility when application and
library classes overlap in one package; no automatic or additional dependency
module is introduced. Ordinary resources retain their module-relative paths.
Each library's META-INF TLD metadata receives a separate build-discovery path,
so distinct META-INF/taglib.tld descriptors remain discoverable by URI. Original
JAR bytes stay unchanged under WEB-INF/lib for explicit physical TLD references.
TLD parsing remains build-only; no archive/XML parser is added to the server.

Byte-identical duplicate class/resource outputs are deduplicated; incompatible
duplicates abort deployment without overwriting the earlier output. Unsafe or
noncanonical ZIP paths fail before extraction. Explicit module descriptors,
multi-release entries, web fragments and classpath service metadata require
separate adaptation and are rejected, rather than discarding their semantics.
This bounded test-runner assembly does not change production Maven packaging.

## Official API signature check

The official-only profile uses maven-dependency-plugin 3.8.1 to copy the exact
configured Pages, Servlet and EL API versions into target/signature-api. The
unchanged upstream driver receives those API paths through sigTestClasspath and
its extracted java.base image through jimage.dir. No signature file or expected
member is altered. The signature tool's build-side classpath is separate from the
application/host module path; no implementation or Maven tooling is shipped.

```bash
./mvnw -ntp -Ptck,tck-official -pl ibarra-tck clean test \
  -Dtest=ee.jakarta.tck.pages.signaturetest.jsp.JSPSigTestIT
```

The default adapter profile does not activate this resource-copy execution.

## Private bean property access

Legacy WAR classes have no Peano annotation-processing output. The runner uses
Class-File metadata to emit application-owned AccessorTable implementations and
register an existing Peano AccessorProvider service before page precompilation.
Property reads/writes call public instance methods directly; primitive reads box
and writes use the supplied ELContext for conversion. Read-only/write-only/null
properties preserve the resolution-flag contract. Inherited getters prefer primitive
boolean is methods and the most-specific covariant return; compatible setter
overloads use their unique most-specific parameter type.

Tables remain in their bean's package. To support the standard reflective Jakarta
EL `BeanELResolver`, the runner qualified-exports packages containing generated
bean tables only to `jakarta.el`; it adds no unqualified exports or opens. Discovery
and provider class literals do not initialize the beans. Native
Map/List/ResourceBundle/Record types retain their standard resolvers. Binary-path
mismatches and generated class collisions abort adaptation without rewriting the
original WAR classes.

Hierarchy/signature lookup follows the application module's non-static reads,
transitive visibility and qualified/unqualified exports. Unavailable/inaccessible
targets receive explicit diagnostics in bean-accessor-errors.txt and no unusable
table; their actual page failures remain visible. Lookup checks exact resources,
so unrelated unreadable package owners cannot mask valid classes.

This is bounded property adaptation in the unpublished runner. Arbitrary method
invocation, static members, conversion/functional-interface tables and explicit
handwritten BeanELResolver instances remain separate capabilities. Production
applications continue to use Peano's processor or their own complete accessor
providers; neither Ibarra core nor its Maven plugin gains this automatic feature.

## Collection method access

After page compilation the runner scans WAR/application class-file allocations
for public concrete java.util Collection classes from java.base. It emits direct
method-only tables in the private tck.generated.collections package and appends
their Peano provider, preserving all compiled PageIndex, component and bean services.
Original WAR class bytes remain unchanged; compiler output is never needed at runtime.

MethodDescriptor constants and MethodSelection supply signatures and overload
selection. Metadata does not invoke the method, arguments are defensively copied,
and actual invocation converts operands through the supplied ELContext. Primitive
and void returns, resolved null results and target exception causes are preserved.
Property operations remain unresolved, so native list indexing retains precedence.
Only exact runtime target classes are claimed; unknown names/targets remain unresolved.

Discovery follows the existing readable/exported module checks, inspects signatures
from the actual helper package, and does not initialize application classes. Names
with varargs, runtime method annotations or inaccessible signature types are omitted
as a whole, with bean-accessor-errors.txt diagnostics, preserving real page failures.
Object's inherited methods and static methods are outside this bounded adaptation.
Generated class/provider collisions fail before overwriting application bytes.
This is an unpublished runner capability; production applications continue to
generate/register their own complete Peano accessor providers.

Current adapter diagnostics explicitly reject unadapted library metadata,
annotated Servlet components requiring generated metadata, ServletContainerInitializer
providers. Bean/accessor adaptation, runtime JSP descriptor exposure and signature-test
configuration remain incomplete. Invalid global metadata still aborts deployment.
Unsupported compiler semantics retain their existing diagnostics. None of
these failures is silently skipped or converted into a passing test.
