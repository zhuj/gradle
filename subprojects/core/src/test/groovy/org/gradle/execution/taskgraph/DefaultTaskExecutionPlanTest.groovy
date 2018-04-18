/*
 * Copyright 2012 the original author or authors.
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

import org.gradle.api.Action
import org.gradle.api.BuildCancelledException
import org.gradle.api.Task
import org.gradle.api.internal.GradleInternal
import org.gradle.api.internal.TaskInternal
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.execution.TaskFailureHandler
import org.gradle.initialization.BuildCancellationToken
import org.gradle.internal.resources.ResourceLock
import org.gradle.internal.resources.ResourceLockCoordinationService
import org.gradle.internal.resources.ResourceLockState
import org.gradle.internal.work.WorkerLeaseRegistry
import org.gradle.internal.work.WorkerLeaseService
import spock.lang.Unroll

import static org.gradle.util.WrapUtil.toList

class DefaultTaskExecutionPlanTest extends AbstractTaskExecutionPlanTest {

    DefaultTaskExecutionPlan executionPlan
    ProjectInternal root
    def cancellationHandler = Mock(BuildCancellationToken)
    def workerLeaseService = Mock(WorkerLeaseService)
    def coordinationService = Mock(ResourceLockCoordinationService)
    def workerLease = Mock(WorkerLeaseRegistry.WorkerLease)
    def gradle = Mock(GradleInternal)

    def setup() {
        executionPlan = new DefaultTaskExecutionPlan(workGraph, cancellationHandler, coordinationService, workerLeaseService, Mock(GradleInternal), failureCollector)
        _ * workerLeaseService.getProjectLock(_, _) >> Mock(ResourceLock) {
            _ * isLocked() >> false
            _ * tryLock() >> true
        }
        _ * workerLease.tryLock() >> true
        _ * coordinationService.withStateLock(_) >> { args ->
            args[0].transform(Mock(ResourceLockState))
            return true
        }
    }

    def "stops returning tasks on task execution failure"() {
        RuntimeException exception = new RuntimeException("failure")

        when:
        Task a = task([failure: exception],"a")
        Task b = task("b")
        addToGraphAndPopulate([a, b])

        then:
        executedTasks == [a]

        when:
        executionPlan.awaitCompletion()

        then:
        RuntimeException e = thrown()
        e == exception
    }

    def "stops returning tasks when build is cancelled"() {
        2 * cancellationHandler.cancellationRequested >>> [false, true]
        Task a = task("a")
        Task b = task("b")

        when:
        addToGraphAndPopulate([a, b])

        then:
        executedTasks == [a]

        when:
        executionPlan.awaitCompletion()

        then:
        BuildCancelledException e = thrown()
        e.message == 'Build cancelled.'
    }

    def "stops returning tasks on first task failure when no failure handler provided"() {
        RuntimeException failure = new RuntimeException("failure")
        Task a = task("a", failure: failure)
        Task b = task("b")

        when:
        addToGraphAndPopulate([a, b])

        then:
        executedTasks == [a]

        when:
        executionPlan.awaitCompletion()

        then:
        RuntimeException e = thrown()
        e == failure
    }

    def "stops execution on task failure when failure handler indicates that execution should stop"() {
        RuntimeException failure = new RuntimeException("failure")
        Task a = task("a", failure: failure)
        Task b = task("b")

        addToGraphAndPopulate([a, b])

        TaskFailureHandler handler = Mock()
        RuntimeException wrappedFailure = new RuntimeException("wrapped")
        handler.onTaskFailure(a) >> {
            throw wrappedFailure
        }

        when:
        executionPlan.useFailureHandler(handler)

        then:
        executedTasks == [a]

        when:
        executionPlan.awaitCompletion()

        then:
        RuntimeException e = thrown()
        e == wrappedFailure
    }

    def "continues to return tasks and rethrows failure on completion when failure handler indicates that execution should continue"() {
        RuntimeException failure = new RuntimeException()
        Task a = task("a", failure: failure)
        Task b = task("b")
        addToGraphAndPopulate([a, b])

        when:
        executionPlan.useFailureHandler(createIgnoreTaskFailureHandler(a))

        then:
        executedTasks == [a, b]

        when:
        executionPlan.awaitCompletion()

        then:
        RuntimeException e = thrown()
        e == failure
    }

    @Unroll
    def "continues to return tasks when failure handler does not abort execution and tasks are #orderingRule dependent"() {
        RuntimeException failure = new RuntimeException()
        Task a = task("a", failure: failure)
        Task b = task("b", (orderingRule): [a])
        addToGraphAndPopulate([a, b])

        when:
        executionPlan.useFailureHandler(createIgnoreTaskFailureHandler(a))

        then:
        executedTasks == [a, b]

        when:
        executionPlan.awaitCompletion()

        then:
        RuntimeException e = thrown()
        e == failure

        where:
        orderingRule << ['mustRunAfter', 'shouldRunAfter']
    }

    def "does not attempt to execute tasks whose dependencies failed to execute"() {
        RuntimeException failure = new RuntimeException()
        final Task a = task("a", failure: failure)
        final Task b = task("b", dependsOn: [a])
        final Task c = task("c")
        addToGraphAndPopulate([b, c])

        when:
        executionPlan.useFailureHandler(createIgnoreTaskFailureHandler(a))

        then:
        executedTasks == [a, c]

        when:
        executionPlan.awaitCompletion()

        then:
        RuntimeException e = thrown()
        e == failure
    }

    def "clear removes all tasks"() {
        given:
        _ * coordinationService.withStateLock(_) >> { args ->
            args[0].transform(Mock(ResourceLockState))
            return true
        }
        Task a = task("a")

        when:
        addToGraphAndPopulate(toList(a))
        executionPlan.clear()

        then:
        executionPlan.tasks == []
        executedTasks == []
    }

    def "can add additional tasks after execution and clear"() {
        given:
        _ * coordinationService.withStateLock(_) >> { args ->
            args[0].transform(Mock(ResourceLockState))
            return true
        }
        Task a = task("a")
        Task b = task("b")

        when:
        addToGraphAndPopulate([a])

        then:
        executes(a)

        when:
        executionPlan.clear()
        addToGraphAndPopulate([b])

        then:
        executes(b)
    }

    private TaskFailureHandler createIgnoreTaskFailureHandler(Task task) {
        Mock(TaskFailureHandler) {
            onTaskFailure(task) >> {}
        }
    }

    @Override
    void determineExecutionPlan() {
        executionPlan.determineExecutionPlan()
    }

    @Override
    List<TaskInternal> getExecutedTasks() {
        List<TaskInternal> tasks = []
        def moreTasks = true
        while (moreTasks) {
            moreTasks = executionPlan.executeWithTask(workerLease, new Action<TaskInternal>() {
                @Override
                void execute(TaskInternal task) {
                    tasks << task
                }
            })
        }
        return tasks
    }
}

