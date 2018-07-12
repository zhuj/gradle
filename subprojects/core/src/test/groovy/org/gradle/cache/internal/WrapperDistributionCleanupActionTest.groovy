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

package org.gradle.cache.internal

import org.gradle.test.fixtures.file.TestFile
import org.gradle.test.fixtures.file.TestNameTestDirectoryProvider
import org.gradle.util.GradleVersion
import org.gradle.util.JarUtils
import org.junit.Rule
import spock.lang.Specification
import spock.lang.Subject

class WrapperDistributionCleanupActionTest extends Specification implements VersionSpecificCacheAndWrapperDistributionCleanupServiceFixture {

    @Rule TestNameTestDirectoryProvider temporaryFolder = new TestNameTestDirectoryProvider()

    def userHomeDir = temporaryFolder.createDir("user-home")

    @Subject def cleanupAction = new WrapperDistributionCleanupAction(userHomeDir)

    def "deletes distributions for unused versions"() {
        given:
        def versionToCleanUp = GradleVersion.version("2.3.4")
        def oldDist = createDistributionChecksumDir(versionToCleanUp)
        def currentDist = createDistributionChecksumDir(currentVersion)

        when:
        cleanupAction.execute(versionToCleanUp)

        then:
        oldDist.parentFile.assertDoesNotExist()
        currentDist.assertExists()
    }

    def "deletes custom distributions for unused versions"() {
        given:
        def versionToCleanUp = GradleVersion.version("2.3.4")
        def oldAllDist = createCustomDistributionChecksumDir("my-all-dist-${versionToCleanUp.version}", versionToCleanUp)
        def oldBinDist = createCustomDistributionChecksumDir("my-bin-dist-${versionToCleanUp.version}", versionToCleanUp)
        def currentAllDist = createCustomDistributionChecksumDir("my-all-dist-${currentVersion.version}", currentVersion)
        def currentBinDist = createCustomDistributionChecksumDir("my-bin-dist-${currentVersion.version}", currentVersion)

        when:
        cleanupAction.execute(versionToCleanUp)

        then:
        oldAllDist.parentFile.assertDoesNotExist()
        oldBinDist.parentFile.assertDoesNotExist()
        currentAllDist.assertExists()
        currentBinDist.assertExists()
    }

    def "does not delete custom parent directories that are still in use"() {
        given:
        def versionToCleanUp = GradleVersion.version("2.3.4")
        def oldDist = createCustomDistributionChecksumDir("my-dist", versionToCleanUp)
        def currentDist = createCustomDistributionChecksumDir("my-dist", currentVersion)

        when:
        cleanupAction.execute(versionToCleanUp)

        then:
        oldDist.assertDoesNotExist()
        currentDist.assertExists()
    }

    def "ignores custom distributions with multiple sub directories"() {
        given:
        def versionToCleanUp = GradleVersion.version("2.3.4")
        def distributionWithMultipleSubDirectories = createCustomDistributionChecksumDir("my-dist", versionToCleanUp)
        distributionWithMultipleSubDirectories.createDir("foo")

        when:
        cleanupAction.execute(versionToCleanUp)

        then:
        distributionWithMultipleSubDirectories.assertExists()
    }

    def "ignores custom distributions without gradle-base-services JAR"() {
        given:
        def versionToCleanUp = GradleVersion.version("2.3.4")
        def distributionWithoutJar = createCustomDistributionChecksumDir("my-dist", versionToCleanUp, DEFAULT_JAR_PREFIX, { version, jarFile -> })

        when:
        cleanupAction.execute(versionToCleanUp)

        then:
        distributionWithoutJar.assertExists()
    }

    def "ignores custom distributions without build receipt in gradle-base-services JAR"() {
        given:
        def versionToCleanUp = GradleVersion.version("2.3.4")
        def distributionWithoutBuildReceiptInJar = createCustomDistributionChecksumDir("my-dist", versionToCleanUp, DEFAULT_JAR_PREFIX) { version, jarFile ->
            jarFile << JarUtils.jarWithContents(foo: "bar")
        }

        when:
        cleanupAction.execute(versionToCleanUp)

        then:
        distributionWithoutBuildReceiptInJar.assertExists()
    }

    def "ignores distributions with invalid gradle version"() {
        given:
        def versionToCleanUp = GradleVersion.version("2.3.4")
        def distributionWithInvalidVersionInDirName = createCustomDistributionChecksumDir("gradle-1-invalid-all", versionToCleanUp, DEFAULT_JAR_PREFIX, { version, jarFile -> })

        when:
        cleanupAction.execute(versionToCleanUp)

        then:
        distributionWithInvalidVersionInDirName.assertExists()
    }

    def "checks for gradle-core-*.jar"() {
        given:
        def version = GradleVersion.version("1.0")
        def distribution = createDistributionChecksumDir(version, "gradle-core")

        when:
        cleanupAction.execute(version)

        then:
        distribution.assertDoesNotExist()
    }

    def "checks for gradle-version-info-*.jar"() {
        given:
        def version = GradleVersion.version("3.0")
        def distribution = createDistributionChecksumDir(version, "gradle-version-info")

        when:
        cleanupAction.execute(version)

        then:
        distribution.assertDoesNotExist()
    }

    def "does not clean up if no version is found"() {
        given:
        def version = GradleVersion.version("3.0")
        def distribution = createDistributionChecksumDir(version, "some-other-jar")

        when:
        cleanupAction.execute(version)

        then:
        distribution.assertExists()
    }

    @Override
    TestFile getGradleUserHomeDir() {
        return userHomeDir
    }
}
