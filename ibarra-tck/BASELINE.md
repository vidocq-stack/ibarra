# Jakarta Pages 4.0.0 official TCK baseline

Date: 2026-10-17. Temurin Java 25, Maven 3.9.16, macOS; Ibarra development
version `0.4.0-SNAPSHOT` based on commit `add1204`, with the current Ibarra
working-tree changes applied, plus modular Foy/Chappe and published Peano
snapshots. This is the complete official Pages TCK compatibility result; formal
Jakarta certification is a separate process.

```bash
sdk env
./run-official-tck-pages-4.0.sh all
```

The script prepared the official TCK artifacts, built the Ibarra reactor and ran
the selected official classes. Distribution checksums and artifact preparation
commands are in [README.md](README.md). No provenance skip was used.

Foy #51 has merged; its parent/API/core snapshots include JSP property-group
URL-pattern routing. The post-merge Ibarra build and official smoke workflows
also completed successfully.

## Recorded results

| Measure | Result |
|---|---:|
| Official classes selected | 92 |
| Declared runtime-visible JUnit `@Test` methods in the official binary | 682 |
| Official Surefire entries reported | 682 |
| Passing test entries | 682 |
| Method-level errors | 0 |
| Class-level setup/deployment errors | 0 |
| Total reported errors | 0 |
| Assertion failures reported as Surefire `failure` | 0 |
| Skipped entries | 0 |
| Classes with no reported errors | 92/92 |
| Smoke subset methods passing within this full run | 18/18 |

Surefire reports `Tests run: 682, Failures: 0, Errors: 0, Skipped: 0` and exits
successfully. All 92 selected classes deploy and all 682 declared methods pass.
The measured full-suite pass rate is **682/682 (100%)**; no official method is
skipped or reports an error.

Before this full-suite snapshot, targeted reruns on 2026-10-14 passed all 11 methods in
`bodytagsupport.URLClientIT`, including `bodyTagSupportVariableSynchronizationTest`.
On 2026-10-14, another targeted rerun passed all 11 methods in
`simpletagsupport.URLClientIT`, including `simpleTagSupportVariableSynchronizationTest`
and `simpleTagSupportSkipPageExceptionTest`.
The combined targeted rerun on 2026-10-14 passed all four TEI metadata classes:
`functioninfo.URLClientIT` (3/3), `tagattributeinfo.URLClientIT` (7/7),
`tagdata.URLClientIT` (5/5) and `tagextrainfo.URLClientIT` (1/1), for 16/16
methods. The adapter passed 35/35. Generated page initialization invokes
`TagExtraInfo.validate` with the available `TagInfo`/`TagData`, and the generated
`TagLibraryInfo` exposes the TLD function catalogue. These results are included in
the complete run reported above.

On 2026-10-14, the focused official rerun for tag-file implicit objects passed
TCK-054 `implicitobjects.URLClientIT` (7/7). The subsequent focused TCK-056
`tagfiles.semantics.URLClientIT` rerun after IBARRA-141 passes all eight methods,
including dynamic attributes, declared attributes, context-wrapper scopes and
literal-path forwarding. Its former unresolved `request` deployment error was
fixed by IBARRA-140. The focused TCK-057 `tldres.URLClientIT` rerun after
IBARRA-142 passes all 12 methods, including `tldResPathAbsUriNotFoundTest`. The
TCK-018 `tagvariableinfo.URLClientIT` focused rerun after IBARRA-143 passes all five
methods. The full-suite rerun on 2026-10-17 includes these fixes and all later
tag-file work; the verified totals are recorded above.

Historical full-suite snapshots from 2026-10-14 are not directly comparable because
the set of methods that reached execution changed. An earlier `all --no-install`
run recorded 655 entries (513 passes, 142 errors, no setup errors). A fresh run on
merged commit `06a12bb`, before IBARRA-139, also recorded 655 entries but had 538
passes, 114 method errors and three class-level deployment errors (TCK-017, TCK-054
and TCK-056). The run after IBARRA-139 but before IBARRA-140 reported 669 entries,
553 passes, 114 method errors and two class-level deployment errors. The run on
`0c9207f` recorded 682 entries, 563 passes and 119 method errors, with no
class-level deployment errors. The post-IBARRA-142 full run reported 571 passes
and 111 errors. The full run after IBARRA-143 reported 576 passes and 106 errors;
the full run after IBARRA-144 reported 581 passes and 101 errors; the full run
after IBARRA-145 reported 595 passes and 87 errors, including all 14 TCK-014
methods. This full run after IBARRA-146 reports 600 passes and 82 errors,
including all 13 TCK-015 methods. The run after IBARRA-147 reports 603 passes and
79 errors, with TCK-021 and TCK-031 fully passing. The 2026-10-16 run after
IBARRA-148/149 reports 605 passes and 77 errors; TCK-023 and TCK-024 pass 3/3.
The complete 2026-10-17 run above supersedes these diagnostic snapshots and passes
all 682 methods with no regression or remaining TCK error.

