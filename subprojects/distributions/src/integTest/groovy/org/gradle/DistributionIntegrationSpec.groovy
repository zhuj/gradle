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

package org.gradle

import org.apache.commons.io.IOUtils
import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import org.gradle.test.fixtures.archive.JarTestFixture
import org.gradle.test.fixtures.file.TestFile
import org.gradle.util.GUtil
import org.gradle.util.GradleVersion
import org.gradle.util.PreconditionVerifier
import org.junit.Rule
import spock.lang.Shared

import java.util.zip.ZipEntry
import java.util.zip.ZipFile

import static org.hamcrest.Matchers.equalTo
import static org.junit.Assert.assertThat

abstract class DistributionIntegrationSpec extends AbstractIntegrationSpec {

    @Rule public final PreconditionVerifier preconditionVerifier = new PreconditionVerifier()

    @Shared String baseVersion = GradleVersion.current().baseVersion.version

    abstract String getDistributionLabel()

    int getLibJarsCount() {
        195
    }

    def "no duplicate entries"() {
        given:
        def entriesByPath = zipEntries.findAll { !it.name.contains('/META-INF/services/') }.groupBy { it.name }
        def dupes = entriesByPath.findAll { it.value.size() > 1 }

        when:
        def dupesWithCount = dupes.collectEntries { [it.key, it.value.size()]}

        then:
        dupesWithCount.isEmpty()
    }

    def "all files under lib directory are jars"() {
        when:
        def nonJarLibEntries = libZipEntries.findAll { !it.name.endsWith(".jar") }

        then:
        nonJarLibEntries.isEmpty()
    }

    def "no additional jars are added to the distribution"() {
        when:
        def jarLibEntries = libZipEntries.findAll { it.name.endsWith(".jar") }

        then:
        //ME: This is not a foolproof way of checking that additional jars have not been accidentally added to the distribution
        //but should be good enough. If this test fails for you and you did not intend to add new jars to the distribution
        //then there is something to be fixed. If you intentionally added new jars to the distribution and this is now failing please
        //accept my sincere apologies that you have to manually bump the numbers here.
        jarLibEntries.size() == libJarsCount
    }

    protected List<? extends ZipEntry> getLibZipEntries() {
        zipEntries.findAll { !it.isDirectory() && it.name.tokenize("/")[1] == "lib" }
    }

    protected List<? extends ZipEntry> getZipEntries() {
        ZipFile zipFile = new ZipFile(zip)
        try {
            zipFile.entries().toList()
        } finally {
            zipFile.close()
        }
    }

    protected TestFile unpackDistribution(type = getDistributionLabel(), TestFile into = testDirectory) {
        TestFile zip = getZip(type)
        zip.usingNativeTools().unzipTo(into)
        assert into.listFiles().size() == 1
        into.listFiles()[0]
    }

    protected TestFile getZip(String type = getDistributionLabel()) {
        buildContext.distributionsDir.file("gradle-$baseVersion-${type}.zip")
    }

