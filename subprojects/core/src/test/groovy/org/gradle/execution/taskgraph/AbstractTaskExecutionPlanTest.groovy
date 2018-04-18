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

package org.gradle.execution.taskgraph

import org.gradle.api.CircularReferenceException
import org.gradle.api.Task
import org.gradle.api.internal.TaskInputsInternal
import org.gradle.api.internal.TaskInternal
import org.gradle.api.internal.TaskOutputsInternal
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.api.internal.tasks.TaskDestroyablesInternal
import org.gradle.api.internal.tasks.TaskLocalStateInternal
import org.gradle.api.internal.tasks.TaskStateInternal
import org.gradle.api.specs.Spec
import org.gradle.api.tasks.TaskDependency
import org.gradle.api.tasks.TaskDestroyables
import org.gradle.test.fixtures.AbstractProjectBuilderSpec
import org.gradle.util.Path
import org.gradle.util.TextUtil

import static org.gradle.util.TestUtil.createRootProject
import static org.gradle.util.WrapUtil.toList

abstract class AbstractTaskExecutionPlanTest extends AbstractProjectBuilderSpec {
    ProjectInternal root

    def failureCollector = new TaskFailureCollector()
    WorkGraph workGraph = new WorkGraph(failureCollector)

    def setup() {
        root = createRootProject(temporaryFolder.testDirectory)
    }

    def "schedules tasks in dependency order"() {
        given:
        Task a = task("a")
        Task b = task("b", dependsOn: [a])
        Task c = task("c", dependsOn: [b, a])
        Task d = task("d", dependsOn: [c])

        when:
        addToGraphAndPopulate([d])

        then:
        executes(a, b, c, d)
    }

    def "schedules task dependencies in name order when there are no dependencies between them"() {
        given:
        Task a = task("a")
        Task b = task("b")
        Task c = task("c")
        Task d = task("d", dependsOn: [b, a, c])

        when:
        addToGraphAndPopulate([d])

        then:
        executes(a, b, c, d)
    }

    def "schedules a single batch of tasks in name order"() {
        given:
        Task a = task("a")
        Task b = task("b")
        Task c = task("c")

        when:
        addToGraphAndPopulate(toList(b, c, a))

        then:
        executes(a, b, c)
    }

    def "schedules separately added tasks in order added"() {
        given:
        Task a = task("a")
        Task b = task("b")
        Task c = task("c")
        Task d = task("d")

        when:
        workGraph.addToTaskGraph(toList(c, b))
        workGraph.addToTaskGraph(toList(d, a))
        determineExecutionPlan()

        then:
        executes(b, c, a, d)
    }

    def "common tasks in separate batches are schedules only once"() {
        Task a = task("a")
        Task b = task("b")
        Task c = task("c", dependsOn: [a, b])
        Task d = task("d")
        Task e = task("e", dependsOn: [b, d])

        when:
        workGraph.addToTaskGraph(toList(c))
        workGraph.addToTaskGraph(toList(e))
        executionPlan.determineExecutionPlan()

        then:
        executes(a, b, c, d, e)
    }

    def "all dependencies scheduled when adding tasks"() {
        Task a = task("a")
        Task b = task("b", dependsOn: [a])
        Task c = task("c", dependsOn: [b, a])
        Task d = task("d", dependsOn: [c])

        when:
        addToGraphAndPopulate(toList(d))

        then:
        executes(a, b, c, d)
    }

    def "cannot add task with circular reference"() {
        Task a = createTask("a")
        Task b = task("b", dependsOn: [a])
        Task c = task("c", dependsOn: [b])
        Task d = task("d")
        relationships(a, dependsOn: [c, d])

        when:
        addToGraphAndPopulate([c])

        then:
        def e = thrown CircularReferenceException
        e.message == TextUtil.toPlatformLineSeparators("""Circular dependency between the following tasks:
:a
\\--- :c
     \\--- :b
          \\--- :a (*)

(*) - details omitted (listed previously)
""")
    }

    def "does not build graph for or execute filtered tasks"() {
        given:
        Task a = filteredTask("a")
        Task b = task("b")
        Spec<Task> filter = Mock()

        and:
        filter.isSatisfiedBy(_) >> { Task t -> t != a }

        when:
        workGraph.useFilter(filter)
        addToGraphAndPopulate([a, b])

        then:
        executes(b)
        filtered(a)
    }

    def "does not build graph for or execute filtered dependencies"() {
        given:
        Task a = filteredTask("a")
        Task b = task("b")
        Task c = task("c", dependsOn: [a, b])
        Spec<Task> filter = Mock()

        and:
        filter.isSatisfiedBy(_) >> { Task t -> t != a }

        when:
        workGraph.useFilter(filter)
        addToGraphAndPopulate([c])

        then:
        executes(b, c)
        filtered(a)
    }

