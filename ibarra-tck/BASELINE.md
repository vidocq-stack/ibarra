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
| Official Surefire entries reported | 239 |
| Passing test methods | 174 |
| Failing method entries | 11 |
| Class-level setup/deployment errors | 54 |
| Total reported errors | 65 |
| Assertion failures reported as Surefire `failure` | 0 |
| Skipped entries | 0 |
| Adapter regression tests (separate invocation) | 4/4 |
| CI smoke subset (JspFactory + JspEngineInfo) | 4/4 |

Surefire reports `Tests run: 239, Failures: 0, Errors: 65, Skipped: 0` and exits
nonzero. The TCK's HTTP assertion helper reports failures as exceptions, hence
`errors` rather than `failures`; zero in the failure column does not mean success.

**174/682 = 25.5% confirmed passes**, a conservative measured floor. Only
185 method entries actually ran; the 54 empty-name class-level error entries are
not executed test methods. A failed deployment prevents the methods in that class
from running, so `239/682` is not an execution percentage and `174/239` is not a
suite pass rate. No full-suite certification or complete coverage is claimed.

The denominator was inventoried from the verified official jar with Java 25:

```bash
javap -v -p -classpath jakarta-pages-tck-4.0.0.jar <all 92 names ending in IT.class>
```

Count only annotation display lines containing exactly
`org.junit.jupiter.api.Test`, not constant-pool references or comments in sources.

## Passing coverage

Thirty classes pass completely, including JspFactory (3), JspEngineInfo (1),
JspWriter (30), PageContext (43), ErrorData (5), BodyContent (5), DynamicAttributes
(2), TagAdapter (8), VariableInfo (5), several EL API/implicit-resolver classes,
implicit objects (9), includes/imported taglibs, and scripting syntax. Other
classes pass partially, including forwarding, bean-property setting and
precompile-query handling. These counts are for the official classes, not the
ordinary Ibarra unit tests or Peano's independent EL TCK.

## Remaining errors and priorities

| Category | Evidence / next step |
|---|---|
| Eager translation of intentionally invalid pages | Whole WAR fails before positive or negative assertions run; IBARRA-084. Preserve per-page errors without runtime parsing. |
| WAR adaptation | Explicit diagnostics for libraries, Servlet factories/annotations/initializers and JSP property groups; IBARRA-083 and runner limits. |
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

The earlier exploratory run reported 187 passing entries. This final baseline
rejects previously ignored JSP property-group declarations and component
annotations explicitly; those fixtures no longer contribute misleading passes.
Multiple simultaneous deployments are supported (IBARRA-082 fixed).

Raw per-class Surefire XML/text and extracted WAR/server logs remain under
`ibarra-tck/target` immediately after the command; another `clean` replaces them.
Reproduce a class with the exact selector shown in [BUG.md](../BUG.md), prefixing
it with `ee.jakarta.tck.pages.`. All test failures remain visible; the smoke
subset is a separate green regression check and never rewrites this baseline.
