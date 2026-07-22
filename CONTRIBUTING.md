# Contributing

## Development

This project uses `.cljc` (portable Clojure/ClojureScript) and the `clojure` CLI for testing and demo execution.

### Prerequisites

- Clojure CLI (`clojure --version`)
- Java 17+
- This repo's `deps.edn` pins `io.github.kotoba-lang/langgraph` and
  `io.github.kotoba-lang/langchain-store` via in-monorepo `:local/root`
  paths -- a standalone fork must override those coordinates (see
  `deps.edn`'s own comment)

### Development Workflow

1. Edit files in `src/clinicops/`
2. Run tests: `clojure -M:dev:test`
3. Run the linter: `clojure -M:lint`
4. Run the demo: `clojure -M:dev:run`
5. Commit and push to a feature branch
6. Submit a pull request

### Code Style

- Use `.cljc` for portable code
- Keep Governor checks immutable and deterministic
- Document all operations in the allowlist
- Write tests before implementing features -- every assertion must be a
  real `clojure.test/is` that can genuinely fail, not a hand-rolled
  print-and-continue harness

## Testing

All changes must pass:
- Unit tests in `test/clinicops/`
- The demo scenarios in `clinicops.sim/-main` (`clojure -M:dev:run`)

## Governance

This actor operates under three HARD, un-overridable Governor checks. See GOVERNANCE.md.