    protected void checkMinimalContents(TestFile contentsDir) {
        // Check it can be executed
        executer.inDirectory(contentsDir).usingExecutable('bin/gradle').withTaskList().run()

        // Scripts
        contentsDir.file('bin/gradle').assertIsFile()
        contentsDir.file('bin/gradle.bat').assertIsFile()

        // Top level files
        contentsDir.file('LICENSE').assertIsFile()

        // Core libs
        def coreLibs = contentsDir.file("lib").listFiles().findAll {
            it.name.startsWith("gradle-") && !it.name.startsWith("gradle-api-metadata") && !it.name.startsWith("gradle-kotlin-dsl")
        }
        assert coreLibs.size() == 22
        coreLibs.each { assertIsGradleJar(it) }

        def toolingApiJar = contentsDir.file("lib/gradle-tooling-api-${baseVersion}.jar")
        toolingApiJar.assertIsFile()
        assert toolingApiJar.length() < 360 * 1024 // tooling api jar is the small plain tooling api jar version and not the fat jar.

        // Plugins
        assertIsGradleJar(contentsDir.file("lib/plugins/gradle-dependency-management-${baseVersion}.jar"))
        assertIsGradleJar(contentsDir.file("lib/plugins/gradle-version-control-${baseVersion}.jar"))
        assertIsGradleJar(contentsDir.file("lib/plugins/gradle-plugins-${baseVersion}.jar"))
        assertIsGradleJar(contentsDir.file("lib/plugins/gradle-ide-${baseVersion}.jar"))
        assertIsGradleJar(contentsDir.file("lib/plugins/gradle-scala-${baseVersion}.jar"))
        assertIsGradleJar(contentsDir.file("lib/plugins/gradle-code-quality-${baseVersion}.jar"))
        assertIsGradleJar(contentsDir.file("lib/plugins/gradle-antlr-${baseVersion}.jar"))
        assertIsGradleJar(contentsDir.file("lib/plugins/gradle-announce-${baseVersion}.jar"))
        assertIsGradleJar(contentsDir.file("lib/plugins/gradle-maven-${baseVersion}.jar"))
        assertIsGradleJar(contentsDir.file("lib/plugins/gradle-osgi-${baseVersion}.jar"))
        assertIsGradleJar(contentsDir.file("lib/plugins/gradle-signing-${baseVersion}.jar"))
        assertIsGradleJar(contentsDir.file("lib/plugins/gradle-ear-${baseVersion}.jar"))
        assertIsGradleJar(contentsDir.file("lib/plugins/gradle-platform-native-${baseVersion}.jar"))
        assertIsGradleJar(contentsDir.file("lib/plugins/gradle-ide-native-${baseVersion}.jar"))
        assertIsGradleJar(contentsDir.file("lib/plugins/gradle-language-native-${baseVersion}.jar"))
        assertIsGradleJar(contentsDir.file("lib/plugins/gradle-platform-jvm-${baseVersion}.jar"))
        assertIsGradleJar(contentsDir.file("lib/plugins/gradle-language-jvm-${baseVersion}.jar"))
        assertIsGradleJar(contentsDir.file("lib/plugins/gradle-language-java-${baseVersion}.jar"))
        assertIsGradleJar(contentsDir.file("lib/plugins/gradle-language-groovy-${baseVersion}.jar"))

        // Docs
        contentsDir.file('getting-started.html').assertIsFile()

        // Others
        assertIsGradleApiMetadataJar(contentsDir.file("lib/gradle-api-metadata-${baseVersion}.jar"))

        // Jars that must not be shipped
        assert !contentsDir.file("lib/tools.jar").exists()
        assert !contentsDir.file("lib/plugins/tools.jar").exists()
    }

    protected void assertIsGradleJar(TestFile jar) {
        jar.assertIsFile()
        assertThat(jar.manifest.mainAttributes.getValue('Implementation-Version'), equalTo(baseVersion))
        assertThat(jar.manifest.mainAttributes.getValue('Implementation-Title'), equalTo('Gradle'))
    }

    private static void assertIsGradleApiMetadataJar(TestFile jar) {
        new JarTestFixture(jar.canonicalFile).with {
            def apiDeclaration = GUtil.loadProperties(IOUtils.toInputStream(content("gradle-api-declaration.properties")))
            assert apiDeclaration.size() == 2
            assert apiDeclaration.getProperty("includes").contains(":org/gradle/api/**:")
            assert apiDeclaration.getProperty("excludes").split(":").size() == 1
            def parameterNames = GUtil.loadProperties(IOUtils.toInputStream(content("gradle-api-parameter-names.properties")))
            assert parameterNames.size() > 2900
            assert parameterNames["org.gradle.api.DomainObjectCollection.withType(java.lang.Class,org.gradle.api.Action)"] == "type,configureAction"
        }
    }
}
