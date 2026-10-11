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
The Maven `precompile` goal emits pages and an index into an application module;
a packaged application JAR is discovered and rendered without compiler modules.
EL startup interpretation is an explicit preview option; build-time EL awaits
Peano's compiler. Precompiled pages now run over HTTP on Foy/Chappe with extension
mappings, named JSP registrations, includes and build-generated TLD listeners.
Tag handlers and official TCK certification remain planned.
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
| `ibarra-compiler` | JSP/JSPX parser, page model, initial Java generator and build-time compiler | I2 implemented; I3 in progress |
| `ibarra-maven-plugin` | `precompile` goal at process-classes; module service registration | Initial I4 implemented |
| `ibarra-foy` | Optional Foy engine provider; application layer, routing and TLD listener lifecycle | Initial I5 implemented |
| `ibarra-tck` | Official Pages 4.0 TCK runner (`-Ptck` profile only) | skeleton |

Planned modules (`ibarra-bench`) are described in [PLAN.md](PLAN.md) and created by
the milestone that needs them. JSTL is out of scope.

## Build

```bash
sdk env          # Java 25 (Temurin) + Maven 3.9.16, pinned by .sdkmanrc
./mvnw -ntp install
./mvnw -ntp -f examples/precompiled-pages/pom.xml clean package
./mvnw -Ptck -pl ibarra-tck test                 # TCK runner skeleton
./mvnw -Ptck,tck-official -pl ibarra-tck test    # official TCK (coordinates to confirm)
```

## License

Triple-licensed, at your option: **EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later**.
See [LICENSE](LICENSE) and [NOTICE](NOTICE).
