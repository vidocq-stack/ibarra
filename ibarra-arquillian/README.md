# Ibarra Arquillian adapter

`io.vidocq.ibarra:ibarra-arquillian` is an Arquillian `DeployableContainer` that
precompiles each deployed Jakarta Pages WAR on the build machine, then runs it on
modular Foy and Chappe in a separate process. The official Pages 4.0 TCK runner
([`ibarra-tck`](../ibarra-tck/README.md)) uses it with no configuration. It is published so that
other runners, such as Bodoni's JSTL TCK runner, reuse it through the properties below.

It is **test tooling**: take it at `test` scope only. It is never part of an
application, and no Vidocq runtime extension brings it.

```xml
<dependency>
    <groupId>io.vidocq.ibarra</groupId>
    <artifactId>ibarra-arquillian</artifactId>
    <version>${ibarra.version}</version>
    <scope>test</scope>
</dependency>
```

The jar has no `module-info`: Arquillian, ShrinkWrap and the official TCK clients are
not Java modules, so the adapter runs on the test class path (keep Surefire's
`useModulePath` at `false`), like `ibarra-maven-plugin` runs on Maven's build class
path. What it deploys stays strictly modular: a named `tck.application` module and a
named `tck.host` server module, started with `--limit-modules`, without compilers and
with `-Dpeano.reflection=deny`. Its Arquillian container SPI (1.8.0.Final) and
ShrinkWrap (1.2.6) dependencies, both Apache-2.0, are named in the
[reference page](../docs/en/modules/ROOT/pages/modules/arquillian-adapter.adoc).

## Arquillian alignment

The adapter needs only `arquillian-container-spi`, binary-compatible from 1.8 to 1.10
(1.9 only adds a default `getConfigurationMapper()`). A consumer whose test suite uses a
newer Arquillian, such as the Tags TCK on 1.9.4.Final, imports
`org.jboss.arquillian:arquillian-bom` at that version in its `dependencyManagement`, so
that every Arquillian artifact resolves to one version:

```xml
<dependency>
    <groupId>org.jboss.arquillian</groupId>
    <artifactId>arquillian-bom</artifactId>
    <version>1.9.4.Final</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
```

Keep Foy, Peano and Chappe on the versions Ibarra uses (the host compiles against Foy
internals), keep Surefire's `useModulePath` at `false`, and do not run JUnit tests in
parallel: the adapter keeps its deployments in one unsynchronised map.

## Configuration

Three optional `arquillian.xml` container properties. Each is a comma-separated list,
trimmed, empty by default. With none set, the adapter deploys exactly as before:
same application descriptor, same server command line.

```xml
<container qualifier="ibarra" default="true">
    <configuration>
        <property name="applicationLibraries">io.vidocq.bodoni.core,io.vidocq.bodoni.peano,jakarta.servlet.jsp.jstl,com.h2database</property>
        <property name="jdkModules">java.sql,java.naming,jdk.localedata</property>
        <property name="applicationOpens">com.sun.ts.tests.jstl.common.resources</property>
    </configuration>
</container>
```

| Property | Meaning | Refused |
|---|---|---|
| `applicationLibraries` | Module names found on the test class path and deployed in the application layer. An explicit module is `requires`d by `tck.application` and put on the build-side module path, so its TLDs are discovered. An automatic module (a JDBC driver) is runtime-only and never given to the precompiler. | At `setup`: not a module name, listed twice, build tooling (`java.compiler`, `io.vidocq.ibarra.compiler`, `io.vidocq.peano.compiler`), a host module, or absent from the test class path. |
| `jdkModules` | JDK modules appended to the server's `--limit-modules` and `--add-modules`. | At `setup`: not a JDK module, or a module that is or requires build tooling (`java.compiler`, `jdk.compiler`). |
| `applicationOpens` | Packages of the deployed application that `tck.application` opens to every module (unqualified `opens`). The `opens` survives the collection-table descriptor rewrite. | At `setup`: not a package name. At deployment: a package absent from the application fails that deployment; the adapter stays usable. |

Setup refusals are Arquillian `ConfigurationException`s; deployment failures are
`DeploymentException`s that name the retained work directory. When libraries are
configured, the server resolves every module of the application layer as a root.

Limits: an explicit library that `requires` an automatic library is not supported
(the build-side compile fails with a javac diagnostic). The test class path is probed
entry by entry; a directory entry is skipped as a whole when any jar in it is not a
module.

## Tests

```bash
./mvnw -pl ibarra-arquillian test    # 67 adapter regression tests, default reactor
```

## Deployment model and limits

Arquillian clients and ShrinkWrap use a test-only classpath, as in `foy-tck`.
The adapter copies WAR bytecode into a named `tck.application` module, compiles
its descriptor and pages on the build machine, then launches a named `tck.host`
with Foy and Chappe. Generated page indexes construct pages directly. The server
asserts that both compilers and `java.compiler` are absent, and starts with
`peano.reflection=deny`. Application packages receive no exports or opens beyond
the packages named by `applicationOpens`.

Each archive has its own process, application layer, port and resource root.
Multiple Arquillian deployments can coexist. Undeployment drains/stops the
server and releases the application; forced cleanup waits for termination and
preserves interruption. Work directories and `server.log` remain under
`target/pages-deployments` of the consuming module (system property
`ibarra.tck.work`) for diagnosis, until the next clean.

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

The adapter calls explicit `PagePrecompiler.precompileDeployment`. Genuine per-page
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
Modules that a test suite needs next to the WAR are configured with
`applicationLibraries` instead.

Byte-identical duplicate class/resource outputs are deduplicated; incompatible
duplicates abort deployment without overwriting the earlier output. Unsafe or
noncanonical ZIP paths fail before extraction. Explicit module descriptors,
multi-release entries, web fragments and classpath service metadata require
separate adaptation and are rejected, rather than discarding their semantics.
This bounded test-tooling assembly does not change production Maven packaging.

## Private bean property access

Legacy WAR classes have no Peano annotation-processing output. The adapter uses
Class-File metadata to emit application-owned AccessorTable implementations and
register an existing Peano AccessorProvider service before page precompilation.
Property reads/writes call public instance methods directly; primitive reads box
and writes use the supplied ELContext for conversion. Read-only/write-only/null
properties preserve the resolution-flag contract. Inherited getters prefer primitive
boolean is methods and the most-specific covariant return; compatible setter
overloads use their unique most-specific parameter type.

Tables remain in their bean's package. To support the standard reflective Jakarta
EL `BeanELResolver`, the adapter qualified-exports packages containing generated
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

This is bounded property adaptation in the test-tooling adapter. Arbitrary method
invocation, static members, conversion/functional-interface tables and explicit
handwritten BeanELResolver instances remain separate capabilities. Production
applications continue to use Peano's processor or their own complete accessor
providers; neither Ibarra core nor its Maven plugin gains this automatic feature.

## Collection method access

After page compilation the adapter scans WAR/application class-file allocations
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
This is a test-tooling capability; production applications continue to
generate/register their own complete Peano accessor providers.

Current adapter diagnostics explicitly reject unadapted library metadata,
annotated Servlet components requiring generated metadata, ServletContainerInitializer
providers. Bean/accessor adaptation, runtime JSP descriptor exposure and signature-test
configuration remain incomplete. Invalid global metadata still aborts deployment.
Unsupported compiler semantics retain their existing diagnostics. None of
these failures is silently skipped or converted into a passing test.
