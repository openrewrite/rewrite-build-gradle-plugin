/*
 * Copyright 2023 the original author or authors.
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

import org.junit.jupiter.api.Test;
import org.openrewrite.gradle.RewriteRecipeAuthorAttributionTask.Contributor;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.openrewrite.gradle.RewriteRecipeAuthorAttributionTask.Contributor.distinct;

public class ContributorTest {

    @Test
    void mostRecentUsernameForEmail() {
        assertThat(distinct(List.of(
                contrib("Jonathan Schnéider", "j@gmail.com"),
                contrib("Jonathan Schneider", "j@gmail.com")
        ))).isEqualTo(List.of(contrib("Jonathan Schnéider", "j@gmail.com")));
    }

    @Test
    void notNoReply() {
        assertThat(distinct(List.of(
                contrib("Jonathan Schneider", "5619476+j@users.noreply.github.com"),
                contrib("Jonathan Schneider", "j@gmail.com")
        ))).isEqualTo(List.of(contrib("Jonathan Schneider", "j@gmail.com")));
    }

    private Contributor contrib(String name, String email) {
        return new Contributor(
                name, email, 1
        );
    }
}
