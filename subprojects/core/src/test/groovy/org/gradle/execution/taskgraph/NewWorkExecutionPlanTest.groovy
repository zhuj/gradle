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

import org.gradle.api.Task
import org.gradle.api.internal.TaskInternal
import org.junit.Assume

class NewWorkExecutionPlanTest extends AbstractTaskExecutionPlanTest {

    def executionPlan = new NewWorkExecutionPlan(workGraph)

    @Override
    void determineExecutionPlan() {
        executionPlan.determineExecutionPlan()
    }

    @Override
    List<TaskInternal> getExecutedTasks() {
        def tasks = []
        def moreTasks = true
        while (moreTasks) {
            def iterator = executionPlan.readyToExecute.iterator()
            moreTasks = iterator.hasNext()
            if (!moreTasks) {
                break
            }
            def nextTask = iterator.next()
            tasks.add(nextTask.task)
            executionPlan.finishedExecuting(nextTask)
        }
        return tasks
    }

    @Override
    void shouldRunAfter(TaskInternal task, List<Task> shouldRunAfterTasks) {
        Assume.assumeTrue("shouldRunAfter is not yet supported", shouldRunAfterTasks.isEmpty())
        super.shouldRunAfter(task, shouldRunAfterTasks)
    }

    @Override
    void finalizedBy(TaskInternal task, List<Task> finalizedByTasks) {
        Assume.assumeTrue("finalizedBy is not yet supported", finalizedByTasks.isEmpty())
        super.finalizedBy(task, finalizedByTasks)
    }
}
