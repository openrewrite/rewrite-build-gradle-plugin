# 1. Recipe Marketplace CSV Gradle Tasks

Date: 2025-10-26

## Status

Accepted

## Context

Recipe library projects need a standardized way to generate and maintain `recipes.csv` files that describe their recipe marketplace. The Recipe Marketplace CSV format (defined in [rewrite ADR-006](https://github.com/openrewrite/rewrite/blob/main/doc/adr/0006-recipe-marketplace-csv-format.md)) provides a human-readable format for documenting:

- Recipe names, display names, and descriptions
- Recipe categorization in hierarchies
- Recipe options with their metadata
- Optional bundle information (ecosystem, package name, version)

However, maintaining these CSV files manually is error-prone and can easily become out of sync with the actual recipes in the codebase. We need Gradle tasks that:

1. **Automatically generate** `recipes.csv` from recipe JARs
2. **Merge** generated data with existing CSV customizations
3. **Validate** CSV content for formatting rules (display names, descriptions)
4. **Validate** CSV completeness to ensure synchronization between CSV and JAR

These tasks should be available to any project using the `RewriteRecipeLibraryPlugin` or `RewriteRecipeLibraryBasePlugin`.

## Decision

We will create three new Gradle tasks as part of the rewrite-build-gradle-plugin:

### 1. `recipeCsvGenerate` Task

**Purpose**: Generates `recipes.csv` from the recipe JAR and merges with existing CSV if present.

**Implementation**:
- Task class: `RecipeMarketplaceCsvGenerateTask`
- Automatically looks up GAV (group, artifact, version) from the `nebula` Maven publication
- Automatically looks up the recipe JAR from the `jar` task output
- Automatically supplies the `main` source set's runtime classpath to the generator
- Uses `MavenRecipeMarketplaceGenerator` to scan the recipe JAR and extract recipe metadata
- Reads existing `recipes.csv` from `src/main/resources/META-INF/rewrite/recipes.csv` if present
- Merges generated marketplace into existing marketplace using `RecipeMarketplace.merge()`
  - Merge semantics: recipes with same name in same category are replaced by generated versions
  - Manual customizations in other categories or for other recipes are preserved
- Writes merged result back to `src/main/resources/META-INF/rewrite/recipes.csv`
- Depends on the `jar` task to ensure recipe JAR is built first

**Configuration**:
The task requires no configuration - it automatically discovers all necessary information:
- GAV from the `nebula` publication
- JAR location from the `jar` task
- Runtime classpath from the `main` source set
- Output location defaults to `src/main/resources/META-INF/rewrite/recipes.csv` (customizable if needed)

### 2. `recipeCsvValidateContent` Task

**Purpose**: Validates the formatting and content quality of `recipes.csv`.

**Implementation**:
- Task class: `RecipeMarketplaceCsvValidateContentTask`
- Uses `RecipeMarketplaceContentValidator` from rewrite-core
- Validates:
  - Display names start with uppercase letters
  - Display names do not end with periods
  - Descriptions end with periods (when non-empty)
- Fails the build with detailed error messages if validation fails
- **Does not fail** if `recipes.csv` does not exist (gracefully skips)

### 3. `recipeCsvValidateCompleteness` Task

**Purpose**: Validates that `recipes.csv` is synchronized with the recipe JAR.

**Implementation**:
- Task class: `RecipeMarketplaceCsvValidateCompletenessTask`
- Automatically looks up the recipe JAR from the `jar` task output
- Automatically supplies the `main` source set's runtime classpath to load the recipe environment
- Uses `RecipeMarketplaceCompletenessValidator` from rewrite-core
- Validates:
  - Every recipe in CSV exists in the JAR (detects "phantom recipes")
  - Every recipe in JAR is listed in the CSV (detects "missing recipes")
- Loads the recipe JAR environment using `ResolvedMavenRecipeBundle`
- Fails the build with detailed error messages if validation fails
- **Does not fail** if `recipes.csv` does not exist (gracefully skips)
- Depends on the `jar` task to ensure recipe JAR is built first

### 4. `recipeCsvValidate` Composite Task

**Purpose**: Convenience task that runs both validation tasks.

**Implementation**:
- Depends on both `recipeCsvValidateContent` and `recipeCsvValidateCompleteness`
- Provides single command to validate all aspects of `recipes.csv`

## Consequences

### Positive

1. **Automated generation**: Recipe library authors can generate accurate CSV files from their JARs without manual effort
2. **Zero configuration**: Tasks automatically discover GAV from nebula publication, JAR from jar task, and classpath from source sets
3. **Merge preservation**: Existing customizations in CSV are preserved when regenerating
4. **Quality assurance**: Content validation ensures consistent formatting across all recipe libraries
5. **Synchronization**: Completeness validation prevents CSV from becoming stale as recipes evolve
6. **Non-breaking**: Tasks gracefully skip validation if `recipes.csv` doesn't exist, allowing gradual adoption
7. **Composability**: Separate tasks for generation and validation allow flexible CI/CD integration
8. **Reusable infrastructure**: Tasks leverage existing validators and generators from rewrite-core and rewrite-maven
9. **Runtime classpath awareness**: Generator uses actual runtime dependencies to properly load and analyze recipes

### Negative

1. **Build dependency**: Tasks require recipe JAR to be built first, adding time to the build
2. **Merge conflicts**: Automatic merge may not handle all edge cases (e.g., recipe renamed between runs)
3. **Limited customization**: Generated output uses automatic categorization based on package names, which may not always match desired organization

### Trade-offs

- **Merge strategy**: We chose to merge generated data into existing CSV (rather than vice versa) to ensure that generated data takes precedence for recipes that exist in the JAR. This means manual edits to generated recipe metadata will be overwritten on regeneration. Alternative: provide configuration to control merge direction.

- **Graceful skipping**: We chose to make validation tasks skip (rather than fail) when `recipes.csv` doesn't exist. This allows projects to incrementally adopt the CSV format. Alternative: fail hard to enforce CSV presence.

- **Task registration**: Tasks are registered in `RewriteRecipeLibraryBasePlugin` to make them available to all recipe library projects, including those using custom plugin configurations.

- **Nebula publication coupling**: Tasks depend on the `nebula` Maven publication existing to extract GAV. This is appropriate since all OpenRewrite recipe libraries use the nebula publishing plugin. Projects not using nebula would need a different approach.

- **Runtime classpath**: We use the `main` source set's runtime classpath. For projects with complex classpath configurations, this may not capture all necessary dependencies, though it works for typical recipe library layouts.

## Usage Examples

### Generate CSV from scratch

```bash
./gradlew recipeCsvGenerate
```

### Regenerate after adding new recipes

```bash
./gradlew recipeCsvGenerate
```

Existing customizations are preserved, new recipes are added.

### Validate before publishing

```bash
./gradlew recipeCsvValidate
```

### CI/CD integration

```yaml
# GitHub Actions example
- name: Validate Recipe CSV
  run: ./gradlew recipeCsvValidate
```

Add to `check` task dependency for automatic validation:

```kotlin
// build.gradle.kts
tasks.named("check") {
    dependsOn("recipeCsvValidate")
}
```

## Future Considerations

1. **Customization hooks**: Allow projects to customize category assignment or other generation aspects
2. **Incremental generation**: Track which recipes are manually curated vs auto-generated to preserve manual edits
3. **Multi-module support**: Generate combined CSV for multi-module recipe projects
4. **IDE integration**: Provide quick-fix actions or code generation support in IDEs
5. **Format options**: Support additional output formats (JSON, YAML) alongside CSV

## References

- [rewrite ADR-006: Recipe Marketplace CSV Format](https://github.com/openrewrite/rewrite/blob/main/doc/adr/0006-recipe-marketplace-csv-format.md)
- `MavenRecipeMarketplaceGenerator` in rewrite-maven
- `RecipeMarketplaceContentValidator` in rewrite-core
- `RecipeMarketplaceCompletenessValidator` in rewrite-core
