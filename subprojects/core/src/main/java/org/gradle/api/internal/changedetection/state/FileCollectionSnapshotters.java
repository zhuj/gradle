/*
 * Copyright 2010 the original author or authors.
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

import com.google.common.collect.Iterables;
import org.gradle.api.NonNullApi;
import org.gradle.api.file.FileCollection;
import org.gradle.api.internal.cache.StringInterner;
import org.gradle.api.internal.changedetection.state.mirror.PhysicalSnapshot;
import org.gradle.api.internal.changedetection.state.mirror.logical.DefaultFileCollectionFingerprint;
import org.gradle.api.internal.changedetection.state.mirror.logical.FingerprintingStrategy;
import org.gradle.api.internal.file.FileCollectionInternal;
import org.gradle.internal.serialize.SerializerRegistry;
import org.gradle.internal.serialize.Serializers;

@NonNullApi
public abstract class FileCollectionSnapshotters {

    public static void registerSerializers(SerializerRegistry serializerRegistry, StringInterner stringInterner) {
        serializerRegistry.register(DefaultFileCollectionFingerprint.class, new DefaultFileCollectionFingerprint.SerializerImpl(stringInterner));
        serializerRegistry.register(EmptyFileCollectionSnapshot.class, Serializers.constant(EmptyFileCollectionSnapshot.INSTANCE));
    }

    public static FileCollectionSnapshot createFingerprint(FileCollection input, FingerprintingStrategy strategy, FileSystemSnapshotter fileSystemSnapshotter) {
        FileCollectionInternal fileCollection = (FileCollectionInternal) input;
        Iterable<PhysicalSnapshot> roots = fileSystemSnapshotter.snapshotFileCollection(fileCollection);
        if (Iterables.isEmpty(roots)) {
            return EmptyFileCollectionSnapshot.INSTANCE;
        }
        return new DefaultFileCollectionFingerprint(strategy, roots);
    }
}
