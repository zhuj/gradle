/*
 * Copyright 2017 the original author or authors.
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

import com.google.common.base.Objects;
import com.google.common.collect.ImmutableSet;
import org.gradle.api.provider.SetProperty;

import java.util.Collection;
import java.util.Set;

public class DefaultSetProperty<T> extends AbstractCollectionProperty<T, Set<T>> implements SetProperty<T> {
    public DefaultSetProperty(Class<T> elementType) {
        super(Set.class, elementType);
    }

    @Override
    protected Set<T> fromValue(Collection<T> values) {
        return ImmutableSet.copyOf(values);
    }

    public static <T> DefaultSetProperty<T> from(ProviderInternal<T> provider) {
        return new SingleElementSetProvider<T>(provider);
    }

    public static class SingleElementSetProvider<T> extends DefaultSetProperty<T> {
        private final ProviderInternal<T> providerInternal;

        public SingleElementSetProvider(ProviderInternal<T> providerInternal) {
            super(providerInternal.getType());
            this.providerInternal = providerInternal;
            this.add(providerInternal);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            SingleElementSetProvider that = (SingleElementSetProvider) o;
            return Objects.equal(providerInternal, that.providerInternal);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(providerInternal);
        }
    }
}
