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
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Ordering;
import org.gradle.api.Action;
import org.gradle.api.CircularReferenceException;
import org.gradle.api.NonNullApi;
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
import java.util.Queue;
import java.util.Set;

@NonNullApi
public class NewWorkExecutionPlan {

    private final Collection<TaskInfo> nodesLeft;
    private final Multimap<TaskInfo, TaskInfo> haveToRunSuccessors;
    private final Ordering<TaskInfo> nodeOrdering;
    private final Set<TaskInfo> requiredNodes = new HashSet<TaskInfo>();
    private boolean ignoreFailures = false;
    private final Multimap<TaskInfo, GraphEdge> outgoingEdges;
    private final Multimap<TaskInfo, GraphEdge> incomingEdges;
    private final List<TaskInfo> readyToExecute;

    public static NewWorkExecutionPlan determineExecutionPlan(final Collection<TaskInfo> entryTasks) {
        final Multimap<TaskInfo, TaskInfo> finalizerEdges = LinkedListMultimap.create();
        Collection<TaskInfo> allTasksToExecute = discoverAllTasksToExecute(entryTasks, finalizerEdges);
        Iterable<TaskInfo> requestedTasks = FluentIterable.from(allTasksToExecute).filter(new Predicate<TaskInfo>() {
            @Override
            public boolean apply(TaskInfo node) {
                return finalizerEdges.containsKey(node) || entryTasks.contains(node);
            }
        });
        addShouldRunAfterEdgesForFinalizers(finalizerEdges);
        Multimap<TaskInfo, TaskInfo> haveToRunSuccessors = HashMultimap.create();
        determineHaveToRunSuccessors(finalizerEdges, haveToRunSuccessors);
        breakCycles(entryTasks);

        Multimap<TaskInfo, GraphEdge> outgoingEdges = LinkedHashMultimap.create();
        Multimap<TaskInfo, GraphEdge> incomingEdges = LinkedHashMultimap.create();
        List<TaskInfo> readyToExecute = new ArrayList<TaskInfo>();
        Map<TaskInfo, Integer> nodeIndex = new HashMap<TaskInfo, Integer>();
        determineWorkExecutionGraph(requestedTasks, outgoingEdges, incomingEdges, readyToExecute, nodeIndex);
        Ordering<TaskInfo> nodeOrderingByIndex = Ordering.<Integer>natural().onResultOf(Functions.forMap(nodeIndex));
        return new NewWorkExecutionPlan(outgoingEdges, incomingEdges, readyToExecute, haveToRunSuccessors, nodeOrderingByIndex, allTasksToExecute);
    }

    public NewWorkExecutionPlan(Multimap<TaskInfo, GraphEdge> outgoingEdges, Multimap<TaskInfo, GraphEdge> incomingEdges, List<TaskInfo> readyToExecute, Multimap<TaskInfo, TaskInfo> haveToRunSuccessors, Ordering<TaskInfo> nodeOrdering, Collection<TaskInfo> nodesLeft) {
        this.outgoingEdges = outgoingEdges;
        this.incomingEdges = incomingEdges;
        this.readyToExecute = readyToExecute;
        this.haveToRunSuccessors = haveToRunSuccessors;
        this.nodeOrdering = nodeOrdering;
        this.nodesLeft = nodesLeft;
        Collections.sort(readyToExecute, nodeOrdering);
    }

    private static void determineWorkExecutionGraph(Iterable<TaskInfo> requestedTasks, Multimap<TaskInfo, GraphEdge> outgoingEdges, Multimap<TaskInfo, GraphEdge> incomingEdges, List<TaskInfo> readyToExecute, Map<TaskInfo, Integer> nodeIndex) {
        int currentIndex = 0;
        Deque<TaskInfo> nodeQueue = new ArrayDeque<TaskInfo>();
        Iterables.addAll(nodeQueue, requestedTasks);

        HashSet<TaskInfo> visitingNodes = new HashSet<TaskInfo>();
        HashSet<TaskInfo> visitedNodes = new HashSet<TaskInfo>();

        while (!nodeQueue.isEmpty()) {
            TaskInfo node = nodeQueue.getFirst();

            if (node.isIncludeInGraph() || visitedNodes.contains(node)) {
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
                        onOrderingCycle(requestedTasks);
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
                visitedNodes.add(node);
                nodeIndex.put(node, currentIndex++);
                if (isReadyToExecute(node, incomingEdges)) {
                    readyToExecute.add(node);
                }
            }
        }
    }

