# Jakarta Pages 4.0.0 diagnostic baseline

Date: 2026-10-13. Temurin Java 25, Maven 3.9.16, macOS; Ibarra development
version `0.4.0-SNAPSHOT`, modular Foy/Chappe and published Peano snapshots.
This records compatibility checks, not a performance benchmark or certification.

```bash
./run-official-tck-pages-4.0.sh smoke --no-prepare
./run-official-tck-pages-4.0.sh all --no-install --no-prepare
```

Prerequisites were prepared by `prepare-tck.py`, preserving the official embedded
POM; the smoke command clean-installs the current reactor before execution. Distribution checksums
and exact preparation commands are in [README.md](README.md).

## Recorded results

| Measure | Result |
|---|---:|
| Official classes selected | 92 |
| Declared runtime-visible JUnit `@Test` methods in the official binary | 682 |
| Official Surefire entries reported | 682 |
| Passing test methods | 510 |
| Failing method entries | 172 |
| Class-level setup/deployment errors | 0 |
| Total reported errors | 172 |
| Assertion failures reported as Surefire `failure` | 0 |
| Skipped entries | 0 |
| Adapter regression tests (separate invocation) | 33/33 |
| CI smoke subset (factory/engine + character sequence/EL/encoding configuration) | 18/18 |

Surefire reports `Tests run: 682, Failures: 0, Errors: 172, Skipped: 0` and exits
nonzero. The TCK's HTTP assertion helper reports failures as exceptions, hence
`errors` rather than `failures`; zero in the failure column does not mean success.

**510/682 = 74.8% measured full-suite pass rate**. All 682 declared method entries
now execute across the 92 official classes; there are no class-level setup errors
or skipped entries. This records complete method execution for this distribution,
not certification: 172 methods still report errors.

The deferred-signature simple-name fix was rechecked with the full suite on
2026-10-13: the same 504 methods pass and the same 178 methods fail, with no
regression. The two deferred Vector method scenarios now pass translation but
remain blocked by direct EL method metadata (IBARRA-113/114), so this fix adds no
official passing method. The clean default reactor passes 285 tests; adapter
regressions remain 26/26 and the independent Maven example packages successfully.

Direct collection method tables then restore methodInfoTest and
positiveELDeferredMethodValueTest on the same date: 506 passes and 176 method
errors, zero setup errors/skips and no regression among previous passes. Public
java.base collection allocations discovered in compiled WAR/page bytecode receive
method-only application providers. All existing module services and native list
properties are preserved. The adapter passes 30 regressions, including a real HTTP
deferred/overloaded call and isolated-module metadata, conversion, exception,
resolution-flag, null-context and collision checks. No production dependency changes.

JSP writer output-state checks then restore unbufferedWriteForwardTest:
507 passes and 175 method errors, zero setup errors/skips, 41 completely passing
classes and no regression among previous passes. All 18 smoke methods pass.
The clean default reactor passes 290 tests, the adapter 31 tests and the independent
Maven example packages. Forwarding rejects emitted unbuffered output or a JSP flush
even when the Servlet response remains uncommitted. Error pages include after such
output, retaining the original exception; unwritten unbuffered and pending buffered
pages still forward. Partial write failures preserve the emitted-output state.

The parallel compiler batch restores positiveBeanPropertyEditorTest,
elInvocationAndUsageTest and precompileNegativeTest: 510 passes, 172 method
errors, zero setup/skips, 44 completely passing classes and no regression against
main's previously passing methods. The clean ordinary reactor passes 303 tests,
adapter 33/33, smoke 18/18 and the independent Maven example packages. Final
verification did not skip build provenance generation.

The delivered scope is direct literal BeanInfo editor selection, opaque
tagdependent bodies and the complete valid/invalid precompile request gate.
The three positive precompile methods already passed the official assertion before
the fix, but its case-sensitive unexpected text did not prove body suppression;
the new HTTP counter/alias regression does. The include-forward prototype is
withheld after a concrete child response-wrapper regression (IBARRA-119/130),
so positiveIncludeForwardTest is still an error and receives no coverage credit.

