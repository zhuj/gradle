/*
 * Copyright 2016 the original author or authors.
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

package org.gradle.java.compile

import org.gradle.api.internal.tasks.compile.processing.AnnotationProcessorPathFactory
import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import org.gradle.language.fixtures.HelperProcessorFixture
import spock.lang.Issue

abstract class AbstractJavaCompileAvoidanceIntegrationSpec extends AbstractIntegrationSpec {
    def setup() {
        settingsFile << """
include 'a', 'b'
"""
        buildFile << """
            allprojects {
                apply plugin: 'java'
                task emptyDirs(type: Sync) {
                    into 'build/empty-dirs'
                    from 'src/empty-dirs'
                    includeEmptyDirs = true
                }
            }
        """
    }

    def deactivateIncrementalCompile() {
        buildFile << """
            allprojects {
                tasks.withType(JavaCompile) {
                    options.incremental = false
                }
            }
        """
    }

    def useJar() {
        buildFile << """
            allprojects {
                jar {
                    from emptyDirs
                }
            }
"""
    }

    def useClassesDir() {
        buildFile << """import static org.gradle.api.attributes.Usage.*;
            allprojects {
                configurations.apiElements.outgoing.variants {
                    classes {
                        attributes.attribute(USAGE_ATTRIBUTE, objects.named(Usage, Usage.JAVA_API_CLASSES))
                        artifact file: compileJava.destinationDir, builtBy: compileJava
                        artifact file: emptyDirs.destinationDir, builtBy: emptyDirs
                        artifact file: processResources.destinationDir, builtBy: processResources
                    }
                }
            }
        """
    }

    def "doesn't recompile when private element of implementation class changes"() {
        given:
        buildFile << """
            project(':b') {
                dependencies {
                    compile project(':a')
                }
            }
        """
        def sourceFile = file("a/src/main/java/ToolImpl.java")
        sourceFile << """
            public class ToolImpl { 
                private String thing() { return null; }
                private ToolImpl t = this;
            }
        """
        file("b/src/main/java/Main.java") << """
            public class Main { ToolImpl t = new ToolImpl(); }
        """

        when:
        succeeds ':b:compileJava'

        then:
        executedAndNotSkipped ':a:compileJava'
        executedAndNotSkipped ':b:compileJava'

        when:
        // change signatures
        sourceFile.text = """
            public class ToolImpl { 
                private Number thing() { return null; }
                private Object t = this;
            }
"""

        then:
        succeeds ':b:compileJava'
        executedAndNotSkipped ':a:compileJava'
        skipped ':b:compileJava'

        when:
        // add private elements
        sourceFile.text = """
            public class ToolImpl { 
                private Number thing() { return null; }
                private Object t = this;
                private static void someMethod() { }
                private String s;
            }
"""

        then:
        succeeds ':b:compileJava'
        executedAndNotSkipped ':a:compileJava'
        skipped ':b:compileJava'

        when:
        // remove private elements
        sourceFile.text = """
            public class ToolImpl { 
            }
"""

        then:
        succeeds ':b:compileJava'
        executedAndNotSkipped ':a:compileJava'
        skipped ':b:compileJava'

        when:
        // add public method, should change
        sourceFile.text = """
            public class ToolImpl { 
                public void execute() { String s = toString(); } 
            }
"""

        then:
        succeeds ':b:compileJava'
        executedAndNotSkipped ':a:compileJava', ":b:compileJava"

        when:
        // add public field, should change
        sourceFile.text = """
            public class ToolImpl { 
                public static ToolImpl instance; 
                public void execute() { String s = toString(); } 
            }
"""

        then:
        succeeds ':b:compileJava'
        executedAndNotSkipped ':a:compileJava', ":b:compileJava"

        when:
        // add public constructor to replace the default, should not change
        sourceFile.text = """
            public class ToolImpl { 
                public ToolImpl() { }
                public static ToolImpl instance; 
                public void execute() { String s = toString(); } 
            }
"""

        then:
        succeeds ':b:compileJava'
        executedAndNotSkipped ':a:compileJava'
        skipped ':b:compileJava'

        when:
        // add public constructor, should change
        sourceFile.text = """
            public class ToolImpl { 
                public ToolImpl() { }
                public ToolImpl(String s) { }
                public static ToolImpl instance; 
                public void execute() { String s = toString(); } 
            }
"""

        then:
        succeeds ':b:compileJava'
        executedAndNotSkipped ':a:compileJava', ":b:compileJava"
    }

    def "doesn't recompile when comments and whitespace of implementation class changes"() {
        given:
        buildFile << """
            project(':b') {
                dependencies {
                    compile project(':a')
                }
            }
        """
        def sourceFile = file("a/src/main/java/ToolImpl.java")
        sourceFile << """ 
            public class ToolImpl { }
        """
        file("b/src/main/java/Main.java") << """
            public class Main { ToolImpl t = new ToolImpl(); }
        """

        when:
        succeeds ':b:compileJava'

        then:
        executedAndNotSkipped ':a:compileJava'
        executedAndNotSkipped ':b:compileJava'

        when:
        // add comments, change whitespace
        sourceFile.text = """
/**
 * A thing
 */
