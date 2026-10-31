# crap4s coding standards

Scope: all Scala code in this repository. Scala 3 only, Scala CLI build.
Each rule has one reason. Source tags:
- **[A8]**: from "Scala Architecture and Design Patterns - Part 8", Jonathan A., LinkedIn, 2026-10-17
  (https://www.linkedin.com/pulse/scala-architecture-design-patterns-part-8-jonathan-altamura-rxsxc/).
- **[S3]**: general Scala 3 practice.

## 1. Design

1. **Keep each concern in its own object or file** (TASTy loading, coverage parsing, complexity, mapping, CRAP formula, report output, CLI). [A8] Reason: a change then touches the smallest part of the code.
2. **Compose the pipeline from small steps** in `Main`: read, analyze, map, score, write. [A8] Reason: each step is easy to read and to test alone.
3. **Model the domain with case classes and enums** (`Unit`, `Coverage`, `Score`), not with raw `String`, `Int` or tuples. [A8] Reason: names in code show the domain, and the compiler finds mix-ups.
4. **Use a trait only where there are two or more real implementations, or a test double is necessary** (for example, a coverage source). [A8] Reason: abstraction has a cost; do not add it without a need.
5. **Inject dependencies through constructor or function parameters.** Do not use a DI framework. [A8] Reason: plain parameters are enough and keep tests simple.
6. **Keep side effects at the edges.** Only capability implementations at the edge read files, print, read the environment, and exit. Core and pipeline are pure functions (see rule 21). [A8] Reason: pure code is predictable and needs no files to test.
7. **Write a unit test for each business rule** (each complexity rule, each formula case) without file system access where possible. [A8] Reason: small tests find the exact failure.

## 2. Scala 3 code

8. **Use Scala 3 syntax**: `enum`, `extension`, `given`/`using`, top-level definitions, `@main`. [S3] Reason: one style, no Scala 2 forms.
9. **Use braceless (indentation) syntax** and `end` markers only for definitions longer than 20 lines. [S3] Reason: same style everywhere; scalafmt enforces it.
10. **Give an explicit result type to every public or top-level definition.** [S3] Reason: stable API and faster, clearer compiler errors.
11. **Do not use `null`, `return`, `var` in core logic, or `asInstanceOf`.** If a Java API can return `null`, wrap it in `Option(...)` at once. [S3] Reason: these break the pure, type-safe core.
12. **Report errors as values** (`Either[Error, A]` with an `enum Error`). No exception may escape (see rule 20). [S3] Reason: errors stay visible in types.
13. **Use immutable collections and `Vector`/`List`.** [S3] Reason: no shared mutable state.
14. **Keep the CRAP score of every unit below 4**: small methods, few branches, high coverage. [crap4s project rule] Reason: the tool must pass its own gate.
15. **Write modules as capabilities (opaque function types) and use them with `?=>`.** Wire them only in `App.scala`. Put the flow only in `Pipeline.scala`. Only `Main` and the input adapters touch files or the console. [S3] Reason: the flow has no wiring and no side effects, so a test can give it fake modules.

    ```scala
    // Capabilities.scala: a module is an opaque function type with the function type as upper bound
    opaque type Units <: () => Either[Error, List[CodeUnit]] = () => Either[Error, List[CodeUnit]]
    object Units:
      def apply(f: () => Either[Error, List[CodeUnit]]): Units = f
    opaque type Stage[A, B] <: (A => Either[Error, B]) = A => Either[Error, B]
    object Stage:
      def apply[A, B](f: A => Either[Error, B]): Stage[A, B] = f
      def total[A, B](f: A => B): Stage[A, B] = f.andThen(Right(_))
    type Mapping = Stage[Inputs, Mapped]
    def mapping(using m: Mapping): Mapping = m

    // Pipeline.scala: the flow is one flatMap chain and asks for the modules with ?=>
    def pipeline: (Units, Coverage, Mapping, Scoring, Report) ?=> Either[Error, Result] =
      loadInputs
        .flatMap(mapping)
        .flatMap(scoring)
        .flatMap(report)

    // App.scala: the only place that picks the implementations
    given Units = tastyUnitsAt(config.tastyDirs, entries, baseDir)
    given Report = reportFor(config.format, config.threshold, config.all, color)
    pipeline
    ```

16. **Use top-level defs in place of helper objects (`CapabilityObject`).** An `object` that holds only defs is not allowed. The companion of an opaque type and `App` are allowed. [S3] Reason: Scala 3 has top-level definitions, so the object adds only a name.

    ```scala
    // Good
    def parseLine(line: String): Option[Entry] = ...

    // Bad
    object LineParser:
      def parseLine(line: String): Option[Entry] = ...
    ```

17. **Inline a pass-through def (`PassThrough`).** Do not write a def whose body only calls another function with its own parameters or constants. [S3] Reason: the def adds a name and a call, but no logic. Use `@nowarn("crap4s:passthrough")` only when the def is necessary.

    ```scala
    // Good
    names.map(_.trim)

    // Bad
    def trimAll(names: List[String]): List[String] = names.map(_.trim)
    ```

18. **Do not extend a capability (`NoExtendsCapability`).** No class, object or enum case extends a capability. Give a function value to the capability constructor. [S3] Reason: a capability is a function type, so a function is its only implementation.

    ```scala
    // Good
    val units: Units = Units(() => Right(Nil))

    // Bad
    object NoUnits extends Units
    ```

19. **Give a capability only in `App.scala` (`GivenOnlyInApp`).** A `given` of a capability is only in `App.scala`. Test sources are allowed. [S3] Reason: one place picks the implementations.

    ```scala
    // Good: in App.scala
    given Units = tastyUnitsAt(config.tastyDirs, entries, baseDir)

    // Bad: in Pipeline.scala
    given Units = () => Right(Nil)
    ```

20. **No exception may escape (`NoThrow`, `NoUncheckedTry`, `NoUnsafeCall`).** Every failure is a typed `Error` case in an `Either`. Do not write `throw`. Do not write `try` outside the allowed files. Wrap each call that can throw with the helpers in `Eithers.scala` or `Effects.scala`. Do not use `.get`, `.head`, `.last`, `.tail`, `.reduce`, `.toInt`, `.toDouble`, `require`, `assert` or `???`. Use `Option`, `headOption`, `toIntOption` and pattern matching. [S3] Reason: a failure is then a value, and the type shows it.

    ```scala
    // Good
    Eithers.catching(Error.Unreadable(path))(Files.readString(path))
    text.toIntOption.toRight(Error.BadNumber(text))

    // Bad
    Files.readString(path)
    text.toInt
    ```

21. **Do side effects only at the edge (`SideEffectsAtEdge`).** Exit, print, console, environment, `java.io`, `Files`, `FileSystems`, `var` and `scala.collection.mutable` are allowed only in the edge files (see section 4.2). Core and pipeline are pure. The edge gives these effects as capabilities (`Terminal`, `Env`, and others). [S3] Reason: pure code needs no files and no console to test.

22. **Keep one last-resort guard, in `Main` only.** `Main` catches an exception that no one expected. It prints one line, it prints no stack trace, and it exits with code 2. No other code has this guard. [S3] Reason: the exit code is the same for every failure, and the user sees no stack trace.

23. **Do not hide a violation.** Do not add a file to the allow lists in `.scalafix.conf` to pass the `lint` job. Change the code. Add a file to a list only when it is a real edge or a real wrapper. [S3] Reason: a long allow list removes the protection of the rules.

24. **Make stages with `Stage.total` or `Stage(...)`.** Do not write a def that only wraps a value in `Right`. Use `Stage.total` for a function that cannot fail. Use `Stage(...)` for a function that can fail. [S3] Reason: the smart constructor adds the `Right`, so a wrapper def is not necessary.

    ```scala
    // Good
    given Mapping = Stage.total(in => Mapped(mapCoverage(in.units, in.statements)))

    // Bad
    private def mapInputs(in: Inputs): Either[Error, Mapped] =
      Right(Mapped(mapCoverage(in.units, in.statements)))
    ```

## 3. Formatting (scalafmt)

Proposed `.scalafmt.conf` in the repository root (scalafmt 3.11.5 is the newest release on Maven Central on 2026-10-20):

```hocon
version = "3.11.5"
runner.dialect = scala3
maxColumn = 100
align.preset = none
rewrite.scala3.convertToNewSyntax = true
rewrite.scala3.removeOptionalBraces = true
rewrite.scala3.insertEndMarkerMinLines = 20
rewrite.rules = [RedundantBraces, RedundantParens, SortModifiers, Imports]
rewrite.imports.sort = scalastyle
newlines.source = keep
docstrings.style = Asterisk
fileOverride { "glob:**/*.sc" { runner.dialect = scala3 } }
```

Check: `scala-cli fmt --check .` Fix: `scala-cli fmt .`

## 4. Static checks

### 4.1 Compiler options (Scala 3.10)

Put these in `project.scala`:

```scala
//> using options -deprecation -feature -unchecked -Werror
//> using options -Wunused:all -Wvalue-discard -Wnonunit-statement -Wsafe-init
//> using options -Wconf:any:verbose
```

Reasons: `-Werror` makes each warning a CI failure; `-Wunused:all` finds dead code and is necessary for the Scalafix `RemoveUnused` rule; `-Wvalue-discard` and `-Wnonunit-statement` find lost results; `-Wsafe-init` finds bad initialization order.
Optional, later: `-Yexplicit-nulls` (strict, but tasty-query and Java APIs make it noisy).

### 4.2 Scalafix (through Scala CLI)

`scala-cli --power fix --check .` runs Scalafix (default version 0.14.9 in Scala CLI 1.18.0) and fails when a rewrite is necessary. Proposed `.scalafix.conf`:

```hocon
rules = [
  RemoveUnused,
  DisableSyntax,
  LeakingImplicitClassVal,
  NoValInForComprehension,
  RedundantSyntax,
  OrganizeImports
]
DisableSyntax {
  noNulls = true
  noReturns = true
  noAsInstanceOf = true
  noIsInstanceOf = true
  noXml = true
  noFinalize = true
}
OrganizeImports {
  targetDialect = Scala3
  removeUnused = true
}
```

Note: `RemoveUnused` and `OrganizeImports.removeUnused` need `-Wunused:all`.

Rules 16 to 19 describe the local rules. The local rule `PassThrough` (`scalafix/PassThrough.scala`, enabled in `.scalafix.conf`) flags a `def` whose body only calls another function with its own parameters or constants. Inline such a def. The rule skips overrides, defs in a type with parents, a companion `apply` and defs with `@nowarn("crap4s:passthrough")`. The `lint` job fails on 1 or more findings. Scalafix local rules are Scala 2.13 code, so `scalafix/` is its own Scala CLI project with its own tests (`scala-cli test scalafix`). The main build excludes it, and scalafmt skips it.

Three more local rules guard the capability style (`NoExtendsCapability`, `CapabilityObject`, `GivenOnlyInApp`). They are in `scalafix/` and use the names in `CapabilityStyle` in `.scalafix.conf`.

Four more local rules guard errors and side effects: `NoThrow`, `NoUncheckedTry`, `NoUnsafeCall` and `SideEffectsAtEdge`. They check only files under `src/`. `NoThrow` flags `throw`. `NoUncheckedTry` flags `try` outside the files in `UncheckedTryFiles`, and flags a `catch` that does not return `Left(...)` outside the files in `UncheckedTryGuards`. `NoUnsafeCall` flags `.get` without arguments, `.head`, `.last`, `.tail`, `.reduce`, `.toInt`, `.toDouble`, `require`, `assert` and `???`. `SideEffectsAtEdge` flags exit, print, console, environment, `java.io`, `Files`, `FileSystems`, `var` and `scala.collection.mutable` outside the path parts in `SideEffectsAllow`. All lists are in `.scalafix.conf`.

The allowed edge files in `.scalafix.conf` are:
- `UncheckedTryFiles`: `crap4s/core/Eithers.scala`, `crap4s/Effects.scala`. These files may use `try`.
- `UncheckedTryGuards`: `crap4s/Effects.scala`. A `catch` outside this file must return `Left(...)`.
- `SideEffectsAllow`: `crap4s/edge/`, `crap4s/input/`, `crap4s/Main.scala`. Only these paths may do IO.

When you add a capability, add its name to `CapabilityStyle`.

### 4.3 WartRemover (optional)

WartRemover has a build for Scala 3.10.0 (`org.wartremover:wartremover_3.10.0:3.6.3` on Maven Central, checked 2026-10-20). Use it only if Scalafix `DisableSyntax` is not enough:

```scala
//> using plugin org.wartremover:::wartremover:3.6.3
//> using options -P:wartremover:traverser:org.wartremover.warts.Unsafe
```

Risk: the plugin version must match the exact Scala version, so each Scala update can block the build until a new release.

## 5. CI gate

On each pull request, GitHub Actions runs: format check, compile with `-Werror`, Scalafix check, tests, and the crap4s self-check (CRAP < 4 for every unit). The Sandcastle loop merges a PR only when all checks pass.
