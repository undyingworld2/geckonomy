# Task M0 — Foundation

**Goal:** Build tooling, dependencies, and the empty layered skeleton so later milestones drop into
place. No business logic.

**Read first:** `../ARCHITECTURE.md`, `../CODING_STANDARDS.md`, plan §9.

## Do
1. **Dependencies** (`pom.xml`):
   - `org.jetbrains.kotlinx:kotlinx-coroutines-core`
   - `com.zaxxer:HikariCP`
   - `org.xerial:sqlite-jdbc`, `org.mariadb.jdbc:mariadb-java-client`
   - Test: `org.junit.jupiter:junit-jupiter`, `io.mockk:mockk`,
     `com.github.seeu.dev` MockBukkit (`org.mockbukkit.mockbukkit:mockbukkit-v1.21` or current),
     `org.testcontainers:mariadb` + `org.testcontainers:junit-jupiter`.
   - Decide runtime delivery: **Paper PluginLoader + MavenLibraryResolver** (preferred, slim jar) vs
     `maven-shade-plugin` relocation (already present). Document the choice in the pom.
   - Configure `maven-surefire-plugin` for JUnit 5; ensure kotlin test compilation.
2. **Package skeleton** under `src/main/kotlin/com/the1mason/geckonomy/`:
   `domain/{model,policy,port}`, `application/{service,usecase,result}`,
   `infrastructure/{persistence,config,i18n,vault,bukkit}`. Add a `package-info`-style KDoc or a short
   `README` note per layer describing its rule.
3. **Composition root:** flesh out `Geckonomy.kt` with `onEnable`/`onDisable` that currently only log and
   hold placeholders for the wiring order from `ARCHITECTURE.md §7`.
4. **Logging:** use the plugin `Logger`; add a thin helper if useful. No custom logging framework.
5. If using PluginLoader, add the loader class + `loader:` entry to `paper-plugin.yml`.

## Acceptance
- `mvn clean package` succeeds; produces a jar.
- Plugin enables and disables on a Paper test server with only log output.
- A trivial JUnit 5 test runs in `mvn test`.
- No domain/application code depends on Bukkit/JDBC yet.

## Out of scope
Any real economy logic, DB access, commands.
