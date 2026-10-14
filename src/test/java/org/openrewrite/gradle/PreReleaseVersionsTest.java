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

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class PreReleaseVersionsTest {

    @ParameterizedTest
    @ValueSource(strings = {
      "7.0.9",
      "4.3.30.RELEASE",
      "5.6.15.Final",
      "2.2.55",
      "1.0.0.GA",
      "33.4.0-jre",
      "1.9.0-eap"
    })
    void releases(String version) {
        assertThat(PreReleaseVersions.isPreRelease(version)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
      "7.1.0-SNAPSHOT",
      "7.1.0-M2",
      "2.0.0.M1",
      "6.0.0-RC2",
      "5.0.0.RC1",
      "3.0.0-rc-1",
      "1.1.0-rc.2",
      "4.0.0-RC",
      "5.0.0.CR1",
      "1.0.0-beta.2",
      "2.0.0-Beta-1",
      "1.0.0-alpha1",
      "1.0-preview3",
      "21-ea",
      "17-ea+35"
    })
    void preReleases(String version) {
        assertThat(PreReleaseVersions.isPreRelease(version)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"7.+", "+", "latest.release", "[7,8)", "(,8.0]", "]7,8["})
    void dynamicVersions(String version) {
        assertThat(PreReleaseVersions.isDynamic(version)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"7.0.9", "4.0.0-M1", "7.1.0-SNAPSHOT"})
    void fixedVersions(String version) {
        assertThat(PreReleaseVersions.isDynamic(version)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"6.0.0-rc1", "6.0.0-RC1", "6.0.0-Rc-2", "6.0.0-RC"})
    void repositoriesExcludeReleaseCandidatesRegardlessOfCase(String version) {
        assertThat(version).matches(PreReleaseVersions.RELEASE_CANDIDATE_REGEX);
    }
}