    private static void addShouldRunAfterEdgesForFinalizers(Multimap<TaskInfo, TaskInfo> finalizerEdges) {
        CachingDirectedGraphWalker<TaskInfo, TaskInfo> graphWalker = new CachingDirectedGraphWalker<TaskInfo, TaskInfo>(new DirectedGraph<TaskInfo, TaskInfo>() {
            @Override
            public void getNodeValues(TaskInfo node, Collection<? super TaskInfo> values, Collection<? super TaskInfo> connectedNodes) {
                connectedNodes.addAll(node.getDependencySuccessors());
                values.add(node);
            }
        });
        for (TaskInfo finalizer : finalizerEdges.keySet()) {
            graphWalker.add(finalizer);
            Set<TaskInfo> dependencies = graphWalker.findValues();
            for (TaskInfo finalized : finalizerEdges.get(finalizer)) {
                for (TaskInfo dependency : dependencies) {
                    dependency.addShouldSuccessor(finalized);
                }
            }
        }
    }

    private static void determineHaveToRunSuccessors(Multimap<TaskInfo, TaskInfo> finalizerEdges, Multimap<TaskInfo, TaskInfo> haveToRunSuccessors) {
        CachingDirectedGraphWalker<TaskInfo, TaskInfo> graphWalker = new CachingDirectedGraphWalker<TaskInfo, TaskInfo>(new DirectedGraph<TaskInfo, TaskInfo>() {
            @Override
            public void getNodeValues(TaskInfo node, Collection<? super TaskInfo> values, Collection<? super TaskInfo> connectedNodes) {
                connectedNodes.addAll(node.getDependencySuccessors());
                connectedNodes.addAll(node.getFinalizers());
                values.add(node);
            }
        });
        for (TaskInfo finalizer : finalizerEdges.keySet()) {
            graphWalker.add(finalizer);
            Set<TaskInfo> dependencies = graphWalker.findValues();
            for (TaskInfo finalized : finalizerEdges.get(finalizer)) {
                for (TaskInfo dependency : dependencies) {
                    haveToRunSuccessors.put(finalized, dependency);
                }
            }
        }
    }

    private static Collection<TaskInfo> discoverAllTasksToExecute(Collection<TaskInfo> entryTasks, Multimap<TaskInfo, TaskInfo> finalizerEdges) {
        Set<TaskInfo> visitingNodes = new HashSet<TaskInfo>();
        Deque<TaskInfo> nodeQueue = new ArrayDeque<TaskInfo>();
        Set<TaskInfo> allTasksToExecute = new LinkedHashSet<TaskInfo>();
        Iterables.addAll(nodeQueue, entryTasks);

        while (!nodeQueue.isEmpty()) {
            TaskInfo node = nodeQueue.getFirst();

            if (node.isIncludeInGraph() || allTasksToExecute.contains(node)) {
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
                allTasksToExecute.add(node);
                for (Iterator<TaskInfo> it = node.getFinalizers().descendingIterator(); it.hasNext(); ) {
                    TaskInfo finalizer = it.next();
                    finalizerEdges.put(finalizer, node);
                    if (visitingNodes.contains(finalizer) || allTasksToExecute.contains(finalizer)) {
                        continue;
                    }
                    nodeQueue.addFirst(finalizer);
                }
            }
        }
        return allTasksToExecute;
    }

    public List<Task> getTasks() {
        throw new UnsupportedOperationException();
    }

