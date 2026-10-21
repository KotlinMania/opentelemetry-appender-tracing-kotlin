# Swift Export Rollout — Recipe for the Claude GitHub Agent

> **Distributed source of truth:** `template/SWIFT.md` is the canonical copy
> distributed to projects. Keep the KotlinMania-root `SWIFT.md` byte-for-byte
> synchronized with it. Update these copies through one coordinated writer.

> **Self-contained instruction set.** This file lives at the root of every
> repository that has the canonical KotlinMania Swift Export rollout
> committed to its `automation/swift-export-rollout` branch. The Claude
> GitHub Agent will be assigned a per-repo issue that asks it to walk this
> recipe top-to-bottom and finish the rollout by pushing the branch and
> opening the pull request. Nothing in this recipe depends on context from
> outside the current repository.

## TL;DR

A workspace-wide rollout already produced a local branch named
`automation/swift-export-rollout` with a single canonical commit (the
"rollout commit") that wires the Kotlin → Swift Export → SPM → swift-test
loop into the repository. **Your job, Claude, is to:**

1. **Push** the local `automation/swift-export-rollout` branch to `origin`.
2. **Open a pull request** against the base branch the rollout was made
   from (NOT necessarily `main`), using the title and body template in
   [§ PR template](#pr-template) below.
3. **Verify** the PR landed cleanly and CI was kicked off.
4. **Comment on the assigned issue** linking the new PR and close the
   issue with a one-line summary.

Do **NOT** modify any source files. Do **NOT** rebase, squash, or amend
the rollout commit. Do **NOT** change the branch base. If anything in
the precondition checks fails, [stop and report](#what-to-do-when-the-recipe-can-not-be-followed)
rather than improvising.

---

## Background — what's already on this branch

The rollout commit applies a strict, idempotent set of five changes
that are byte-identical across every KotlinMania repository it touched.
These were produced by `automation-artifacts/swift-export-rollout/apply.sh`
in the workspace, mirroring the canonical pattern that landed in
[`libc-kotlin`](https://github.com/KotlinMania/libc-kotlin) and was
proven end-to-end with a working `swift test` invocation against the
`embedSwiftExportForXcode`-produced Swift Package Manager package.

### The five changes

1. **`build.gradle.kts`** — **both** iOS Simulator framework binaries
   (`iosSimulatorArm64` *and* `iosX64`) now have `isStatic = true`.
   The Swift Export SPM bridge expects a static framework for the iOS
   Simulator so that the SPM linker can pull in the symbol
   implementations directly. Both simulator slices must match — if
   `iosSimulatorArm64` is static and `iosX64` is left dynamic, the
   `assembleDebugIosSimulatorFatFrameworkFor<Name>XCFramework` task
   fails with:

   ```
   Cannot create a fat framework from:
     <Name> - arm64 - static
     <Name> - x64 - dynamic
     All input frameworks must be either static or dynamic
   ```

   The other Apple targets (`iosArm64`, `tvosArm64`,
   `tvosSimulatorArm64`, `watchos*`, `macosArm64`) stay dynamic.

   > **Note**: earlier versions of this recipe and of `apply.sh` flipped
   > only `iosSimulatorArm64`. Repos that landed the rollout before this
   > correction need a follow-up commit adding `isStatic = true` to
   > `iosX64`. http-kotlin's `build.gradle.kts:218-231` is the working
   > reference shape.

2. **`.github/workflows/swift.yml`** — a new platform workflow with a
   `workflow_call:` trigger, matching the shape of the existing
   `macos.yml` / `ios.yml` / `tvos.yml` / etc. workflows. It:
     - Sets every Xcode-style environment variable that the Kotlin
       Multiplatform plugin's `BuildSPMSwiftExportPackage` task reads
       as input. The list includes `BUILT_PRODUCTS_DIR`,
       `TARGET_BUILD_DIR`, `SDK_NAME=macosx`, `CONFIGURATION=Debug`,
       `ARCHS=arm64`, `FRAMEWORKS_FOLDER_PATH=Frameworks`,
       `MACOSX_DEPLOYMENT_TARGET=14.0`, and crucially
       `DEPLOYMENT_TARGET_SETTING_NAME=MACOSX_DEPLOYMENT_TARGET` —
       which Xcode normally injects per-target but the Kotlin plugin
       has no default for. Without it,
       `BuildSPMSwiftExportPackage` fails its property validation
       with `property 'deploymentTargetSettingName' doesn't have a
       configured value`.
     - Runs `./gradlew embedSwiftExportForXcode --no-configuration-cache`
       to populate `build/swift-test/` with a `lib<Module>.a` static
       archive containing every `@ExportedBridge`-annotated function
       generated from the upstream Kotlin source, plus four
       `.swiftmodule/` bundles named `<Module>`, `KotlinRuntimeSupport`,
       `ExportedKotlinPackages`, and `KotlinRuntime`.
     - Changes directory into `swift-test-harness/` and runs
       `swift test`, which compiles and runs the smoke test against
       the just-produced Swift module.
     - Uploads the static archive, swiftmodule bundles, and the full
       generated SPM package tree as a `swift-export-artifacts` build
       artifact on both success and failure so a broken run is
       debuggable from the GitHub Actions run summary.

3. **`.github/workflows/ci.yml`** — a four-line `swift:` job is wired
   in next to the existing `wasm:` and `js:` orchestrator entries with
   the same `permissions: contents: read` ceiling. This makes the
   `swift.yml` workflow run as part of the regular CI orchestration.

4. **`swift-test-harness/`** — a new top-level directory containing:
     - `Package.swift` — a Swift Package Manager package depending on
       the Kotlin-generated SPM at
       `../build/SPMPackage/macosArm64/Debug` with one `testTarget`
       (`SwiftTestHarnessTests`). The testTarget carries a
       `linkerSettings` block with
       `unsafeFlags(["-L", "../build/swift-test", "-l<Module>"])` so
       that `swift test` (run outside an `xcodebuild`-driven
       invocation) can resolve the `__root____*` and KotlinError
       symbols from the static archive that `embedSwiftExportForXcode`
       drops next to the Sources/ tree. This linker hint is necessary
       because the Kotlin plugin's generated `Package.swift` does NOT
       reference its own `lib<Module>.a` as a binary target (one of
       the two upstream gaps noted in [§ Known upstream gaps](#known-upstream-gaps)
       below).
     - `Tests/SwiftTestHarnessTests/<Module>ExportTests.swift` — a
       smoke test that does `import <Module>` and asserts a single
       `XCTAssertTrue(true, ...)`. Because the import is at the
       module level, the file's mere ability to compile proves that
       `<Module>.swiftmodule` exists and is correctly placed; the
       fact that the test executable links proves the static archive
       is reachable; the fact that the test runs proves the full
       loop is green for this repo.
     - `.gitignore` — ignores the SPM `.build/` output directory.

5. **`.gitignore`** — appended with a Swift/Xcode workspace state
   section: `.swiftpm/`, `DerivedData/`, `*.xcuserstate`,
   `*.xcworkspace/xcuserdata/`, `*.xcodeproj/xcuserdata/`. The
   Kotlin Swift Export staging directory `build/swift-test/` is
   already covered by the existing `build/` rule.

### Background — what Swift Export does

Kotlin 2.2+ ships an experimental Swift Export feature
(`kotlin.experimental.swift-export.enabled`) that emits idiomatic
Swift bindings for a Kotlin Multiplatform module's exported API.
Instead of consuming the Kotlin output through Objective-C interop
(the legacy approach that produces `KotlinInt`, mangled names, and
`underscore_prefixed_calls`), Swift consumers get a real
`.swiftmodule` they can `import <Module>` against. Kotlin typealiases
are exported too; generic type parameters are erased to their upper
bounds rather than preserved as concrete Swift types.

The KotlinMania workspace standardized on Swift Export across every
repository that ships an XCFramework so that downstream Swift / Xcode
consumers don't have to opt into the legacy interop. The rollout
this recipe describes is the mechanical part — wiring up the build,
the test harness, and the CI workflow.

### Direct-export contracts and generated evidence

Use the [official direct Swift export guide](https://kotlinlang.org/docs/native-swift-export.html)
to assess the source API, then read the generated Swift declarations and
Kotlin bridges to verify what the installed toolchain actually exports.
Emission alone does not prove that an API satisfies the documented contract.

- Ordinary exported classes must be final and directly inherit from
  `Any`. The guide separately documents sealed hierarchies and
  cross-language inheritance through a Kotlin interface plus an open
  Kotlin base; those are not general permission to export exception
  subclasses.
- Generic parameters erase to their upper bounds, generic types are
  generally unsupported, and operator support is limited. A Kotlin
  `SynResult<T>` whose generated `getOrThrow()` returns
  `(any KotlinRuntimeSupport._KotlinBridgeable)?` is not a concrete,
  typed Swift result.
- `Unit` maps to `Void`, and constructors map to initializers. Diagnose
  generated conversion warnings separately from source API violations.
- Declarations requiring opt-in need the explicit module compiler
  `optIn` required by the guide. Opting in does not repair unrelated
  unchecked casts or redundant expressions.

For diagnostic APIs, follow
[the concrete SynError repair and verification rules](#diagnostic-apis-repair-the-exported-types-not-their-aliases)
below. A new name alone does not remove inherited or erased Kotlin APIs.

---

## Preconditions to verify before acting

Run these checks in order. If any of them fails, stop and report —
**do NOT attempt to fix the repository state**; the workspace-level
rollout will be re-run instead.

### 1. The rollout branch exists locally

```sh
git rev-parse --verify automation/swift-export-rollout
```

Must print a commit SHA. If this errors with "unknown revision", the
rollout never landed in this repo and there's nothing to push.

### 2. The rollout commit is the tip of the branch

```sh
git log -1 --format='%s' automation/swift-export-rollout
```

Must print exactly:

```
Wire Kotlin → Swift Export → swift test loop into CI
```

If the tip commit's subject is something else, someone has amended,
rebased, or added commits on top — stop and report.

### 3. The rollout files are present at the tip

```sh
git show --stat automation/swift-export-rollout
```

Must list at minimum:

- `build.gradle.kts` modified (1 insertion if multi-line iosSimulatorArm64 form, 4 insertions and 1 deletion if single-line form)
- `.github/workflows/swift.yml` new
- `.github/workflows/ci.yml` modified (4-line addition)
- `swift-test-harness/Package.swift` new
- `swift-test-harness/.gitignore` new
- `swift-test-harness/Tests/SwiftTestHarnessTests/<Module>ExportTests.swift` new
- `.gitignore` modified

It may also include this `SWIFT_EXPORT_ROLLOUT.md` file at the repo
root — that's the recipe being committed alongside the rollout.

If any of the seven (or eight, counting this recipe) files are missing
from the diff, stop and report.

### 4. The base branch is known

The rollout branch was created off the repo's HEAD at the time of the
rollout — that's the **base branch**, and that's where the PR should
target. Determine it by walking back through the local reflog:

```sh
git reflog show automation/swift-export-rollout | tail -5
```

The earliest entry's "from" branch is the base. In most repos this
will be `main`. In a small handful it will be a feature branch like
`setup-ci`, `automation/kotlinmania-ci-h14-4`, or
`port/drop-bignum-add-build-gate` (these are repos that had
in-progress work at rollout time; the rollout was branched off the
feature branch to keep scopes clean).

If you cannot determine the base from the reflog, default to `main`
**but** flag this in your final summary so the human reviewer can
verify.

### 5. Origin remote exists and points at GitHub

```sh
git remote get-url origin
```

Must be a `github.com/KotlinMania/<repo>` URL (HTTPS or SSH).

---

## The work

### Step 1 — push the rollout branch

```sh
git push -u origin automation/swift-export-rollout
```

Expected outcome: the remote ref is created (or fast-forwarded if
it already existed from a previous attempt). If the push is rejected
because the remote ref exists and has diverged, **do not force-push**;
stop and report.

### Step 2 — open the pull request

Use `gh pr create` with the title and body from
[§ PR template](#pr-template) below. The base branch is the one you
determined in precondition #4.

```sh
gh pr create \
    --base "<base-branch>" \
    --head "automation/swift-export-rollout" \
    --title "Wire Kotlin → Swift Export → swift test loop into CI" \
    --body-file <(cat <<'BODY'
... see § PR template ...
BODY
)
```

Capture the returned PR URL — you'll need it for the issue comment.

### Step 3 — verify CI kicked off

```sh
gh pr view <pr-number> --json statusCheckRollup
```

If the field is empty or shows "queued" / "in_progress", that's
expected — the workflow_call orchestration may take a minute to fan
out. Don't wait for completion; just verify a run was registered.

If the PR was created against a base branch that does NOT have CI
configured to run on PRs (e.g., a `chore/*` feature branch with no
PR triggers), there will be no check rollup. That's still fine —
note it in the summary.

### Step 4 — close the assigned issue

Post a comment on the assigned issue (the one that asked you to do
this work) with the format below, then close the issue.

```
Pushed `automation/swift-export-rollout` and opened PR:
<pr-url>

PR base: <base-branch>
CI status: <queued | in_progress | n/a — no PR triggers on base>
Rollout commit: <commit-sha>

Done.
```

---

## PR template

### Title

```
Wire Kotlin → Swift Export → swift test loop into CI
```

### Body

```markdown
## Summary

This PR wires the Kotlin → Swift Export → SPM → `swift test` loop into
this repository's CI, mirroring the canonical pattern established by
[libc-kotlin](https://github.com/KotlinMania/libc-kotlin) and proven
end-to-end with a passing `swift test` invocation against the
`embedSwiftExportForXcode`-produced Swift Package Manager package.

The full recipe for the rollout — including the rationale for each
change, the two upstream Kotlin Multiplatform plugin gaps it works
around, and the precondition / verification checks the Claude agent
ran before opening this PR — is checked in at the repo root as
[`SWIFT_EXPORT_ROLLOUT.md`](./SWIFT_EXPORT_ROLLOUT.md).

## What this PR changes

- `build.gradle.kts` — `iosSimulatorArm64` framework binary now has
  `isStatic = true` so the Swift Export SPM bridge can link against
  it.
- `.github/workflows/swift.yml` (new) — `workflow_call:` platform
  workflow that sets the full Xcode-style environment, runs
  `./gradlew embedSwiftExportForXcode`, then runs `swift test` from
  `swift-test-harness/`. Uploads the static archive and `.swiftmodule`
  bundles as artifacts on success-or-failure.
- `.github/workflows/ci.yml` — adds the `swift:` job alongside
  `wasm:` and `js:`.
- `swift-test-harness/` (new) — a Swift Package Manager package with
  one smoke test that asserts the Swift module imports cleanly.
- `.gitignore` — Swift/SPM workspace state section.

## Test plan

- [ ] The `Build (Swift)` job in CI lands BUILD SUCCESSFUL.
- [ ] The `Run swift test against Kotlin-exported module` step prints
      1 test executed, 0 failures.
- [ ] The uploaded `swift-export-artifacts` archive contains
      `lib<Module>.a` and the four `.swiftmodule/` directories.

## Known upstream gaps

Two issues live in the upstream Kotlin Multiplatform plugin and are
worked around per-repo in this PR. Both would, if fixed upstream,
allow every Kotlin Swift Export consumer to drop the local workaround
at once:

1. **`Package.swift` doesn't reference its own static archive.** The
   Kotlin plugin emits Swift source files for the SPM package but
   does not declare `lib<Module>.a` as a binary target or expose its
   link path. `swift-test-harness/Package.swift` compensates with
   `unsafeFlags(["-L", "../build/swift-test", "-l<Module>"])`.
2. **`BuildSPMSwiftExportPackage` validates
   `deploymentTargetSettingName` as a non-optional input** but
   provides no default. Outside Xcode the env var
   `DEPLOYMENT_TARGET_SETTING_NAME` isn't set, so the task fails
   property validation. `swift.yml` sets it explicitly.

🤖 Opened by the Claude GitHub Agent following
[`SWIFT_EXPORT_ROLLOUT.md`](./SWIFT_EXPORT_ROLLOUT.md).
```

---

## What to do when the recipe can NOT be followed

If any precondition check fails, or if any step errors out in a way
not anticipated by the recipe, **do NOT improvise**. Specifically:

- Do NOT force-push.
- Do NOT amend, rebase, or squash the rollout commit.
- Do NOT modify any source files.
- Do NOT change the PR base.
- Do NOT close the issue without a PR link, unless the rollout truly
  doesn't apply to this repo (e.g., no XCFramework declaration, no
  Apple targets).

Instead, post a comment on the assigned issue with the format:

```
Cannot complete rollout — <one-line summary>.

What I observed:
<copy/paste the relevant `git`, `gh`, or shell output>

What this implies:
<short interpretation>

Leaving the issue open for a human to follow up. The local
`automation/swift-export-rollout` branch (if it exists) has not
been modified.
```

Then stop. A human will pick it up.

---

## Known upstream gaps

1. **Kotlin-plugin-generated `Package.swift` doesn't reference its
   static archive.** Workaround in `swift-test-harness/Package.swift`:
   `unsafeFlags(["-L", "../build/swift-test", "-l<Module>"])`.

2. **`BuildSPMSwiftExportPackage.deploymentTargetSettingName` has no
   default.** Workaround in `.github/workflows/swift.yml`:
   `DEPLOYMENT_TARGET_SETTING_NAME=MACOSX_DEPLOYMENT_TARGET` in the
   env block for the macOS build.

3. **`KotlinStdlib.kt` unchecked-cast bridge** (the big one). When a
   declaration that reaches the Swift boundary exposes
   `kotlin.Result<T>` or `kotlin.Throwable` / `kotlin.Exception`, the
   Kotlin plugin generates
   `build/SwiftExport/<target>/<config>/files/KotlinStdlib/KotlinStdlib.kt`
   containing `Any?` → `Array<Any?>` unchecked casts. Under the
   workspace-canonical `allWarningsAsErrors.set(true)` these become
   compile errors.

   **First-choice fix:** keep the Apple-facing public API usable. Replace
   `kotlin.Result<T>` / `Throwable` surfaces with Swift-exportable outcome
   and error types, or keep the Swift-hostile implementation internal and
   expose a concrete facade.

   ```kotlin
   package io.github.kotlinmania.example

   public sealed class ParseOutcome {
       public data class Ok(public val value: Value) : ParseOutcome()
       public data class Err(public val error: ParseError) : ParseOutcome()
   }

   public data class ParseError(public val message: String)

   public class Parser {
       public fun tryParse(input: String): ParseOutcome =
           when (val parsed = parseValue(input)) {
               is InternalParseResult.Ok -> ParseOutcome.Ok(parsed.value)
               is InternalParseResult.Err -> ParseOutcome.Err(ParseError(parsed.message))
           }
   }
   ```

   `@HiddenFromObjC` is not a fix. It only removes the declaration from
   the Objective-C / Swift framework surface; Kotlin callers still see it,
   but Apple consumers do not. Repair the public API by renaming colliding
   declarations and replacing Swift-hostile shapes with strongly-typed,
   bridgeable Kotlin declarations. See
   [`triage-kotlin-stdlib-in-public-api.md`](./triage-kotlin-stdlib-in-public-api.md)
   for the workspace-wide hit list.

4. **Swift Export does NOT preserve Kotlin sealed-subclass casts.**
   Discovered empirically while making http-kotlin's `swift test`
   actually link. A `sealed class Foo { class Ok(...) : Foo(); class
   Err(...) : Foo() }` does not expose `Http.FooOk` / `Http.FooErr`
   as Swift types that consumers can reach with `as? Http.FooOk`.
   The Swift consumer can `import` `Foo` but cannot pattern-match on
   its sealed subclasses. **Use a flat class with nullable fields
   and boolean predicates instead** — see "Recipe for replacing
   `kotlin.Result<T>` in a public API" below.

5. **Swift accesses Kotlin companion objects as `Type.Companion.shared.member`**,
   not `Type.companion.member`. Common gotcha when writing the smoke
   test in `swift-test-harness/`. The Swift binding generated by the
   plugin uses a capital `Companion` struct type with a `shared`
   singleton accessor, not a lowercase `companion` property.

6. **Mutable generic collections in public APIs crash the bridging code.**
   `MutableList<T>` / `MutableSet<T>` / `MutableMap<K, V>` as public
   property types or return types cause the Swift Export compiler
   plugin to crash while emitting Swift-to-Kotlin bridging code,
   typically with type mismatch errors between `MutableList<Any?>`
   and `MutableList<T>`. The plugin's generic erasure to upper bound
   (`Any?`) collides with the mutability covariance constraints.
   **Per-repo workaround**: change public properties and return types
   to their immutable equivalents (`List<T>`, `Set<T>`, `Map<K, V>`).
   Where mutability is genuinely needed internally, hide the mutable
   collection with `internal` and expose the read-only view publicly.

7. **`macos-latest` ships an SDK older than the Kotlin/Native platform
   caches reference.** The Kotlin/Native 2.3.x distribution embeds
   prebuilt caches —
   `liborg.jetbrains.kotlin.native.platform.{CoreFoundation,Foundation,darwin}-cache.a`
   — that reference macOS 26 / iOS 26 SDK symbols
   (`_NSCalendarIdentifier{Vikram,Bangla,Dangi,Gujarati,…}`,
   `_NSURLUbiquitousItem{IsSyncPaused,SupportedSyncControls}Key`,
   `_mach_vm_reclaim_update_kernel_accounting_trap`,
   `_xpc_{connection,session,listener}_set_peer_requirement`,
   `_kCF{Bangla,Dangi,…}Calendar`). On GitHub Actions, `macos-latest`
   currently resolves to `macos-15` with Xcode 16 / macOS 15 SDK, so
   `swift test` link fails with "Undefined symbols for architecture
   arm64" on every symbol the cache references but the SDK doesn't
   carry. A consumer whose Kotlin code never reaches into those APIs
   may not notice (libc-kotlin happens to be in this lucky bucket),
   but any consumer that does (schemars-kotlin et al.) link-fails.
   **Workaround**: in `.github/workflows/swift.yml` set
   `runs-on: macos-26`. The macos-26 image's default Xcode is in the
   26.x line (carries 26.0.1 / 26.1.1 / 26.2 / 26.3 / 26.4.1 / 26.5;
   26.4.1 is the image default at the time of writing) and supplies
   the SDK stubs the platform cache expects.

   > **Do NOT add a `maxim-lobanov/setup-xcode@v1` step.** Earlier
   > revisions of this recipe pinned the Xcode version explicitly via
   > that third-party action. KotlinMania's enterprise GitHub Actions
   > policy does not whitelist third-party actions, and any workflow
   > that references one returns `startup_failure` at dispatch time
   > with zero jobs spawned — confirmed across two consecutive
   > dispatches on itertools-kotlin/automation/swift-export-rollout
   > (runs `26326086457`, `26326121639`). The pin step was redundant
   > anyway: `swift --version` from a working run on macos-26 prints
   > `Target: arm64-apple-macosx26.0` and `Xcode 26.4.1` straight from
   > the image default. Keep `runs-on: macos-26`; omit the pin step.

These first seven gaps are filable upstream against the Kotlin Multiplatform plugin
(or, for #7, against the Kotlin/Native distribution's platform-cache
build pipeline). When they're fixed, the workarounds in this recipe
become deletable.

8. **The Kotlin Swift Export bridge file casts `Any` / `Any?` to typed
   Kotlin values under `allWarningsAsErrors=true`.** The plugin-generated
   bridge at `build/SwiftExport/<target>/<config>/files/<Module>/<Module>.kt`
   pushes every typed Kotlin value through an untyped `Any` channel
   and then casts back to the concrete type on the way out. Under the
   workspace-canonical `allWarningsAsErrors.set(true)` every such cast
   trips `Unchecked cast` and `compileSwiftExportMainKotlin<Target>`
   fails. There are **two distinct API shapes** that provoke this; the
   bridge file looks similar in both cases but the source-side fix is
   different.

   **Trigger 8a — unconstrained generic public classes.** When a
   public Kotlin API exposes a generic class whose type parameter is
   unconstrained (`WithPosition<T>`, `ZipEq<L, R>`, `Unfold<St, T>`,
   etc.), the plugin erases `T` to `kotlin.Any?` in the bridge and
   emits:

   ```kotlin
   val ____self = dereferenceExternalRCRef(self) as io.github.kotlinmania.<pkg>.ZipEq<kotlin.Any?, kotlin.Any?>
   // w: Unchecked cast: 'Any?' to 'ZipEq<Any?, Any?>'.
   ```

   Same shape as gap #3 but for generic public types rather than
   `kotlin.Result`. The flat-class workaround in [§ Recipe for replacing
   `kotlin.Result<T>` in a public API](#recipe-for-replacing-kotlinresultt-in-a-public-api)
   does NOT apply — these are iterator/wrapper types that cannot be
   flattened without unfaithful translation.

   *Per-repo fix:* stop exposing the unconstrained generic classes.
   Swift Export only emits bridge code for symbols visible in the
   public API surface — internal types are skipped, so no
   `as <UserClass><Any?>` cast gets generated for them. The pattern
   from itertools-kotlin commit `40513a5`:

   - Mark every public unconstrained generic iterator/wrapper class
     `internal class` (the class itself, its companion factories, and
     any nested sealed subclasses).
   - Public factory functions keep their generic type parameters but
     declare their return type as a stdlib interface
     (`Iterator<T>`, `Sequence<T>`, `Iterable<T>`, `List<T>`, etc.)
     instead of the concrete internal class. The internal class still
     implements that interface, so callers get the same behavior with
     no API change visible to Kotlin or Swift consumers.
   - Tests in `commonTest` of the same module retain visibility of the
     internal classes via Kotlin's module-private rules, so
     type-specific assertions (`sizeHint`, internal `state`, `putBack`,
     etc.) keep working by constructing the internal class directly.
     Iteration-shape assertions can continue to use the public
     factory.

   **Trigger 8b — Kotlin function types in public API positions.** A
   public field, parameter, or return type of `() -> R` / `(A) -> R` /
   `(A, B) -> R` triggers the same `Unchecked cast` shape, even when
   the *enclosing* class has no generic parameter. The Kotlin source
   has no `<T>` in sight, but Kotlin function types compile to
   `Function0<R>` / `Function1<A, R>` / ... under the hood — same
   erasure surface, same untyped bridge channel. The bridge emits:

   ```kotlin
   val ____self = dereferenceExternalRCRef(self) as kotlin.Function0<kotlin.Long>
   // w: Unchecked cast of 'Any' to '() -> Long'.
   ```

   Live in-the-wild example from tree-sitter-language-kotlin CI on
   2026-05-23 (`compileSwiftExportMainKotlinMacosArm64` failed on
   `build/SwiftExport/macosArm64/Debug/files/TreeSitterLanguage/TreeSitterLanguage.kt:12:37`
   with `Unchecked cast of 'Any' to '() -> Long'.`):

   ```kotlin
   // Source (Language.kt) — no visible generic, but () -> Long erases
   class LanguageFn private constructor(private val raw: () -> Long) {
       companion object { fun fromRaw(f: () -> Long): LanguageFn = LanguageFn(f) }
       fun intoRaw(): () -> Long = raw
   }
   ```

   *Per-repo fix:* replace the public function-type with a named
   `fun interface` (single-abstract-method interface). SAM conversion
   lets every existing lambda call site stay unchanged
   (`LanguageFn.fromRaw { 42L }` still compiles), but the bridge sees a
   stable nominal type instead of an erased `FunctionN<...>`. The
   pattern:

   ```kotlin
   // Named SAM interface — bridge-friendly, lambda-friendly
   fun interface LanguageProvider {
       fun call(): Long
   }

   class LanguageFn private constructor(private val raw: LanguageProvider) {
       companion object {
           fun fromRaw(f: LanguageProvider): LanguageFn = LanguageFn(f)
       }
       fun intoRaw(): LanguageProvider = raw
   }
   ```

   `fun interface` is the right knob — not a plain `interface` (loses
   SAM conversion at call sites) and not `Function0<Long>` (same
   erasure problem in the bridge). If the function-type value is
   internal-only, marking the field `internal` is also valid.

   **Primary solution when Kotlin compatibility must stay public.** Keep
   the public Kotlin API source-compatible by changing the surface to
   bridgeable nominal types, not by hiding declarations. For trigger 8a,
   public factories keep their Kotlin generic parameters but return
   stdlib interfaces (`Iterator<T>`, `Sequence<T>`, `Iterable<T>`,
   `List<T>`, etc.) while the concrete implementation class becomes
   internal. For trigger 8b, replace public function-type positions with
   named `fun interface` SAMs so existing lambda call sites still compile
   and Swift Export sees a stable type name. For Swift and Java emitted
   name collisions, rename the Kotlin declaration or file itself and
   migrate callers; do not preserve the old colliding spelling with a
   typealias or platform naming annotation. The compatibility path is a
   real bridgeable API, not an annotation tag.

   **DO NOT** scope `allWarningsAsErrors=false` to the
   `compileSwiftExportMain*` task family for either trigger. That was
   tried first (itertools-kotlin PR #22 for trigger 8a) and is symptom
   suppression: it leaves the unchecked casts in the bridge and only
   mutes the compiler. The `tasks.matching { ... }.withType<KotlinCompilationTask<*>>()`
   block (and any `KotlinCompilationTask` import that accompanies it)
   should be deleted whenever found.

   With either fix in place, `allWarningsAsErrors=true` applies
   uniformly. The bridge surface shrinks accordingly — in itertools-kotlin
   it went from ~20 `@ExportedBridge` functions and 12 generic
   `@file:BindClassToObjCName(... :: class, ...)` annotations down to
   6 `@ExportedBridge` functions and zero generic class bindings,
   leaving only the genuinely-public concrete types (e.g. `Position`)
   bridged to Swift. itertools-kotlin commit `40513a5` is the canonical
   reference for trigger 8a; tree-sitter-language-kotlin (the
   `LanguageFn` / `LanguageProvider` rewrite) is the canonical reference
   for trigger 8b. tree-sitter-kotlin's batch SAM-ification of seven
   public function-type surfaces (preemptive, not reactive — done from
   the bridge-clean source before CI surfaced the warnings) is the
   canonical reference for the audit-and-sweep pattern below.

   *Audit grep (run once per repo after a fresh `embedSwiftExportForXcode`):*

   ```sh
   # Combined audit — both triggers, both shapes the bridge writes
   grep -nE "as kotlin\\.Function[0-9]+<|as io\\.github\\.kotlinmania\\.[^<]+<kotlin\\.Any" \
       build/SwiftExport/macosArm64/Debug/files/*/*.kt
   ```

   Every match in a `kotlin.FunctionN<...>` is trigger 8b (function
   type in public surface); every match in
   `io.github.kotlinmania.<pkg>.<Type><kotlin.Any` is trigger 8a
   (unconstrained generic). Both are fixable per-repo via the
   recipes below. No matches rule out only those searched patterns;
   still check the documented source contracts and the complete
   generated compilation diagnostics.

9. **Audit coroutine export against the installed toolchain and strict
   generated compilation.** The historical `crossterm-kotlin` rollout
   (public `Flow<InternalEvent>` event stream) encountered the following
   configuration, runtime, cache and package issues. Diagnose each from
   actual output; they are not an unconditional four-part recipe.
   One observed symptom was that the generated
   `OrgJetbrainsKotlinxKotlinxCoroutinesCore.swift` references a
   `KotlinCoroutineSupport` module that is neither imported nor emitted
   (`cannot find type 'KotlinCoroutineSupport' in scope`).

   **9a — Verify configuration rather than inferring runtime inclusion
   from a setting name.** In the inspected Kotlin 2.4.20 KGP action,
   module `settings` become `SwiftModuleConfig.experimentalFeatures`;
   the global `SwiftExportConfig` coroutine-support Boolean is separate.
   Removing `settings["enableCoroutinesSupport"] = "true"` from Syn did
   not remove `KotlinCoroutineSupport` from freshly generated output.
   Do not claim that removing an unused module setting fixes runtime
   warnings. Check the resolved plugin, actual configuration and emitted
   module inputs. Use documented configuration supported by that
   installed version; do not invent settings or mutate cached tools.

   **9b — Keep `allWarningsAsErrors=true` for generated bridge and runtime
   compilation. There is no coroutine-runtime exception.** This replaces
   the former recipe that disabled strict warnings on `compileSwiftExport*`.
   A warning in generated source still fails the gate.

   Classify the cause before changing code. Source API shapes can pull
   unsupported stdlib bridges into the output; repair those shapes as
   described under Class Stdlib. The documented explicit compiler opt-in
   resolved Syn's inheritance opt-in diagnostic in the inspected 2.4.20
   run with strict warnings retained. It did not fix the runtime's two
   iterator casts or redundant Elvis expression.

   Concrete constructor bridges also emitted
   `return run { _result; true }` after initialization and produced
   `UNUSED_EXPRESSION`. A nominal callback fixed its separate Function1
   cast without fixing that constructor conversion. Preserve these
   distinctions in the evidence. Verify any generator/runtime repair
   through regeneration and strict compilation; a proposed rewrite or
   successful source compilation is not a completed bridge repair.
   Do not patch generated bridge sources, suppress diagnostics, or edit
   installed compiler/cache JARs to manufacture a passing gate.

   **9c — Disable the Kotlin/Native incremental cache.** Linking the
   Swift Export binary builds a K/N cache for `…_swiftExportMain.klib`,
   and the generated `SwiftFlowIterator` (a `NativePtr` continuation)
   hits an internal compiler error in C-bridge lowering
   (`doesn't correspond to any C type: kotlin.native.internal.NativePtr`,
   in `CBridgeGen.convertBlockPtrToKotlinFunction`). The compiler's own
   message recommends the workaround; set it in `gradle.properties`:

   ```properties
   kotlin.incremental.native=false
   ```

   Correctness-neutral (cache only). Revisit when the K/N
   Swift-Export-plus-coroutines cache path is fixed upstream.

   **9d — Inject a `platforms` declaration into the generated SPM
   `Package.swift`.** The Kotlin-generated `Package.swift` omits a
   `platforms:` clause, so a standalone `swift test` builds it below
   macOS 10.15 and every Swift Concurrency symbol in the coroutine
   bridge (`Task`, `AsyncSequence`, `withUnsafeThrowingContinuation`, …)
   fails with *"is only available in macOS 10.15 or newer."* Setting
   `MACOSX_DEPLOYMENT_TARGET` in the embed env (gap-#7 / the workflow)
   does NOT propagate into the generated manifest. Patch it in the
   `swiftExportSmokeTest` task, between `embedSwiftExportForXcode` and
   `swift test` (`name:` must precede `platforms:` in the initializer):

   ```kotlin
   val generatedPackageSwift =
       layout.buildDirectory.file("SPMPackage/macosArm64/Debug/Package.swift").get().asFile
   if (generatedPackageSwift.exists()) {
       val text = generatedPackageSwift.readText()
       if (!text.contains("platforms:")) {
           generatedPackageSwift.writeText(
               text.replaceFirst(
                   Regex("(name:\\s*\"[^\"]*\",)"),
                   "$1\n    platforms: [.macOS(.v14)],",
               ),
           )
       }
   }
   ```

   Also give `swift-test-harness/Package.swift` the same
   `platforms: [.macOS(.v14)]`. If a prior failed run cached a bad
   manifest, clear `swift-test-harness/.build` once.

   **Where Flow-exporting symbols live in the generated package.**
   Inspect the emitted SPM targets and dependencies, including
   `KotlinCoroutineSupport` and the exported coroutine library. The
   historical crossterm smoke result under the former warning exception
   does not establish that current strict compilation and tests pass.
   Record fresh results for the exact source, plugin and configuration.

## Recipe for replacing `kotlin.Result<T>` in a public API

The canonical pattern from http-kotlin commits `a179143` and the
follow-up that removed `!!`. Two principles guide the shape:

  * **The class invariant ("exactly one of value/error is non-null")
    is encoded explicitly with `init { require(...) }`.** That makes
    the invariant a real fact the type-checker can rely on after
    construction.
  * **No `!!` and no `@Suppress("UNCHECKED_CAST")`.** Both are
    workspace-forbidden per every repo's `CLAUDE.md`. `!!` is the
    runtime cousin of `@Suppress`: it suppresses the null-safety
    type-check at the cost of silencing a real bug class. The
    `when { value != null -> ...; error != null -> ...; else ->
    error("...") }` form below reads only the field whose
    non-null-ness is guaranteed in that branch, with the unreachable
    `else` as a defensive belt-and-suspenders for invariant
    violations.

```kotlin
/**
 * Result of [SomeApi.someFactoryThatCanFail].
 *
 * Flat-class shape (rather than sealed Ok / Err variants) — the
 * Kotlin Swift Export plugin does not currently emit Swift bindings
 * that let consumers pattern-match on sealed subclasses. A flat
 * class with `isSuccess()` / `isFailure()` predicates and nullable
 * `value` / `error` accessors bridges cleanly to Swift, where the
 * consumer uses `if result.isSuccess() { result.value?.thing() }`.
 *
 * The class invariant — exactly one of [value] / [error] is non-null —
 * is enforced at construction by [init]. That makes the otherwise
 * unreachable branch in [getOrThrow] expressible without `!!`.
 */
class SomeResult internal constructor(
    val value: SomeType?,
    val error: SomeConcreteError?,
) {
    init {
        require((value == null) != (error == null)) {
            "SomeResult must carry exactly one of value or error (got value=$value, error=$error)"
        }
    }

    companion object {
        internal fun ok(value: SomeType) = SomeResult(value, null)
        internal fun err(error: SomeConcreteError) = SomeResult(null, error)
    }

    fun getOrThrow(): SomeType = when {
        value != null -> value
        error != null -> throw SomeException(error)
        else -> error("SomeResult class invariant violated: both value and error are null")
    }

    fun getOrNull(): SomeType? = value

    fun isSuccess(): Boolean = value != null
    fun isFailure(): Boolean = error != null
}
```

### What NOT to do

These patterns look like they work but quietly suppress bugs:

```kotlin
// WRONG: !! suppresses the null check, hiding any future bug where
// the class invariant is violated. The `error` field "should never"
// be null when `value` is null, but the type system can't see that.
fun getOrThrow(): SomeType = value ?: throw SomeException(error!!)

// WRONG: `internal val error` hides the field from the public Swift
// surface to dodge an export problem, but Swift consumers legitimately
// need to inspect the failure. The fix is to make the Swift Export
// boundary handle the type, not to hide the API.
class SomeResult internal constructor(
    val value: SomeType?,
    internal val error: SomeConcreteError?,
)

// WRONG: sealed Ok/Err variants don't survive Swift Export — see
// upstream gap #4 above. Compiles in Kotlin but the Swift consumer
// can't do `as? SomeResultOk`.
sealed class SomeResult {
    class Ok(val value: SomeType) : SomeResult()
    class Err(val error: SomeConcreteError) : SomeResult()
}
```

---

## Recipe for hiding unconstrained generic iterator classes

The canonical pattern from itertools-kotlin commit `40513a5`. Applies
whenever a public Kotlin API exposes a generic class whose type
parameter is unconstrained (`class Foo<T>`, `class Bar<L, R>`, etc.)
and the class is reachable through the module's exported surface.

The principle: **public factories, internal implementations.** Swift
Export only emits bridge code for symbols that are part of the
public API — `internal` types are skipped. So the offending unchecked
`Any?` casts disappear if the concrete class is hidden behind a stdlib
interface return type.

### Before

```kotlin
package io.github.kotlinmania.itertools

class ZipEq<A, B>(
    private val left: Iterator<A>,
    private val right: Iterator<B>,
) : Iterator<Pair<A, B>> {
    override fun hasNext(): Boolean = TODO()
    override fun next(): Pair<A, B> = TODO()
}

fun <A, B> zipEq(left: Iterator<A>, right: Iterator<B>): ZipEq<A, B> =
    ZipEq(left, right)
```

Plugin-generated bridge file (`build/SwiftExport/.../Itertools.kt`) ends up with:

```kotlin
@ExportedBridge
fun __root___ZipEq_next(self: kotlin.native.internal.NativePtr): kotlin.native.internal.NativePtr {
    val ____self = dereferenceExternalRCRef(self) as io.github.kotlinmania.itertools.ZipEq<kotlin.Any?, kotlin.Any?>
    // ^^^ Unchecked cast: 'Any?' to 'ZipEq<Any?, Any?>'
    ...
}
```

Under `allWarningsAsErrors.set(true)` that warning becomes an error
and `compileSwiftExportMainKotlin<Target>` fails.

### After

```kotlin
package io.github.kotlinmania.itertools

internal class ZipEq<A, B>(
    private val left: Iterator<A>,
    private val right: Iterator<B>,
) : Iterator<Pair<A, B>> {
    override fun hasNext(): Boolean = TODO()
    override fun next(): Pair<A, B> = TODO()
}

fun <A, B> zipEq(left: Iterator<A>, right: Iterator<B>): Iterator<Pair<A, B>> =
    ZipEq(left, right)
```

The class is `internal`, so it never enters the Swift Export bridge.
The factory's return type changed from `ZipEq<A, B>` (the concrete
class) to `Iterator<Pair<A, B>>` (the stdlib interface). Kotlin
consumers in other modules can still call `zipEq(...)` and iterate
the result; Swift consumers get a bridge for the factory function
alone, with no `<Any?>` cast.

### Tests

Tests in `commonTest` of the same module retain visibility of
`internal` classes (Kotlin's module-private rules). When a test
asserts on a type-specific member that isn't part of `Iterator<T>` —
e.g. a custom `sizeHint`, `state`, `putBack`, internal counter —
construct the internal class directly instead of going through the
factory:

```kotlin
// commonTest can still see ZipEq's internal members
@Test
fun zipEqSizeHint() {
    val iter = ZipEq(listOf(1).iterator(), listOf("a", "b").iterator())
    assertEquals(1, iter.sizeHint())
}

// Iteration-shape assertions can still use the public factory
@Test
fun zipEqIterationShape() {
    val seq = zipEq(listOf(1, 2).iterator(), listOf("a", "b").iterator()).asSequence().toList()
    assertEquals(listOf(1 to "a", 2 to "b"), seq)
}
```

### What NOT to do

```kotlin
// WRONG: silencing the warning leaves the unchecked Any? cast in the
// bridge file. The plugin's runtime cast remains and the type-erased
// shape of the bridge stays the same — only the compiler is muted.
// Itertools-kotlin PR #22 tried this and was reverted by PR after.
tasks.matching { it.name.startsWith("compileSwiftExportMain") }
    .withType<KotlinCompilationTask<*>>()
    .configureEach {
        compilerOptions.allWarningsAsErrors.set(false)
    }

// WRONG: making the class `public abstract` and exposing only a
// public interface doesn't help — Swift Export still bridges the
// abstract class and emits the same `Any?` cast.
abstract class ZipEq<A, B> : Iterator<Pair<A, B>>

// WRONG: a `typealias` to a stdlib interface doesn't hide the
// underlying class from Swift Export. The original class still
// participates in bridging.
typealias ZipEqAlias<A, B> = ZipEq<A, B>
```

### Scope check

For each repo running Swift Export, grep the bridge file once after
a fresh `embedSwiftExportForXcode` run:

```sh
grep -nE "as io\\.github\\.kotlinmania\\.<pkg>\\.[A-Z][A-Za-z0-9_]+<kotlin\\.Any" \
    build/SwiftExport/macosArm64/Debug/files/*/*.kt
```

Every match is a candidate for the `internal class` + interface-return
treatment. If grep returns no results, this trigger (8a) doesn't apply
to this repo and no API change is needed.

---

## Recipe for hiding Kotlin function types behind a `fun interface`

The canonical pattern from the tree-sitter-language-kotlin
`LanguageFn` / `LanguageProvider` rewrite (2026-05-23). Applies
whenever a public Kotlin API uses a Kotlin function type
(`() -> R`, `(A) -> R`, `(A, B) -> R`, `suspend (A) -> R`, etc.) in a
**public** field, parameter, or return position.

The principle: **named SAM interface, not anonymous function type.**
Swift Export bridges named types as stable nominal symbols; it bridges
function types by erasing them through `Function0<R>` / `Function1<A, R>`
/ ... which collide with the `Any` channel in the bridge. A `fun interface`
gives the bridge a real symbol, and Kotlin's SAM conversion keeps lambda
call sites unchanged.

### Before

```kotlin
package io.github.kotlinmania.treesitterlanguage

class LanguageFn private constructor(private val raw: () -> Long) {
    companion object {
        fun fromRaw(f: () -> Long): LanguageFn = LanguageFn(f)
    }
    fun intoRaw(): () -> Long = raw
}
```

Plugin-generated bridge file (`build/SwiftExport/.../TreeSitterLanguage.kt`)
ends up with:

```kotlin
@ExportedBridge
fun __root___LanguageFn_intoRaw(self: kotlin.native.internal.NativePtr): kotlin.native.internal.NativePtr {
    val ____self = dereferenceExternalRCRef(self) as kotlin.Function0<kotlin.Long>
    // ^^^ Unchecked cast of 'Any' to '() -> Long'
    ...
}
```

Under `allWarningsAsErrors.set(true)` the warning becomes an error
and `compileSwiftExportMainKotlin<Target>` fails.

### After

```kotlin
package io.github.kotlinmania.treesitterlanguage

/**
 * Single-abstract-method interface that stands in for the upstream
 * Rust `unsafe extern "C" fn() -> *const c_void` function pointer.
 * Named so Swift Export can bridge a nominal symbol instead of an
 * erased `FunctionN<...>`.
 */
fun interface LanguageProvider {
    fun call(): Long
}

class LanguageFn private constructor(private val raw: LanguageProvider) {
    companion object {
        fun fromRaw(f: LanguageProvider): LanguageFn = LanguageFn(f)
    }
    fun intoRaw(): LanguageProvider = raw
}
```

`LanguageProvider` is `fun interface`, so Kotlin's SAM conversion still
accepts a lambda at the call site:

```kotlin
val fn = LanguageFn.fromRaw { tree_sitter_language() }
// Equivalent to: LanguageFn.fromRaw(LanguageProvider { tree_sitter_language() })
```

The bridge file's exported function now references `LanguageProvider`
(a nominal symbol Swift Export emits a binding for) instead of the
erased `Function0<Long>`. No `Any` cast.

### After (migration variant: name the SAM method `invoke`)

The recipe above uses `fun call()` as the SAM method, which is right
for new APIs. For repos *migrating* from a public typealias whose
call sites already use call-syntax (`callback(arg)` instead of
`callback.call(arg)`), name the SAM method `operator fun invoke(...)`
so Kotlin's invoke-operator convention keeps every existing call site
working without an edit:

```kotlin
// Before (typealias):
typealias ParseProgressCallback = (currentByteOffset: UInt, hasError: Boolean) -> Boolean

// After (fun interface with invoke):
fun interface ParseProgressCallback {
    operator fun invoke(currentByteOffset: UInt, hasError: Boolean): Boolean
}
```

Every prior caller of `callback(byte, hasError)` continues to compile
unchanged — `callback(...)` is now `callback.invoke(...)` via the
operator convention. `StableRef<ParseProgressCallback>` references in
nativeMain actuals also compile unchanged because the type name is
the same; only its kind changed (typealias → fun interface). Evidence:
tree-sitter-kotlin batch SAM-ification of seven public typealiases
and inline callback parameters, where this naming choice meant the
`jvmMain`, `androidMain`, and `nativeMain` actuals needed zero edits.

### Kotlin compatibility through named SAMs

The `fun interface` rewrite preserves the normal Kotlin call shape:
callers that pass lambdas keep writing the same lambda expression.
Callers that wrote out `(UInt, Boolean) -> Boolean` as an explicit type
must move to the named SAM type. That is the intended compatibility
boundary because the published API is now a bridgeable nominal type
instead of an erased `FunctionN`.

```kotlin
package io.github.kotlinmania.treesitter

fun interface ParseProgressCallback {
    fun invoke(
        byteOffset: UInt,
        hasError: Boolean,
    ): Boolean
}

public class Parser {
    public fun parseWithProgress(callback: ParseProgressCallback): Tree =
        parseInternal { offset, hasError -> callback.invoke(offset, hasError) }
}
```

When most or all of a subpackage is implementation runtime code that
should never reach Swift or Java consumers, make it `internal` and keep
public entry points in a small strongly-typed API layer. Do not ship a
public API and then hide it from one platform.

### Tests

SAM-convertible interfaces work the same as function types in tests
— pass a lambda or an explicit implementation, your choice:

```kotlin
@Test
fun fromRawAcceptsLambda() {
    val fn = LanguageFn.fromRaw { 42L }
    assertEquals(42L, fn.intoRaw().call())
}

@Test
fun fromRawAcceptsExplicitImpl() {
    val provider = object : LanguageProvider {
        override fun call(): Long = 99L
    }
    val fn = LanguageFn.fromRaw(provider)
    assertSame(provider, fn.intoRaw())
}
```

### What NOT to do

```kotlin
// WRONG: regular `interface` loses SAM conversion. Every existing
// `LanguageFn.fromRaw { ... }` call site breaks because Kotlin won't
// auto-wrap the lambda. Use `fun interface` instead.
interface LanguageProvider {
    fun call(): Long
}

// WRONG: switching to Function0<Long> is the same erasure problem.
// The bridge still writes `as kotlin.Function0<kotlin.Long>` because
// Function0 IS the underlying type of `() -> Long`.
class LanguageFn private constructor(private val raw: Function0<Long>)

// WRONG: silencing the warning leaves the unchecked cast in the
// bridge file. The plugin's runtime cast remains and the bridge
// shape is unchanged — only the compiler is muted.
tasks.matching { it.name.startsWith("compileSwiftExportMain") }
    .withType<KotlinCompilationTask<*>>()
    .configureEach {
        compilerOptions.allWarningsAsErrors.set(false)
    }

// WRONG: typealias to a Function type does NOT hide it from the
// bridge. The underlying function type still participates.
typealias LanguageProvider = () -> Long
```

### Scope check

For each repo running Swift Export, grep the bridge file once after
a fresh `embedSwiftExportForXcode` run:

```sh
grep -nE "as kotlin\\.Function[0-9]+<" \
    build/SwiftExport/macosArm64/Debug/files/*/*.kt
```

Every match is a candidate for the `fun interface` treatment. As a
source-side audit (preferred — it also catches surfaces the bridge
hasn't been generated for yet, e.g. new ports or new public
typealiases added to existing ports), grep for public function-type
signatures:

```sh
grep -nE "^(public |internal |private )?(class |object |val |var |fun )[^=]*: \\(.*\\) -> " \
    src/commonMain/kotlin/**/*.kt
grep -nE "^(public |internal |private )?(class |object |val |var |fun )[^=]*\\(.* \\(.*\\) -> " \
    src/commonMain/kotlin/**/*.kt
grep -rnE "^typealias [A-Z][A-Za-z0-9_]+ = \\(.*\\) -> " \
    src/commonMain/kotlin/
```

If all three passes return no results, trigger 8b doesn't apply and
no API change is needed.

### Preemptive sweep (run on every fresh port)

A new repo's public function-type surfaces should be SAM-ified
**before** Swift Export ever runs against them, not after CI surfaces
the warning. The audit greps above are cheap to run from a clean
checkout. The pattern from tree-sitter-kotlin (seven SAMs landed in
a single PR ahead of CI evidence):

1. Run the three source-side greps. Catalog every public
   function-type surface.
2. For each surface, decide:
   - **typealias whose call sites use `callback(arg)` syntax** →
     replace with `fun interface X { operator fun invoke(...) }`
     so call sites compile unchanged.
   - **new SAM, no existing call-syntax constraint** → name the SAM
     method semantically (`fun call()`, `fun read(byte: UInt)`, etc.).
   - **inline `((State) -> Boolean)?` parameter on a builder class**
     → introduce a co-located named SAM and replace the parameter
     type.
   - **subpackage that is internal-by-convention runtime port** →
     make it `internal` and expose only the strongly-typed public entry
     points that Swift and Java consumers should actually use.
3. Compile the three highest-coverage targets (`macosArm64`, `jvm`,
   `androidMain`) locally to confirm the SAM conversion didn't break
   any call site. Lambda call sites at invocation positions
   SAM-convert automatically; `StableRef<TypeName>` references stay
   the same because the type name didn't change, only its kind.

---

## Gaps discovered during the crossterm-kotlin port (2026-05-26)

Three previously undocumented issues surfaced during the crossterm-kotlin
Swift Export rollout that are general enough to hit other repos. All
three produced hard link failures (`ld: symbol(s) not found for
architecture arm64`) rather than compiler warnings, making them
impossible to miss — but also impossible to diagnose from the Swift
side alone.

### 9. Public `internal expect fun` symbols leak into the Swift Export bridge

**Symptom.** `swift test` link fails with `Undefined symbols for
architecture arm64: _io_github_kotlinmania_<pkg>_<symbol>` for a
function that *is* declared `internal` in commonMain but is also the
`actual` implementation of an `expect fun` that was itself `internal`.

**Root cause.** When commonMain declares `internal expect fun foo()` and
a platform source set provides `internal actual fun foo()`, the Kotlin
Swift Export plugin may still generate an `@ExportedBridge` entry for
the symbol because the `expect`/`actual` mechanism creates a public
entry point during metadata compilation even though the original
declaration is `internal`. The macOS native binary then doesn't export
the symbol (it's truly internal), and the bridge reference dangles.

**Fix.** Make the function truly invisible to the bridge by ensuring it
doesn't participate in the exported surface. For utility functions that
only exist as internal implementation details (like `getTtyFd()` which
returns a raw fd for termios calls), the fix was to remove `expect`
entirely and make the function a plain `internal fun` in the platform
source set that needs it. The function was never part of the public API
— it was only `expect` so that multiple platform source sets could share
the signature, but since each platform that calls it already has its
own copy, the `expect` was unnecessary overhead that leaked into the
bridge.

```kotlin
// BEFORE (leaks into bridge):
// commonMain:
internal expect fun getTtyFd(): Int

// posixMain:
internal actual fun getTtyFd(): Int { ... }

// AFTER (no bridge leak, no expect needed):
// posixMain only (no commonMain declaration):
internal fun getTtyFd(): Int { ... }
```

**When to use this fix.** Only for `internal expect fun` declarations
where every platform actual is already in a source set that's a
dependency of the Apple target. If the `expect` exists so that
`otherMain` / `jvmMain` / `wasmJsMain` can provide a no-op or
JVM-specific actual, you still need the `expect`/`actual` pair. For
Apple export, keep it `internal` and expose the public operation through
a strongly-typed bridgeable declaration.

### 10. JVM class name clashes between commonMain and platform source sets

**Symptom.** `compileAndroidMain` (or `jvmMainClasses`) fails with:

```
Duplicate JVM class name 'io/github/kotlinmania/<pkg>/FooKt'
generated from: FooKt, FooKt
```

**Root cause.** Kotlin compiles each source set's top-level functions
into a class named after the source file (`Foo.kt` → `FooKt`). When
`commonMain/src/Foo.kt` defines `expect fun bar()` and
`jvmMain/src/Foo.kt` defines `actual fun bar()`, both compile to
`FooKt.class` on JVM targets. The JVM class loader can't resolve which
one to use.

This is a JVM-only issue — Kotlin/Native and Kotlin/JS don't have this
problem because they don't use the same class-file naming scheme. But
androidMain, jvmMain, and any JVM-based source set all hit it.

**Fix.** Rename the Kotlin file or declaration that emits the duplicate
JVM class. The actual-implementation file gets a descriptive name that
reflects what it contains, not the same name as the commonMain expect
file:

```kotlin
// commonMain/src/AnsiSupport.kt -> defines expect fun enableVtProcessing()
// jvmMain/src/VtProcessing.kt   -> defines actual fun enableVtProcessing()
// androidMain/src/VtProcessing.kt -> defines actual fun enableVtProcessing()
```

The JVM class names become `AnsiSupportKt` (commonMain) and
`VtProcessingKt` (jvmMain + androidMain) — no clash.

Do not use `@file:JvmName` or `@file:JvmMultifileClass` to paper over
the collision. Those annotations change the Java interop surface and
keep the underlying naming mistake in place. The project rule for Java
matches the Swift rule: rename the Kotlin source element that emits the
bad name.

**Prevention.** After porting `expect`/`actual` declarations, check
for JVM class name clashes by compiling `compileAndroidMain` or
`jvmMainClasses` before declaring victory.

### 11. Display name constants (`internal const val`) referenced as
     function calls in test files

**Symptom.** `compileTestKotlin<Platform>` fails with `Unresolved
reference 'keyCodeBackspaceDisplayName'` (or similar).

**Root cause.** When `expect fun keyCodeBackspaceDisplayName(): String`
 declarations across all platform source sets are consolidated into
 `internal const val KEY_CODE_BACKSPACE_DISPLAY_NAME: String = "Backspace"`
 in commonMain (part of reducing the expect surface), the test files
 still reference them as function calls: `keyCodeBackspaceDisplayName()`.
 The `const val` form is not callable — it's a value, not a function.

**Fix.** Update test files to reference the constant directly:

```kotlin
// BEFORE (function call syntax from the expect fun era):
assertEquals(keyCodeBackspaceDisplayName(), KeyCode.Backspace.toString())

// AFTER (constant reference):
assertEquals(KEY_CODE_BACKSPACE_DISPLAY_NAME, KeyCode.Backspace.toString())
```

Since these are `internal` constants in the same module, `commonTest`
can access them. The naming convention stays SCREAMING_SNAKE_CASE for
constants per Kotlin convention.

## Gaps discovered during the build-gate / test-wiring pass (2026-05-30)

Two general defects in how `build`, `check`, and the per-platform CI
workflows wire test *execution* vs. *compilation*. Neither is a Swift
Export bridge bug — they decide whether each platform's `actual`s ever
actually run, including the Swift smoke test.

### 12. The build gate must BUILD every target but must not single out one platform's tests to RUN

**Symptom.** `fullTargetBuildTaskNames` (the all-target build gate) lists
`testAndroidHostTest` — an actual test *run* — alongside the compile/link
tasks, while every other target contributes only `*MainClasses` /
`*TestClasses` / `${target}Binaries` / `${target}TestBinaries` (compile
and link, no execution). So `./gradlew build` *runs* the Android unit
tests but for every other target merely links the `test.kexe` without
ever executing it. The gate looks symmetric but silently tests exactly
one platform.

**Root cause.** A "build must compile every target" contract and "run the
tests" are two different jobs. The build gate is about *compilation
coverage*; test *execution* is host-dependent (you can't run a Linux or
mingw test on a macOS runner, and device / Android-Native targets have no
host-runnable test task at all). Burying one runnable test in the build
set conflates the two.

**Fix.** Keep `fullTargetBuildTaskNames` pure-build (compile + link every
target, including each target's test binary — that proves the test code
*compiles*). Move test *execution* to `check`, where KMP's `allTests`
already runs every host-runnable test (`jvmTest`, `macosArm64Test`, the
Apple simulator tests, `jsNodeTest`, `wasmJsNodeTest`, ...). Add the
Android host test and the Swift smoke test there too:

```kotlin
tasks.named("check") {
    dependsOn(tasks.withType<io.gitlab.arturbosch.detekt.Detekt>())
    dependsOn("ktlintCheck")
    dependsOn("testAndroidHostTest")     // not in the build set
    dependsOn("swiftExportSmokeTest")
}
```

Do **not** try to make tests depend on the `build` lifecycle task to
force this ordering: `build → check → allTests` already exists, so
`anyTest.dependsOn("build")` forms the cycle
`build → check → allTests → build` and Gradle rejects it at
configuration time. Depend on the underlying compile/link tasks (or just
let `check` own execution) instead.

### 13. Every per-platform CI workflow must RUN its platform's test, not just compile it

**Symptom.** A platform's reusable workflow compiles and assembles but
never invokes a test-*run* task, so that platform's `actual`s are never
exercised in CI. Observed: `android.yml` ran
`compileAndroidMain assembleUnitTest assembleAndroidTest` with **no**
`testAndroidHostTest` — Android built green forever while its tests
never ran. (`watchos.yml` separately still listed the retired
`compileKotlinWatchosArm32`, which fails "task not found" once the target
is scrubbed — audit for retired targets in the same pass.)

**Fix.** Each `<platform>.yml` runs the test task that executes on its
runner: `macosArm64Test`, `linuxX64Test`, `mingwX64Test`,
`iosSimulatorArm64Test`, `tvosSimulatorArm64Test`,
`watchosSimulatorArm64Test`, `jsNodeTest`/`jsBrowserTest`,
`wasmJsNodeTest`/`wasmJsBrowserTest`/`wasmWasiNodeTest`,
`testAndroidHostTest`, and `swift test` (swift.yml). Audit rule: a
workflow whose Gradle task list contains only `compile*` / `assemble*`
and no `*Test` run is a platform building-but-not-testing.

**Honest limits (compile-only, by physics — not laziness).**
`androidNative*` (ELF for Android ABIs, needs an emulator per ABI),
`linuxArm64` on an x64 runner, and the device Apple slices (`iosArm64`,
`tvosArm64`, `watchosArm64`, `watchosDeviceArm64`, which have no
host-runnable test task) can only be compiled/linked. Their `actual`s
share the `appleMain`/`iosMain`/etc. source sets that the corresponding
**simulator** test exercises, so the same code is covered — state this
in the workflow rather than implying the binary was tested.

The local `swiftExportSmokeTest` (kasuari-kotlin is the reference shape)
runs `swift test` against the `embedSwiftExportForXcode` output and must
be wired into `check` so Swift Export breakage surfaces on
`./gradlew check` locally, not only in `swift.yml`. When aligning a repo,
confirm `project.frameworkName` (→ `swiftExport.moduleName`) matches the
harness's `import <Module>` — a mismatched module name fails `swift test`
with "no such module" even though the bridge compiled cleanly.

---

## Why the recipe is in every repo

Two reasons:

1. **The Claude agent operates per-repo** in the GitHub App context.
   Each repo's agent invocation only sees the repo it's working in.
   Pulling instructions from a central source would require the agent
   to make cross-repo API calls; embedding the recipe in the repo
   makes the work fully self-contained.

2. **The recipe ages with the rollout commit.** When the workarounds
   in [§ Known upstream gaps](#known-upstream-gaps) eventually become
   unnecessary, the recipe's removal (or amendment) will land in the
   same commit as the workaround removal. Future spelunkers reading
   `git log -- SWIFT_EXPORT_ROLLOUT.md` will see the full history of
   what was tried and why.

— Sydney Renee, KotlinMania

---

## Additional Workspace Guidelines and Hazard Classes

### The mandatory infrastructure pins

- **`swift.yml` runs on `runs-on: macos-26`** — not `macos-latest`. The Kotlin/Native 2.3.x SDK cache requires it.
- **Never add `maxim-lobanov/setup-xcode@v1`** or any other third-party Xcode setup action. Third-party actions cause `startup_failure` on the KotlinMania allowlist.
- **`swift test` is wired into the Kotlin `test` task.** A Swift Export failure must surface on `./gradlew test` locally. Do not push and let remote CI find it — by that point you've burned a CI run.
- **iOS Simulator XCFramework fat-stage rule.** Both `iosSimulatorArm64` AND `iosX64` must be `isStatic = true`. Mixing one static / one dynamic fails `assembleDebugIosSimulatorFatFramework<Name>` with:
  ```
  Cannot create a fat framework from:
    <Name> - arm64 - static
    <Name> - x64 - dynamic
    All input frameworks must be either static or dynamic
  ```
  This is **gap #7** in the rollout. Earlier rollouts flipped only `iosSimulatorArm64`; if a repo still has the asymmetric shape, fix `iosX64` too in the same commit.

### The four hazard classes (what makes Swift Export fail under `-Werror`)

Swift Export's generated bridge fails `allWarningsAsErrors=true` in four distinct ways. **Do not scope `allWarningsAsErrors=false` to `compileSwiftExportMain*` to silence them — that's symptom suppression and a forbidden fix.** Apply the structural remedy below.

**Class A — Unchecked cast `Any?` → `Foo<T>`.** Public generic types with unconstrained type parameters force the bridge to erase to `Any?` and cast back. Fix: wrap the generic with an `internal` implementation + a non-generic façade for Swift, or use a stdlib type the bridge already handles.

**Class B — `Cannot infer T`.** Public generic functions Swift can't project a single Swift type for. Fix: `internal` factory that fixes the type parameter at the boundary; the public Kotlin function stays generic for Kotlin callers.

**Class C — `Array`/`List`/`Iterator` mismatch.** Public collection surfaces that Swift would bridge to non-equivalent types. Fix: non-generic collection at the boundary, or wrap in a thin Swift-friendly type.

**Class Stdlib (the fourth class) — warnings in *auto-generated* `KotlinStdlib.kt`.** Public `kotlin.Result<X>`, classes extending `RuntimeException` / `Exception` / any `Throwable` subclass, public `MutableList<X>`, public `MutableMap<K, V>` each drag the `Throwable.getStackTrace()` → `Array` bridge or the `MutableList`/`MutableMap` bridge into the generated `KotlinStdlib.kt`, which then fails Unchecked-cast under `-Werror`. Fixes:

- **Public `kotlin.Result<T>`** → a named, concrete success/failure API for the exported value type, with a non-Throwable diagnostic. Merely replacing it with another generic result does not restore concrete Swift typing.
- **`class FooError : RuntimeException(...)`** (or `Exception`) → data class / sealed class that does NOT extend any `Throwable`. Kotlin callers that need to throw it wrap in their own throw site.
- **Public `MutableList<X>` / `MutableMap<K, V>`** → internal-backed `List<X>` / a custom read-only Map wrapper; internal helpers do copy-and-replace to keep the read-only public surface.
- **Public `Pair<A, B>`** → named record class.
- **Public `() -> X` / `(A) -> B`** function types → `fun interface` SAM type. Swift Export can't bridge raw Kotlin function types.

### Diagnostic APIs: repair the exported types, not their aliases

`Swift.Error` name collisions and unsupported exception inheritance are
separate problems. Rename the actual Kotlin declaration to a distinct
name where needed, and repair its actual superclass and parameter types.
Renaming `Error.kt`, adding `typealias SynError = Error`, or renaming an
exception subclass to `SynError` does not stop re-exporting its raw
Kotlin/Native functionality. Keeping `typealias Error = SynError` also
retains the colliding exported name. Generated namespace aliases must be
traced to their underlying declaration; they are not evidence that its
API has been repaired.

The Syn bridge audit exposed this concrete chain before the diagnostic
redesign:

```text
SynError : IllegalArgumentException
  → exported Throwable constructors and getStackTrace()
  → Array<String> stack trace
  → erased Array<Any?> receivers in Array get / iterator / set / size
  → four unchecked-cast diagnostics in KotlinStdlib.kt
```

Its `new`, `newAt` and `newSpanned` message factories also accepted
Kotlin `Any`. Their generated Swift parameters were
`any KotlinRuntimeSupport._KotlinBridgeable`, even though callers needed
text. Renaming the class cannot repair those factory signatures.
Warnings in `KotlinStdlib.kt` therefore do not, by themselves, establish
that the only remedy is an upstream compiler change.

The required source repair is:

1. Make `SynError` an actual non-Throwable diagnostic, with no exception
   superclass. Preserve its message, span and compile-error operations.
   Preserve the same diagnostic object when propagating a failure result.
2. Use concrete, non-null Kotlin `String` messages in the diagnostic
   property and every exported message factory or constructor. Format
   other values into text at the caller. Do not keep an `Any` overload
   that reintroduces the erased exported parameter.
3. Migrate `catch (error: SynError)` and `throw diagnostic` sites
   deliberately. Prefer result-based propagation in the original parse
   order. A Kotlin `OrThrow` boundary can throw an internal exception
   carrying the original diagnostic; do not publish another Throwable
   subclass as its replacement. Record source and binary compatibility
   changes and rebuild consumers. A generated function named `getOrThrow`
   is not evidence of a Swift `throws` contract.
4. Provide concrete result types at Swift-facing boundaries that need
   typed success values. A non-Throwable diagnostic does not by itself
   solve the generic `SynResult<T>` erasure described above.

**Verify the repair in regenerated output, not just the source diff.**
Record the source revision, resolved plugin version and configuration,
target/build type, generated compilation inputs and their hashes.
Inspect both the module's `.swift` declarations and its Kotlin bridges,
then follow dependencies into `KotlinStdlib.kt`:

- `SynError` must no longer inherit the exported Kotlin exception class;
  its message property and message parameters must emit `Swift.String`.
- The Throwable constructors, `getStackTrace()` and four Array receiver
  casts brought in by that diagnostic must disappear. If they remain,
  identify the other exported declaration keeping them reachable.
  Inspect newly exposed stdlib shapes as well; removal of one chain
  does not prove the entire export surface is clean.
- Retain identity, message/span and failure-path regression tests and
  exercise the concrete diagnostic/result API from the Swift harness.
  Keep `allWarningsAsErrors=true` through bridge/runtime compilation
  and run the actual Swift tests. Generation, linking, skipped tests and
  earlier results for a different revision are not current test passes.

The non-Throwable/String redesign addresses this source API chain.
Bundled coroutine warnings and concrete Unit-conversion warnings require
their own evidence and validated repairs; neither disappears merely
because the diagnostic was renamed or redesigned.

### Result APIs: retain concrete success and diagnostic types

Apply the same source-level repair to `SynResult` at public operations
whose success type is known. In the inspected generated Swift,
`SynResult.success`, `Success.value`, `getOrThrow`, `getOrElse` and
`fold` use optional `KotlinRuntimeSupport._KotlinBridgeable` values.
The absence of an unchecked-cast diagnostic does not restore the lost
success type. A sealed-case wrapper around an erased payload does not
restore it either.

Use an actual, non-generic result class for each concrete public
contract: `FileParseResult` for `parseFile`, Syn's `TokenStreamParseResult`
for token transformations, and the consumer's `ArgsParseResult` for
async-trait arguments. Each class has a concrete nullable `value`, a
nullable non-Throwable `SynError`, and exactly one populated field.
Private constructors and concrete success/failure factories enforce
that invariant. Conversion must retain the original value or diagnostic
object.

Change the operation's actual return type. Do not use
`typealias TokenStreamParseResult = SynResult<TokenStream>`, inherit
from `SynResult<TokenStream>`, substitute `Any` for the payload, or
hide the operation while leaving no equivalent concrete API. Generic
results can serve algorithms whose parser callbacks genuinely choose
arbitrary types, but that does not justify exposing an erased result
from an operation with a known output. Public generic `fold`/`map`
callbacks must not reintroduce the same erased result surface.

Record the source and binary signature changes, migrate callers and
rebuild compiled consumers. Regenerate the bridge and inspect the
operation return, factory arguments, result properties and accessors.
Swift consumer tests must assign the success payload to its concrete
type and use the diagnostic's `String` message without forced casts.
Run those tests through the full strict KMP gate. Neither a Kotlin-only
test nor a warning-free but erased declaration proves this contract.

### Annotation hiding is not an API repair

`@HiddenFromObjC` hides a declaration from Objective-C / Swift export.
That is not a repair for a published Apple framework. If a declaration
should not be exported, make it `internal`. If a declaration is part of
the public API, make it bridgeable by construction.

For Swift name collisions, the fix is to rename the Kotlin type itself.
Do not hide the declaration, do not rely on `@ObjCName`, and do not keep a
backward-compatible `typealias` with the old colliding name. Swift Export
emits the Kotlin declaration surface, including typealiases, so the old
name keeps colliding.

The concrete reference is `syn-kotlin`: commit `e34775e` renamed
`Type` / `Error` / `Result` to `SynType` / `SynError` / `SynResult`, and
commit `afbebfe` removed the aliases after CI showed
`typealias Type = SynType` was still exported and rejected by Swift.

```kotlin
// Before: collides with Swift's `foo.Type` metatype expression.
public sealed class Type

// Correct: rename the Kotlin API and migrate callers.
public sealed class SynType

public data class BareFnArg(
    public val ty: SynType,
)

// Wrong: aliases are exported too, so this keeps the Swift collision.
public typealias Type = SynType
```

The reviewed API-hiding failure case is `lru-kotlin` PR #18. The PR
annotated the main cache type:

```kotlin
class LruCache<K : Any, V : Any> private constructor(...)
```

The generated framework could still import, but Swift / Objective-C
consumers could not construct or use the cache. The correct direction is
one of:

- keep `LruCache` exported and make its public surface bridgeable;
- when the failure is a Swift name collision, rename the Kotlin type to a
  Swift-safe name and update Kotlin callers to that name;
- when the failure is generic bridge shape, keep a generic implementation
  internal and provide exported concrete types with Swift-safe names;
- make unsupported iterator/view/helper types `internal` while the
  primary cache API remains exported and usable.

Do not accept an import-only smoke test as proof that Swift Export is
healthy. The Swift harness must instantiate at least one primary exported
type and call a representative method.

**Fallback** (when faithfulness isn't a constraint): mark `internal` + expose a factory returning the public stdlib interface.

### Project goal: strongly-typed public APIs — generics only where design requires

After a Swift Export compatibility pass lands and a repo's gate is green,
the **follow-up pass is to de-generify**. The project goal across the
workspace is **strongly-typed public APIs**: no unconstrained generics, no
`<T : Any>` exposed to Swift, no `<T, E>` Result-style wrappers — unless
the design genuinely requires the type parameter (parser combinators,
typed builders, container types where the element type carries real
meaning).

Why this is a real rule and not a style preference:
- Annotation hiding only removes the Swift bridge problem from view. The Swift side sees no API for that surface at all, which is worse than a non-generic Swift API. Swift callers either cannot use the type or have to drop into Objective-C interop.
- The Class A "unchecked cast `Any?` → `Foo<T>`" failure is generated by the same erased-bridge code path no matter what you do; hiding it means the next type the bridge tries to express through that path hits the same wall.
- Strongly-typed public APIs survive Kotlin/Native compiler upgrades. The generic-bridge handling in Swift Export is the most volatile surface of the K/N toolchain and keeps changing between 2.3.x patches; non-generic APIs don't care.

**The strong-typing checklist:**
1. Is the generic parameter actually doing structural work for callers?
   If **no**, replace it with the concrete type or a small set of
   concrete named subtypes (`IntFoo`, `StringFoo`, `DoubleFoo`).
2. If the parameter is doing structural work but public use only
   instantiates one or two concrete types, hoist those instantiations
   into named non-generic subclasses (`class IntCell : Cell<Int>()`),
   expose those publicly, and make the generic base `internal`.
3. If the parameter is genuinely required by design (for example, a
   typed builder DSL or a generic container callers instantiate at
   arbitrary types), leave the generic and document why in a one-line
   `// generic by design:` comment so the next sweep does not mistake it
   for unfinished work.
4. `Pair<A, B>` -> named record class. Always. There is no design
   justification for leaking `kotlin.Pair` into a public API.
5. `Result<T, E>` / generic Outcome wrappers -> per-error-domain sealed
   classes. `AddConstraintOutcome` not `Outcome<Unit, AddConstraintError>`.
6. Function-type surfaces (`(A) -> B`) -> `fun interface` SAM with a
   meaningful name.

**Forbidden phrasing** in commits and PR descriptions:
- "Hide `Foo` from Swift" as the *only* change for that API. → Not acceptable when `Foo` is the only useful Apple-facing API; provide an exported facade or redesign the surface.
- "Generic API kept for flexibility." → Specify *which caller* needs the flexibility, or de-generify.

Memory hook: `feedback_swift_export_three_patterns.md`,
`feedback_swift_export_throwable_result_array.md`, and
`feedback_swift_export_gap8_internal_generics.md` predate the stricter
public-API rule. Treat them as bridge-failure diagnostics, not as
permission to hide the only Swift-facing API.

### The 5-class sweep (per-repo, run before every Swift release)

Inspect commonMain for **all** of these, not just whatever last tripped CI:
1. `runs-on:` on `swift.yml` is `macos-26`; no `setup-xcode` action.
2. Public mutable collection surfaces (`MutableList`, `MutableMap`, `MutableSet`) → read-only Kotlin types + internal copy-and-replace helpers.
3. Public generic types / SAMs / function-type surfaces -> **first choice: de-generify** (concrete named subtypes, `fun interface` SAM with a real name, internal generic base). See "Project goal: strongly-typed public APIs" above.
4. Public `kotlin.Result<X>` / `Throwable` subclasses → concrete outcome APIs and non-Throwable diagnostic classes; use `String` messages, migrate catches deliberately, and verify that regenerated output removes the inherited Throwable/Array chain and erased message parameters.
5. Public `Pair<A, B>` helpers → named record class.

Run the gate after every repair, not just at the end:
```bash
./gradlew embedSwiftExportForXcode --no-daemon
./gradlew test --no-daemon          # must include swift test
```

### 12. Kotlin names that conflict with Swift or Java emitted names

**Symptom.** `compileSwiftExportMainKotlinMacosArm64` or the
`xcodebuild` step inside `macosArm64DebugBuildSPMPackage` fails with:

```
error: type member must not be named 'Type', since it would conflict with the 'foo.Type' expression
error: type member must not be named 'Error'
error: type member must not be named 'Result'
```

**Root cause.** Swift Export emits Kotlin declaration names into the
generated Swift module, and JVM compilation emits Kotlin files and
declarations as Java-visible class and method names. If a Kotlin public
type or typealias is named like a Swift keyword, metatype expression,
protocol, or built-in type (`Type`, `Error`, `Result`, etc.), the Swift
compiler rejects the module. If common/platform Kotlin files or
declarations emit the same JVM class or method signature, Java/JVM
compilation fails.

`@HiddenFromObjC` only removes the API from Apple consumers. `@ObjCName`,
`@JvmName`, and `@JvmMultifileClass` are not fixes. A Kotlin `typealias`
does not preserve compatibility either because Swift Export emits aliases
too: `typealias Type = SynType` still exports `Type` and still collides.

**Fix.** Rename the Kotlin declaration or file itself to an emitted-safe
name and migrate every Kotlin caller to that new name. Remove any
compatibility typealias or platform naming annotation that preserves the
old colliding name. If downstream `*-kotlin` repos use the old name,
update them in the same compatibility pass; there is no bridge that
preserves the old source spelling without reintroducing the collision.

Concrete pattern from `syn-kotlin`:

```kotlin
// Before
public sealed class Type {
    public data class Tuple(
        val elems: Punctuated<Type, Comma>,
    ) : Type()
}

// After
public sealed class SynType {
    public data class Tuple(
        val elems: Punctuated<SynType, Comma>,
    ) : SynType()
}

public data class BareFnArg(
    public val ty: SynType,
)

// Forbidden: this is exported and collides in Swift.
public typealias Type = SynType
```

**Evidence.** `syn-kotlin` commit `e34775e` renamed
`Type` / `Error` / `Result` to `SynType` / `SynError` / `SynResult`.
Commit `afbebfe` then removed `typealias Type = SynType`,
`typealias Error = SynError`, and `typealias Result<T> = SynResult<T>`
after CI confirmed the aliases were exported and rejected by Swift.

### 13. `NoClassDefFoundError: kotlinx/coroutines/internal/intellij/IntellijCoroutines` during Swift Export

**Symptom.** The `macosArm64DebugSwiftExport` Gradle task logs an
`ERROR: Worker exited due to exception` with
`NoClassDefFoundError: kotlinx/coroutines/internal/intellij/IntellijCoroutines`.
GitHub Actions annotates the step with a red `##[error]` marker.

**Root cause.** The Kotlin compiler embeds parts of the IntelliJ platform
for its worker processes. The embedded platform expects
`kotlinx.coroutines.internal.intellij.IntellijCoroutines` on the worker
classpath, but the Swift Export task's worker process doesn't include
the `kotlinx-coroutines-core` JAR.

**Impact.** The error is **non-fatal** — the Swift Export and SPM
package generation continue past it. The actual build failure (if any)
comes from downstream compilation errors, not from this worker exception.
If the only `##[error]` annotations in the Swift CI job are
`IntellijCoroutines`-related, the Swift Export itself may have succeeded.

**Current workaround.** Adding `kotlinx-coroutines-core` to the
`buildscript` classpath does **not** fix it — the worker process has its
own classpath that doesn't inherit the buildscript classpath. There is
no known per-repo fix at this time; the error is filed upstream against
the Kotlin Gradle plugin.

### 14. Custom sealed generic result types in public API cause argument type mismatch in Swift Export bridge

**Symptom.** `compileSwiftExportMainKotlinMacosArm64` fails with:

```
Argument type mismatch: actual type is '(ParseNestedMeta) -> SynResult<Any?>',
  but '(ParseNestedMeta) -> SynResult<Unit>' was expected.
Cannot infer type for type parameter 'R'. Specify it explicitly.
```

**Root cause.** Swift Export generates bridge code for public API
declarations. When a function takes or returns a custom generic sealed
class (e.g. `SynResult<T>`), the bridge erases the type parameter to
`Any?` because it doesn't understand the sealed-class type hierarchy.
This produces Kotlin-level argument type mismatches in the generated
bridge file that fail compilation.

**Fix.** If these are Kotlin-internal parser APIs that Swift consumers do
not need, make them `internal`. If they are public API, replace the
custom generic sealed result with a Swift-exportable concrete outcome
type.

The flat-class pattern from [§ Recipe for replacing `kotlin.Result<T>`
in a public API](#recipe-for-replacing-kotlinresultt-in-a-public-api) is
the correct shape for exported result APIs. The flat class with
`value`/`error` nullable fields and `isSuccess()`/`isFailure()`
predicates bridges cleanly.

**Evidence.** syn-kotlin's durable fix was the type-rename pass:
`Type` / `Error` / `Result` became `SynType` / `SynError` / `SynResult`,
and the compatibility aliases were removed after CI proved aliases are
exported too.

## Reproducing the isolated Kotlin 2.4.20 Swift bridge repair (2026-10-20)

This is a diagnostic reconstruction of the released exporter, **not an installed compiler replacement or a product rollout**. The generated Native and Swift code compiles strictly and **all twelve Swift/Flow tests pass under Swift 6 with warnings as errors**. Three earlier sendability errors were corrected in the fixture's ownership model, without further runtime/exporter changes. No Syn/Libc/Async/Image integration, full KMP gate, CI, release or Maven publication is established by this experiment.

### Inputs and isolation

Use the existing artifact directory, relative to the workspace root:

```
automation-artifacts/2026-10-20-syn-isolated-exporter-integration
```

The host was EmberHarmony.local, Xcode 26.6 build 17F113, Apple Swift 6.3.3 (`swiftlang-6.3.3.1.3`), with the fixture targeting macOS ARM64 15.0. KGP/compiler/exporter are **2.4.20**; coroutines is **1.11.0**. The original exporter JAR SHA256 is `24c6159bcf0087ecf86bfc9bad01a975e192abcf75005b248292d85a139c2055`; the isolated final exporter SHA256 is `170771a2b55387a94ab1adab9ecd259c3eed9d7622997150f259cb8069896a37`. ZIP timestamps can change a rebuilt archive's whole-file hash: verify its entry contents and class audit, rather than assuming archive-byte reproducibility.

All six upstream source files are pinned to official Kotlin revision **890ac1d94fdb80eb85f0eeb5be5e4352df987b2f**, resolved from v2.4.20:

- `native/swift/sir-providers/src/org/jetbrains/kotlin/sir/providers/impl/BridgeProvider/TypeBridging.kt`
- `native/swift/sir-providers/src/org/jetbrains/kotlin/sir/providers/impl/BridgeProvider/SirBridgeProviderImpl.kt`
- `native/swift/sir-printer/src/org/jetbrains/sir/printer/impl/SirAsSwiftSourcesPrinter.kt`
- `native/swift/swift-export-standalone/resources/swift/KotlinCoroutineSupport.kt`
- `native/swift/swift-export-standalone/resources/swift/KotlinRuntimeSupport.swift`
- `native/swift/swift-export-standalone/resources/swift/KotlinCoroutineSupport.swift`

`producer-input-manifest.json` records each upstream path, Git blob, SHA256 and the sixteen exact compiler/exporter dependency JAR paths/hashes. Original files are in `producer-inputs/`; changes are in `producer-src/`. The Kotlin coroutine resource SHA256 is `cc30c61170e4a5fee58fcdda09539f30781cbada4c59389d99da8fed2a6f6d6f`. Its identity was also observed in 2.4.21, but **this exporter integration was tested only on 2.4.20**.

`prepare-producer.rb` acquires the initial exact sources and hash-checks/copies the captured dependency inputs; it deliberately refuses to overwrite originals. `prepare-swift-strict-sources.rb` acquires the additional printer/Swift resources. The exact official reverse-provider API response is retained in `sir-provider-api.json`; `build-reverse-producer.rb` verifies/records its source. Do not use an unrelated local compiler source archive as the release source.

`seed-isolation.rb`, `copy-native-inputs.rb` and `restore-native-dependencies.rb` copied dependency inputs into `gradle-home/`, `native-sdk/` and `konan-data/`, materializing input symlink targets as ordinary files. The restored `.extracted` receipt names only the three copied LLVM/libffi/lldb dependencies. The fixture's `kotlin.native.home` points to the copied SDK. Temporary files, Gradle/Konan/compiler caches, SwiftPM build/package/module caches, Java user/IDE directories and product outputs are inside the artifact directory. Installed compiler/dependency inputs were read-only; `input-reverification.json` independently rechecks all sixteen original/copied JARs. No Maven-local or sibling project substitution is involved.

### Source changes and their purpose

1. In TypeBridging's Kotlin conversion templates, consume the expression as the result of `run`, then transform it with `let`: Unit uses `run { expression }.let { }`, Boolean sentinels use `.let { true }`, nullable Nothing uses `.let { null }`, and the impossible reverse Nothing fallback uses `.let { throw IllegalStateException() }`. This evaluates the expression once, retaining effects and exception propagation without unused expressions.
2. In Swift conversion templates, explicitly consume discarded bridge values with `_ = expression`. Give callback result locals their actual Swift type. For a Nothing-returning callback/reverse method, emit only the terminating call; do not create a Never local or append an unreachable return/fatalError. A forward noreturn C call already imports as Never and needs no extra fatalError. The failed explicit `return <Never>` to a Bool bridge attempt is preserved; the verified form is a terminating call statement.
3. In the Kotlin coroutine resource, replace the completed-error Elvis with an explicit local error check followed by `return null`. Match opaque iterator receiver casts with `SwiftFlowIterator<*>`/`<*>?` and the continuation payload with `SwiftFlowIterator<*>.Value?`, avoiding invariant unchecked casts while preserving the actual inner payload.
4. In the printer and both Swift runtime resources, replace deprecated implementation-only imports with `internal import`, following [Swift SE-0409](https://github.com/swiftlang/swift-evolution/blob/main/proposals/0409-access-level-on-imports.md). In KotlinRuntimeSupport.swift remove the same-type `[Any]` forced cast and replace the optional-only protocol-wrapper cast with its explicit unwrap. In KotlinCoroutineSupport.swift use `catch is CancellationError` instead of binding an unused error.

There are no generated-source rewrites, warning suppressions, diagnostic severity changes, library-evolution flag workaround, Objective-C API decorators or `@JvmName` changes. The fixture applies the documented module-level `InternalForInheritanceCoroutinesApi` opt-in required by the generated Job implementation; this is explicit API consent, not a warning waiver.

### Build and verification commands

Obtain an explicit shared local slot before any compiler/build/test command. A returned `execution-grant.json` is intentionally inactive: the coordinator must grant a new run. Iris is excluded and must not be started, resumed or messaged. Keep the completed inspection receipt; do not reinterpret it as permission to interact with Iris.

From the artifact directory, archive existing logs before a new experiment, then run sequentially:

```sh
ruby build-producer.rb
ruby prepare-swift-strict-sources.rb
ruby build-printer.rb
ruby build-reverse-producer.rb
ruby package-producer.rb
ruby run-fixture.rb baseline
ruby run-fixture.rb patched
ruby run-swift-tests.rb
ruby prepare-serial-tests.rb
ruby run-swift-serial-tests.rb
ruby compare-abi.rb
```

The baseline fixture is an **expected failure described below**; the corrected full Swift suite must succeed with twelve executed tests. Inspect each result before proceeding, rather than chaining with `&&` or treating every nonzero exit as the same failure. The serial subset is retained for historical comparison and is not a substitute for the full suite. Compiler args, environment, source hashes, exits and logs are persisted by each runner. The scripts contain the exact captured classpath and input selection; `exporter-selection.init.gradle` replaces only the exporter on the original sixteen-JAR worker classpath, including the IntelliJ coroutine dependency. An ordinary Gradle-resolved replacement classpath previously lost that dependency and was rejected.

The source-only producer reconstruction uses the copied 2.4.20 K2JVMCompiler with `-Werror`, module-specific friend path/name, JVM target 1.8 and no-compatibility default methods. This JVM invocation builds the exporter implementation; product validation is Native/Swift. Official sources contain newer square-bracket Pair destructuring syntax: the build scripts normalize only compiler inputs to equivalent parentheses in both controls (ten TypeBridging sites, three printer sites and four provider sites). Acceptance requires original versus rebuilt-baseline instruction equality for every packaged class, not merely successful compilation.

The JAR audit checks **6263 entries**, no additions/removals, exactly **nine classes plus three resources** changed. Nine original/baseline/patched `javap -p -s` signatures match; baseline `javap -p -c` instruction blocks match original after constant-pool index normalization and member-order-independent comparison. The initial printer mismatch was only method ordering; no instruction differences were waived.

`run-fixture.rb` invokes the read-only Gradle 9.8.0 distribution's `embedSwiftExportForXcode --continue --no-daemon --max-workers=2 --no-configuration-cache`, writes variant-separated output, and requires `allWarningsAsErrors=true` for actual generated Native compilation. `run-swift-tests.rb` uses SwiftPM with Swift 6 test sources and `-Xswiftc -warnings-as-errors`. Its explicit `-target arm64-apple-macosx15.0` aligns dependency compilation with the fixture's declared target: the generated package itself omits a deployment minimum. This proves macOS 15 compatibility, not support for older Apple OS versions.

### Verified results and remaining limits

- Final baseline session **12504** exited 1 with the expected **29 Native warnings**: 26 unused expressions, one redundant Elvis and two unchecked casts. Baseline generation and its Swift modules succeeded; strict Native compilation prevented a baseline linked binary.
- Final patched session **56059** exited 0: actual exporter generation, strict generated Native compilation, static linking, SPM generation and embedding passed. Earlier patched **95692** also exited 0, but was the initial Native-only candidate, not a Swift/Flow behavior pass.
- Historical full Swift session **18167** exited 1 after generated Swift compiled cleanly. Three fixture tests shared non-Sendable iterator/probe captures across Task boundaries: suspended producer/resumption, cancellation while waiting for the producer, and concurrent-next rejection. The source and failed diagnostics are preserved under `sendability-review/`. These are fixture ownership errors, resolved by the actor correction below; no unchecked Sendable conformance or relaxed language mode was introduced.
- Final full Swift session **48138** exited 0: **all twelve Swift Testing cases executed and passed, zero failures**, using Swift 6 and `-warnings-as-errors`. This includes all three formerly failing concurrency cases. The exporter JAR, generated product source and runtime resources were unchanged for this correction.
- Separate serial session **31865** exited 0: **nine actual Swift Testing cases passed**, preserving original test bodies. These exercise constructors/setters, both Unit callback directions, nullable Nothing, String order, full-width UInt, nullable object payloads, empty/repeated completion, exact original Kotlin error identity, iterator release before start and cancellation of a backpressured producer on release. `serial-subset.json` records the full/subset hashes and three omitted compile-blocked cases. This is explicitly a partial behavior result, not a green full-suite gate.
- The original repeated-error expectation was corrected against [Apple's AsyncIteratorProtocol contract](https://developer.apple.com/documentation/swift/asynciteratorprotocol): after an error, future next calls must return nil. The final test checks the first wrapped error against the producer's original object and then checks repeated terminal nil; no runtime behavior was changed to satisfy this correction. The fetched official text is retained in `asynciteratorprotocol.md`.
- Final ABI session **35990** exited 0. All **five generated C headers are byte-identical**, and all **37 declared Integration C exports** exist in the patched static library. Compiled Swift API dumps for Integration, KotlinRuntimeSupport and KotlinCoroutineSupport have identical declarations after excluding import nodes. Complete raw dumps differ by the expected ImplementationOnly import attributes; they are retained, not described as identical. This is not a cross-version binary compatibility guarantee: there is no strictly linked baseline binary.

Never callback signatures/generation compile, but no deliberately fatal Swift Never callback was executed. Other Apple targets, 2.4.21 exporter execution and all product KMP/Swift integration gates remain unverified. The twelve-case isolated full suite is green; the earlier nine-case subset remains historical evidence.

### Correct Swift 6 ownership in the concurrency fixture

All three diagnostics pointed to fixture Task initializers. A closure captured a non-Sendable iterator associated with a probe still used by the caller. Declaring those exported reference types Sendable would assert a broader concurrency contract and was not required. The verified correction is the private `SuspendedFlowReceiver` actor in `swift-harness/Tests/ExporterIntegrationTests/BridgeTests.swift`:

- Construct and retain both the FlowProbe and its iterator inside the actor. Tasks capture only that actor; probe and iterator references never cross its boundary.
- In its receive method, take a local reference to the iterator and use `try await iterator.next(isolation: self)`, the standard [isolation-aware AsyncIteratorProtocol API](https://developer.apple.com/documentation/swift/asynciteratorprotocol/next(isolation:)). It is available from macOS 15, matching this fixture. The installed SDK's protocol extension and Apple's exact API documentation were read and recorded in `sendability-review/next-isolation.md`.
- Return only Sendable String?/Int32/Bool observations. Read the Kotlin probe's existing thread-safe start-count/stopped state inside the actor, with a five-second deadline and suspending one-millisecond clock sleeps. The exported non-Sendable probe is not sent to a second executor merely to await a signal.
- Actor reentrancy while the first receive is suspended allows the second actor call to exercise the real Kotlin concurrent-receiver guard. The test still requires rejection, releases the producer, verifies the first receiver gets its value and verifies completion/finalization. Cancellation still cancels the actual pending receiver Task, requires CancellationError and checks the producer finally block.

No tests were removed, no unsafe Sendable declaration or suppression was added, and no runtime/exporter change was needed for sendability. After an explicit coordinator regrant, run the existing `ruby run-swift-tests.rb` once; final session 48138 built, linked and executed all twelve cases successfully. `sendability-review/classification-and-candidate.json`, `actor-full12.log` and `actor-full12-result.json` preserve exact source hash, diagnosis and result. No ABI rerun is needed for this fixture-only change; the audited exporter/runtime/native inputs and their ABI receipt remain unchanged.

### Return and cleanup boundary

All owned compiler/build/test commands exited and the local slot was explicitly returned in `local-slot-return.json`; the active grant is false. Preserve source manifests, input copies, scripts, full failed/passing logs and ABI receipts. Reverting this experiment requires no installed compiler or cache restoration because those were never changed. Do not delete shared inputs or other owners' artifacts. Any removal of this artifact directory should be a separately authorized cleanup after retaining its evidence. Product adoption, installed deployment, CI/publication and fleet rollout require separate approval and their own full gates.

### Actual syn-kotlin isolated validation (2026-10-20)

This follow-up used the existing shared `syn-kotlin` checkout at **3cfb17f796a13499be94019cd88947420ad36768**, with the same verified exporter SHA256 **170771a2b55387a94ab1adab9ecd259c3eed9d7622997150f259cb8069896a37** and official Kotlin 2.4.20 revision **890ac1d94fdb80eb85f0eeb5be5e4352df987b2f** described above. It is an actual-library validation attempt, **not a green integration gate**. No replacement exporter incorporating the later enum candidate has been compiled or verified.

All paths below are relative to the workspace root. The owned artifact directory is `automation-artifacts/2026-10-20-syn-isolated-library-validation`. Its `inputs.json` records the exact source/configuration hashes, original SDK and generated-output baseline, exporter hash and copied inputs. `prepare-inputs.rb` requires the recorded clean source revision and materializes dependency/SDK inputs as ordinary files in this directory. It copies input binaries, never a Syn source snapshot. Installed Gradle/Native inputs, the shared dependency caches and the existing project Android SDK are read-only; no Maven-local or sibling substitution is used.

The source preparation introduced two narrow changes: avoid rewriting an already identical `local.properties`, and let the real Swift harness's Package.swift read `KOTLINMANIA_SWIFT_EXPORT_BUILD` while retaining `../build` as the default. The six existing Swift test bodies were unchanged. The tested build-script formatting was subsequently corrected to put its multiline initializer on the next line; that correction has not been rebuilt and must not be described as the exact tested source.

After a fresh serialized LOCAL grant, the exact entry points are:

```sh
cd /Volumes/stuff/Projects/kotlinmania/automation-artifacts/2026-10-20-syn-isolated-library-validation
ruby prepare-inputs.rb
ruby run-library.rb preflight
ruby run-library.rb gate
ruby verify-published-metadata.rb
ruby correlate-consumed-dependencies.rb
ruby audit-generated-api.rb
```

Preparation refuses unexpected source state; do not overwrite a previous run or re-activate its grant to repeat this sequence. Use a newly allocated owned artifact directory and reviewed input manifest for a subsequent revision. The current grant is inactive. The gate expands to `CI=true test build --continue --no-daemon --max-workers=2 --no-configuration-cache`, with `--project-cache-dir` and an init script inside the artifact directory. It is a full KMP gate, not a JVM smoke test. `logs/gate-invocation.json` preserves every argument and environment value.

`isolation.init.gradle` sets layout.buildDirectory before project configuration, selects only the verified exporter on the original compiler worker classpath, redirects Node/Yarn locks and SwiftPM dependency checkout paths, and rejects declared task outputs outside the artifact root. Gradle, Konan, Kotlin persistent state, Java temporary/user/IDE directories, Android user/AVD state, Swift/Clang module caches, npm/Yarn caches and all products are isolated. `native-sdk/` and `konan-data/` are owned input copies. The init script removes only the known appended GenerateSPMPackage action that rewrites generated source/manifest; the real generation action remains. Preflight observed StandardTaskAction plus AbstractTask$TaskActionWrapper. The later action receipt captured a mutable action list after mutation, so its original_actions field is incomplete; the preflight inspection and removed-wrapper field are the evidence for the two-action starting state.

The strict Swift build uses `strict-swift.xcconfig` with warnings as errors. `run-real-swift-tests.rb` invokes the existing shared `swift-test-harness` with artifact-local SwiftPM scratch/package/config/security/module paths, Swift 6 and `-Xswiftc -warnings-as-errors`, targeting macOS ARM64 15.0. It replaces the outer smoke runner's nested Gradle invocation, retaining the real embed dependency and six real test bodies. No generated output was patched to force a pass.

Observed results:

- Session **49789** performed actual Native compilation/static linking and the full KMP task graph. Fresh XML reports contain **3,896 passing cases, zero failures/errors/skips across eleven environments**: JVM 356; macOS ARM64, Android host, JS browser/node, Wasm JS browser/node, Wasm WASI node, iOS simulator ARM64, tvOS simulator ARM64 and watchOS simulator ARM64 each 354. `actual-test-results.json` records every XML hash. This is no claim of Windows/Linux runtime execution or Android device execution.
- **Zero of the six real Swift tests executed.** Strict dependency SPM compilation failed on six exhaustive `Delimiter`/`Spacing` switches with unreachable `default: fatalError()`, at generated ProcMacro2 Swift lines 21/32/71/83/92/125. `logs/current-spm-diagnostics.json` and the decoded Xcode activity log preserve the diagnostics.
- The fresh KGP report records four failed tasks: `macosArm64DebugBuildSPMPackage`, `detektMacosArm64SwiftExportMainSourceSet`, `ktlintKotlinScriptCheck`, and `runKtlintCheckOverMacosArm64SwiftExportMainSourceSet`. Detekt identifies the generated coroutine resource's unused private `Throw` class at line 127 and reports three analysis compiler errors. The source build-script lint error was at line 173; its formatting correction is source-only. The generated-source ktlint task's precise exception was not recovered before client loss; it remains an unclassified failure, not an assumed filter defect.
- The exec connection was lost before the outer runner could write `gate-result.json`. Its owned daemon log explicitly records client disconnection, build cancellation and daemon shutdown. The fresh KGP build report records the failures, but there is no reliable numeric process exit or final full-gate completion. `local-slot-return.json` records this distinction; no cancellation/restart was requested by this worker.
- `generated-api-abi.json` verifies **six byte-identical generated C headers** against the retained pre-existing baseline and identical selected public declaration lines in seven generated Swift files. All **7,300 forward Syn C exports** are present in the actual Native static library, SHA256 `2d39857573e887eee5e37a4beb4091f0d475c94f9ed53efb1d861e2b486e5fed`. The **774 reverse Swift declarations** and a complete compiled real-library Swift API/ABI comparison remain unverified because SPM failed. Declaration-line comparison is not a complete binary ABI proof.
- The corrected independent Central audit fetched metadata for 108 coordinates and matched 142 of 156 copied binary candidates. Fourteen unmatched older Quote cache candidates were preserved, not silently replaced. The fresh KGP compiler-argument report exposes four consumed JVM JAR paths; all four match Central SHA256 metadata, including Quote. This limited path coverage does **not** prove all consumed Native/Android artifacts. `published-metadata-verification.json` and `consumed-dependency-verification.json` record the scope. The older filename-based verifier used incorrect KMP URLs; its 404s are not evidence of an unpublished dependency.

Source-only producer analysis fetched and read `native/swift/sir-light-classes/src/org/jetbrains/sir/lightclasses/nodes/SirEnumFromKtSymbol.kt` at the same official revision (blob `b568e90314c57fbd493a1554a2bab395a753331d`). The candidate omits the fallback only in the three exhaustive nonempty switch-self bodies, retaining empty-enum and integer-input fallbacks and invalid-string validation. `enum-producer-analysis.json` and the original/candidate/diff record it. **No compiler, class ABI, packaging or behavior validation has run on that candidate.** It must not replace the proven artifact for another owner's build until separately validated.

The full real-library gate remains blocked by strict exporter diagnostics and generated-source lint, plus the interrupted aggregate and the unretested formatting correction. The earlier twelve-test fixture remains valid fixture evidence only. Installed tooling and shared caches require no rollback because they were not modified. Preserve raw receipts/logs and coordinate the next serialized build; do not delete inputs, deploy the exporter, publish a toolchain/library, or imply fleet adoption from this validation.

### Swift language-mode qualification and enum follow-up preparation

The retained Syn Xcode compiler activity confirms `KotlinRuntimeSupport` was compiled with **`-swift-version 5 -warnings-as-errors`**. A Swift tools 6 test harness does not force a generated tools 5.9 dependency package into Swift 6 mode. The twelve passing fixture test cases are real Swift 6 test-target evidence; they are **not** proof that every generated dependency module compiles in Swift 6 language mode. `automation-artifacts/2026-10-20-syn-isolated-library-validation/swift-language-mode-review.json` records the observed command and activity-log hash.

Libc's subsequent isolated run explicitly enabled Swift 6 for generated dependencies and reported that the exported `KotlinError: Error` value stores a non-Sendable `KotlinBase`. This is separately observed Libc runtime evidence, not a new Syn run, and no `@preconcurrency`, unsafe Sendable declaration or language downgrade has been applied as a repair. A complete Swift 6 generated-runtime gate remains unverified.

The parent-authorized enum follow-up is prepared under `automation-artifacts/2026-10-20-syn-isolated-library-validation/enum-repair/`. Its inactive grant, frozen input hashes, bounded allocation request, official baseline/candidate compiler and class/JAR audit scripts, strict generation runners, six Swift enum behaviors and lint-provenance plan are inspectable there. No producer compile, fixture generation or new test has executed in this follow-up. The enum tests cover empty/single/multiple cases, descriptions and ordinals, invalid/extreme inputs and actual Native reference round-trips. This diagnostic isolation of the enum family must record generated dependency language modes and cannot establish that the separate Swift 6 runtime issue is fixed. Preserve the earlier interrupted real-library result and 3,896 passes as historical exact-revision evidence; no new full KMP result is claimed.

### Bounded enum result and immutable runtime diagnostic candidate

The bounded follow-up used unchanged clean Syn **61290caba7ebe4d23ee5ef613d70634f08bd4434**. Both official baseline and candidate producer compilations passed `-Werror` in session **89168**. Packaging did not pass: audits 69252/7856/20102 rejected reconstruction differences. Constant-pool indices, `ldc`/`ldc_w` widths and instruction-label relocation explain some differences, but unchanged `SirEnumFromKtSymbol.attributes_delegate$lambda$0` still differs in local register allocation and instruction/control layout. No broader difference was waived. Raw disassemblies and method-graph differences remain under `enum-repair/logs/`. No candidate JAR was produced; zero enum embeds/Swift tests executed.

Lint dry run **4284** exited 0 but includes `compileKotlinMacosArm64` and `macosArm64DebugSwiftExport`, so no diagnostic execution was allowed or attempted. Initial sandboxed wrapper 39490 failed before Gradle initialization because its lock-contention socket was denied; the same dry run succeeded with reviewed socket access. `enum-repair/local-slot-return.json` records all terminal sessions and inactive allocation. Session 49789 was not restarted; no new full gate supersedes its historical 3,896 cases.

Sydney directed resetting expectations against Rust semantics rather than preserving current code as dogma. The full existing Syn `tmp/syn/src/error.rs` and `thread.rs` were read: diagnostic messages are owned Strings; span access is thread-bound and falls back to call-site on other threads. Libc's owner independently reviewed its raw status/errno API and found no original Throwable-object identity requirement. This supersedes the experiment's earlier requirement to preserve unrestricted Swift `KotlinError.wrapped` object access.

The uncompiled/unpackaged source candidate is `automation-artifacts/2026-10-20-syn-isolated-library-validation/runtime-snapshot-repair/producer-src/KotlinRuntimeSupport.swift`, SHA256 **69ce24e6cad0169a25a129d79cc597883c8359e23d6f3241a485c41f0c6fee25**. `KotlinError` stores only `public let message: String`; description returns it. `init(wrapped: KotlinBase)` retains its synchronous call signature, immediately captures the existing message/fallback expression, and stores no Kotlin object. `init(message: String)` constructs an owned diagnostic directly. Removing public mutable wrapped is intentional API change, not an ABI-neutral rename or unchecked assertion. The two confirmed synchronous/asynchronous producer construction sites retain their calls; strict compilation must verify the source-level compatibility.

`runtime-snapshot-repair/candidate-contract.json` pins the resource/Rust inputs with verified=false. Prepared fixture changes retain all twelve behavior scenarios, replace object identity with diagnostic stability after source-message mutation, and add a detached-task throw/catch test: thirteen candidate cases. MutableStateFlow protects the source-message mutation. No fixture case was deleted, no generated output edited, and no unsafe Sendable/preconcurrency/handle workaround or warning waiver introduced. Libc independently reviewed this source contract, not its compiler result.

Next runtime validation must explicitly compile generated dependencies in Swift 6 with warnings as errors. The earlier dependency-mode-5 fixture does not prove that coroutine Task captures or generic continuation transfers satisfy Swift 6. Fresh producer generation, actual untouched-runtime regression, all thirteen fixture cases and real-library exact-head gates remain unverified, and require fresh serialized allocations. The proven exporter SHA170771a2 remains unchanged and does not contain this candidate. No installed tooling, shared caches, rollout or publication changed.

### Resource-only snapshot validation under explicit Swift 6

The next authorized bounded allocation froze 31 input files in `runtime-snapshot-repair/frozen-inputs.json` (manifest SHA256 `5cb83fe08d538705177840f59e6ae86c30f1addde01459273ee7a31c5deb1142`) and retained product checkout **61290caba7ebe4d23ee5ef613d70634f08bd4434** unchanged. Exact runners in that artifact directory are `package-runtime.rb`, `run-runtime-fixture.rb baseline`, `run-runtime-typecheck.rb baseline`, `run-runtime-fixture.rb corrected`, then the remaining corrected typecheck/13-test runner only when authorized and module compilation is green. Do not execute this list blindly: inspect each result and stop on a new unexpected diagnostic.

`package-runtime.rb` session **79691** exited 0. The candidate JAR is `runtime-snapshot-repair/toolchain/runtime-snapshot-swift-export-embeddable-2.4.20.jar`, SHA256 **7b0007f8f93843a0bcf785cd6fc4d85f2540f14e808f07a62595cb53b61c8695**. The full 6,263-entry audit proves exactly `swift/KotlinRuntimeSupport.swift` changed, zero class changes, no additions/removals. The proven SHA170771a2 JAR is unchanged. No K2 recompile or enum-class packaging occurred.

Both tiny fixture builds use explicit `SWIFT_VERSION = 6.0` and warnings-as-errors in the owned Xcode config; recorded frontend commands verify language mode 6 for KotlinRuntimeSupport, KotlinCoroutineSupport, ExportedKotlinPackages and the exported coroutines module. All Gradle/Konan/product/cache writes stay in the existing owned artifact root; installed/shared caches remain read-only. The actual fixtures and thirteen candidate behaviors use the new owned-message contract described above.

- Baseline build **37556** exited 1 and reproduced the exact non-Sendable `KotlinError.wrapped` error. Native compilation and static linking passed. This is the expected negative, not a product pass.
- Pre-typecheck wrapper **47234** launched no Swift compiler: a strict resource-hash assertion discovered the exporter appends exactly one trailing LF. Full byte comparison proved that sole difference, and `input-manifest.json` pins the actual untouched generated runtime/C inputs. No generated output was changed. The generated baseline runtime SHA256 is `59c15fb0cf52f6002a4848b491593031fbcc021ed8902a132dca1691db12f8dd`, distinct from the raw JAR resource hash.
- Baseline focused regression **85939** returned wrapper exit 0 because the actual Swift compiler exited **1** with the exact expected Sendable diagnostic under `-swift-version 6 -warnings-as-errors`. `logs/baseline-result.json` preserves both statuses.
- Corrected build **40499** exited 1 on the next producer family: generated KotlinCoroutineSupport.swift lines **66 and 91** capture non-Sendable `KotlinTask` in `@Sendable` cancellation closures. Native compilation/static linking passed. KotlinRuntimeSupport's corrected Swift 6 module was emitted successfully; its module hash and actual compiler modes are in `corrected-output-evidence.json`. This validates that module's snapshot correction, not the whole runtime or its behavior suite.

The first new blocker stopped execution. The standalone corrected-output typecheck and **all thirteen behavior cases remain unexecuted**. `corrected-input-manifest.json` pins freshly generated untouched corrected runtime/C inputs for a future allocation. Four generated C headers are byte-identical between these two tiny fixture variants; this is not a real Syn/Libc binary ABI guarantee, and KotlinError's intentionally changed Swift public API is not ABI-neutral.

All owned commands are terminal, the grant is inactive, and `runtime-snapshot-repair/local-slot-return.json` records the explicit return. Cancellation follow-up must preserve native-job/Swift-task cancellation and lifetime semantics; simply asserting Sendable or holding a lock around a native call is not a correction, because callbacks may re-enter Swift. No additional compile, retry, suppression, unsafe Sendable, generated-output edit, installed deployment, adoption, CI, full KMP repeat or publication followed this blocker.

### Cancellation ownership follow-up: prepared, not executed

A source-only candidate for the two Swift 6 cancellation captures is recorded under `automation-artifacts/2026-10-20-syn-isolated-library-validation/runtime-cancellation-repair/`. Its `producer-src/KotlinCoroutineSupport.swift` SHA256 is **f8acaaeb7f4f42a3ea1948b9e89265c5e16274f66dd2b674969d9b804055664c**. The bound KotlinTask remains non-Sendable. A checked `KotlinTaskState` protects the current-task handle, and a checked `KotlinCancellation` owns the bound native Job wrapper through Mutex storage. Native callbacks capture the checked state; Sendable cancellation handlers capture the checked owner. Native calls occur after releasing the owner mutex, with explicit lifetime extension on direct calls. No unchecked Sendable conformance, preconcurrency conversion or new Objective-C annotation was added.

This is a candidate, not a proven ownership repair. `candidate-contract.json` explicitly requires compiler validation of sending parameters and Mutex-region transfers, review of inner externalRCRef lifetime at every generated conversion, and reentrancy checks for callbacks registered on an already-cancelled Job. The installed SDK explicitly marks UnsafeCurrentTask's Sendable conformance unavailable. Kotlin's [Job concurrency contract](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-job/) permits concurrent Job operations, but does not establish Swift wrapper lifetime. No lock may be held across a native callback-capable call merely to make a declaration compile.

`run-cancellation-regression.rb baseline` is prepared against actual unchanged coroutine source generated in session40499, its emitted Swift6 runtimeSupport module, and the original C module headers. `baseline-input-manifest.json` pins these real inputs; no fake KotlinBase or generated-output patch is used. The expected negative requires actual nonzero compiler exit and the precise KotlinTask Sendable-capture diagnostic. A corrected mode requires separately pinned fresh producer output and compiler exit0. Neither mode has run. `prepared-allocation-request.json` freezes the candidate/negative inputs and requests a new bounded slot; current grant is inactive. All thirteen real fixture behavior cases remain mandatory and unexecuted in this new contract.

Separately, the template owner received read-only actual-library KLIB receipts in `syn-local-klib-handoff.json` and `syn-native-dependency-path-handoff.json`. The existing macOS Syn KLIB is an unpacked77-file directory with content-tree SHA256 `e208fb3a4c249ff7f64f81d13cc9f40c0f55600820537eb8756f9d6f0c942786`; its source-root historical output is byte-identical to the isolated actual-library output. Actual source was compiled at3cfb17f; current61290ca changes only build-script formatting and all Kotlin source hashes match. Fixture Integration/EnumFixture KLIBs are not Syn library outputs. The retained report does not expose the Native compiler argument list; cached resolved-coordinate candidates are therefore qualified, not represented as proven consumed inputs. Atomicfu's main/cinterop files have distinct required identities; Quote has an older duplicate plus a current independently published-checksum match. No compilation/download/output recreation was performed for this handoff.

### Lifetime review correction and test-parity qualification

The cancellation candidate above at hashf8acaa is superseded by source SHA256 **374d75a6f978ba56cb3e56b55c822f5f10b15886e4f93d3dd25f13a06f881269** at the same candidate path. The prior source is retained, hash-checked, in `runtime-cancellation-repair/review-history/KotlinCoroutineSupport-f8acaa.swift`. Source review identified that the Swift forwarding getter does not itself inherit KotlinBase's NS_RETURNS_INNER_POINTER lifetime semantics. The candidate now explicitly encloses the entire forward `fn` invocation in `withExtendedLifetime(cancellation)`. Both official `swiftAsyncCall` generation and iterator.next execute their complete foreign call inside this nonescaping scope; direct setTask/cancel calls already extend self. This addresses the identified scope gap in source, not a compiler/runtime proof.

All thirteen candidate scenarios are retained. The existing deterministic cancellation case additionally prepares eight concurrent cancel/release races, permits only successful released-value delivery or CancellationError, waits for producer finalization, and issues late cancellation after the receiver result/clear. The unsafe current-task handle is not copied out of its mutex. Source/race hashes and unproved reverse-transfer/cache-identity/reentrancy requirements remain in `candidate-contract.json` and `prepared-allocation-request.json`. No new compiler, JAR or test execution followed this review; no green fixture claim is made.

The historical 3,896 XML cases also require a newly discovered qualification: **55 executions were empty SizeTest bodies**, five tests across eleven environments. Reading the entire matching Rust test file confirms exact full-value size assertions Expr176, Item352, Type224, Pat184 and Lit24, under compiler/pointer-width/span-location guards. They measure the inline enum value including payload and padding, not the discriminant alone. Current Kotlin AST roots are managed sealed classes without the asserted inline-value representation. Neither comments, counts, serialized lengths, fixed-size dummy buffers nor C structs unused by the actual parser would preserve those operations. The source-only statement mapping and required real-representation/actual-stride proposal are `automation-artifacts/2026-10-20-syn-dependency-repair/size-parity-review/statement-parity-review.json`. Those five false-green test bodies remain unrepaired; neither 3,896 XML cases nor the unaudited remainder establish Rust test parity. The owned historical JSON reports now carry this qualification while preserving their XML totals and raw evidence.

### Bounded cancellation validation: expected negative and pointer-contract failure

The next granted run used the frozen 57-input manifest `42bae75193cf8848add5eecf3bfd4a49c1fd97289db81fe9adec36a914b25b2e` under `runtime-cancellation-repair`. Packaging session71093 exited0: exactly `swift/KotlinCoroutineSupport.swift` changed atop snapshot JAR7b000, with zero class changes and no added/removed entries. The resulting experimental JAR SHA256 is `a44af057f5cfdf81b63a31f653605f377beeaa92558ced3c13a7831bc28fa193`.

`ruby run-cancellation-regression.rb baseline` ran in session87418 against the untouched40499 output: actual Swift6/Werror compiler exit1 reproduced the KotlinTask capture diagnostic; wrapper exit0 means the expected negative matched. `ruby run-runtime-fixture.rb corrected` session10502 exited1 after30seconds/16 executed tasks. Native compile/static link passed. Swift compilation rejected three uses at coroutine source lines66/76/329 because the newly introduced forwarding method returns `UnsafeMutableRawPointer?` while native bridge parameters require nonoptional pointers. The raw compiler log hash is `39315e00058bc46ec2b67143a0b87ed84313e023513b1963835d0ddf0dc7cf7f`. No corrected standalone typecheck or behavior runner followed: **zero of thirteen behavior cases executed**. The allocation was returned with no retry; `local-slot-return.json` records each actual exit separately.

The source-only follow-up `runtime-cancellation-repair/next-candidate/KotlinCoroutineSupport.swift` restores the forwarding getter's nonoptional result contract without casts, force unwraps, suppression or generated edits. Its hash is `b5597d8c475c7c470afb01b737051736dfc0b04ab06e849a0b9e2b719eeaa4f7`. It is uncompiled/unpackaged; the previous frozen candidate and actual generated failure output remain intact. Peer lifetime, cached-wrapper transfer and cancellation reentrancy obligations remain unproved. Run the same bounded audit/negative/fresh-generation/typecheck/all-thirteen sequence only after another allocation, with fresh manifests and output paths. This is not product integration, publication or toolchain adoption.

### Pointer baseline diagnostic parser correction

The separate `runtime-pointer-repair` run packaged source b5597d8c as experimental exporter SHA256 `b49fd36c8a0c7989db5a6a37b9ac91b8f117b1065afa02ca6399523772583877` in session18883; audit again found exactly one coroutine resource delta and zero class/add/remove changes. Baseline session31593 actual Swift6/Werror compiler exit1 reproduced the same three located optional-pointer errors at66:43,76:48,329:126. The original runner exited1 because it counted the three inline highlight renderings again. Raw log SHA256 `42dc07093a40d1e9d97f615b968b58ca4f3a466759fe3d03eb607cde143ff4f3` and original runner remain intact. Offline anchored source-location classification confirms three errors, with no additional family; the mismatch is a harness counter defect, not an inferred producer regression.

No corrected embed or behavior test started in that allocation. The slot was returned. `run-pointer-regression-v2.rb` corrects only diagnostic counting; the fresh87-input continuation manifest is `494aec801d4b52dd52088705c8a6baf669bf6593ad58456d3c367ac69f1d2bdb`. The remaining requested allocation reuses the existing verified JAR and baseline evidence, runs one corrected embed, and only after module success pins fresh output, typechecks it and runs all thirteen cases. No baseline rerun, repack or successful candidate claim is implied.

### Pointer continuation: actual Swift 6 ownership errors

After a fresh87-input check, corrected embed session65330 exited1 after30seconds/16tasks. Native compilation and static linking passed. The nonoptional pointer correction removed the previous three pointer errors. Actual dependency Swift6/Werror compilation then rejected five ownership operations in generated KotlinCoroutineSupport.swift: line37 inout-sending mutex state retained task isolation; lines85 and112 attempted to send the borrowed UnsafeCurrentTask; line89 transferred an unconstrained generic continuation value; line110 passed a task-isolated closure to Task's sending parameter. These are real compiler diagnostics, not diagnostic-counter duplication. The ownership design is not validated by using checked Sendable on the containing classes.

`runtime-pointer-repair/continuation-local-slot-return.json` records terminal exit1 and the five complete diagnostics. The raw log SHA256 is `a18ba4da5d4e6d9427c441cec3e35c364e9cb24a013925243b33dbd8f0bf8bbb`. The first unexpected diagnostic stopped the allocation; no corrected standalone typecheck or thirteen-case behavior runner executed. No baseline retry, repack or second generation occurred. Both execution grants are inactive and the coordinator received explicit slot return. Exporter b49 remains an experimental failed candidate, not an adoptable/published fix. A genuine ownership redesign must address borrowed current-task lifetime, continuation payload transfer and reverse Task closure isolation before new behavioral evidence can be claimed.

### Prepared owned-task and end-to-end callback repair (uncompiled)

The next source candidate is isolated in `runtime-task-ownership-repair`. Runtime SHA256 `22fc88554fa88fe96df572da918cd34a2863b650cdbc6a2ea62c432c601bca1e` removes stored borrowed current-task handles. Reverse calls retain the actual Task<Void,Never> handle with synchronized pre-install cancellation and early-completion state, and invoke Task.cancel outside locks. Forward calls create no child task: the native callback records cancellation, and every completion exit closes state and marks the resumed caller cancelled inside a synchronous withUnsafeCurrentTask scope. This covers successful fallback and non-cancellation failure after native cancellation; it does not claim identical instantaneous observer timing before resumption.

The repair also changes real generator sources: TypeBridging SHA256 `8f4912c4fb0f8e4fd5ecad3179461ab0bf070f68ac1cecd78d1e7ac2e486a58e`, SirBridgeProviderImpl `37313372163c70f365f10e2e075be7ff22b96c6790f6500f5599512a5c1cad5f`, and SirAsSwiftSourcesPrinter `5a80e47960b763c7f8ee0933f09d2567292eab8cfd7be763104ae5448024786b`. Forward continuation ownership survives its generated adapter; async closure APIs require checked @Sendable, preserve dynamic actor isolation, and transfer parameter/result regions. This intentionally changes closure source contracts. It adds no KotlinBase Sendable conformance, unchecked assertion, preconcurrency import or warning waiver. The design follows [SE-0430](https://github.com/swiftlang/swift-evolution/blob/main/proposals/0430-transferring-parameters-and-results.md) and [Mutex ownership](https://github.com/swiftlang/swift-evolution/blob/main/proposals/0433-mutex.md); those references do not substitute for actual compilation and alias/lifetime proof.

All original thirteen fixture cases are byte-preserved. Six added cases cover generated reverse suspend success/error/Kotlin-origin cancellation, forward native cancellation followed by fallback/other error with caller cancellation status, and concrete reverse Payload referent identity. An additional compile-negative must reject retaining a non-Sendable Payload alias in the asynchronous Sendable callback. All nineteen behavior cases and the new negative remain unexecuted. Existing thirteen alone never invoked withKotlinTask; fresh output must contain real reverse invocations before any both-direction claim.

The prepared120-input manifest is `5c9fc3c37bfc8e203a4745e6716b1e60c4be0cdde4dd1533608f359c6cfcf391`; request hash `3cce212c17da9857862031cbab9ce39772efd76c38bfa1acefc21b7c56563487`. After explicit serialized allocation, run `build-generator.rb baseline provider`, `candidate provider`, `baseline printer`, `candidate printer` (four K2 tool-generator calls). Then `audit-and-package.rb` requires exact official-baseline instructions/signatures before packaging four specified existing classes and one runtime resource atop b49. No widened normalization or enum patch is allowed. Next run the actual65330 five-error negative and one fresh strict corrected embed. Only after module success, pin fresh outputs, verify actual reverse callers/concrete ownership signatures and recovered Swift6/Werror frontend commands, run the corrected typecheck and retained-alias negative, and run all nineteen cases. Each command and stop rule is in prepared-allocation-request.json. No compiler call, package creation or adoption occurred during preparation. Cached foreign-wrapper aliases and adversarial scheduler interleavings remain explicit proof limits.

Async acceptance remains a separately published producer contract: public io.github.kotlinmania.syn.TokenStreamParseResult and public SynResult<TokenStream>.toTokenStreamParseResult(), plus the consumer KDoc import/reference, must resolve from the independently fetched release. Maintained Async PR168 and the tmp/async-trait-kotlin new build-template checkout are separate consumer revisions. Local source existence and an isolated exporter never establish publication; no new compatible version is claimed.

### Anyhow exact-head remote corroboration and current repair limits

Anyhow CI [39500059547](https://github.com/KotlinMania/anyhow-kotlin/actions/runs/39500059547) checked out **d8583d90a195e4a32367c27183ee3dce4615de50**. Its real `macosArm64DebugBuildSPMPackage` invoked xcodebuild on Xcode26.6 / Apple Swift6.3.3 (`swiftlang-6.3.3.1.3`); the log identifies Kotlin/Native2.4.20. The actual KotlinRuntimeSupport frontend has `-swift-version 6 -warnings-as-errors` and no `-enable-library-evolution`. This is independent real-library CI corroboration of the existing published-runtime blocker, not a new warning family or an isolated producer success.

Generated `build/SPMPackage/macosArm64/Debug/Sources/KotlinRuntimeSupport/KotlinRuntimeSupport.swift` reported:

- 1:22: `using '@_implementationOnly' without enabling library evolution for 'KotlinRuntimeSupport' may lead to instability during execution`
- 5:16: `stored property 'wrapped' of 'Sendable'-conforming struct 'KotlinError' has non-Sendable type 'KotlinBase'`
- 2:1: `add '@preconcurrency' to treat 'Sendable'-related errors from module 'KotlinRuntime' as warnings`

The last diagnostic is compiler output, not permission to apply that workaround. No preconcurrency import, weaker language mode, unsafe Sendable declaration or generated-output edit is part of this repair. The retained log is `automation-artifacts/2026-10-20-anyhow-ci-repair/ci39500059547-swift.log`, SHA256 **0db99357f7d75909ceff59a9df8d6a463adf40b852a170f9d8e1ee0266b3599b**. The owner reports zero of two real Swift harness cases executed; a module failure is not a runtime test pass. Only2.4.20 producer execution is established here; this is not verification of a2.4.21 fix.

| Evidence | Verified result | Limit |
| --- | --- | --- |
| Original twelve-case isolated fixture | Twelve behavior cases passed with Swift6 test sources | Retained dependency compiler command used Swift5/Werror. A Swift6 test target did **not** establish Swift6 dependency compilation. |
| Snapshot runtime candidate69ce24 / JAR7b000 | Actual Swift6/Werror KotlinRuntimeSupport module compiled with owned String diagnostic and internal import | Coroutine module then failed; zero new13-case behaviors executed. This is partial module evidence. |
| Corrected pointer embed65330 / JARb49 | Native compile/link passed; optional-pointer errors removed | Actual Swift6 coroutine compile reported five ownership-transfer errors; zero13-case behaviors executed. |
| Owned-task/end-to-end candidate22fc8855 and three generator sources | Four K2 tool-generator compilations and strict audit-v4/package67641 passed | Baseline31685 used typecheck only and unexpectedly produced no diagnostics. No corrected embed or nineteen-case behavior runner executed; no adopted/published repair is established. |

The strict audit stop preserves the official-baseline instruction comparison and raw output. Source-specific member deltas are recorded in `runtime-task-ownership-repair/compiled-member-delta-review.json`; they require explicit bounded audit review rather than silently broadening normalization. Full real Syn/Libc validation and a legitimate verified published promotion route remain separate from fixture/compiler evidence.

### Strict generator audit passed; object-stage negative still required

Audit-v4 session67641 exited0. For all four selected classes, the rebuilt unchanged official baseline matches original signatures, normalized instructions and complete class-file access flags. Candidate added/removed members match the frozen names, descriptors and complete flags, including static/final/synthetic bits. The removed printer lambda6 and replacement lambda7 have equal normalized bodies after the explicit declaration-name correspondence; instruction normalization was not widened. Earlier audit64439 remains a historical stop, superseded by this reviewed executable audit.

`runtime-task-ownership-repair/package-results-v4.json` records the resulting isolated `toolchain/owned-task-swift-export-embeddable-2.4.20.jar`, SHA256 **f7715a1c7a3ddf443853fd38e6939813c7c3b3fd093a5bc0b7db545f1673d0f3**. It retains6263 entries, adds/removes none, and changes exactly the four approved existing classes plus `swift/KotlinCoroutineSupport.swift` atop b49. This proves the bounded packaging audit; it does not prove generated Swift compilation, public Swift ABI compatibility or concurrency behavior. The intentional async closure source-contract changes still require actual generated signatures and behavior checks.

The next command, baseline31685, ran `swiftc -typecheck` on the untouched generated65330 source with Swift6/Werror. The compiler exited0 with an empty diagnostic log; the regression wrapper exited1 because it required the five previously observed ownership errors. Actual Xcode65330 used object compilation (`-c`) and failed those five operations. A compiler-stage gap is therefore a hypothesis to verify, not evidence that the old source was repaired. The first unexpected result stopped execution and the local slot was explicitly returned. No corrected embed, output pin, alias negative or nineteen-case runner followed.

The changed-stage recipe is frozen in `runtime-task-ownership-repair/object-stage-request.json` (SHA256 **03b5f56fe0dd0c450fcb10f6d592089df721d247c0e621f26105f4d41eb449fa**) and `frozen-object-stage-inputs.json` (148 inputs, SHA256 **76db766021ac9b5ed35e88c64314fbccf069badfe2711da2ac38a3d6df18ef85**). After a fresh serialized allocation and input verification, run from that directory:

1. `ruby run-ownership-object-regression.rb baseline`: compile the actual untouched65330 source with `-c`, Swift6/Werror and an artifact-local object output; require the exact five located errors.
2. Only after that expected negative matches, run the single fresh `ruby run-runtime-fixture.rb corrected` embed.
3. Only after module compilation succeeds, run `ruby pin-corrected-inputs.rb` to verify untouched outputs, actual reverse withKotlinTask callers, concrete Payload ownership signatures and recovered dependency Swift6/Werror frontend commands.
4. Run `ruby run-ownership-object-regression.rb corrected`, then `ruby run-alias-object-negative.rb`, then `ruby run-swift-tests-object-gated.rb` for all nineteen preserved/additional behaviors, each gated on its predecessor.

These object-stage runners are prepared, not executed. Preserve the previous scripts, raw logs and result receipts. Stop on the first unexpected diagnostic or provenance mismatch. This continuation needs no K2 rebuild, audit repetition or repack; it authorizes no second generation, warning waiver, generated edit, installed/shared-cache mutation, product adoption or publication. Cached wrapper aliases and deterministic cancellation race coverage remain explicit proof limits even if the declared cases pass.

### Object-stage baseline verified; corrected embed exposes Flow and Kotlin callback defects

Object-stage baseline40300 exited0 at the regression wrapper and1 at the actual Swift compiler. Untouched65330 source reproduced all five expected ownership diagnostic families at37/85/89/110/112: inout-sending task isolation, borrowed currentTask transfer, generic continuation transfer and Task closure transfer. `logs/object-baseline-family-verification.json` verifies the diagnostic families, not merely their number and locations. This confirms that the preceding typecheck-only command missed checks exercised by object compilation.

The single corrected embed87904 exited1 after26seconds. Actual Swift6/Werror object compilation reports `KotlinCoroutineSupport.swift:338:21: sending value of non-Sendable type 'Element?' risks causing data races` at the Flow iterator's `continuation(.some(element))`. The five baseline operations were not reported in this output; their absence is not a behavior pass. Separately, Native bridge compilation fails under Werror on generated `Integration.kt` lines78/86/94: unchecked casts from Any to callbacks `(String) -> Unit`, `(NSError?) -> Unit`, and `(Payload) -> Unit`. Both failed tasks concern producer output; no generated file was patched.

The Flow source creates a possibly cached KotlinBase wrapper before handing generic Element to a sending continuation. Its lifetime/alias and isolation contract needs repair; encoding a pointer as an integer or declaring blanket Sendable would not establish that contract. The Kotlin callback source chain is asyncContinuationBridges/renderKotlinSuspendSwiftCoroutine, then AsCovariantBlock.inKotlinSources retaining the function object, then AsCovariantBlock.helperBridges casting dereferenced Any to parameterized Function1. A concrete callback carrier could make the runtime cast checkable, but requires producer implementation and actual tests; it is not yet a verified fix.

`runtime-task-ownership-repair/object-stage-local-slot-return.json` records both terminal sessions and explicit slot return. Corrected embed log SHA256 is **6b0d9cc8a9a0776601179cb8101d844157c87779e4e5a5941f2648115097c772**. No corrected-output pin, standalone corrected object compile, retained-alias negative or nineteen-case runner executed. All nineteen behavior cases remain unexecuted. No retry, second generation, toolchain adoption or publication occurred.

### Compatible managed Flow handoff and concrete callback carrier (source only)

The Element:Sendable restriction was rejected before compilation: it would remove the retained `nullPayloadIsDistinctFromCompletion` behavior over Flow<Payload?>. The original nineteen cases remain byte-preserved. The replacement source under `runtime-task-ownership-repair/flow-handoff-candidate/` keeps the managed value in Kotlin until the caller resumes, then obtains the external reference synchronously and materializes the wrapper in the caller's isolation. Only Bool readiness crosses the continuation. Explicit isolated iterator overloads and caller-inheriting legacy methods follow [SE-0421](https://github.com/swiftlang/swift-evolution/blob/main/proposals/0421-generalize-async-sequence.md) and [SE-0461](https://github.com/swiftlang/swift-evolution/blob/main/proposals/0461-async-function-isolation.md); the latter syntax requires Swift6.2 or newer and remains uncompiled in this candidate.

A reviewed source interleaving showed that a second next could advance the producer between publication and take. The candidate now acquires native request ownership before advancing, retains it through value consumption, and rejects overlap without clearing the first request. EOS/error release ownership. The existing next C entry retains its payload-pointer callback semantics; a separate nextReady entry implements readiness-only delivery, and takeValue consumes the managed slot. This is an additive native interface, not a claim that new Swift can run with old Native. Pin/generate Kotlin runtime, Swift resource and C header coherently.

Generated Kotlin callbacks now have a proposed concrete private holder containing their exact typed function. The bridge retains that holder and checks its concrete class before invocation, replacing the unchecked Any-to-FunctionN cast. Existing required opt-ins apply to the holder declaration. This changes producer source; no generated output was rewritten. Three new prepared cases exercise caller-actor object identity, legacy iterator identity and the real C publication-to-take gap, including the old C entry's retained-pointer behavior. All original nineteen remain unchanged; twenty-two declared cases are unexecuted.

The source-only request is `flow-handoff-candidate/allocation-request.json`, SHA256 **f1efe46b752e730b3e9272d69c9e9829ebf9e8b04ea72802cb3de5d52608ad35**, with162 inputs in `frozen-inputs.json`, SHA256 **d49b8647e8e653e15099ea95f99c5033ee9730dcc6a6dce56b2126ad28898c5d**. Its ceiling is one candidate provider K2 tool-generator compilation and, only if green, unchanged-baseline signatures/instructions/full-flags review using existing frozen class files. It permits no packaging or Native/Swift build. Concrete member deltas require review before a later package. No new JAR, module pass, ABI pass or behavior pass is established. Arbitrary cached aliases on other actors, pending-value GC, callback-holder release and declaration deduplication remain proof obligations; same-actor identity alone does not establish cross-actor safety.

### Flow provider compilation and four-class baseline review

The one candidate K2 provider compile87850 exited0 with Werror. Read-only review73115 exited0: all four selected classes match their unchanged parent/reused baselines in signatures, normalized instructions and full access flags. `flow-handoff-candidate/provider-review.json` SHA256 **f7ba9c055f013cb12be8626db10798ac6ec27a2a5c214210a290df51e365e21f** records the candidate deltas; `reviewed-delta-mapping.json` maps them to source and freezes full changed-block hashes. They include the converter's captured owner/constructor, carrier-name capture, the new proxy registration method, descriptor metadata and typed-holder declaration. A read-only peer review found these intended changes consistent with source, without asserting generated lifetime or product ABI success. Adding an abstract proxy method does not establish compatibility with old third-party implementations.

Inspection then found a fifth required existing tool class: carrier opt-in emission calls `SirBridgeProviderImplKt.access$getOptInAnnotation(List):String`. A read-only member parser found the added accessor flags4121 and no removed members; the fifth class's full unchanged-baseline comparison has not run. `additional-class-dependency.json` records that finding. The next request is `fifth-review-request.json`, SHA256 **403d4067a49ff71652eb420f7c66a053e30448f32cecb0ffb03a8974240a6400**, with173 frozen inputs in `frozen-fifth-inputs.json`, SHA256 **57e3d3fd6e6d4833bca335d23ca25a010932708675c026f467352af3ea53e3bc**. It requests only read-only signatures/instructions/full-flags review using existing compiled output, with no compiler repeat or packaging. The four-class pass alone is not whole-bundle acceptance.

`local-slot-return.json` records both terminal exits and the returned allocation. No JAR was packaged, no Native/Swift/Gradle command ran, and none of the twenty-two behavior cases executed. An initial missing log-directory shell redirection failed before Ruby/compiler launch; the single actual K2 invocation count remained one. Keep these generator results separate from strict generated Kotlin/Swift and real-library gates.

### Fifth-class audit stop and dependency-contained source revision

Read-only fifth-class session91955 exited1: unchanged parent/reused-baseline signatures matched, but normalized instructions differed in SirBridgeProviderImplKt.isSupported. Retained `fifth-baseline-difference.json` identifies that one method; store/load versus dup/swap operations and branch/register layout differ. No equivalence is claimed, no normalization was widened, and the review stopped before candidate acceptance. `fifth-local-slot-return.json` records the inactive grant and terminal return. No package or compiler rerun followed that allocation.

The source-only revision `flow-handoff-candidate-v2` emits the carrier's opt-in declaration locally inside BridgeFunctionDescriptor using public ClassId methods. It removes the new call to the private file-facade getter. Any future bundle must retain the parent SirBridgeProviderImplKt entry byte-identically, rather than replace unrelated code whose baseline did not match. The next review explicitly rejects new file-facade members or method dependencies from the four selected classes; the prior mismatch is not waived. Runtime, header and all twenty-two test sources are unchanged from the previous candidate.

The v2 request is `allocation-request.json` SHA256 **4f3b2aa1d11aa983f0538d972bb0fb74b87a840a095b57a254499646707b806d**, with185 frozen inputs in `frozen-inputs.json` SHA256 **7fab198a1f5f07ee02038339e8d3d3ac93ca6f085ef3aa3ba3726307c66b2fc6**. Its scope is one materially changed provider K2 compilation and conditional read-only class/dependency review, with no baseline compile, packaging, Native/Swift/Gradle/tests, retry or adoption. At this checkpoint v2 is uncompiled and zero of twenty-two behavior cases have run.

### Coherent Flow/carrier v2 executed: runtime modules pass, generated call ownership fails

The v2 provider compile83345 and baseline/dependency review94630 exited0. Every selected-class file-facade reference resolves against the retained parent descriptor and full access flags; the parent facade remains byte-identical. Package55550 exited0 and produced `flow-handoff-candidate-v2/toolchain/flow-handoff-swift-export-embeddable-2.4.20.jar`, SHA256 **2fceea1c40c94e76928066b0fc6d2ddadf65bf8ff48150e15257053c2c19bfc2**. It changes exactly four reviewed existing classes and three coherent KotlinCoroutineSupport resources, retains6263 entries and adds/removes none. This supersedes the preceding v2-uncompiled checkpoint; it does not waive the historical fifth-class audit failure.

The combined recipe is frozen in `runtime-task-ownership-repair/flow-handoff-candidate-v2/validation-request.json`, SHA256 **20d5acce4be7848253fe574f7a0116e455bfa0a55de3f873c82f77eebe29af6d**, and250-input `validation-inputs.json`, SHA256 **bc07a75aa33e086b0ee94fbc26e13437d4991ae0661e3c8b5e79499c31e4b795**. Run its sequential package audit, actual baseline object regression and corrected embed under an explicitly serialized local allocation. Baseline51839 verified the actual untouched87904 Flow Element? error at338:21: regression wrapper0, actual Swift compiler1. It used object compilation, Swift6 and warnings as errors; typecheck alone is insufficient.

Corrected embed88568 exited1 after32seconds. Actual generated Native bridge compilation and static linking passed, and the previous unchecked Any-to-Function1 casts were absent. Both KotlinRuntimeSupport and KotlinCoroutineSupport compiled under actual dependency Swift6/Werror; the Flow Element? transfer error was absent. These are module/compiler results, not runtime behavior results.

Generated `Integration.swift` then reported25 unique ownership errors. At92:47, a Payload wrapper constructed from a foreign callback pointer cannot be passed to the sending continuation. Async functional adapters at505/528/551/585 cannot transfer their converted continuation, exception, cancellation wrapper and operation closure into withKotlinTask; Payload arguments also fail transfer. Reverse @_cdecl bridges at629/647 encounter the same transfer problem, including captured receiver/raw argument state. The source chain is AsContravariantBlock.inSwiftSources and createReverseAsyncSwiftBridge. Runtime helper signatures alone do not establish disconnected ownership for values materialized from foreign parameters or cached wrappers. The next repair must address those actual generated boundaries without unsafe Sendable assertions, pointer laundering, generated-output edits or a weaker language mode.

Retained evidence is `flow-handoff-candidate-v2/validation-local-slot-return.json` and `logs/fixture-corrected.log`, SHA256 **6543778483c2593ad2c951e2a65f9ce3f6ad06d23c4e87b59cf25eb1045d24cc**. All commands are terminal and the allocation was returned. Execution stopped before output pinning, corrected standalone object compilation, retained-alias negative and the verbose named behavior runner. **Zero of twenty-two behavior cases executed.** All original nineteen and three added cases remain preserved. No real-library pass, broad concurrency-safety proof, ABI compatibility, published producer repair or toolchain adoption follows from this partial result. The prior Swift5 dependency-mode qualification remains unchanged.

### Actor-isolation boundary probes and forward managed-result source

The next isolated probe imports the actual88568 KotlinRuntime headers and real non-Sendable KotlinBase. Session6584 made one object compile: the unconfined call was rejected because its KotlinBase result crosses from an opaque @isolated(any) operation to a nonisolated context. The original runner expected only the later `risks causing data races` wording and stopped; its source/log remain preserved. The coordinator accepted the exact retained semantic negative without rerunning it. Two remaining object compiles then completed in terminal tool chunk32c7e6: both compiler exits were1. Passing operation.isolation into a local isolated actor parameter still did not establish that the opaque operation result shares that isolation. The alternative `[isolated isolation]` capture syntax was rejected by this installed compiler. These are expressivity results under Swift6/Werror, not producer repairs or behavior passes. Evidence: `flow-handoff-candidate-v2/boundary-isolation-probe/{results.json,remaining-results.json,local-slot-return.json,remaining-local-slot-return.json}`. A proposal's future isolated-capture rules must not be assumed to compile.

The resulting source iteration is `flow-handoff-candidate-v2/forward-managed-result-source`. Native SwiftJob stores a managed per-request result before publishing Bool readiness; generated additive ready/take entries consume a concrete typed result carrier, and Swift performs the ordinary result conversion after resumption. A nonnull holder distinguishes a successful nullable value from no published result. The old C entries remain, and direct-dispatch generation has matching additive entries. This source addresses general forward object results rather than declaring cached KotlinBase wrappers transferable. It is **uncompiled** and does not repair the reverse callback/job/receiver transfers. The next bounded executable check is the changed provider's K2 Werror compilation described in compiler-request.json; no packaged replacement or positive Native/Swift behavior result exists for it. All twenty-two retained behavior cases remain unexecuted.

### Managed forward result verified with real actor-origin and Native behavior

Provider83205 and printer60631 each passed the one changed K2 tool-generator compilation with Werror. Their logs contain the JDK's four sun.misc.Unsafe::invokeCleaner notices from Kotlin FastJarFileSystem, not Kotlin source diagnostics; do not describe those logs as empty. New forward source is under `runtime-task-ownership-repair/flow-handoff-candidate-v2/forward-managed-result-source-v2`. Native notification failures discard the pending result before propagating failure; successful fallback after cancellation retains its value. The printer emits explicit nonisolated(nonsending) on every async SirFunction, including protocol requirements; this syntax requires Swift6.2 or newer. Accessors/initializers are not changed by that printer branch.

Audit15449 passed exact unchanged parent/reused class bytes, normalized instructions and complete member flags. Frozen source mapping covers eleven added Descriptor members, fourteen new/changed Descriptor bodies and the printer's one changed declaration body. An earlier audit stopped because the *unpackaged* recompiled facade no longer generated the unused access$swiftAsyncCall accessor. The refined guard accepts only that exact removed member/descriptor/flags4121, accepts no additions, resolves all nine used references against the retained parent and preserves the actual parent facade byte-identically. It does not widen instruction normalization. Package47935 passed the exact6263-entry, zero-add/remove audit: two existing classes and KotlinCoroutineSupport.kt/.swift change atop2fce. Candidate SHA256 is **49851dd9c565dec2c0aab8806690946706713f42f5115e491214b78f19ed313e**; retained facade SHA256 remains **64f6eba8e05f61e13fb1f06590175a499d6af5ee9a706df298094a1ab2756fdf**.

The323-input continuation manifest is `continuation-inputs.json`, SHA256 **bd2d5e97b8ce3d2c66cb43370090882ca6bb84a770b5c4cc448d7d6619414bac**; request SHA256 **bf39e690bfa1882fdc5db5e01c5aaadf6283e7f7dff50fc71d12c9315f215cdb**. After fresh serialized ownership and hash checks, the demonstrated sequence is `ruby review-forward-v2.rb`, `ruby package-forward.rb`, `ruby run-fixture.rb forward`, output pinning, then the strict SwiftPM test runner. Reuse the existing artifact-local Gradle home, Native SDK and Konan data paths recorded in run-fixture.rb; fresh project/output/module/SwiftPM caches remain under this candidate root. There is no installed compiler replacement or shared user-cache write. This is a focused fixture, not a product full-KMP build.

Actual embed21290 exited0 after35seconds: Native compilation/static linking, strict Swift package compilation, merge and embed all passed. The runtime keeps the concrete per-request result in Kotlin, sends Bool readiness and materializes the wrapper synchronously after resumption. Untouched emitted resources match the candidate plus the exporter-added newline; generated source contains the typed carrier and ready/take entries, while the old entry remains. The real @MainActor client awaits echoPayload with a retained Payload alias and asserts referential identity and its message/label after resumption.

SwiftPM9926 actually exited0. Both named Swift tests passed once, with zero skips: forwardObjectResultRemainsOnCallerActor and managedNativeResultCleanupIsExact. The second invokes the real compiled Native helper and executes four checks: nullable success versus absent/already consumed result, false payload versus readiness, exact throwing-notification failure with discarded result, and successful fallback after cancellation. These are actual runtime assertions, not declarations or simulated JVM tests.

The first wrapper nevertheless exited1 because its frontend selector also included SwiftPM's two Package.swift **configuration** compilations (harness mode6, generated manifest mode5, neither Werror). Preserve that raw result. Independent classification of the retained log proves that all thirteen actual product/test frontend commands across ExportedKotlinPackages, KotlinRuntimeSupport, KotlinCoroutineSupport, Integration, ForwardTests and ForwardTestsPackageTests used **Swift6 and warnings as errors**. This is direct dependency compilation evidence, not an inference from the test target. The actual log SHA256 is **80151c0fd210d33307040d5fddc3179c634af31457837652ec98085b5edb7cad**. `logs/actual-forward-result-classification.json` identifies the exact two manifest paths and all product commands. No test was rerun, no raw exit rewritten, no diagnostic suppressed and no language mode weakened. A future runner with the exact manifest/product distinction is prepared separately as run-forward-tests-v2.rb.

Successful Gradle console output did not expose frontend commands. The first pin guard stopped on missing evidence; no generated output was edited or rebuild performed. Retained Xcode activity data instead records four actual Swift driver commands, each mode6/Werror. pin-forward-output-v2.rb records those as **driver** evidence; the subsequent verbose SwiftPM run supplies the actual frontend proof above. Keep those two evidence types distinct. All commands are terminal and the local slot was explicitly returned; `forward-local-slot-return.json` records the sessions and limits.

This establishes only the focused forward path. The original twenty-two cases remain byte-preserved and **zero of those twenty-two ran**. Four newly authored actual protocol witness/override/reverse cases under witness-fixture also remain unexecuted; a top-level forward pass does not establish their acceptance. The opaque @isolated(any) reverse callback still lacks a proven compatible transfer for arbitrary cached non-Sendable receiver/payload aliases. Existing Swift6.3.3 probes reject the tested isolation connection and isolated-capture syntax. A separate owner is preparing an explicit actor-owned full-request adapter; that registration contract is different and must not be silently substituted for the existing API. No broad concurrency fix, real Syn/Libc pass, compatible Maven release or toolchain adoption/publication is claimed.

### Upstream source patch provenance and separate reverse registration experiment

The current forward candidate's seven unified source patches are preserved under `automation-artifacts/2026-10-20-syn-isolated-library-validation/runtime-task-ownership-repair/flow-handoff-candidate-v2/forward-managed-result-source-v2/upstream-source-patch/`. Its `provenance.json` SHA256 is **e72bdbaee2620e8ff7b0daec1d72cb8b2a9d2edf50768515478ab0f181e1bc5a**. It maps exact upstream paths, baseline hashes, six pinned Git blobs, current source hashes and patch hashes against JetBrains/kotlin revision890ac1d94fdb80eb85f0eeb5be5e4352df987b2f (2.4.20). The KotlinCoroutineSupport.h baseline is independently pinned from the official exporter resource; its Git blob is not independently verified. These inputs are artifact-local official source files. **There is no full Kotlin source Git checkout in this investigation**, and no upstream submission or official replacement release exists. The isolated JAR must not be published under JetBrains coordinates or silently installed as their compiler.

Separately, Anyhow's explicit MainActor-owned request adapter compiled in session95637 with actual `swiftc -c -swift-version 6 -warnings-as-errors`, producing two objects with no diagnostics. The real imported Payload/KotlinBase client retains aliases; this object check used retained88568 module declarations, whose earlier module emission was not an object or behavior pass. `automation-artifacts/2026-10-20-anyhow-ci-repair/reverse-call-adapter/object-check-result.json` records exact inputs and commands. The adapter requires the complete request and typed take to be formed in the actual lexical owner scope. It does not transparently adapt the existing opaque arbitrary-actor callback. Its six lifecycle cases have not yet been verified at this checkpoint. No runtime, foreign reverse ABI, original22 or public API compatibility result follows from object compilation alone.

### Reverse adapter lifecycle execution and actual generated witness blocker

The separate explicit MainActor request experiment subsequently passed runtime session83710: all six exact Payload lifecycle cases ran once, with zero failures/skips, process0, unchanged inputs, and four actual product frontend commands under Swift6/Werror/macOS15. The cases cover cached object identity, cancellation before dispatch, throwing-ready cleanup, cancellation during suspension, cancellation after readiness/repeated discard, and weak frame release. Its retained `runtime-v4-local-return.json` records log SHA256 **34d9145405bf2087aff08ba8a340e9499295f8145d3bbbdd90de82feb91c1160**. This supersedes the preceding unexecuted-six checkpoint, but remains a caller-side lexical MainActor registration contract; it does not repair an arbitrary opaque operation or generated reverse entry.

Actual generated witness embed37271 then exited1 after32seconds/16 executed tasks. Native compilation and static linking passed. Generated PayloadEcho protocol requirements and PayloadEchoBase open overrides contain explicit nonisolated(nonsending), establishing that the printer emits the intended declarations. Swift compilation fails in two actual reverse C entries at Integration.swift233/251: each reports transfers of __continuation, __exception, __cancellation and the operation closure into withKotlinTask, for eight unique located errors. They materialize cached receiver/callback/job wrappers at an unisolated foreign entry and then transfer them to a task; reference retention alone does not establish actor ownership. None of the four new witness cases ran. No original22 case ran, no output was patched and no successful protocol witness or reverse ABI behavior is inferred. The build stopped before conditional output pinning/testing and its separate allocation was returned.

Evidence is `forward-managed-result-source-v2/witness-local-slot-return.json` and `logs/fixture-witness.log`, SHA256 **a3fe2f586fdb800206bdd53c503497d1bc8bc72ae5b8c48854bcf52f0dd764cb**. Request SHA256 **a2cecf793e54e6f3b81c9fb47519fa28d77e290971f012dee23b47f1c9ca11bd** freezes328 inputs in witness-validation-inputs.json, SHA256 **17dd9d44c6a980ab86998c0961bbfcc1ca921d8db21f44e0de78dbf1ee876bf4**. A preliminary sandbox startup11128 failed before configuration/tasks because Gradle's local lock-coordination socket was denied; its raw log/invocation/result remain separately preserved with .sandbox-startup suffix. The same command then ran with execution permission in37271; that startup correction is not a second generated-candidate attempt.
