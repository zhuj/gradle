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

package org.gradle.execution.taskgraph;

import com.google.common.base.Functions;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Ordering;
import org.gradle.api.CircularReferenceException;
import org.gradle.internal.graph.CachingDirectedGraphWalker;
import org.gradle.internal.graph.DirectedGraph;
import org.gradle.internal.graph.DirectedGraphRenderer;
import org.gradle.internal.graph.GraphNodeRenderer;
import org.gradle.internal.logging.text.StyledTextOutput;

import java.io.StringWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class NewWorkExecutionPlan {

    private final Multimap<TaskInfo, GraphEdge> incomingEdges = LinkedHashMultimap.create();
    private final Multimap <TaskInfo, GraphEdge> outgoingEdges = LinkedHashMultimap.create();
    private final Set<TaskInfo> nodes = new HashSet<TaskInfo>();
    private final ArrayList<TaskInfo> readyToExecute = new ArrayList<TaskInfo>();
    private final Map<TaskInfo, Integer> nodeIndex = new HashMap<TaskInfo, Integer>();
    private final Ordering<TaskInfo> nodeOrderingByIndex = Ordering.<Integer>natural().onResultOf(Functions.forMap(nodeIndex));
    private final WorkGraph workGraph;

    public NewWorkExecutionPlan(WorkGraph workGraph) {
        this.workGraph = workGraph;
    }

    public void determineExecutionPlan() {
        int currentIndex = 0;
        Deque<TaskInfo> nodeQueue = new ArrayDeque<TaskInfo>();
        Iterables.addAll(nodeQueue, workGraph.getEntryTasks());

        HashSet<TaskInfo> visitingNodes = new HashSet<TaskInfo>();
        Deque<TaskInfo> path = new ArrayDeque<TaskInfo>();

        while (!nodeQueue.isEmpty()) {
            TaskInfo node = nodeQueue.getFirst();

            if (node.isIncludeInGraph() || nodes.contains(node)) {
                nodeQueue.removeFirst();
                visitingNodes.remove(node);
                continue;
            }

            boolean alreadyVisited = visitingNodes.contains(node);

            if (!alreadyVisited) {
                visitingNodes.add(node);
                Iterator<TaskInfo> descendingIterator =
                    Iterators.concat(
                        new ArrayDeque<TaskInfo>(node.getDependencySuccessors()).descendingIterator(),
                        new ArrayDeque<TaskInfo>(node.getMustSuccessors()).descendingIterator()
                    );
                while (descendingIterator.hasNext()) {
                    TaskInfo dependency = descendingIterator.next();
                    if (visitingNodes.contains(dependency)) {
                        onOrderingCycle();
                    }
                    if (!dependency.isIncludeInGraph()) {
                        GraphEdge edge = new GraphEdge(node, dependency);
                        outgoingEdges.put(node, edge);
                        incomingEdges.put(dependency, edge);
                        nodeQueue.addFirst(dependency);
                    }
                }
                path.push(node);
            } else {
                // Have visited this task's dependencies - add it to the end of the plan
                nodeQueue.removeFirst();
                visitingNodes.remove(node);
                path.pop();
                nodes.add(node);
                nodeIndex.put(node, currentIndex++);
                if (isReadyToExecute(node)) {
                    readyToExecute.add(node);
                }
            }
        }
        Collections.sort(readyToExecute, nodeOrderingByIndex);
    }

    private boolean isReadyToExecute(TaskInfo node) {
        return !outgoingEdges.containsKey(node);
    }

    private void onOrderingCycle() {
        CachingDirectedGraphWalker<TaskInfo, Void> graphWalker = new CachingDirectedGraphWalker<TaskInfo, Void>(new DirectedGraph<TaskInfo, Void>() {
            @Override
            public void getNodeValues(TaskInfo node, Collection<? super Void> values, Collection<? super TaskInfo> connectedNodes) {
                connectedNodes.addAll(node.getDependencySuccessors());
                connectedNodes.addAll(node.getMustSuccessors());
            }
        });
        graphWalker.add(workGraph.getEntryTasks());
        final List<TaskInfo> firstCycle = new ArrayList<TaskInfo>(graphWalker.findCycles().get(0));
        Collections.sort(firstCycle);

        DirectedGraphRenderer<TaskInfo> graphRenderer = new DirectedGraphRenderer<TaskInfo>(new GraphNodeRenderer<TaskInfo>() {
            @Override
            public void renderTo(TaskInfo node, StyledTextOutput output) {
                output.withStyle(StyledTextOutput.Style.Identifier).text(node.getTask().getIdentityPath());
            }
        }, new DirectedGraph<TaskInfo, Object>() {
            @Override
            public void getNodeValues(TaskInfo node, Collection<? super Object> values, Collection<? super TaskInfo> connectedNodes) {
                for (TaskInfo dependency : firstCycle) {
                    if (node.getDependencySuccessors().contains(dependency) || node.getMustSuccessors().contains(dependency)) {
                        connectedNodes.add(dependency);
                    }
                }
            }
        });
        StringWriter writer = new StringWriter();
        graphRenderer.renderTo(firstCycle.get(0), writer);
        throw new CircularReferenceException(String.format("Circular dependency between the following tasks:%n%s", writer.toString()));
    }

    public void finishedExecuting(TaskInfo node) {
        readyToExecute.remove(node);
        for (GraphEdge graphEdge : incomingEdges.get(node)) {
            TaskInfo dependentNode = graphEdge.from;
            outgoingEdges.remove(dependentNode, graphEdge);
            if (isReadyToExecute(dependentNode)) {
                readyToExecute.add(dependentNode);
            }
        }
        incomingEdges.removeAll(node);
        Collections.sort(readyToExecute, nodeOrderingByIndex);
    }

    public Iterable<TaskInfo> getReadyToExecute() {
        return readyToExecute;
    }

    private static class GraphEdge {
        private final TaskInfo from;
        private final TaskInfo to;

        private GraphEdge(TaskInfo from, TaskInfo to) {
            this.from = from;
            this.to = to;
        }
    }
}
