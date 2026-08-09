# Repository Guidelines

## Project Structure & Module Organization

JLShell is a Maven multi-module Java 21 desktop application. Key modules are:

- `app/`: entry point and manual dependency wiring in `AppContext`.
- `ui/`: JavaFX views, services, themes, fonts, and i18n bundles.
- `core/`: shared session, connection, terminal, credential, and exception APIs.
- `ssh/`, `sftp/`, `terminal/`: SSHJ, SFTP, and JediTerm implementations.
- `data/`: SQLite/JDBI persistence, schema, and credential crypto.
- `plugin-api/`, `plugin-loader/`, `plugins/`: plugin contracts, loader, and examples.
- `api-server/`: local JSON-RPC API server.
- `docs/`: design specs and implementation plans.

Sources use `src/main/java`; tests use `src/test/java`; resources use `src/main/resources`.

## Build, Test, and Development Commands

- `mvn clean package`: build all modules and run tests.
- `mvn test`: run the full JUnit test suite.
- `mvn test -pl plugin-loader -Dtest=CapabilityBusImplTest`: run one module test class.
- `mvn install -DskipTests -q && mvn javafx:run -pl app`: build local artifacts, then start the JavaFX app.
- `./build-dist.sh`: create a platform distributable in `dist/`.
- `./build-dist.sh --all`: build all platform packages; requires env vars such as `JDK21_WIN`.

Use JDK 21 and Maven 3.9+. Runtime scripts include required JVM `--add-opens` flags.

## Coding Style & Naming Conventions

Use 4-space indentation, UTF-8, and Java 21 idioms. Keep packages under `com.jlshell.<module>`. Name classes `PascalCase`, methods and fields `camelCase`, constants `UPPER_SNAKE_CASE`, and tests `*Test`.

The project uses manual dependency injection; add services through `app/src/main/java/com/jlshell/app/AppContext.java`. Keep blocking DB, SSH, and SFTP work off the JavaFX thread. Chinese comments are acceptable when they clarify design intent.

## Testing Guidelines

Tests use JUnit 5 with Mockito and AssertJ where useful. Place tests in the owning module under `src/test/java`. Prefer focused tests for parsing, persistence, plugin RPC, and API behavior. Run `mvn test` before a PR, or at least the affected module with `-pl`.

## Commit & Pull Request Guidelines

Recent history uses Conventional Commit prefixes such as `feat:` and `fix:`. Keep messages imperative, for example `fix: prevent terminal freeze on idle`.

Base daily work and feature branches on `develop`. Merge stable changes with a
GitHub Pull Request from `develop` to `main`; do not push development commits
directly to `main`. Create release tags only from `main`.

Pull requests should include a concise summary, linked issue when applicable, test evidence, and screenshots or recordings for UI changes. Note database, plugin API, or distribution-script impact explicitly.

## Release Notes Requirements

- Before releasing any `X.Y.Z` version, add `.github/release-notes/X.Y.Z.md` in the same branch as the release changes.
- The release note must start with `# JLShell vX.Y.Z`, followed by a one-line `> ` summary, and include at least one `## ` section with a list item.
- Run `.github/scripts/prepare_release_metadata.py` for the target version before triggering the Release workflow. A release must not proceed if this validation fails.
- Release notes, release code, and the Release workflow invocation must all resolve to commits merged into `main`; never publish a version whose release note exists only on `develop` or a feature branch.
- Do not overwrite or reuse a release-note file for a version that has already been published.

## Security & Configuration Tips

Do not commit secrets, local vault files, generated keys, or user data from `~/.jlshell/`. Credential handling must preserve AES-GCM encryption and clear sensitive payloads after use. Plugin storage is namespaced by plugin ID; sensitive plugin values must use `SecureStorage`, which is encrypted in a separate table. Only trusted classpath Program plugins may provide global access policies; external plugin JARs must never become authorization authorities.
