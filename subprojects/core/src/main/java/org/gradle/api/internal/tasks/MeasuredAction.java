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

import org.gradle.api.Action;

public class MeasuredAction<T> implements Action<T> {
    private final Action<T> delegate;
    private final Bucket bucket;

    public MeasuredAction(Action<T> delegate, Bucket bucket) {
        this.delegate = delegate;
        this.bucket = bucket;
    }

    @Override
    public void execute(T t) {
        long startTime = System.nanoTime();
        delegate.execute(t);
        long endTime = System.nanoTime();
        bucket.accumulate(Math.max(0, endTime-startTime));
    }
}
