# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> Joaquín Ibarra (1725–1785), royal printer in Madrid, composed the 1780 Real Academia
> edition of *Don Quixote* once, then printed it on demand — a nod to `cervantes`, the
> MicroProfile JWT brick. The Ibarra project implements **Jakarta Pages 4.0**: pages are
> composed (translated and compiled) during the build, then rendered on each request.

**Status: in design.** Read [PLAN.md](PLAN.md) before any change — it is the design of
record (spec scope, build-time page compilation, Java Modules layout, Foy JSP-engine SPI,
Peano integration, TCK plan, milestones I0–I9, open questions). Structural decisions
marked "to validate" there need the maintainer's sign-off first.

## Prerequisites

- **Java 25** + **Maven 3.9.16** (`.sdkmanrc` provided — use `sdk env`)
- `jakarta.servlet.jsp:jakarta.servlet.jsp-api:4.0.0` is already modular upstream
  (module `jakarta.servlet.jsp`, requires `jakarta.servlet` and `jakarta.el`
  transitively) — no Vidocq fork needed.
- The official Pages 4.0 TCK coordinates are **not yet confirmed** (see PLAN.md
  "TCK plan"); it is an Arquillian suite and will need Foy as the container.
- The build uses `git-commit-id-maven-plugin` (from `vidocq-parent`): it needs at least
  one commit in the repository.

## Essential Commands

```bash
sdk env
./mvnw -ntp install                                # default reactor (no TCK)
./mvnw test
./mvnw -Ptck -pl ibarra-tck test                   # TCK runner skeleton
./mvnw -Ptck,tck-official -pl ibarra-tck test      # official TCK (coordinates to confirm)
```

`ibarra-tck` is **in-reactor behind the `tck` Maven profile** from day one: a plain
`./mvnw install` neither builds nor downloads anything TCK-related.

## Architecture

```
ibarra-core   ← Page runtime (scaffold today): JspFactory, PageContext, JspWriter,
                tag handler lifecycle, JspApplicationContext, Pages EL resolver chain
ibarra-tck    ← Official Pages 4.0 TCK runner, in-reactor behind `tck`
(planned)     ibarra-api (generated-code SPI: page index), ibarra-compiler (parser +
              generator), ibarra-maven-plugin (build-time precompilation),
              ibarra-foy (Foy JSP-engine SPI binding), ibarra-bench (JMH)
```

## Architecture Constraints Not to Violate

1. **`ibarra-core` depends on the Jakarta Pages API only** (plus what it brings:
   Servlet, EL). It obtains its `ExpressionFactory` through `ExpressionFactory.newInstance()`
   — Peano is the default provider, not a hard dependency (PLAN.md open question 4).
2. **Only `ibarra-foy` depends on Foy.** The core stays container-neutral.
3. **Pages are compiled at build time.** Runtime translation (`javax.tools`) is a
   documented degraded mode, off by default in production; never make the runtime path
   the default.
4. **No reflection in generated code** — tag handlers, attribute setters and `useBean`
   classes are known at translation time: generated code calls `new` and setters
   directly. Pages are found through the generated page index, never by class name.
5. **No `synchronized`, no `ThreadLocal`** — page state travels in the `PageContext`;
   no tag-handler pooling unless a `BENCH.md` entry justifies it.
6. **JSTL is out of scope** — do not add standard tag library code here.
7. **JUnit 6** (`junit-bom` managed by `vidocq-parent`), no Mockito — hand-written
   fixtures and servlet doubles only.
8. **Language** — code, Javadoc, comments, commit messages and every `.md` file are in
   **English**.

## Conventions

- **Maven groupId**: `io.vidocq.ibarra`; version `0.4.0-SNAPSHOT` (workspace dev line,
  parent `io.vidocq:vidocq-parent:0.4.0-SNAPSHOT`). Upstream Vidocq versions
  (`peano.version`, `foy.version`) are properties, for cross-repo PR overrides.
- **Packages**: `io.vidocq.ibarra.core.internal.*` = implementation, not exported.
- Licence header from `etc/license-header.txt` on every `.java` file.
- Commits: Conventional Commits, `git commit -S -s`, `Co-Authored-By` trailer when AI
  assisted (see workspace `CLAUDE.md`).

## TDD — Test-Driven Development (mandatory)

Red → green → refactor, one spec chapter at a time, following the PLAN.md milestones.
Cite the spec section in commit messages / PR descriptions (e.g. "Pages 4.0 JSP.1.10
directives").

## TCK — Technology Compatibility Kit

Not run yet (milestone I8). Once green, 100 % PASS is a merge gate; any challenge goes
in a `TCK.md` with spec citation — created only if one is actually filed.

## AI Principles — Collaboration on This Repository

- **Plan mode by default** on any structural change (new module, new SPI, change to the
  generated page shape).
- Use the Java Modules guardian agent and **`classfile-codegen`**, **`virtual-threads-reviewer`**,
  **`dependency-gatekeeper`** proactively on `module-info.java`, codegen, concurrent
  code, or `pom.xml` changes.
- If the rules in this file need updating, keep `AGENTS.md` aligned.

## Documentation (Antora) conventions

The project documentation lives in `docs/en` as an Antora component and is
aggregated by the **vidocq-docs** site, which provides a **shared UI bundle** (banner,
logo, fonts, colours, footer). **Never customise the documentation UI per project** —
all visual harmonisation is centralised in `vidocq-docs/ui-bundle`.

### Gold reference
**Vauban** is the reference implementation for documentation structure. Mirror its
`docs/en` layout when creating or updating docs. **Chappe** (HTTP server)
and **Vidocq** (runtime orchestrator) are *special cases*, not references: they are not
Jakarta EE / MicroProfile spec implementations.

### Repository layout
- `docs/en/antora.yml` → `name: <project>`, `title:`, versioned per branch (`dev` prerelease on `main`, `'<version>'` on `docs/<version>`), `project-version` attribute, `nav:`, `lang: en`.
- Pages in `modules/ROOT/pages/`, navigation in `modules/ROOT/nav.adoc`, images in
  `modules/ROOT/images/`.
- **English-only** (ADR 0004 in vidocq-docs): no French mirror — do not reintroduce one.

### Canonical navigation (section order)
`index` → `getting-started` → `usage` → `concepts` → `internals` → `tck` →
`performance` → `reference` → `migration`

Multi-module projects (e.g. Vidocq, Mansart) may append `modules/*` / `sub-modules/*`
sub-pages after `migration`.

### TCK / Performance rule (not mutually exclusive)
- Every **spec implementation** — i.e. **all projects except Chappe and Vidocq** — MUST
  have a **`tck`** section documenting TCK coverage/status.
- Projects with a performance story (e.g. **Chappe**) keep their **`performance`** section.
- When **both** sections exist, order them **TCK first, then Performance**.
- **Chappe** and **Vidocq** do not require a `tck` section (not spec implementations).

### `index.adoc` structure
Follow Vauban's `index.adoc`: page title (`= <Project>`), `:description:`, a centred logo
(`image::<project>-logo.png[...,role=module-logo]`), a `[.lead]` paragraph, then
`== Origin of the name`, an `== At a glance` table, and ecosystem / quick-links sections.

### Logo
Provide `modules/ROOT/images/<project>-logo.png` (PNG), referenced from `index.adoc`.

> When you change these documentation rules, keep `AGENTS.md` and `CLAUDE.md` in sync.

## Terminology

Use **Java Modules** (or **Java module** for a single module) when referring to
the Java Platform Module System. Do **not** use the abbreviation **JPMS** — in
prose, identifiers, or documentation.
