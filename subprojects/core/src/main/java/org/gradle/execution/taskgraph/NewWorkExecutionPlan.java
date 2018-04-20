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

import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Ordering;
import org.gradle.api.CircularReferenceException;
import org.gradle.api.Task;
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
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class NewWorkExecutionPlan {

    private final Multimap<TaskInfo, GraphEdge> outgoingEdges = LinkedHashMultimap.create();
    private final Multimap<TaskInfo, GraphEdge> incomingEdges = LinkedHashMultimap.create();
    private final Set<TaskInfo> nodes = new LinkedHashSet<TaskInfo>();
    private final ArrayList<TaskInfo> readyToExecute = new ArrayList<TaskInfo>();
    private final Map<TaskInfo, Integer> nodeIndex = new HashMap<TaskInfo, Integer>();
    private final Ordering<TaskInfo> nodeOrderingByIndex = Ordering.<Integer>natural().onResultOf(Functions.forMap(nodeIndex));
    private final WorkGraph workGraph;

    public NewWorkExecutionPlan(WorkGraph workGraph) {
        this.workGraph = workGraph;
    }

    public void determineExecutionPlan() {
        Iterable<TaskInfo> entryTasks = discoverAllTasksToExecute(workGraph.getEntryTasks());
        breakCycles();
        int currentIndex = 0;
        Deque<TaskInfo> nodeQueue = new ArrayDeque<TaskInfo>();
        Iterables.addAll(nodeQueue, entryTasks);

        HashSet<TaskInfo> visitingNodes = new HashSet<TaskInfo>();

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
                        node.getDependencySuccessors().descendingIterator(),
                        node.getMustSuccessors().descendingIterator(),
                        node.getShouldSuccessors().descendingIterator()
                    );
                while (descendingIterator.hasNext()) {
                    TaskInfo dependency = descendingIterator.next();
                    if (visitingNodes.contains(dependency)) {
                        onOrderingCycle();
                    }
                    if (!dependency.isIncludeInGraph()) {
                        boolean propagateFailure = node.getDependencySuccessors().contains(dependency);
                        GraphEdge edge = new GraphEdge(dependency, node, propagateFailure);
                        incomingEdges.put(node, edge);
                        outgoingEdges.put(dependency, edge);
                        nodeQueue.addFirst(dependency);
                    }
                }
            } else {
                // Have visited this task's dependencies - add it to the end of the plan
                nodeQueue.removeFirst();
                visitingNodes.remove(node);
                nodes.add(node);
                nodeIndex.put(node, currentIndex++);
                if (isReadyToExecute(node)) {
                    readyToExecute.add(node);
                }
            }
        }
        Collections.sort(readyToExecute, nodeOrderingByIndex);
    }

    private Iterable<TaskInfo> discoverAllTasksToExecute(Iterable<TaskInfo> entryTasks) {
        List<TaskInfo> entryTasksAndFinalizers = new ArrayList<TaskInfo>();
        Set<TaskInfo> visitingNodes = new HashSet<TaskInfo>();
        Deque<TaskInfo> nodeQueue = new ArrayDeque<TaskInfo>();
        Iterables.addAll(nodeQueue, entryTasks);
        List<TaskInfo> finalizers = new ArrayList<TaskInfo>();

        while (!nodeQueue.isEmpty()) {
            TaskInfo node = nodeQueue.getFirst();

            if (node.isIncludeInGraph() || entryTasksAndFinalizers.contains(node)) {
                nodeQueue.removeFirst();
                visitingNodes.remove(node);
                continue;
            }

            boolean alreadyVisited = visitingNodes.contains(node);

            if (!alreadyVisited) {
                visitingNodes.add(node);
                Iterator<TaskInfo> descendingIterator = node.getDependencySuccessors().descendingIterator();
                while (descendingIterator.hasNext()) {
                    TaskInfo dependency = descendingIterator.next();
                    if (!visitingNodes.contains(dependency) && !dependency.isIncludeInGraph()) {
                        nodeQueue.addFirst(dependency);
                    }
                }
            } else {
                // Have visited this task's dependencies - add it to the end of the plan
                nodeQueue.removeFirst();
                visitingNodes.remove(node);
                entryTasksAndFinalizers.add(node);
                for (Iterator<TaskInfo> it = node.getFinalizers().descendingIterator(); it.hasNext(); ) {
                    TaskInfo finalizer = it.next();
                    if (visitingNodes.contains(finalizer) || entryTasksAndFinalizers.contains(finalizer)) {
                        continue;
                    }
                    nodeQueue.addFirst(finalizer);
                    finalizers.add(finalizer);
                }
            }
        }
        entryTasksAndFinalizers.retainAll(ImmutableSet.<TaskInfo>builder().addAll(entryTasks).addAll(finalizers).build());
        return entryTasksAndFinalizers;
    }

    public List<Task> getTasks() {
        return FluentIterable.from(nodes).transform(new Function<TaskInfo, Task>() {
            @Override
            public Task apply(TaskInfo input) {
                return input.getTask();
            }
        }).toList();
    }

    private void breakCycles() {
        Deque<TaskInfo> nodeQueue = new ArrayDeque<TaskInfo>();
        Iterables.addAll(nodeQueue, workGraph.getEntryTasks());

        Set<TaskInfo> visitingNodes = new HashSet<TaskInfo>();
        Deque<TaskInfo> path = new ArrayDeque<TaskInfo>();
        Deque<GraphEdge> walkedShouldRunAfterEdges = new ArrayDeque<GraphEdge>();
        List<TaskInfo> selectedNodes = new LinkedList<TaskInfo>();
        HashMap<TaskInfo, Integer> planBeforeVisiting = new HashMap<TaskInfo, Integer>();

        while (!nodeQueue.isEmpty()) {
            TaskInfo node = nodeQueue.getFirst();

            if (node.isIncludeInGraph() || selectedNodes.contains(node)) {
                nodeQueue.removeFirst();
                visitingNodes.remove(node);
                maybeRemoveProcessedShouldRunAfterEdge(walkedShouldRunAfterEdges, node);
                continue;
            }

            boolean alreadyVisited = visitingNodes.contains(node);

            if (!alreadyVisited) {
                visitingNodes.add(node);
                recordEdgeIfArrivedViaShouldRunAfter(walkedShouldRunAfterEdges, path, node);
                removeShouldRunAfterSuccessorsIfTheyImposeACycle(visitingNodes, node);
                takePlanSnapshotIfCanBeRestoredToCurrentTask(planBeforeVisiting, selectedNodes, node);
                Iterator<TaskInfo> descendingIterator =
                    Iterators.concat(
                        node.getDependencySuccessors().descendingIterator(),
                        node.getMustSuccessors().descendingIterator(),
                        node.getShouldSuccessors().descendingIterator()
                    );
                while (descendingIterator.hasNext()) {
                    TaskInfo dependency = descendingIterator.next();
                    if (visitingNodes.contains(dependency)) {
                        if (!walkedShouldRunAfterEdges.isEmpty()) {
                            //remove the last walked should run after edge and restore state from before walking it
                            GraphEdge toBeRemoved = walkedShouldRunAfterEdges.pop();
                            toBeRemoved.to.removeShouldRunAfterSuccessor(toBeRemoved.from);
                            restorePath(path, toBeRemoved);
                            restoreQueue(nodeQueue, visitingNodes, toBeRemoved);
                            restoreSelectedNodes(planBeforeVisiting, selectedNodes, toBeRemoved);
                            break;
                        } else {
                            onOrderingCycle();
                        }
                    }
                    if (!dependency.isIncludeInGraph()) {
                        nodeQueue.addFirst(dependency);
                    }
                }
                path.push(node);
            } else {
                // Have visited this task's dependencies - add it to the end of the plan
                nodeQueue.removeFirst();
                maybeRemoveProcessedShouldRunAfterEdge(walkedShouldRunAfterEdges, node);
                visitingNodes.remove(node);
                path.pop();
                selectedNodes.add(node);
            }
        }
    }

    private boolean isReadyToExecute(TaskInfo node) {
        return !incomingEdges.containsKey(node);
    }

    private void maybeRemoveProcessedShouldRunAfterEdge(Deque<GraphEdge> walkedShouldRunAfterEdges, TaskInfo taskNode) {
        if (!walkedShouldRunAfterEdges.isEmpty() && walkedShouldRunAfterEdges.peek().from.equals(taskNode)) {
            walkedShouldRunAfterEdges.pop();
        }
    }

    private void recordEdgeIfArrivedViaShouldRunAfter(Deque<GraphEdge> walkedShouldRunAfterEdges, Deque<TaskInfo> path, TaskInfo taskNode) {
        if (!path.isEmpty() && path.peek().getShouldSuccessors().contains(taskNode)) {
            walkedShouldRunAfterEdges.push(new GraphEdge(taskNode, path.peek(), true));
        }
    }

    private void removeShouldRunAfterSuccessorsIfTheyImposeACycle(final Set<TaskInfo> visitingNodes, final TaskInfo taskNode) {
        Iterables.removeIf(taskNode.getShouldSuccessors(), new Predicate<TaskInfo>() {
            public boolean apply(TaskInfo input) {
                return visitingNodes.contains(input);
            }
        });
    }

    private void takePlanSnapshotIfCanBeRestoredToCurrentTask(HashMap<TaskInfo, Integer> planBeforeVisiting, List<TaskInfo> selectedNodes, TaskInfo taskNode) {
        if (taskNode.getShouldSuccessors().size() > 0) {
            planBeforeVisiting.put(taskNode, selectedNodes.size());
        }
    }

    private void restorePath(Deque<TaskInfo> path, GraphEdge toBeRemoved) {
        TaskInfo removedFromPath = null;
        while (!toBeRemoved.to.equals(removedFromPath)) {
            removedFromPath = path.pop();
        }
    }

    private void restoreQueue(Deque<TaskInfo> nodeQueue, Set<TaskInfo> visitingNodes, GraphEdge toBeRemoved) {
        TaskInfo nextInQueue = null;
        while (!toBeRemoved.to.equals(nextInQueue)) {
            nextInQueue = nodeQueue.getFirst();
            visitingNodes.remove(nextInQueue);
            if (!toBeRemoved.to.equals(nextInQueue)) {
                nodeQueue.removeFirst();
            }
        }
    }

    private void restoreSelectedNodes(HashMap<TaskInfo, Integer> planBeforeVisiting, List<TaskInfo> selectedNodes, GraphEdge toBeRemoved) {
        selectedNodes.subList(planBeforeVisiting.get(toBeRemoved.to), selectedNodes.size()).clear();
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
        boolean taskFailure = node.isFailed();
        for (GraphEdge graphEdge : outgoingEdges.get(node)) {
            TaskInfo dependentNode = graphEdge.to;
            incomingEdges.remove(dependentNode, graphEdge);
            if (taskFailure && graphEdge.propagateFailure) {
                nodeSkipped(dependentNode);
            } else if (isReadyToExecute(dependentNode)) {
                readyToExecute.add(dependentNode);
            }
        }
        outgoingEdges.removeAll(node);
        Collections.sort(readyToExecute, nodeOrderingByIndex);
    }

    private void nodeSkipped(TaskInfo node) {
        readyToExecute.remove(node);
        for (GraphEdge graphEdge : outgoingEdges.get(node)) {
            TaskInfo dependentNode = graphEdge.to;
            incomingEdges.remove(dependentNode, graphEdge);
            nodeSkipped(dependentNode);
        }
        for (GraphEdge graphEdge : incomingEdges.get(node)) {
            outgoingEdges.get(graphEdge.from).remove(graphEdge);
        }
        outgoingEdges.removeAll(node);
    }

    public Iterable<TaskInfo> getReadyToExecute() {
        return readyToExecute;
    }

    private static class GraphEdge {
        private final TaskInfo from;
        private final TaskInfo to;
        private final boolean propagateFailure;

        private GraphEdge(TaskInfo from, TaskInfo to, boolean propagateFailure) {
            this.from = from;
            this.to = to;
            this.propagateFailure = propagateFailure;
        }
    }
}
