/*
 * Copyright 2022 the original author or authors.
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

import org.gradle.api.provider.Property;

public interface RewriteJavaExtension {

    /**
     * @return <code>true</code> if the project requires Kotlin for its unit tests.
     * The core OpenRewrite projects should be moving away from Kotlin now that
     * template strings are available as a GA feature in Java. While the team doesn't
     * find there to be anything inherently wrong with Kotlin, using Java in the core
     * projects makes configuration simpler and hopefully reaches a broader audience of
     * JVM developers.
     */
    Property<Boolean> getKotlinTests();

    Property<String> getJacksonVersion();
}
