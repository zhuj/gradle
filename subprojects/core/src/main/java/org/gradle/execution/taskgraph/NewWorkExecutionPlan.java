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
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import com.google.common.collect.Iterables;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import org.gradle.api.Action;
import org.gradle.api.CircularReferenceException;
import org.gradle.api.NonNullApi;
import org.gradle.api.Task;
import org.gradle.internal.Actions;
import org.gradle.internal.graph.CachingDirectedGraphWalker;
import org.gradle.internal.graph.DirectedGraph;
import org.gradle.internal.graph.DirectedGraphRenderer;
import org.gradle.internal.graph.GraphNodeRenderer;
import org.gradle.internal.logging.text.StyledTextOutput;

import javax.annotation.Nullable;
import java.io.StringWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;

@NonNullApi
public class NewWorkExecutionPlan {

    private static final Comparator<? super Edge> EDGE_TYPE_PRECEDENCE = new Comparator<Edge>() {
        @Override
        public int compare(Edge edge1, Edge edge2) {
            return edge1.getType().compareTo(edge2.getType());
        }
    };

    private final Set<TaskInfo> requiredNodes = Sets.newHashSet();
    private final Deque<TaskInfo> readyToExecute;
    private boolean ignoreFailures = false;
    private final Graph graph;

    public static NewWorkExecutionPlan determineExecutionPlan(final Collection<TaskInfo> entryTasks) {
        final Graph graph = new Graph();

        // Collect accessible nodes from entry tasks
        Queue<TaskInfo> queue = new ArrayDeque<TaskInfo>();
        Iterables.addAll(queue, entryTasks);
        Set<TaskInfo> allNodes = Sets.newHashSet();
        while (true) {
            TaskInfo node = queue.poll();
            if (node == null) {
                break;
            }
            if (!node.isRequired()) {
                // TODO: WHy doesn't this work?
                //continue;
            }
            if (!allNodes.add(node)) {
                continue;
            }
            for (TaskInfo finalizer : node.getFinalizers()) {
                graph.addEdge(new Edge(node, finalizer, EdgeType.FINALIZER));
                queue.add(finalizer);
            }
            for (TaskInfo dependency : node.getDependencySuccessors()) {
                // Reversed!
                graph.addEdge(new Edge(dependency, node, EdgeType.DEPENDENT));
                queue.add(dependency);
            }
            for (TaskInfo mustSuccessor : node.getMustSuccessors()) {
                if (mustSuccessor.getFinalizers().contains(node)) {
                    // Ignore must-run-afters added for finalizers
                    continue;
                }
                graph.addEdge(new Edge(mustSuccessor, node, EdgeType.HARD_ORDERING));
                queue.add(mustSuccessor);
            }
            for (TaskInfo shouldSuccessor : node.getShouldSuccessors()) {
                graph.addEdge(new Edge(shouldSuccessor, node, EdgeType.SOFT_ORDERING));
                queue.add(shouldSuccessor);
            }
        }

        // Find live nodes
        Set<TaskInfo> liveNodes = graph.findLiveNodesAccessibleFrom(entryTasks);

        // Discard non-live nodes
        graph.retainAll(liveNodes);

        // Add soft ordering between entry tasks
        Iterator<TaskInfo> iEntryTask = entryTasks.iterator();
        if (iEntryTask.hasNext()) {
            TaskInfo previousTask = iEntryTask.next();
            while (iEntryTask.hasNext()) {
                TaskInfo task = iEntryTask.next();
                if (graph.findEdgeBetween(previousTask, task) == null) {
                    graph.addEdge(new Edge(previousTask, task, EdgeType.SOFT_ORDERING));
                }
                previousTask = task;
            }
        }

        // Add soft ordering for finalizers
        CachingDirectedGraphWalker<TaskInfo, TaskInfo> dependencyWalker = new CachingDirectedGraphWalker<TaskInfo, TaskInfo>(new DirectedGraph<TaskInfo, TaskInfo>() {
            @Override
            public void getNodeValues(TaskInfo node, Collection<? super TaskInfo> values, Collection<? super TaskInfo> connectedNodes) {
                connectedNodes.addAll(graph.getIncoming(node, EdgeType.DEPENDENT));
                values.add(node);
            }
        });
        for (Edge edge : graph.getEdges()) {
            if (edge.getType() != EdgeType.FINALIZER) {
                continue;
            }

            TaskInfo finalized = edge.getFrom();
            TaskInfo finalizer = edge.getTo();
            dependencyWalker.add(finalizer);
            Set<TaskInfo> dependencies = dependencyWalker.findValues();
            for (TaskInfo dependency : dependencies) {
                if (graph.findEdgeBetween(finalized, dependency) == null) {
                    graph.addEdge(new Edge(finalized, dependency, EdgeType.SOFT_ORDERING));
                }
            }
        }

//        final Multimap<TaskInfo, TaskInfo> finalizerEdges = LinkedListMultimap.create();
//        Collection<TaskInfo> allTasksToExecute = discoverAllTasksToExecute(entryTasks, finalizerEdges);
//        Iterable<TaskInfo> requestedTasks = FluentIterable.from(allTasksToExecute).filter(new Predicate<TaskInfo>() {
//            @Override
//            public boolean apply(TaskInfo node) {
//                return finalizerEdges.containsKey(node) || entryTasks.contains(node);
//            }
//        });
//        addShouldRunAfterEdgesForFinalizers(finalizerEdges);
//        addShouldRunAfterEdgesBetweenEntryTasks(entryTasks);

        // Multimap<TaskInfo, TaskInfo> haveToRunSuccessors = HashMultimap.create();
        // determineHaveToRunSuccessors(finalizerEdges, haveToRunSuccessors);

//        Multimap<TaskInfo, GraphEdge> outgoingEdges = LinkedHashMultimap.create();
//        Multimap<TaskInfo, GraphEdge> incomingEdges = LinkedHashMultimap.create();

        breakCycles(graph, entryTasks);

        // Deque<TaskInfo> readyToExecute = new ArrayDeque<TaskInfo>();
        // determineWorkExecutionGraph(requestedTasks, outgoingEdges, incomingEdges, readyToExecute);

        // Determine nodes ready to execute
        Set<TaskInfo> readyToExecute = new LinkedHashSet<TaskInfo>(entryTasks);
        readyToExecute.addAll(graph.outgoingEdges.keySet());
        readyToExecute.removeAll(graph.incomingEdges.keySet());
        System.out.println(">> Nodes to execute: " + Joiner.on(", ").join(readyToExecute));

        return new NewWorkExecutionPlan(graph, new ArrayDeque<TaskInfo>(readyToExecute));
    }