The denominator was inventoried from the verified official jar with Java 25:

```bash
javap -v -p -classpath jakarta-pages-tck-4.0.0.jar <all 92 names ending in IT.class>
```

Count only annotation display lines containing exactly
`org.junit.jupiter.api.Test`, not constant-pool references or comments in sources.

## Passing coverage

Thirty-nine classes pass completely, including JspFactory (3), JspEngineInfo (1),
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
| WAR adaptation | No setup failures remain. Legacy WAR libraries assemble into the private application module (IBARRA-102 fixed); explicit web.xml components use direct factories (IBARRA-100 fixed). Unadapted library metadata remains an explicit boundary. JSP property groups apply at build time; TLD keyword names no longer block deployment. Runtime JSP descriptor exposure remains pending upstream. |
| Tag files and full tag metadata | `urn:jsptagdir`, variables, TEI/TLV and related metadata remain I6/I7 work. |
| Ordinary TLD attributes | IBARRA-093/094 fixed: keyword names accepted and actual JavaBeans setter types selected/coerced from class files. Deferred/fragment protocols remain exact. |
| BeanELResolver fixture access | One method fails in the private application module; IBARRA-085. |
| JspIdConsumer | All three methods pass; IBARRA-086 fixed by stable per-occurrence generated identifiers. |
| TagLibraryValidator | Two methods fail; IBARRA-087. |
| TryCatchFinally | The official method passes; IBARRA-088 was an EL verifier-access issue, fixed through direct WAR property accessors. |
| Unbuffered forward | One method fails; IBARRA-089. |
| PropertyEditor conversion | One method fails; IBARRA-090. |
| Invalid precompile query | One method fails; IBARRA-091. |
| Signature tooling | One method fails with an empty signature-tool classpath; IBARRA-092. |

The previous runner baseline (PR #22) recorded 174 passes, 11 method errors and
54 setup errors. The per-page recovery stage recorded 427 passes, 179 method errors and 8 setup
errors: 421 additional method entries now execute instead of being blocked by
whole-WAR precompilation or unsupported JSP configuration. The unmodified
official tests remain authoritative; genuine negative translation errors are
served as errors, never replaced with canned passing responses.

Ordinary setter/name fixes then raise the confirmed count from 427 to 467,
and execution from 606 to 654 methods. Three keyword-related TLD setup failures
are removed. Four previously failing EL methods now pass; no previously passing
method regressed. Remaining method errors rise from 179 to 187 as additional
methods become runnable. The two stages are recorded separately from certification.

Descriptor component factories then raise confirmed passes from 467 to 470,
and execution from 654 to 660 methods, removing all three component setup blockers.
The newly runnable general configuration class reports three method errors;
remaining method errors are 190 and the two library deployments still fail setup.
No previously passing method regressed. A final repeat after constructor-failure
hardening used `all --no-install --no-prepare`, following the clean reactor install.

Legacy library assembly raises confirmed passes from 470 to 485 and execution
from 660 to all 682 methods. Both library setup blockers are removed; 22 newly
runnable methods yield 15 passes and 7 errors. No previous pass regressed.
The 197 remaining errors are method assertions, not deployment failures.

JspIdConsumer generation then restores sameJspIdTest, setJspIdTest and
multipleJspIdTest, raising passes from 485 to 488 and reducing method errors to
194. No previous pass regressed. Identifiers are constant per occurrence across
requests/includes/fragments; handlers remain fresh. Caller setter overwrite
protection is covered by IBARRA-104 and ordinary/named/dynamic regression cases.

Direct WAR bean property services then raise passes from 488 to 504 and reduce
method errors from 194 to 178. Sixteen restored methods cover BodyTagSupport,
TagSupport, TryCatchFinally and five scripting EL coercion scenarios. No previous
pass regressed. Complete method execution and zero deployment errors are preserved.
The runner's bounded property adaptation uses existing Peano services with private
packages/reflection denial; arbitrary methods/static members remain separate work.

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