    private static void breakCycles(Iterable<TaskInfo> entryTasks) {
        Deque<TaskInfo> nodeQueue = new ArrayDeque<TaskInfo>();
        Iterables.addAll(nodeQueue, entryTasks);

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
                            onOrderingCycle(entryTasks);
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

    private static boolean isReadyToExecute(TaskInfo node, Multimap<TaskInfo, GraphEdge> incomingEdges) {
        return !incomingEdges.containsKey(node);
    }

    private static void maybeRemoveProcessedShouldRunAfterEdge(Deque<GraphEdge> walkedShouldRunAfterEdges, TaskInfo taskNode) {
        if (!walkedShouldRunAfterEdges.isEmpty() && walkedShouldRunAfterEdges.peek().from.equals(taskNode)) {
            walkedShouldRunAfterEdges.pop();
        }
    }

    private static void recordEdgeIfArrivedViaShouldRunAfter(Deque<GraphEdge> walkedShouldRunAfterEdges, Deque<TaskInfo> path, TaskInfo taskNode) {
        if (!path.isEmpty() && path.peek().getShouldSuccessors().contains(taskNode)) {
            walkedShouldRunAfterEdges.push(new GraphEdge(taskNode, path.peek(), true));
        }
    }

    private static void removeShouldRunAfterSuccessorsIfTheyImposeACycle(final Set<TaskInfo> visitingNodes, final TaskInfo taskNode) {
        Iterables.removeIf(taskNode.getShouldSuccessors(), new Predicate<TaskInfo>() {
            public boolean apply(TaskInfo input) {
                return visitingNodes.contains(input);
            }
        });
    }

    private static void takePlanSnapshotIfCanBeRestoredToCurrentTask(HashMap<TaskInfo, Integer> planBeforeVisiting, List<TaskInfo> selectedNodes, TaskInfo taskNode) {
        if (taskNode.getShouldSuccessors().size() > 0) {
            planBeforeVisiting.put(taskNode, selectedNodes.size());
        }
    }

    private static void restorePath(Deque<TaskInfo> path, GraphEdge toBeRemoved) {
        TaskInfo removedFromPath = null;
        while (!toBeRemoved.to.equals(removedFromPath)) {
            removedFromPath = path.pop();
        }
    }

    private static void restoreQueue(Deque<TaskInfo> nodeQueue, Set<TaskInfo> visitingNodes, GraphEdge toBeRemoved) {
        TaskInfo nextInQueue = null;
        while (!toBeRemoved.to.equals(nextInQueue)) {
            nextInQueue = nodeQueue.getFirst();
            visitingNodes.remove(nextInQueue);
            if (!toBeRemoved.to.equals(nextInQueue)) {
                nodeQueue.removeFirst();
            }
        }
    }

    private static void restoreSelectedNodes(HashMap<TaskInfo, Integer> planBeforeVisiting, List<TaskInfo> selectedNodes, GraphEdge toBeRemoved) {
        selectedNodes.subList(planBeforeVisiting.get(toBeRemoved.to), selectedNodes.size()).clear();
    }

    private static void onOrderingCycle(Iterable<TaskInfo> entryTasks) {
        CachingDirectedGraphWalker<TaskInfo, Void> graphWalker = new CachingDirectedGraphWalker<TaskInfo, Void>(new DirectedGraph<TaskInfo, Void>() {
            @Override
            public void getNodeValues(TaskInfo node, Collection<? super Void> values, Collection<? super TaskInfo> connectedNodes) {
                connectedNodes.addAll(node.getDependencySuccessors());
                connectedNodes.addAll(node.getMustSuccessors());
            }
        });
        graphWalker.add(entryTasks);
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
        final boolean taskFailure = node.isFailed();
        requiredNodes.addAll(haveToRunSuccessors.get(node));
        if (taskFailure && !ignoreFailures) {
            abort();
        }
        removeNode(node, new Action<GraphEdge>() {
            @Override
            public void execute(GraphEdge graphEdge) {
                TaskInfo dependentNode = graphEdge.to;
                if (taskFailure && graphEdge.propagateFailure) {
                    nodeSkipped(dependentNode);
                }
            }
        });
        Collections.sort(readyToExecute, nodeOrdering);
    }

    private void nodeSkipped(TaskInfo initialNode) {
        final Queue<TaskInfo> nodesToSkip = new ArrayDeque<TaskInfo>();
        nodesToSkip.add(initialNode);
        while (!nodesToSkip.isEmpty()) {
            TaskInfo node = nodesToSkip.remove();
            removeNode(node, new Action<GraphEdge>() {
                @Override
                public void execute(GraphEdge graphEdge) {
                    TaskInfo dependentNode = graphEdge.to;
                    if (graphEdge.propagateFailure) {
                        nodesToSkip.add(dependentNode);
                    }
                }
            });
        }
    }

    private void abort() {
        for (TaskInfo node : new ArrayList<TaskInfo>(nodesLeft)) {
            if (!requiredNodes.contains(node)) {
                removeNode(node, new Action<GraphEdge>() {
                    @Override
                    public void execute(GraphEdge graphEdge) {
                    }
                });
            }
        }
    }

    public void ignoreFailures() {
        this.ignoreFailures = true;
    }

    private void removeNode(TaskInfo node, Action<? super GraphEdge> onRemovedEdge) {
        for (GraphEdge graphEdge : outgoingEdges.get(node)) {
            TaskInfo dependentNode = graphEdge.to;
            incomingEdges.remove(dependentNode, graphEdge);
            if (isReadyToExecute(dependentNode, incomingEdges)) {
                readyToExecute.add(dependentNode);
            }
            onRemovedEdge.execute(graphEdge);
        }
        outgoingEdges.removeAll(node);
        for (GraphEdge graphEdge : incomingEdges.get(node)) {
            TaskInfo dependency = graphEdge.from;
            outgoingEdges.remove(dependency, graphEdge);
        }
        incomingEdges.removeAll(node);
        nodesLeft.remove(node);
        readyToExecute.remove(node);
    }

    public Iterable<TaskInfo> getReadyToExecute() {
        Preconditions.checkState(!readyToExecute.isEmpty() || nodesLeft.isEmpty(), "Nothing ready to execute but tasks left! Nodes: %s", nodesLeft);
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
