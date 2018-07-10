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

package org.gradle.api.internal.provider;

import com.google.common.collect.Sets;
import org.gradle.api.Action;
import org.gradle.api.provider.Provider;

import java.util.Set;

public class DefaultChangingValueHandler<T> implements ChangingValue<T> {
    Set<Action<Provider<T>>> onValueChangeActions = Sets.newLinkedHashSet();

    @Override
    public void onValueChange(Action<Provider<T>> action) {
        onValueChangeActions.add(action);
    }

    public void valueChanged(Provider<T> newValue) {
        for (Action<Provider<T>> action : onValueChangeActions) {
            action.execute(newValue);
        }
    }
}
