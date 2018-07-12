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

package org.gradle.api.internal.changedetection.state.mirror.logical;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import org.gradle.api.internal.changedetection.state.DefaultNormalizedFileSnapshot;
import org.gradle.api.internal.changedetection.state.JarHasher;
import org.gradle.api.internal.changedetection.state.NormalizedFileSnapshot;
import org.gradle.api.internal.changedetection.state.ResourceFilter;
import org.gradle.api.internal.changedetection.state.ResourceHasher;
import org.gradle.api.internal.changedetection.state.ResourceSnapshotterCacheService;
import org.gradle.api.internal.changedetection.state.mirror.PhysicalFileSnapshot;
import org.gradle.api.internal.changedetection.state.mirror.PhysicalSnapshot;
import org.gradle.api.internal.changedetection.state.mirror.PhysicalSnapshotVisitor;
import org.gradle.api.internal.changedetection.state.mirror.RelativePathHolder;
import org.gradle.api.internal.changedetection.state.mirror.RelativePathTracker;
import org.gradle.caching.internal.DefaultBuildCacheHasher;
import org.gradle.internal.Factory;
import org.gradle.internal.FileUtils;
import org.gradle.internal.file.FileType;
import org.gradle.internal.fingerprint.IgnoredPathFingerprint;
import org.gradle.internal.hash.HashCode;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.Map;

public class ClasspathFingerprintingStrategy implements FingerprintingStrategy {

    private final NonJarFingerprintingStrategy nonJarFingerprintingStrategy;
    private final ResourceFilter classpathResourceFilter;
    private final ResourceSnapshotterCacheService cacheService;
    private final ResourceHasher classpathResourceHasher;
    private final JarHasher jarHasher;
    private final HashCode jarHasherConfigurationHash;

    public ClasspathFingerprintingStrategy(NonJarFingerprintingStrategy nonJarFingerprintingStrategy, ResourceHasher classpathResourceHasher, ResourceFilter classpathResourceFilter, ResourceSnapshotterCacheService cacheService) {
        this.nonJarFingerprintingStrategy = nonJarFingerprintingStrategy;
        this.classpathResourceFilter = classpathResourceFilter;
        this.cacheService = cacheService;
        this.classpathResourceHasher = classpathResourceHasher;
        this.jarHasher = new JarHasher(classpathResourceHasher, classpathResourceFilter);
        DefaultBuildCacheHasher hasher = new DefaultBuildCacheHasher();
        jarHasher.appendConfigurationToHasher(hasher);
        this.jarHasherConfigurationHash = hasher.hash();
    }

    @Override
    public Map<String, NormalizedFileSnapshot> collectSnapshots(Iterable<PhysicalSnapshot> roots) {
        final ImmutableMap.Builder<String, NormalizedFileSnapshot> builder = ImmutableMap.builder();
        final HashSet<String> processedEntries = new HashSet<String>();
        for (PhysicalSnapshot root : roots) {
            final ImmutableSortedMap.Builder<String, NormalizedFileSnapshot> rootBuilder = ImmutableSortedMap.naturalOrder();
            root.accept(new ClasspathContentSnapshottingVisitor(new ClasspathSnapshotVisitor(processedEntries, rootBuilder)));
            builder.putAll(rootBuilder.build());
        }
        return builder.build();
    }

    public enum NonJarFingerprintingStrategy {
        IGNORE {
            @Nullable
            @Override
            public HashCode determineNonJarFingerprint(HashCode original) {
                return null;
            }
        },
        USE_FILE_HASH {
            @Override
            public HashCode determineNonJarFingerprint(HashCode original) {
                return original;
            }
        };

        @Nullable
        public abstract HashCode determineNonJarFingerprint(HashCode original);
    }

    private static class ClasspathSnapshotVisitor {
        private final RelativePathHolder relativePathHolder;
        private final HashSet<String> processedEntries;
        private final ImmutableSortedMap.Builder<String, NormalizedFileSnapshot> rootBuilder;