public class ToolImpl { 
    // TODO - add some stuff
}
"""

        then:
        succeeds ':b:compileJava'
        executedAndNotSkipped ':a:compileJava'
        skipped ':b:compileJava'
    }

    def "doesn't recompile when implementation class code changes"() {
        given:
        buildFile << """
            project(':b') {
                dependencies {
                    compile project(':a')
                }
            }
        """
        def sourceFile = file("a/src/main/java/ToolImpl.java")
        sourceFile << """
            public class ToolImpl { 
                public Object s = String.valueOf(12);
                public void execute() { int i = 12; } 
            }
        """
        file("b/src/main/java/Main.java") << """
            public class Main { ToolImpl t = new ToolImpl(); }
        """

        when:
        succeeds ':b:compileJava'

        then:
        executedAndNotSkipped ':a:compileJava'
        executedAndNotSkipped ':b:compileJava'

        when:
        // change method body and field initializer
        sourceFile.text = """
            public class ToolImpl { 
                public Object s = "12";
                public void execute() { String s = toString(); } 
            }
"""

        then:
        succeeds ':b:compileJava'
        executedAndNotSkipped ':a:compileJava'
        skipped ':b:compileJava'

        when:
        // add static initializer and constructor
        sourceFile.text = """
            public class ToolImpl { 
                static { }
                public ToolImpl() { }
                public Object s = "12";
                public void execute() { String s = toString(); } 
            }
"""

        then:
        succeeds ':b:compileJava'
        executedAndNotSkipped ':a:compileJava'
        skipped ':b:compileJava'

        when:
        // change static initializer and constructor
        sourceFile.text = """
            public class ToolImpl { 
                static { int i = 123; }
                public ToolImpl() { System.out.println("created!"); }
                public Object s = "12";
                public void execute() { String s = toString(); } 
            }
"""

        then:
        succeeds ':b:compileJava'
        executedAndNotSkipped ':a:compileJava'
        skipped ':b:compileJava'
    }

    def "recompiles when type of implementation class changes"() {
        given:
        buildFile << """
            project(':b') {
                dependencies {
                    compile project(':a')
                }
            }
        """
        def sourceFile = file("a/src/main/java/org/ToolImpl.java")
        sourceFile << """                  
            package org;
            public class ToolImpl { void m() { } }
        """
        file("b/src/main/java/org/Main.java") << """
            package org;    
            public class Main { void go(ToolImpl t) { t.m(); } }
        """

        when:
        succeeds ':b:compileJava'

        then:
        executedAndNotSkipped ':a:compileJava'
        executedAndNotSkipped ':b:compileJava'

        when:
        // change to interface
        sourceFile.text = """
            package org;    
            public interface ToolImpl { void m(); }
