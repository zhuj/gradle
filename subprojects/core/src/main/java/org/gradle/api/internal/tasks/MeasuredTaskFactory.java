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

package org.gradle.api.internal.tasks;

import org.gradle.api.Task;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.internal.project.taskfactory.ITaskFactory;
import org.gradle.internal.reflect.Instantiator;

public class MeasuredTaskFactory implements ITaskFactory {
    private final ITaskFactory delegate;
    private final Bucket bucket;

    public MeasuredTaskFactory(ITaskFactory delegate, Bucket bucket) {
        this.delegate = delegate;
        this.bucket = bucket;
    }

    @Override
    public ITaskFactory createChild(ProjectInternal project, Instantiator instantiator) {
        return new MeasuredTaskFactory(delegate.createChild(project, instantiator), bucket);
    }

    @Override
    public <S extends Task> S create(String name, Class<S> type, Object... args) {
        long startTime = System.nanoTime();
        try {
            return delegate.create(name, type, args);
        } finally {
            long endTime = System.nanoTime();
            bucket.accumulate(Math.max(0, endTime - startTime));
        }
    }

    @Override
    public <S extends Task> S create(String name, Class<S> type) {
        long startTime = System.nanoTime();
        try {
            return delegate.create(name, type);
        } finally {
            long endTime = System.nanoTime();
            bucket.accumulate(Math.max(0, endTime - startTime));
        }
    }
}
