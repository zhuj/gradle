/*
 * Copyright 2012 the original author or authors.
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

package org.gradle.api.publish.internal;

import com.google.common.collect.Lists;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.PublishException;
import org.gradle.api.internal.GradleInternal;
import org.gradle.api.internal.artifacts.ModuleVersionPublishResult;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.publish.internal.PublishBuildOperationType.PublicationType;
import org.gradle.internal.operations.BuildOperationContext;
import org.gradle.internal.operations.BuildOperationDescriptor;
import org.gradle.internal.operations.RunnableBuildOperation;

import java.io.File;
import java.util.List;

public abstract class PublishOperation implements RunnableBuildOperation {

    private final ProjectInternal project;
    private final PublicationInternal<?> publication;
    private final PublicationType type;
    private final String repository;

    protected PublishOperation(Project project, PublicationInternal<?> publication, PublicationType type, String repository) {
        this.project = (ProjectInternal) project;
        this.publication = publication;
        this.type = type;
        this.repository = repository;
    }

    @Override
    public BuildOperationDescriptor.Builder description() {
        GradleInternal gradle = project.getGradle();
        return BuildOperationDescriptor.displayName(gradle.contextualize("Publishing"))
            .details(new PublishBuildOperationType.Details() {
                @Override
                public String getProjectPath() {
                    return project.getProjectPath().getPath();
                }

                @Override
                public String getBuildPath() {
                    return project.getGradle().getIdentityPath().getPath();
                }

                @Override
                public String getName() {
                    return publication.getName();
                }

                @Override
                public PublicationType getType() {
                    return type;
                }

                @Override
                public String getRepository() {
                    return repository;
                }
            });
    }

    protected abstract ModuleVersionPublishResult publish() throws Exception;

    @Override
    public void run(BuildOperationContext context) {
        try {
            final ModuleVersionPublishResult result = publish();
            final ModuleVersionIdentifier coordinates = publication.getCoordinates();
            context.setResult(new PublishBuildOperationType.Result() {
                @Override
                public String getGroup() {
                    return coordinates.getGroup();
                }

                @Override
                public String getName() {
                    return coordinates.getName();
                }

                @Override
                public String getVersion() {
                    return coordinates.getVersion();
                }

                @Override
                public List<String> getArtifacts() {
                    List<String> uploads = Lists.newArrayList();
                    for (File publicationArtifact : PublishOperation.this.getArtifacts()) {
                        uploads.add(result.getPublishedLocation(publicationArtifact).toString());
                    }
                    return uploads;
                }
            });
        } catch (Exception e) {
            throw new PublishException(String.format("Failed to publish publication '%s' to repository '%s'", publication.getName(), repository), e);
        }
    }

    protected abstract List<File> getArtifacts();
}
