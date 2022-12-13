package org.openrewrite.gradle;

import org.gradle.api.provider.Property;

public interface RewriteRecipeLibraryExtension {
    Property<String> getRewriteVersion();
}
