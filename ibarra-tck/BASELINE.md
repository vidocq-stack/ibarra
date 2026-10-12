# Jakarta Pages 4.0.0 diagnostic baseline

Date: 2026-10-12. Temurin Java 25, Maven 3.9.16, macOS; Ibarra development
version `0.4.0-SNAPSHOT`, modular Foy/Chappe and published Peano snapshots.
This records compatibility checks, not a performance benchmark or certification.

```bash
./run-official-tck-pages-4.0.sh all --no-install --no-prepare
```

Prerequisites were prepared by `prepare-tck.py`, preserving the official embedded
POM; the current reactor artifacts were already installed. Distribution checksums
and exact preparation commands are in [README.md](README.md).

## Recorded results

| Measure | Result |
|---|---:|
| Official classes selected | 92 |
| Declared runtime-visible JUnit `@Test` methods in the official binary | 682 |
| Official Surefire entries reported | 614 |
| Passing test methods | 427 |
| Failing method entries | 179 |
| Class-level setup/deployment errors | 8 |
| Total reported errors | 187 |
| Assertion failures reported as Surefire `failure` | 0 |
| Skipped entries | 0 |
| Adapter regression tests (separate invocation) | 7/7 |
| CI smoke subset (factory/engine + character sequence/EL/encoding configuration) | 18/18 |

Surefire reports `Tests run: 614, Failures: 0, Errors: 187, Skipped: 0` and exits
nonzero. The TCK's HTTP assertion helper reports failures as exceptions, hence
`errors` rather than `failures`; zero in the failure column does not mean success.

**427/682 = 62.6% confirmed passes**, a conservative measured floor. Only
606 method entries actually ran; the 8 empty-name class-level error entries are
not executed test methods. A failed deployment prevents the methods in that class
from running, so `614/682` is not an execution percentage and `427/614` is not a
suite pass rate. No full-suite certification or complete coverage is claimed.

The denominator was inventoried from the verified official jar with Java 25:

```bash
javap -v -p -classpath jakarta-pages-tck-4.0.0.jar <all 92 names ending in IT.class>
```

Count only annotation display lines containing exactly
`org.junit.jupiter.api.Test`, not constant-pool references or comments in sources.

## Passing coverage

Thirty-five classes pass completely, including JspFactory (3), JspEngineInfo (1),
JspWriter (30), PageContext (43), ErrorData (5), BodyContent (5), DynamicAttributes
(2), TagAdapter (8), VariableInfo (5), several EL API/implicit-resolver classes,
implicit objects (9), character sequence/EL/encoding configuration (14), i18n
(13), includes/imported taglibs, and scripting syntax. Other
classes pass partially, including forwarding, bean-property setting and
precompile-query handling. These counts are for the official classes, not the
ordinary Ibarra unit tests or Peano's independent EL TCK.

## Remaining errors and priorities

| Category | Evidence / next step |
|---|---|
| Per-page translation failures | IBARRA-084 fixed: genuine parse/generation/javac failures become compiled error servlets, while valid pages still render. Global deployment metadata remains fatal. |
| WAR adaptation | Eight setup failures remain: components/libraries and globally invalid TLD metadata. JSP property groups are now applied at build time (IBARRA-083 fixed). Runtime JSP descriptor exposure remains pending upstream. |
| Tag files and full tag metadata | `urn:jsptagdir`, variables, TEI/TLV and related metadata remain I6/I7 work. |
| TLD validation/defaults | Keyword attribute names and inferred Object setters; IBARRA-093/094. |
| BeanELResolver fixture access | One method fails in the private application module; IBARRA-085. |
| JspIdConsumer | Three methods fail; IBARRA-086. |
| TagLibraryValidator | Two methods fail; IBARRA-087. |
| TryCatchFinally | One method fails; IBARRA-088. |
| Unbuffered forward | One method fails; IBARRA-089. |
| PropertyEditor conversion | One method fails; IBARRA-090. |
| Invalid precompile query | One method fails; IBARRA-091. |
| Signature tooling | One method fails with an empty signature-tool classpath; IBARRA-092. |

The previous runner baseline (PR #22) recorded 174 passes, 11 method errors and
54 setup errors. This update records 427 passes, 179 method errors and 8 setup
errors: 421 additional method entries now execute instead of being blocked by
whole-WAR precompilation or unsupported JSP configuration. The unmodified
official tests remain authoritative; genuine negative translation errors are
served as errors, never replaced with canned passing responses.

The full remaining inventory is in [FAILURES.md](FAILURES.md), tracked by
IBARRA-098. Every currently failing class and method has a reproducible selector;
newly visible failures still require isolated diagnosis. Global property
validation, clean incremental compilation and Unicode diagnostic retention have
regression coverage (IBARRA-095–097 fixed).

Raw per-class Surefire XML/text and extracted WAR/server logs remain under
`ibarra-tck/target` immediately after the command; another `clean` replaces them.
Reproduce a class with the exact selector shown in [BUG.md](../BUG.md), prefixing
it with `ee.jakarta.tck.pages.`. All test failures remain visible; the smoke
subset is a separate green regression check and never rewrites this baseline.
