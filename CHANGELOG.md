# sys CHANGELOG

We use [Break Versioning][breakver]. The version numbers follow a `<major>.<minor>.<patch>` scheme with the following intent:

| Bump    | Intent                                                     |
| ------- | ---------------------------------------------------------- |
| `major` | Major breaking changes -- check the changelog for details. |
| `minor` | Minor breaking changes -- check the changelog for details. |
| `patch` | No breaking changes, ever!!                                |

`-SNAPSHOT` versions are preview versions for upcoming releases.

[breakver]: https://github.com/ptaoussanis/encore/blob/master/BREAK-VERSIONING.md

## 0.3.2 (2026-02-22)

- **FIX** Fix infinite loops when a system has a nested sub-system.
- Improve error message when a system expects more than its components provide.
- System id is now logged at system start.

## 0.3.1 (2026-02-05)

- **FIX** Fix incorrect context when calling set! on an already started system.
- **FIX** Fix components not being marked as stopped if they didn't have a stop function, causing potential restart issues.

## 0.3.0 (2026-01-28)

- **BREAKING** Remove `sys/init!`, add `sys/set!` which serves a similar purpose, but supports REPL workflows better.
- Sys now stores the states of your systems in its own atom.
- Stopping a component now removes the values it provided from the context.
- Internal refactoring.

## 0.2.1 (2026-01-22)

- Add support for using Malli schemas in `:sys.component/expects` and `:sys.component/provides`.

## 0.2.0 (2026-01-13)

- **BREAKING** Change `sys/get` to take a derefed system (not the atom directly).
- Add `sys/context` function.
- Ignore return value of components that provide nothing (`:sys.component/provides` is `nil`).
- Internal refactoring.

## 0.1.0 (2025-01-06)

- Initial release.