    private NewWorkExecutionPlan(Graph graph, Deque<TaskInfo> readyToExecute) {
        this.graph = graph;
        this.readyToExecute = readyToExecute;
    }

    public List<Task> getTasks() {
        throw new UnsupportedOperationException();
    }

    private static void breakCycles(Graph graph, Iterable<TaskInfo> entryTasks) {
        Set<TaskInfo> visitedNodes = Sets.newHashSet();
        Set<TaskInfo> path = Sets.newLinkedHashSet();
        Deque<Edge> softOrderingEdges = new ArrayDeque<Edge>();

        for (TaskInfo entryTask : entryTasks) {
            breakCycles(graph, entryTask, path, softOrderingEdges, visitedNodes);
        }
    }

    private static void breakCycles(final Graph graph, TaskInfo node, final Set<TaskInfo> path, Deque<Edge> softOrderingEdges, Set<TaskInfo> visitedNodes) {
        if (!path.add(node)) {
            // We have a cycle
            if (softOrderingEdges.isEmpty()) {
                DirectedGraphRenderer<TaskInfo> graphRenderer = new DirectedGraphRenderer<TaskInfo>(new GraphNodeRenderer<TaskInfo>() {
                    @Override
                    public void renderTo(TaskInfo node, StyledTextOutput output) {
                        output.withStyle(StyledTextOutput.Style.Identifier).text(node.getTask().getIdentityPath());
                    }
                }, new DirectedGraph<TaskInfo, Object>() {
                    @Override
                    public void getNodeValues(TaskInfo node, Collection<? super Object> values, Collection<? super TaskInfo> connectedNodes) {
                        Collection<Edge> outgoingEdges = graph.getIncomingEdges(node);
                        for (TaskInfo dependency : path) {
                            for (Edge edge : outgoingEdges) {
                                if (edge.getFrom() != dependency) {
                                    continue;
                                }
                                switch (edge.getType()) {
                                    case DEPENDENT:
                                    case FINALIZER:
                                        connectedNodes.add(dependency);
                                        break;
                                    default:
                                        break;
                                }
                            }
                        }
                    }
                });
                StringWriter writer = new StringWriter();
                graphRenderer.renderTo(Lists.newArrayList(path).get(path.size() - 1), writer);
                throw new CircularReferenceException(String.format("Circular dependency between the following tasks:%n%s", writer.toString()));
            } else {
                Edge edgeToRemove = softOrderingEdges.getLast();
                System.out.printf("Removing edge %s%n", edgeToRemove);
                graph.removeEdge(edgeToRemove);
            }
        }
        try {
            if (!visitedNodes.add(node)) {
                return;
            }

            Set<TaskInfo> visitedFromHere = Sets.newHashSet();
            List<Edge> incomingEdges = new ArrayList<Edge>(graph.getIncomingEdges(node));
            Collections.sort(incomingEdges, EDGE_TYPE_PRECEDENCE);

            for (Edge edge : incomingEdges) {
                if (visitedFromHere.add(edge.getFrom())) {
                    if (edge.getType() == EdgeType.SOFT_ORDERING) {
                        softOrderingEdges.push(edge);
                    }
                    System.out.printf("Checking edge %s for cycles%n", edge);
                    breakCycles(graph, edge.getFrom(), path, softOrderingEdges, visitedNodes);
                    if (edge.getType() == EdgeType.SOFT_ORDERING) {
                        softOrderingEdges.pop();
                    }
                }
            }
        } finally {
            path.remove(node);
        }
    }

