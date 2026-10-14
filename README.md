# Ibarra

<p align="center">
  <img src="docs/en/modules/ROOT/images/ibarra-logo.png" alt="Ibarra — Jakarta Pages" width="300">
</p>

**Jakarta Pages 4.0** (formerly JavaServer Pages) implementation for the
[Vidocq](https://codeberg.org/Vidocq/vidocq) runtime — Expression Language
through [Peano](https://codefloe.com/Vidocq/peano), servlet container through
[Foy](https://codefloe.com/Vidocq/foy).

> Named after **Joaquín Ibarra** (1725–1785), royal printer in Madrid, famous for
> the 1780 Real Academia edition of *Don Quixote* — a nod to `cervantes`, the
> MicroProfile JWT brick. Composing pages from a model once, then printing them
> on demand.

## Status

🚧 **I3 in progress; initial I4 packaging and I5 Foy binding implemented.** The runtime and parser are implemented. The initial
generator emits Java servlet source, compiles it at build time and renders
fixtures for scripting, standard actions, configuration and direct bean access.
Static includes can mix JSP/JSPX syntax; source encodings are validated per file.
Byte sources support BOM/prolog detection and strict decoding before translation.
XML output declarations and SYSTEM/PUBLIC DOCTYPE metadata are collected at
translation time across static includes, then emitted before the page body.
Page declarations use the response encoding; input DTDs and external entities
remain rejected. XML `.tagx` files support static markup and `jsp:output`
omission metadata, emitting a UTF-8 declaration at each invocation when requested
without changing the caller's response charset. Standard `.tag` files remain
unsupported.
The Maven `precompile` goal emits pages and an index into an application module;
a packaged application JAR is discovered and rendered without compiler modules.
EL compiles at build time through Peano into application-owned classes; startup
interpretation remains an explicit preview option. Precompiled pages run over HTTP on Foy/Chappe with extension
mappings, named JSP registrations, includes and build-generated TLD listeners.
Initial classic/simple tag handlers now render through direct constructors,
setters and generated scriptless body/named fragments. Named scalar attributes,
explicit bodies and compiler-provided JspIdConsumer identifiers are supported.
Identifiers stay stable across requests while handler instances remain fresh.
Standard `.tag` files and full tag-file metadata, attributes, directives,
expressions, scripting, nested actions and fragments remain pending.
Page type/package imports now resolve through build-generated class references.
TLD functions use generated direct static calls. Typed deferred tag values/methods
are compiled and constructed per invocation. Dynamic import changes and official TCK
certification remain pending.
Directly declared TLD resources and their listeners are discovered at build time,
with separate URI alias and forced XML path resolution.
Session/application beans now use coordinated lookup, direct construction and
publication; their initialization body runs only for the creator, after unlocking.
Forwarding from an included page preserves application response wrappers and cached
writers, clears pending ancestor JSP buffers and stops including pages after success.
The optional Foy binding delegates Servlet reset and asynchronous response policy
through the typed container dispatcher; core remains container-neutral.
The design of record is [PLAN.md](PLAN.md) (spec scope, build-time page
compilation, Java Modules layout, Foy JSP-engine SPI, TCK plan, milestones I0–I9).

## Ecosystem rules

Ibarra follows the transverse Vidocq philosophy (see the workspace
[AGENTS.md](https://codefloe.com/Vidocq/vidocq-workspace/src/branch/main/AGENTS.md) conventions):

- **Zero third-party dependency** — only the Jakarta Pages 4.0 API (which brings
  the Servlet 6.1 and Expression Language 6.0 APIs). No Jasper, no Eclipse JDT.
- **Strict Java Modules** — proper `module-info.java`, minimal `exports`,
  no unjustified `opens`, no classpath.
- **Static code generation first** — pages and tag files are translated and
  compiled during the application build (`ibarra-maven-plugin`);
  runtime translation through `javax.tools` is a documented, degraded
  development mode.
- **TDD** — test (or TCK scenario) first, then code.
- **Virtual threads** for any I/O.
- Bugs tracked in [BUG.md](BUG.md), benchmarks in [BENCH.md](BENCH.md).

## Modules

| Module | Role | State |
|---|---|---|
| `ibarra-api` | Immutable page/listener factories and constructor-based index SPI | I3/I4/I5 SPI implemented |
| `ibarra-core` | Page runtime: factory, context, scopes, output, dispatch and application EL context; tags planned | I1 runtime implemented |
| `ibarra-compiler` | JSP/JSPX parser, page model, Java generator, initial tag handlers and build-time compiler | I2 implemented; I3/I6 in progress |
| `ibarra-maven-plugin` | `precompile` goal at process-classes; module service registration | Initial I4 implemented |
| `ibarra-foy` | Optional Foy engine provider; application layer, routing and TLD listener lifecycle | Initial I5 implemented |
| `ibarra-tck` | Official Pages 4.0 TCK runner (`-Ptck` profile only) | modular WAR adapter; 18-test smoke passes, 77.0% measured full-suite pass rate |

The latest local full run passes 525/682 official methods with no regression against
the previous 523 passes; 157 method errors remain, so certification is pending.
The ordinary reactor passes 341 tests, the adapter 35/35 and the independent example
packages successfully. Typed forwarding uses the producer delivered in
[Foy #47](https://codefloe.com/Vidocq/foy/pulls/47), now merged and published.
See [the exact baseline](ibarra-tck/BASELINE.md).

Planned modules (`ibarra-bench`) are described in [PLAN.md](PLAN.md) and created by
the milestone that needs them. JSTL is out of scope.

## Build

```bash
sdk env          # Java 25 (Temurin) + Maven 3.9.16, pinned by .sdkmanrc
./mvnw -ntp install
./mvnw -ntp -f examples/precompiled-pages/pom.xml clean package
./mvnw -Ptck -pl ibarra-tck test                 # adapter regression tests
./run-official-tck-pages-4.0.sh smoke            # prepare official artifacts, clean install, smoke
./run-official-tck-pages-4.0.sh all              # diagnostic full-suite attempt; not yet green
```

## License

Triple-licensed, at your option: **EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later**.
See [LICENSE](LICENSE) and [NOTICE](NOTICE).