    def "will execute a task whose dependencies have been filtered"() {
        given:
        Task b = filteredTask("b")
        Task c = task("c", dependsOn: [b])
        Spec<Task> filter = Mock()

        and:
        filter.isSatisfiedBy(_) >> { Task t -> t != b }

        when:
        workGraph.useFilter(filter)
        addToGraphAndPopulate([c])

        then:
        executes(c)
        filtered(b)
    }

    abstract List<TaskInternal> getExecutedTasks()
    abstract void determineExecutionPlan()

    void filtered(Task... expectedTasks) {
        assert workGraph.filteredTasks == expectedTasks as Set
    }

    void executes(Task... expectedTasks) {
        assert executedTasks == expectedTasks as List
    }

    void addToGraphAndPopulate(List tasks) {
        workGraph.addToTaskGraph(tasks)
        determineExecutionPlan()
    }

    TaskInternal task(final String name) {
        task([:], name)
    }

    TaskInternal task(Map options, final String name) {
        def task = createTask(name)
        relationships(options, task)
        if (options.failure) {
            failure(task, options.failure)
        }
        task.getDidWork() >> (options.containsKey('didWork') ? options.didWork : true)
        task.getOutputs() >> emptyTaskOutputs()
        task.getDestroyables() >> emptyTaskDestroys()
        task.getLocalState() >> emptyTaskLocalState()
        task.getInputs() >> emptyTaskInputs()
        return task
    }

    TaskInternal filteredTask(final String name) {
        def task = createTask(name)
        task.getTaskDependencies() >> brokenDependencies()
        task.getMustRunAfter() >> brokenDependencies()
        task.getShouldRunAfter() >> brokenDependencies()
        task.getFinalizedBy() >> taskDependencyResolvingTo(task, [])
        return task
    }

    private TaskDependency brokenDependencies() {
        Mock(TaskDependency) {
            0 * getDependencies(_)
        }
    }

    void relationships(Map options, TaskInternal task) {
        dependsOn(task, options.dependsOn ?: [])
        mustRunAfter(task, options.mustRunAfter ?: [])
        shouldRunAfter(task, options.shouldRunAfter ?: [])
        finalizedBy(task, options.finalizedBy ?: [])
    }

    void dependsOn(TaskInternal task, List<Task> dependsOnTasks) {
        task.getTaskDependencies() >> taskDependencyResolvingTo(task, dependsOnTasks)
    }

    void mustRunAfter(TaskInternal task, List<Task> mustRunAfterTasks) {
        task.getMustRunAfter() >> taskDependencyResolvingTo(task, mustRunAfterTasks)
    }

    void finalizedBy(TaskInternal task, List<Task> finalizedByTasks) {
        task.getFinalizedBy() >> taskDependencyResolvingTo(task, finalizedByTasks)
    }

    void shouldRunAfter(TaskInternal task, List<Task> shouldRunAfterTasks) {
        task.getShouldRunAfter() >> taskDependencyResolvingTo(task, shouldRunAfterTasks)
    }

    private TaskDependency taskDependencyResolvingTo(TaskInternal task, List<Task> tasks) {
        Mock(TaskDependency) {
            getDependencies(task) >> tasks
        }
    }

    void failure(TaskInternal task, final RuntimeException failure) {
        task.state.getFailure() >> failure
        task.state.rethrowFailure() >> { throw failure }
    }

    TaskInternal createTask(final String name) {
        TaskInternal task = Mock()
        TaskStateInternal state = Mock()
        task.getProject() >> root
        task.name >> name
        task.path >> ':' + name
        task.identityPath >> Path.path(':' + name)
        task.state >> state
        task.toString() >> "task $name"
        task.compareTo(_ as TaskInternal) >> { TaskInternal taskInternal ->
            return name.compareTo(taskInternal.getName())
        }
        task.getOutputs() >> emptyTaskOutputs()
        task.getDestroyables() >> emptyTaskDestroys()
        task.getLocalState() >> emptyTaskLocalState()
        task.getInputs() >> emptyTaskInputs()
        return task
    }

    private TaskOutputsInternal emptyTaskOutputs() {
        Stub(TaskOutputsInternal)
    }

    private TaskDestroyables emptyTaskDestroys() {
        Stub(TaskDestroyablesInternal)
    }

    private TaskLocalStateInternal emptyTaskLocalState() {
        Stub(TaskLocalStateInternal)
    }

    private TaskInputsInternal emptyTaskInputs() {
        Stub(TaskInputsInternal)
    }
}
