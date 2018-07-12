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

package org.gradle.cache.internal;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.filefilter.RegexFileFilter;
import org.apache.commons.lang.StringUtils;
import org.gradle.api.Action;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.util.GradleVersion;

import java.io.File;
import java.io.FileFilter;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.apache.commons.io.filefilter.FileFilterUtils.directoryFileFilter;
import static org.gradle.util.CollectionUtils.single;

public class WrapperDistributionCleanupAction implements Action<GradleVersion> {

    @VisibleForTesting static final String WRAPPER_DISTRIBUTION_FILE_PATH = "wrapper/dists";
    private static final Logger LOGGER = Logging.getLogger(WrapperDistributionCleanupAction.class);

    private static final ImmutableMap<String, Pattern> JAR_FILE_PATTERNS_BY_PREFIX;
    static {
        Set<String> prefixes = ImmutableSet.of(
            "gradle-base-services", // 4.x
            "gradle-version-info", // 2.x - 3.x
            "gradle-core" // 1.x
        );
        ImmutableMap.Builder<String, Pattern> builder = ImmutableMap.builder();
        for (String prefix : prefixes) {
            builder.put(prefix, Pattern.compile('^' + Pattern.quote(prefix) + "-\\d.+.jar$"));
        }
        JAR_FILE_PATTERNS_BY_PREFIX = builder.build();
    }

    private final File distsDir;
    private Multimap<GradleVersion, File> checksumDirsByVersion;

    public WrapperDistributionCleanupAction(File gradleUserHomeDirectory) {
        this.distsDir = new File(gradleUserHomeDirectory, WRAPPER_DISTRIBUTION_FILE_PATH);
    }

    @Override
    public void execute(GradleVersion version) {
        deleteDistributions(version);
    }

    private void deleteDistributions(GradleVersion version) {
        Set<File> parentsOfDeletedDistributions = Sets.newLinkedHashSet();
        for (File checksumDir : getChecksumDirsByVersion().get(version)) {
            if (FileUtils.deleteQuietly(checksumDir)) {
                parentsOfDeletedDistributions.add(checksumDir.getParentFile());
            }
        }
        for (File parentDir : parentsOfDeletedDistributions) {
            if (listFiles(parentDir).isEmpty()) {
                parentDir.delete();
            }
        }
    }

    private Multimap<GradleVersion, File> getChecksumDirsByVersion() {
        if (checksumDirsByVersion == null) {
            checksumDirsByVersion = determineChecksumDirsByVersion();
        }
        return checksumDirsByVersion;
    }

    private Multimap<GradleVersion, File> determineChecksumDirsByVersion() {
        Multimap<GradleVersion, File> result = ArrayListMultimap.create();
        for (File dir : listDirs(distsDir)) {
            for (File checksumDir : listDirs(dir)) {
                try {
                    GradleVersion gradleVersion = determineGradleVersionFromBuildReceipt(checksumDir);
                    result.put(gradleVersion, checksumDir);
                } catch (Exception e) {
                    LOGGER.debug("Could not determine Gradle version for {}", checksumDir, e);
                }
            }
        }
        return result;
    }

    private GradleVersion determineGradleVersionFromBuildReceipt(File checksumDir) throws Exception {
        List<File> subDirs = listDirs(checksumDir);
        Preconditions.checkArgument(subDirs.size() == 1, "A Gradle distribution must contain exactly one subdirectory: %s", subDirs);
        return determineGradleVersionFromDistribution(single(subDirs));
    }

    @VisibleForTesting
    protected GradleVersion determineGradleVersionFromDistribution(File distributionHomeDir) throws Exception {
        List<File> checkedJarFiles = new ArrayList<File>();
        for (Map.Entry<String, Pattern> entry : JAR_FILE_PATTERNS_BY_PREFIX.entrySet()) {
            List<File> jarFiles = listFiles(new File(distributionHomeDir, "lib"), new RegexFileFilter(entry.getValue()));
            if (!jarFiles.isEmpty()) {
                Preconditions.checkArgument(jarFiles.size() == 1, "A Gradle distribution must contain at most one %s-*.jar: %s", entry.getKey(), jarFiles);
                File jarFile = single(jarFiles);
                GradleVersion gradleVersion = readGradleVersionFromJarFile(jarFile);
                if (gradleVersion != null) {
                    return gradleVersion;
                }
                checkedJarFiles.add(jarFile);
            }
        }
        throw new IllegalArgumentException("No checked JAR file contained a build receipt: " + checkedJarFiles);
    }

    private GradleVersion readGradleVersionFromJarFile(File jarFile) throws Exception {
        ZipFile zipFile = null;
        try {
            zipFile = new ZipFile(jarFile);
            return readGradleVersionFromBuildReceipt(zipFile);
        } finally {
            IOUtils.closeQuietly(zipFile);
        }
    }

    private GradleVersion readGradleVersionFromBuildReceipt(ZipFile zipFile) throws Exception {
        ZipEntry zipEntry = zipFile.getEntry(StringUtils.removeStart(GradleVersion.RESOURCE_NAME, "/"));
        if (zipEntry == null) {
            return null;
        }
        InputStream in = zipFile.getInputStream(zipEntry);
        try {
            Properties properties = new Properties();
            properties.load(in);
            String versionString = properties.getProperty(GradleVersion.VERSION_NUMBER_PROPERTY);
            return GradleVersion.version(versionString);
        } finally {
            in.close();
        }
    }

    private List<File> listDirs(File baseDir) {
        return listFiles(baseDir, directoryFileFilter());
    }

    private List<File> listFiles(File baseDir) {
        return listFiles(baseDir, null);
    }

    private List<File> listFiles(File baseDir, FileFilter filter) {
        File[] dirs = baseDir.listFiles(filter);
        return dirs == null ? Collections.<File>emptyList() : Arrays.asList(dirs);
    }

}