        public ClasspathSnapshotVisitor(HashSet<String> processedEntries, ImmutableSortedMap.Builder<String, NormalizedFileSnapshot> rootBuilder) {
            this.processedEntries = processedEntries;
            this.rootBuilder = rootBuilder;
            relativePathHolder = new RelativePathHolder();
        }

        public boolean preVisitDirectory(PhysicalSnapshot directorySnapshot) {
            relativePathHolder.enter(directorySnapshot);
            return true;
        }

        public void visit(PhysicalSnapshot fileSnapshot, HashCode normalizedContentHash) {
            String absolutePath = fileSnapshot.getAbsolutePath();
            if (processedEntries.add(absolutePath)) {
                NormalizedFileSnapshot normalizedFileSnapshot = relativePathHolder.isRoot() ? IgnoredPathFingerprint.create(fileSnapshot.getType(), normalizedContentHash) : createNormalizedSnapshot(fileSnapshot.getName(), normalizedContentHash);
                rootBuilder.put(
                    absolutePath,
                    normalizedFileSnapshot);
            }
        }

        private NormalizedFileSnapshot createNormalizedSnapshot(String name, HashCode content) {
            relativePathHolder.enter(name);
            NormalizedFileSnapshot normalizedFileSnapshot = new DefaultNormalizedFileSnapshot(relativePathHolder.getRelativePathString(), FileType.RegularFile, content);
            relativePathHolder.leave();
            return normalizedFileSnapshot;
        }

        public void postVisitDirectory() {
            relativePathHolder.leave();
        }
    }

    private class ClasspathContentSnapshottingVisitor implements PhysicalSnapshotVisitor {

        private final ClasspathSnapshotVisitor delegate;
        private RelativePathTracker relativePathTracker = new RelativePathTracker();
        private Factory<String[]> relativePathFactory = new Factory<String[]>() {
            @Override
            public String[] create() {
                return Iterables.toArray(relativePathTracker.getRelativePath(), String.class);
            }
        };

        public ClasspathContentSnapshottingVisitor(ClasspathSnapshotVisitor delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean preVisitDirectory(PhysicalSnapshot directorySnapshot) {
            relativePathTracker.enter(directorySnapshot);
            return delegate.preVisitDirectory(directorySnapshot);
        }

        @Override
        public void visit(PhysicalSnapshot fileSnapshot) {
            if (fileSnapshot.getType() == FileType.RegularFile) {
                HashCode normalizedContent = fingerprintFile((PhysicalFileSnapshot) fileSnapshot);
                if (normalizedContent != null) {
                    delegate.visit(fileSnapshot, normalizedContent);
                }
            }
        }

        @Nullable
        private HashCode fingerprintFile(PhysicalFileSnapshot fileSnapshot) {
            return relativePathTracker.isRoot() ? fingerprintRootFile(fileSnapshot) : fingerprintTreeFile(fileSnapshot);
        }

        @Nullable
        private HashCode fingerprintTreeFile(PhysicalFileSnapshot fileSnapshot) {
            relativePathTracker.enter(fileSnapshot.getName());
            boolean shouldBeIgnored = classpathResourceFilter.shouldBeIgnored(relativePathFactory);
            relativePathTracker.leave();
            if (shouldBeIgnored) {
                return null;
            }
            return classpathResourceHasher.hash(fileSnapshot);
        }

        @Override
        public void postVisitDirectory() {
            relativePathTracker.leave();
            delegate.postVisitDirectory();
        }
    }

    @Nullable
    private HashCode fingerprintRootFile(PhysicalFileSnapshot fileSnapshot) {
        if (FileUtils.hasExtensionIgnoresCase(fileSnapshot.getName(), ".jar")) {
            return snapshotJarContents(fileSnapshot);
        }
        return nonJarFingerprintingStrategy.determineNonJarFingerprint(fileSnapshot.getContentHash());
    }

    @Nullable
    private HashCode snapshotJarContents(PhysicalFileSnapshot fileSnapshot) {
        return cacheService.hashFile(fileSnapshot, jarHasher, jarHasherConfigurationHash);
    }

    @Override
    public FingerprintCompareStrategy getCompareStrategy() {
        return FingerprintCompareStrategy.CLASSPATH;
    }
}