"""

        then:
        succeeds ':b:compileJava'
        executedAndNotSkipped ':a:compileJava'
        executedAndNotSkipped ':b:compileJava'

        when:
        // change to visibility
        sourceFile.text = """
            package org;    
            interface ToolImpl { void m(); }
"""

        then:
        succeeds ':b:compileJava'
        executedAndNotSkipped ':a:compileJava'
        executedAndNotSkipped ':b:compileJava'

        when:
        // change to interfaces
        sourceFile.text = """
            package org;    
            interface ToolImpl extends Runnable { void m(); }
"""

        then:
        succeeds ':b:compileJava'
        executedAndNotSkipped ':a:compileJava'
        executedAndNotSkipped ':b:compileJava'
    }

    def "recompiles when constant value of API changes"() {
        given:
        buildFile << """
            project(':b') {
                dependencies {
                    compile project(':a')
                }
            }
        """
        def sourceFile = file("a/src/main/java/ToolImpl.java")
        sourceFile << """ 
            public class ToolImpl { public static final int CONST = 1; }
        """
        file("b/src/main/java/Main.java") << """
            public class Main { public static final int CONST2 = 1 + ToolImpl.CONST; }
        """

        when:
        succeeds ':b:compileJava'

        then:
        executedAndNotSkipped ':a:compileJava'
        executedAndNotSkipped ':b:compileJava'

        when:
        // change to constant value
        sourceFile.text = """
            public class ToolImpl { public static final int CONST = 10; }
"""

        then:
        succeeds ':b:compileJava'
        executedAndNotSkipped ':a:compileJava'
        executedAndNotSkipped ':b:compileJava'
    }

    def "recompiles when generic type signatures of implementation class changes"() {
        given:
        buildFile << """
            project(':b') {
                dependencies {
                    compile project(':a')
                }
            }
        """
        def sourceFile = file("a/src/main/java/ToolImpl.java")
        sourceFile << """ 
            public interface ToolImpl { void m(java.util.List<String> s); }
        """
        file("b/src/main/java/Main.java") << """
            public class Main { void go(ToolImpl t) { t.m(null); } }
        """

        when:
        succeeds ':b:compileJava'

        then:
        executedAndNotSkipped ':a:compileJava'
        executedAndNotSkipped ':b:compileJava'

        when:
        // add type parameters to interface
        sourceFile.text = """
            public interface ToolImpl<T> { void m(java.util.List<String> s); }
"""

        then:
        succeeds ':b:compileJava'
        executedAndNotSkipped ':a:compileJava'
        executedAndNotSkipped ':b:compileJava'

        when:
        // add type parameters to method
        sourceFile.text = """
            public interface ToolImpl<T> { <S extends T> void m(java.util.List<S> s); }
"""

        then:
        succeeds ':b:compileJava'
        executedAndNotSkipped ':a:compileJava'
        executedAndNotSkipped ':b:compileJava'

        when:
        // change type parameters on interface
        sourceFile.text = """
            public interface ToolImpl<T extends CharSequence> { <S extends T> void m(java.util.List<S> s); }
"""

        then:
        succeeds ':b:compileJava'
        executedAndNotSkipped ':a:compileJava'
        executedAndNotSkipped ':b:compileJava'

        when:
        // change type parameters on method
        sourceFile.text = """
            public interface ToolImpl<T extends CharSequence> { <S extends Number> void m(java.util.List<S> s); }
"""

        then:
        succeeds ':b:compileJava'
        executedAndNotSkipped ':a:compileJava'
        executedAndNotSkipped ':b:compileJava'
    }

    def "doesn't recompile when private inner class changes"() {
        given:
        buildFile << """
            project(':b') {
                dependencies {
                    compile project(':a')
                }
            }
        """
        def sourceFile = file("a/src/main/java/ToolImpl.java")
        sourceFile << """
            public class ToolImpl { 
                private class Thing { }
            }
        """
        file("b/src/main/java/Main.java") << """
            public class Main { ToolImpl t = new ToolImpl(); }
        """

        when:
        succeeds ':b:compileJava'

        then:
        executedAndNotSkipped ':a:compileJava'
        executedAndNotSkipped ':b:compileJava'

        when:
        // ABI change of inner class
        sourceFile.text = """
            public class ToolImpl { 
                private class Thing {
                    public long v; 
                }
            }
