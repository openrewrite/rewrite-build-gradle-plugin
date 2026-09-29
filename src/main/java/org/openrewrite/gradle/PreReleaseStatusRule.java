/*
 * Copyright 2026 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.gradle;

import org.gradle.api.artifacts.CacheableRule;
import org.gradle.api.artifacts.ComponentMetadataContext;
import org.gradle.api.artifacts.ComponentMetadataDetails;
import org.gradle.api.artifacts.ComponentMetadataRule;

import java.util.regex.Pattern;

/**
 * Maven repositories carry no release status, so Gradle takes every version that isn't a snapshot
 * for a release, milestones and release candidates included, and {@code latest.release} selects
 * them. Marked as milestones, they are passed over for the newest actual release.
 */
@CacheableRule
public class PreReleaseStatusRule implements ComponentMetadataRule {
    private static final Pattern PRE_RELEASE = Pattern.compile("(?i).*[.-](m|rc)[.-]?\\d+");

    @Override
    public void execute(ComponentMetadataContext context) {
        ComponentMetadataDetails details = context.getDetails();
        if (PRE_RELEASE.matcher(details.getId().getVersion()).matches()) {
            details.setStatus("milestone");
        }
    }
}
