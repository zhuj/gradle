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
package org.gradle.internal.file.fingerprint.fingerprinter;

import org.gradle.api.file.FileCollection;
import org.gradle.api.internal.changedetection.state.PathNormalizationStrategy;
import org.gradle.api.tasks.FileNormalizer;
import org.gradle.internal.file.fingerprint.FileCollectionFingerprint;
import org.gradle.internal.serialize.SerializerRegistry;
import org.gradle.normalization.internal.InputNormalizationStrategy;

public interface FileCollectionFingerprinter {
    /**
     * The type used to refer to this fingerprinter in the {@link FileCollectionFingerprinterRegistry}.
     */
    Class<? extends FileNormalizer> getRegisteredType();

    /**
     * Registers the serializer(s) that can be used to serialize the {@link FileCollectionFingerprint} implementations produced by this fingerprinter.
     */
    void registerSerializers(SerializerRegistry registry);

    /**
     * Creates a fingerprint of the contents of the given collection.
     */
    FileCollectionFingerprint fingerprint(FileCollection files, PathNormalizationStrategy pathNormalizationStrategy, InputNormalizationStrategy inputNormalizationStrategy);
}
