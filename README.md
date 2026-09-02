<p align="center">
  <a href="https://docs.openrewrite.org">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://github.com/openrewrite/rewrite/raw/main/doc/logo-oss-dark.svg">
      <source media="(prefers-color-scheme: light)" srcset="https://github.com/openrewrite/rewrite/raw/main/doc/logo-oss-light.svg">
      <img alt="OpenRewrite Logo" src="https://github.com/openrewrite/rewrite/raw/main/doc/logo-oss-light.svg" width='600px'>
    </picture>
  </a>
</p>

<div align="center">
  <h1>rewrite-build-gradle-plugin</h1>
</div>

<div align="center">

<!-- Keep the gap above this line, otherwise they won't render correctly! -->
[![ci](https://github.com/openrewrite/rewrite-build-gradle-plugin/actions/workflows/ci.yml/badge.svg)](https://github.com/openrewrite/rewrite-build-gradle-plugin/actions/workflows/ci.yml)
[![Gradle Plugin Portal](https://img.shields.io/maven-metadata/v/https/plugins.gradle.org/m2/org.openrewrite/rewrite-build-gradle-plugin/maven-metadata.xml.svg?label=gradlePluginPortal)](https://plugins.gradle.org/plugin/org.openrewrite.build.root)
[![Apache 2.0](https://img.shields.io/github/license/openrewrite/rewrite-build-gradle-plugin.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![Contributing Guide](https://img.shields.io/badge/Contributing-Guide-informational)](https://github.com/openrewrite/.github/blob/main/CONTRIBUTING.md)
</div>

## What is this?

This project provides a Gradle plugin that provides common build opinions to repositories in the openrewrite GitHub organization.organization's source code.

## Code Genome Project artifacts

`org.openrewrite.build.recipe-repositories` (applied by the `recipe-library` and `language-library` plugins) adds
https://artifacts.codegenomeproject.org/maven as a dependency repository for the `org.openrewrite` and `io.moderne`
groups, and excludes those same groups from Maven Central, to avoid downloading older versions.

Set credentials as Gradle properties in `~/.gradle/gradle.properties` — never in a file under source control:

```properties
codegenomeUsername=you@example.com
codegenomePassword=cgp_...
```

In CI, expose the same values as the `ORG_GRADLE_PROJECT_codegenomeUsername` and `ORG_GRADLE_PROJECT_codegenomePassword`
environment variables.

Ordinary builds still fall back to Maven Central when credentials are absent, so fork pull requests keep working, but
a release build (`-Preleasing`) fails at configuration time rather than releasing against whatever versions Maven
Central happens to carry. This project's own build draws `org.openrewrite` artifacts from the Code Genome Project, and
fails the same way when releasing without credentials.

### One-time local setup

`recipe-repositories` covers a project's own dependencies. It cannot cover the plugins' dependencies: a plugin's
classpath is resolved from the repositories *settings* declares, before any project exists, and those default to the
Gradle Plugin Portal, which proxies Maven Central. So a build that has never been told about the Code Genome Project at
the settings level now fails on the `org.openrewrite` artifacts these plugins are themselves built on, before any of
them can run:

```
> Could not find org.openrewrite:rewrite-core:8.91.4.
    Searched in the following locations:
      - https://plugins.gradle.org/m2/org/openrewrite/rewrite-core/8.91.4/rewrite-core-8.91.4.pom
```

CI gets this from `openrewrite/gh-automation`'s setup action. Locally, install the same init script once — it reads the
credentials you just put in `~/.gradle/gradle.properties`, and applies to every Gradle build on the machine:

```bash
mkdir -p ~/.gradle/init.d
curl -fsSL -o ~/.gradle/init.d/cgp-resolve.init.gradle \
  https://raw.githubusercontent.com/openrewrite/gh-automation/main/.github/actions/setup/cgp-resolve.init.gradle
```

It stays inert until those credentials are set, and leaves a `codegenome` repository a build declares for itself alone.

## Publishing

Recipe and language libraries publish only to the Code Genome Project. `org.openrewrite.build.publish-cgp` (applied by
the `recipe-library` and `language-library` plugins) adds the CGP bucket as a publishing repository, and stays inert
unless `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY` are set, so only CI publishes.

`org.openrewrite.build.root` no longer stages artifacts to Maven Central. Modules that still have to reach Central
during the transition can apply the deprecated `org.openrewrite.build.publish-maven-central` plugin, which restores the
Sonatype repository and its `closeAndReleaseSonatypeStagingRepository` task.