    public void finishedExecuting(TaskInfo node) {
        final boolean taskFailure = node.isFailed();
        System.out.printf("< Finished executing %s%s%n", node, taskFailure ? " (failed)" : "");

        // Add all finalizers and their dependencies as has to run
        Set<TaskInfo> liveFinalizerNodes = graph.findLiveNodesAccessibleFrom(graph.getOutgoing(node, EdgeType.FINALIZER));
        requiredNodes.addAll(liveFinalizerNodes);

        if (taskFailure && !ignoreFailures) {
            abort();
        }
        graph.removeNode(node, readyToExecute, new Action<Edge>() {
            @Override
            public void execute(Edge edge) {
                if (taskFailure && edge.isFailurePropagated()) {
                    nodeSkipped(edge.getTo());
                }
            }
        });
        readyToExecute.remove(node);
        System.out.println(">> Nodes to execute: " + Joiner.on(", ").join(readyToExecute));
    }

    private void nodeSkipped(TaskInfo initialNode) {
        System.out.println("Skipping node " + initialNode);
        final Queue<TaskInfo> nodesToSkip = new ArrayDeque<TaskInfo>();
        nodesToSkip.add(initialNode);
        while (!nodesToSkip.isEmpty()) {
            TaskInfo node = nodesToSkip.remove();
            graph.removeNode(node, readyToExecute, new Action<Edge>() {
                @Override
                public void execute(Edge graphEdge) {
                    TaskInfo dependentNode = graphEdge.getTo();
                    if (graphEdge.isFailurePropagated()) {
                        nodesToSkip.add(dependentNode);
                    }
                }
            });
        }
    }

    private void abort() {
        for (TaskInfo node : new ArrayList<TaskInfo>(graph.incomingEdges.keySet())) {
            if (!requiredNodes.contains(node)) {
                graph.removeNodeAndDiscardIncoming(node, readyToExecute, Actions.doNothing());
            }
        }
    }

    public void ignoreFailures() {
        this.ignoreFailures = true;
    }

    public Iterable<TaskInfo> getReadyToExecute() {
        Preconditions.checkState(!readyToExecute.isEmpty() || graph.incomingEdges.isEmpty(), "Nothing ready to execute but tasks left! Nodes: %s", graph.incomingEdges.keySet());
        return readyToExecute;
    }

    private static class Graph {
        private final Multimap<TaskInfo, Edge> outgoingEdges = LinkedHashMultimap.create();
        private final Multimap<TaskInfo, Edge> incomingEdges = LinkedHashMultimap.create();

        public void addEdge(Edge edge) {
            System.out.println("Adding edge " + edge);
            outgoingEdges.put(edge.getFrom(), edge);
            incomingEdges.put(edge.getTo(), edge);
        }

        public void removeEdge(Edge edge) {
            System.out.println("Removing edge " + edge);
            outgoingEdges.remove(edge.from, edge);
            incomingEdges.remove(edge.to, edge);
        }

        public void removeNodeAndDiscardIncoming(TaskInfo node, Deque<TaskInfo> readyToExecute, Action<? super Edge> onRemovedEdge) {
            System.out.println("Removing node (on abort) " + node);
            for (Edge edge : Iterables.toArray(getIncomingEdges(node), Edge.class)) {
                removeEdge(edge);
                onRemovedEdge.execute(edge);
            }
            removeNodeInternal(node, readyToExecute, onRemovedEdge);
        }

        public void removeNode(TaskInfo node, Deque<TaskInfo> readyToExecute, Action<? super Edge> onRemovedEdge) {
            System.out.println("Removing node " + node);
            if (!getIncomingEdges(node).isEmpty()) {
                // TODO: This doesn't handle abort() property yet
                throw new IllegalStateException("Node to be removed still has incoming edges: " + node);
            }

            removeNodeInternal(node, readyToExecute, onRemovedEdge);
        }

