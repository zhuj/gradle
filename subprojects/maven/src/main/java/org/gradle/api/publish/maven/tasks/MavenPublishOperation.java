/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.publish.maven.tasks;

import org.gradle.api.Project;
import org.gradle.api.Transformer;
import org.gradle.api.publish.internal.PublishBuildOperationType;
import org.gradle.api.publish.internal.PublishOperation;
import org.gradle.api.publish.maven.MavenArtifact;
import org.gradle.api.publish.maven.internal.publication.MavenPublicationInternal;
import org.gradle.api.publish.maven.internal.publisher.MavenNormalizedPublication;
import org.gradle.util.CollectionUtils;

import java.util.List;

abstract class MavenPublishOperation extends PublishOperation {

    private final MavenPublicationInternal publication;
    protected final MavenNormalizedPublication normalizedPublication;

    MavenPublishOperation(Project project, MavenPublicationInternal publication, String repositoryName) {
        super(project, publication, PublishBuildOperationType.PublicationType.MAVEN, repositoryName);
        this.publication = publication;
        this.normalizedPublication = publication.asNormalisedPublication();
    }

    @Override
    protected List<String> getArtifacts() {
        return CollectionUtils.collect((Iterable<MavenArtifact>) normalizedPublication.getAllArtifacts(), new Transformer<String, MavenArtifact>() {
            @Override
            public String transform(MavenArtifact mavenArtifact) {
                return publication.getArtifactFileName(mavenArtifact.getClassifier(), mavenArtifact.getExtension());
            }
        });
    }
}
