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

import org.jspecify.annotations.Nullable;

import java.util.regex.Pattern;

final class PreReleaseVersions {
    private static final Pattern PRE_RELEASE = Pattern.compile(
            "(?i).*[.-](snapshot|alpha|beta|preview|ea|m[.-]?\\d+|rc|cr)(?![a-z]).*");

    private PreReleaseVersions() {
    }

    static boolean isPreRelease(String version) {
        return PRE_RELEASE.matcher(version).matches();
    }

    static boolean isDynamic(@Nullable String version) {
        return version != null && (version.contains("+") ||
                                   version.startsWith("latest.") ||
                                   version.startsWith("[") ||
                                   version.startsWith("]") ||
                                   version.startsWith("("));
    }
}
