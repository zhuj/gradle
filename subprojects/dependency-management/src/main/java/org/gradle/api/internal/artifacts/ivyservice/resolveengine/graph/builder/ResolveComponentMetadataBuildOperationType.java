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

package org.gradle.api.internal.artifacts.ivyservice.resolveengine.graph.builder;

import org.gradle.api.artifacts.result.ResolvedComponentResult;
import org.gradle.api.internal.artifacts.DownloadArtifactBuildOperationType;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.ModuleComponentRepositoryIdentifier;
import org.gradle.internal.operations.BuildOperationType;
import org.gradle.internal.scan.UsedByScanPlugin;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Resolution of a configuration's dependencies.
 *
 * @since 4.4
 */
public final class ResolveComponentMetadataBuildOperationType implements BuildOperationType<ResolveComponentMetadataBuildOperationType.Details, ResolveComponentMetadataBuildOperationType.Result> {

    @UsedByScanPlugin
    public interface Details {

        String getIdentifier();

    }

    @UsedByScanPlugin
    public interface Result {

        String repositoryId();

    }
}