Static XML output and the initial XML `.tagx` subset restore all six previously
failing output-action methods: `doctypeSystemPublicTest`, `simpleDefaultTest`, `doctypeSystemTest`,
`jspOutputJspRootOmitDeclDefaultTest`, `jspOutputOmitDeclValidValuesTest` and
`jspOutputUsageContextTest`. The two tag-file methods now compile static markup and
`jsp:output` omission metadata; requested declarations are UTF-8 and emitted at each
invocation without changing the caller's response charset. Standard `.tag` files,
directives, expressions, scripting, nested JSP actions and tag attributes remain
unsupported. Page declarations continue to use the response encoding and precede
static SYSTEM/PUBLIC DOCTYPE metadata and page output. All original official fixtures
remain unchanged.

On 2026-10-14 the isolated official
`ee.jakarta.tck.pages.api.jakarta_el.beanelresolver.URLClientIT#beanElResolverTest`
passes after the TCK adapter qualified-exports generated bean packages only to
`jakarta.el`; the current full-suite run includes that coverage.

The preceding typed-forward delivery restored exactly `positiveIncludeForwardTest`
against the merged 518-pass baseline, reaching 519 passes and 163 method errors.
Its ordinary reactor passed 319 tests; adapter 34/34 and the independent example
package passed. There are still 48 completely passing classes, including
all twelve include-action methods and all seven forward-action methods. The new
HTTP fixture additionally verifies child-added wrapper headers, cached writer
transformations, nested ancestors, stopped parent side effects and missing-target
404 behavior; the original official assertion alone checks only rendered body.

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

The signature/envelope batch then restores eight methods: the original API
signature check, jspConfigurationIncludes3Test, jspRootPrefixTest and all five
previous errors in jspdocument.general (including the real named-attribute/raw-body
fixture). Final result: 518/682 (76.0%), 164 errors, zero setup/skips, 47 completely
passing classes and no regression against merged PR #32. Default reactor 309,
adapter 33/33, smoke 18/18 and the independent example package pass.

The official signature driver now receives the exact staged spec APIs and its
java.base image. Tagdependent named attributes are evaluated normally while the
explicit body stays opaque. XML output accepts its specified yes/no aliases;
empty-body/context validation prevents the initially observed jspOutputBodyTest
regression. The six earlier output errors remain; no broader prolog/doctype support
is claimed. Typed include forwarding is still a separate upstream-dependent slice.

The denominator was inventoried from the verified official jar with Java 25:

```bash
javap -v -p -classpath jakarta-pages-tck-4.0.0.jar <all 92 names ending in IT.class>
```

Count only annotation display lines containing exactly
`org.junit.jupiter.api.Test`, not constant-pool references or comments in sources.

## Adapter extraction check (2026-10-23)

The Arquillian adapter moves from `ibarra-tck/src/test` into the published module
`ibarra-arquillian`, with no behaviour change. The official suite runs once before the
move, on base commit `53f9414` (the working tree held only the plan commit `3e366f1`,
which changes no code), and once after the whole change. Both runs use:

```bash
./run-official-tck-pages-4.0.sh --no-prepare all
```

Temurin Java 25, Maven 3.9.16, macOS. Classes are the official `*IT` classes that report
a Surefire result; the other columns are the final Surefire totals of the official
invocation.

| Run | Commit | Classes | Tests | Failures | Errors | Skipped |
|---|---|---:|---:|---:|---:|---:|
| before | `53f9414` | 92 | 682 | 0 | 0 | 0 |

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
| Tag files and full tag metadata | `urn:jsptagdir`, standard `.tag` scalar and dynamic attributes, isolated per-invocation page scopes, declared tag-file variables, literal-path `jsp:forward`, PageData TLV coverage and generated implicit objects are implemented (IBARRA-140/141; focused TCK-054 7/7 and TCK-056 8/8). `.tagx` actions, EL, fragments, forward parameters and complete tag metadata remain I6/I7 work. |
| Ordinary TLD attributes | IBARRA-093/094 fixed: keyword names accepted and actual JavaBeans setter types selected/coerced from class files. Deferred/fragment protocols remain exact. |
| BeanELResolver fixture access | IBARRA-085 fixed; the official method passes with qualified exports to `jakarta.el` only. |
| JspIdConsumer | All three methods pass; IBARRA-086 fixed by stable per-occurrence generated identifiers. |
| TagLibraryValidator | IBARRA-087 fixed: both official validator methods pass. PageData page and tag-file methods pass in focused runs; the latest aggregate counts are in the table above. |
| TryCatchFinally | The official method passes; IBARRA-088 was an EL verifier-access issue, fixed through direct WAR property accessors. |
| Unbuffered forward | The method passes; IBARRA-089 is fixed. |
| PropertyEditor conversion | The method passes; IBARRA-090 is fixed. |
| Invalid precompile query | The method passes; IBARRA-091 is fixed. |
| Signature tooling | The exact staged APIs and Java image pass; IBARRA-092 is fixed. |
| XML output | Four former errors now pass; the two remaining methods require XML tag files, IBARRA-136/TCK-033. |

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
