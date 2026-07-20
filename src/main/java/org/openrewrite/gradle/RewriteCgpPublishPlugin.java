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

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.credentials.AwsCredentials;
import org.gradle.api.publish.PublishingExtension;

import java.net.URI;

/**
 * Registers CGP's S3 bucket as a Maven publishing repository so {@code publish} uploads there too.
 * Inert unless AWS credentials are present, so only CI (which exports them) publishes.
 */
public class RewriteCgpPublishPlugin implements Plugin<Project> {

    // Region-qualified host, else Gradle's S3 transport defaults to us-east-1 (the bucket is us-west-2).
    private static final String CGP_URL = "s3://codegenome-artifacts.s3.us-west-2.amazonaws.com/maven";

    @Override
    public void apply(Project project) {
        String accessKey = System.getenv("AWS_ACCESS_KEY_ID");
        if (accessKey == null || accessKey.isEmpty()) {
            return;
        }
        project.getExtensions().configure(PublishingExtension.class, publishing ->
                publishing.getRepositories().maven(repo -> {
                    repo.setName("cgp");
                    repo.setUrl(URI.create(CGP_URL));
                    repo.credentials(AwsCredentials.class, creds -> {
                        creds.setAccessKey(System.getenv("AWS_ACCESS_KEY_ID"));
                        creds.setSecretKey(System.getenv("AWS_SECRET_ACCESS_KEY"));
                        String sessionToken = System.getenv("AWS_SESSION_TOKEN");
                        if (sessionToken != null && !sessionToken.isEmpty()) {
                            creds.setSessionToken(sessionToken);
                        }
                    });
                }));
    }
}