        private void removeNodeInternal(TaskInfo node, Deque<TaskInfo> readyToExecute, Action<? super Edge> onRemovedEdge) {
            Set<TaskInfo> successorNodes = Sets.newLinkedHashSet();
            for (Edge edge : Iterables.toArray(getOutgoingEdges(node), Edge.class)) {
                successorNodes.add(edge.getTo());
                removeEdge(edge);
                onRemovedEdge.execute(edge);
            }

            // TODO: Use iterator to remove stuff that's not good and sort and then add to readyToExecute
            for (TaskInfo successorNode : successorNodes) {
                if (!incomingEdges.containsKey(successorNode)) {
                    System.out.println("> Scheduling node for execution: " + successorNode);
                    readyToExecute.add(successorNode);
                }
            }
        }

        @Nullable
        public Edge findEdgeBetween(TaskInfo from, TaskInfo to) {
            for (Edge edge : incomingEdges.get(to)) {
                if (edge.getFrom() == from) {
                    return edge;
                }
            }
            return null;
        }

        public boolean contains(Edge edge) {
            return incomingEdges.containsEntry(edge.getTo(), edge);
        }

        public Set<TaskInfo> findLiveNodesAccessibleFrom(Collection<TaskInfo> entryTasks) {
            Queue<TaskInfo> queue = new ArrayDeque<TaskInfo>();
            Set<TaskInfo> liveNodes = Sets.newLinkedHashSet();
            Iterables.addAll(queue, entryTasks);
            while (true) {
                TaskInfo node = queue.poll();
                if (node == null) {
                    break;
                }
                if (!liveNodes.add(node)) {
                    continue;
                }
                queue.addAll(getIncoming(node, EdgeType.DEPENDENT));
                queue.addAll(getOutgoing(node, EdgeType.FINALIZER));
            }
            return liveNodes;
        }

        public void retainAll(Set<TaskInfo> liveNodes) {
            Iterator<Edge> iIncomingEdge = incomingEdges.values().iterator();
            while (iIncomingEdge.hasNext()) {
                Edge edge = iIncomingEdge.next();
                if (!liveNodes.contains(edge.getFrom())
                    || !liveNodes.contains(edge.getTo())) {
                    iIncomingEdge.remove();
                    outgoingEdges.remove(edge.to, edge);
                }
            }
        }

        public Collection<Edge> getEdges() {
            return outgoingEdges.values();
        }

        public Collection<Edge> getIncomingEdges(TaskInfo node) {
            return incomingEdges.get(node);
        }

        public Collection<TaskInfo> getIncoming(TaskInfo node, EdgeType type) {
            return Collections2.transform(Collections2.filter(getIncomingEdges(node), type.filter()), new Function<Edge, TaskInfo>() {
                @Override
                public TaskInfo apply(Edge input) {
                    return input.getFrom();
                }
            });
        }

        public Collection<Edge> getOutgoingEdges(TaskInfo node) {
            return outgoingEdges.get(node);
        }

        public Collection<TaskInfo> getOutgoing(TaskInfo node, EdgeType type) {
            return Collections2.transform(Collections2.filter(getOutgoingEdges(node), type.filter()), new Function<Edge, TaskInfo>() {
                @Override
                public TaskInfo apply(Edge input) {
                    return input.getTo();
                }
            });
        }
    }

    private static class Edge {
        private final TaskInfo from;
        private final TaskInfo to;
        private final EdgeType type;

        public Edge(TaskInfo from, TaskInfo to, EdgeType type) {
            this.from = from;
            this.to = to;
            this.type = type;
        }

        public TaskInfo getFrom() {
            return from;
        }

        public TaskInfo getTo() {
            return to;
        }

        public EdgeType getType() {
            return type;
        }

        public boolean isFailurePropagated() {
            return type == EdgeType.DEPENDENT;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Edge edge = (Edge) o;

            if (!from.equals(edge.from)) return false;
            if (!to.equals(edge.to)) return false;
            return type == edge.type;
        }

        @Override
        public int hashCode() {
            int result = from.hashCode();
            result = 31 * result + to.hashCode();
            result = 31 * result + type.hashCode();
            return result;
        }

        @Override
        public String toString() {
            return String.format("%s -> %s (%s)", from, to, type);
        }
    }

    private enum EdgeType {
        /**
         * Points from finalized node to finalizer.
         */
        FINALIZER,

        /**
         * Points from dependency to dependent.
         */
        DEPENDENT,

        /**
         * Points from predecessor to successor.
         */
        HARD_ORDERING,

        /**
         * Points from predecessor to successor.
         */
        SOFT_ORDERING;

        private final Predicate<Edge> filter = new Predicate<Edge>() {
            @Override
            public boolean apply(Edge input) {
                return input.getType() == EdgeType.this;
            }
        };

        public Predicate<Edge> filter() {
            return filter;
        }
    }
}