"""

        then:
        succeeds ':b:compileJava'
        executedAndNotSkipped ':a:compileJava'
        skipped ':b:compileJava'

        when:
        // Remove inner class
        sourceFile.text = """
            public class ToolImpl { 
            }
"""

        then:
        succeeds ':b:compileJava'
        executedAndNotSkipped ':a:compileJava'
        skipped ':b:compileJava'

        when:
        // Anonymous class
        sourceFile.text = """
            public class ToolImpl { 
                private Object r = new Runnable() { public void run() { } };
            }
"""

        then:
        succeeds ':b:compileJava'
        executedAndNotSkipped ':a:compileJava'
        skipped ':b:compileJava'

        when:
        // Add classes
        sourceFile.text = """
            public class ToolImpl { 
                private Object r = new Runnable() { 
                    public void run() {
                        class LocalThing { }
                    } 
                };
                private static class C1 {
                }
                private class C2 { 
                    public void go() {
                        Object r2 = new Runnable() { public void run() { } };
                    }
                }
            }
"""

        then:
        succeeds ':b:compileJava'
        executedAndNotSkipped ':a:compileJava'
        skipped ':b:compileJava'
    }

    def "doesn't recompile when implementation resource is changed in various ways"() {
        given:
        buildFile << """
            project(':b') {
                dependencies {
                    compile project(':a')
                }
            }
        """
        def sourceFile = file("a/src/main/java/ToolImpl.java")
        sourceFile << """
            public class ToolImpl { public void execute() { int i = 12; } }
        """
        def resourceFile = file("a/src/main/resources/a.properties")
        resourceFile.text = "a = 12"
        file("b/src/main/java/Main.java") << """
            public class Main { ToolImpl t = new ToolImpl(); }
        """

        when:
        succeeds ':b:compileJava'

        then:
        executedAndNotSkipped ':a:compileJava'
        executedAndNotSkipped ':b:compileJava'

        when:
        resourceFile.text = "a = 11"

        then:
        succeeds ':b:compileJava'
        skipped ':a:compileJava'
        skipped ':b:compileJava'

        when:
        resourceFile.delete()

        then:
        succeeds ':b:compileJava'
        skipped ':a:compileJava'
        skipped ':b:compileJava'

        when:
        file("a/src/main/resources/org/gradle/b.properties").createFile()

        then:
        succeeds ':b:compileJava'
        skipped ':a:compileJava'
        skipped ':b:compileJava'
    }

    def "doesn't recompile when empty directories are changed in various ways"() {
        given:
        buildFile << """
            project(':b') {
                dependencies {
                    compile project(':a')
                }
            }
        """
        def sourceFile = file("a/src/main/java/ToolImpl.java")
        sourceFile << """
            public class ToolImpl { public void execute() { int i = 12; } }
        """
        file("b/src/main/java/Main.java") << """
            public class Main { ToolImpl t = new ToolImpl(); }
        """
        file("a/src/empty-dirs/ignore-me.txt").createFile()
        file("a/src/empty-dirs/a/dir").mkdirs()

        when:
        succeeds ':b:compileJava'

        then:
        executedAndNotSkipped ':a:compileJava'
        executedAndNotSkipped ':b:compileJava'

        when:
        file("a/src/empty-dirs/a/dir2").mkdirs()

        then:
        succeeds ':b:compileJava'
        skipped ':a:compileJava'
        skipped ':b:compileJava'

        when:
        file("a/src/empty-dirs/a/dir").deleteDir()

        then:
        succeeds ':b:compileJava'
        skipped ':a:compileJava'
        skipped ':b:compileJava'
    }

    def "recompiles source when annotation processor implementation changes when compile classpath is used for annotation processor discovery"() {
        settingsFile << "include 'c'"

        buildFile << """
            project(':b') {
                dependencies {
                    compile project(':a')
                }
            }
            project(':c') {
                dependencies {
                    compile project(':b')
                }
                task run(type: JavaExec) {
                    main = 'TestApp'
                    classpath = sourceSets.main.runtimeClasspath
                }
            }
        """

        def fixture = new HelperProcessorFixture()

        // A library class used by processor at runtime, but not the generated classes
        fixture.writeSupportLibraryTo(file("a"))

        // The processor and annotation
        fixture.writeApiTo(file("b"))
        fixture.writeAnnotationProcessorTo(file("b"))

        // The class that is the target of the processor
        file('c/src/main/java/TestApp.java') << '''
            @Helper
            class TestApp { 
                public static void main(String[] args) {
                    System.out.println(new TestAppHelper().getValue()); // generated class
                }
            }
'''

        when:
        executer.expectDeprecationWarning()
        run(':c:run')

        then:
        executedAndNotSkipped(':a:compileJava')
        executedAndNotSkipped(':b:compileJava')
        executedAndNotSkipped(':c:compileJava')
        outputContains('greetings')
        outputContains(AnnotationProcessorPathFactory.COMPILE_CLASSPATH_DEPRECATION_MESSAGE)

        when:
        executer.expectDeprecationWarning()
        run(':c:run')

        then:
        skipped(':a:compileJava')
        skipped(':b:compileJava')
        skipped(':c:compileJava')
        outputContains('greetings')
        outputContains(AnnotationProcessorPathFactory.COMPILE_CLASSPATH_DEPRECATION_MESSAGE)

        when:
        // Update the library class
        fixture.message = 'hello'
        fixture.writeSupportLibraryTo(file("a"))

        executer.expectDeprecationWarning()
        run(':c:run')

        then:
        executedAndNotSkipped(':a:compileJava')
        skipped(':b:compileJava')
        executedAndNotSkipped(':c:compileJava')
        outputContains('hello')
        outputContains(AnnotationProcessorPathFactory.COMPILE_CLASSPATH_DEPRECATION_MESSAGE)

        when:
        executer.expectDeprecationWarning()
        run(':c:run')

        then:
        skipped(':a:compileJava')
        skipped(':b:compileJava')
        skipped(':c:compileJava')
        outputContains('hello')
        outputContains(AnnotationProcessorPathFactory.COMPILE_CLASSPATH_DEPRECATION_MESSAGE)

        when:
        // Update the processor class
        fixture.suffix = 'world'
        fixture.writeAnnotationProcessorTo(file("b"))

        executer.expectDeprecationWarning()
        run(':c:run')

        then:
        skipped(':a:compileJava')
        executedAndNotSkipped(':b:compileJava')
        executedAndNotSkipped(':c:compileJava')
        outputContains('hello world')
        outputContains(AnnotationProcessorPathFactory.COMPILE_CLASSPATH_DEPRECATION_MESSAGE)
    }

    def "recompiles source when annotation processor implementation changes when separate annotation processor classpath is used for annotation processor discovery"() {
        settingsFile << "include 'c'"

        buildFile << """
            project(':b') {
                dependencies {
                    compile project(':a')
                }
            }
            project(':c') {
                configurations {
                    processor
                }
                dependencies {
                    compile project(':a')
                    processor project(':b')
                }
                compileJava.options.annotationProcessorPath = configurations.processor
                task run(type: JavaExec) {
                    main = 'TestApp'
                    classpath = sourceSets.main.runtimeClasspath
                }
            }
        """

        def fixture = new HelperProcessorFixture()

        // The annotation
        fixture.writeApiTo(file("a"))

        // The processor and library
        fixture.writeSupportLibraryTo(file("b"))
        fixture.writeAnnotationProcessorTo(file("b"))

        // The class that is the target of the processor
        file('c/src/main/java/TestApp.java') << '''
            @Helper
            class TestApp { 
                public static void main(String[] args) {
                    System.out.println(new TestAppHelper().getValue()); // generated class
                }
            }
'''

        when:
        run(':c:run')

        then:
        executedAndNotSkipped(':a:compileJava')
        executedAndNotSkipped(':b:compileJava')
        executedAndNotSkipped(':c:compileJava')
        outputContains('greetings')

        when:
        run(':c:run')

        then:
        skipped(':a:compileJava')
        skipped(':b:compileJava')
        skipped(':c:compileJava')
        outputContains('greetings')

        when:
        // Update the library class
        fixture.message = 'hello'
        fixture.writeSupportLibraryTo(file("b"))

        run(':c:run')

        then:
        skipped(':a:compileJava')
        executedAndNotSkipped(':b:compileJava')
        executedAndNotSkipped(':c:compileJava')
        outputContains('hello')

        when:
        run(':c:run')

        then:
        skipped(':a:compileJava')
        skipped(':b:compileJava')
        skipped(':c:compileJava')
        outputContains('hello')

        when:
        // Update the processor class
        fixture.suffix = 'world'
        fixture.writeAnnotationProcessorTo(file("b"))

        run(':c:run')

        then:
        skipped(':a:compileJava')
        executedAndNotSkipped(':b:compileJava')
        executedAndNotSkipped(':c:compileJava')
        outputContains('hello world')
    }

    def "ignores annotation processor implementation when included in the compile classpath but annotation processing is disabled"() {
        settingsFile << "include 'c'"

        buildFile << """
            project(':b') {
                dependencies {
                    compile project(':a')
                }
            }
            project(':c') {
                dependencies {
                    compile project(':b')
                }
                compileJava.options.annotationProcessorPath = files()
            }
        """

        def fixture = new HelperProcessorFixture()

        fixture.writeSupportLibraryTo(file("a"))
        fixture.writeApiTo(file("b"))
        fixture.writeAnnotationProcessorTo(file("b"))

        file('c/src/main/java/TestApp.java') << '''
            @Helper
            class TestApp { 
                public static void main(String[] args) {
                }
            }
'''

        when:
        run(':c:compileJava')

        then:
        executedAndNotSkipped(':a:compileJava')
        executedAndNotSkipped(':b:compileJava')
        executedAndNotSkipped(':c:compileJava')

        when:
        run(':c:compileJava')

        then:
        skipped(':a:compileJava')
        skipped(':b:compileJava')
        skipped(':c:compileJava')

        when:
        // Update the library class
        fixture.message = 'hello'
        fixture.writeSupportLibraryTo(file("a"))

        run(':c:compileJava')

        then:
        executedAndNotSkipped(':a:compileJava')
        skipped(':b:compileJava')
        skipped(':c:compileJava')

        when:
        // Update the processor class
        fixture.suffix = 'world'
        fixture.writeAnnotationProcessorTo(file("b"))

        run(':c:compileJava')

        then:
        skipped(':a:compileJava')
        executedAndNotSkipped(':b:compileJava')
        skipped(':c:compileJava')
    }

    def "change to transitive super-class in different project should trigger recompilation"() {
        given:
        settingsFile << "include 'c'"

        buildFile << """
            project(':a') {
                dependencies {
                    compile project(':b')
                }
            }
            project(':b') {
                dependencies {
                    compile project(':c')
                }
            }
        """

        file("a/src/main/java/A.java") << "public class A extends B { void a() { b(); String c = c(); } }"
        file("b/src/main/java/B.java") << "public class B extends C { void b() { d(); } }"
        file("c/src/main/java/C.java") << "public class C { String c() { return null; }; void d() {} }"

        when:
        succeeds ':a:compileJava'

        then:
        executedAndNotSkipped ':a:compileJava'
        executedAndNotSkipped ':b:compileJava'
        executedAndNotSkipped ':c:compileJava'

        when:
        file("c/src/main/java/C.java").text = "public class C { void c() {}; void d() {} }"

        then:
        fails ':a:compileJava'
        failure.assertHasErrorOutput 'String c = c()'

        and:
        executedAndNotSkipped ':b:compileJava'
        executedAndNotSkipped ':c:compileJava'

    }

    def "change to transitive super-class in different project should trigger recompilation 2"() {
        given:
        settingsFile << "include 'c', 'd'"

        buildFile << """
            project(':a') {
                dependencies {
                    compile project(':b')
                }
            }
            project(':b') {
                dependencies {
                    compile project(':c')
                }
            }
            project(':c') {
                dependencies {
                    compile project(':d')
                }
            }
        """

        file("a/src/main/java/A.java") << "public class A extends B { void a() { b(); String d = d(); } }"
        file("b/src/main/java/B.java") << "public class B extends C { void b() { } }"
        file("c/src/main/java/C.java") << "public class C extends D { void c() { }; }"
        file("d/src/main/java/D.java") << "public class D { String d() { return null; } }"

        when:
        succeeds ':a:compileJava'

        then:
        executedAndNotSkipped ':a:compileJava'
        executedAndNotSkipped ':b:compileJava'
        executedAndNotSkipped ':c:compileJava'
        executedAndNotSkipped ':d:compileJava'

        when:
        file("d/src/main/java/D.java").text = "public class D { void d() { } }"

        then:
        fails ':a:compileJava'
        failure.assertHasErrorOutput 'String d = d();'

        and:
        executedAndNotSkipped ':b:compileJava'
        executedAndNotSkipped ':c:compileJava'
        executedAndNotSkipped ':d:compileJava'
    }

    @Issue("gradle/gradle#1913")
    def "detects changes in compile classpath"() {
        given:
        buildFile << """
            ${jcenterRepository()}
            
            dependencies {
               if (project.hasProperty('useCommons')) {
                  implementation 'org.apache.commons:commons-lang3:3.5'
               }
               
               // There MUST be at least 3 dependencies, in that specific order, for the bug to show up.
               // The reason is that `IncrementalTaskInputs` reports wrong information about deletions at the
               // beginning of a list, when the collection is ordered. It has been agreed not to fix it now, but
               // rather change the incremental compiler not to rely on this incorrect information
               
               implementation 'net.jcip:jcip-annotations:1.0'
               implementation 'org.slf4j:slf4j-api:1.7.10'
            }
        """
        file("src/main/java/Client.java") << """import org.apache.commons.lang3.exception.ExceptionUtils;
            public class Client {
                public void doSomething() {
                    ExceptionUtils.rethrow(new RuntimeException("ok"));
                }
            }
        """

        when:
        executer.withArgument('-PuseCommons')
        succeeds ':compileJava'

        then:
        noExceptionThrown()

        when: "Apache Commons is removed from classpath"
        fails ':compileJava'

        then:
        failure.assertHasCause('Compilation failed; see the compiler error output for details.')
    }

    def "detects changes in compile classpath order"() {
        given:
        ['a', 'b'].each {
            // Same class is defined in both project `a` and `b` but with a different ABI
            // so one shadows the other depending on the order on classpath
            file("$it/src/main/java/A.java") << """
                public class A {
                    public static String m_$it() { return "ok"; }
                }
            """
        }
        buildFile << """
            ${jcenterRepository()}
            
            dependencies {
               switch (project.getProperty('order') as int) {
                  case 0:
                    implementation 'org.apache.commons:commons-lang3:3.5'
                    implementation project(':a')
                    implementation project(':b')
                    break
                  case 1:
                    implementation 'org.apache.commons:commons-lang3:3.5'
                    implementation project(':b')
                    implementation project(':a')
               }
            }
        """
        file("src/main/java/Client.java") << """import org.apache.commons.lang3.exception.ExceptionUtils;
            public class Client {
                public void doSomething() {
                    ExceptionUtils.rethrow(new RuntimeException(A.m_a()));
                }
            }
        """

        when:
        executer.withArgument('-Porder=0')
        succeeds ':compileJava'

        then:
        noExceptionThrown()

        when: "Order is changed"
        executer.withArgument('-Porder=1')
        fails ':compileJava'

        then:
        failure.assertHasCause('Compilation failed; see the compiler error output for details.')
    }
}
