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

package org.gradle.api.internal.changedetection.state;

import org.gradle.internal.hash.HashCode;

/**
 * A {@link ResourceSnapshotterCacheService} that delegates to the global service for immutable files
 * and uses the local service for all other files. This ensures optimal cache utilization.
 */
public class SplitResourceSnapshotterCacheService implements ResourceSnapshotterCacheService {
    private final ResourceSnapshotterCacheService globalCache;
    private final ResourceSnapshotterCacheService localCache;
    private final WellKnownFileLocations wellKnownFileLocations;

    public SplitResourceSnapshotterCacheService(ResourceSnapshotterCacheService globalCache, ResourceSnapshotterCacheService localCache, WellKnownFileLocations wellKnownFileLocations) {
        this.globalCache = globalCache;
        this.localCache = localCache;
        this.wellKnownFileLocations = wellKnownFileLocations;
    }

    @Override
    public HashCode hashFile(String absolutePath, Iterable<String> relativePath, FileContentSnapshot content, RegularFileHasher hasher, HashCode configurationHash) {
        if (wellKnownFileLocations.isImmutable(absolutePath)) {
            return globalCache.hashFile(absolutePath, relativePath, content, hasher, configurationHash);
        } else {
            return localCache.hashFile(absolutePath, relativePath, content, hasher, configurationHash);
        }
    }
}
