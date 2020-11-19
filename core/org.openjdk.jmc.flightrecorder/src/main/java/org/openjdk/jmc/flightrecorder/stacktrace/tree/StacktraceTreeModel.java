/*
 * Copyright (c) 2019, 2020, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2019, 2020, Datadog, Inc. All rights reserved.
 *
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The contents of this file are subject to the terms of either the Universal Permissive License
 * v 1.0 as shown at http://oss.oracle.com/licenses/upl
 *
 * or the following license:
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted
 * provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of conditions
 * and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of
 * conditions and the following disclaimer in the documentation and/or other materials provided with
 * the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors may be used to
 * endorse or promote products derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY
 * WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.openjdk.jmc.flightrecorder.stacktrace.tree;

import static org.openjdk.jmc.common.item.ItemToolkit.accessor;
import static org.openjdk.jmc.flightrecorder.JfrAttributes.EVENT_STACKTRACE;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import org.openjdk.jmc.common.IMCFrame;
import org.openjdk.jmc.common.IMCStackTrace;
import org.openjdk.jmc.common.item.IAttribute;
import org.openjdk.jmc.common.item.IItem;
import org.openjdk.jmc.common.item.IItemCollection;
import org.openjdk.jmc.common.item.IItemIterable;
import org.openjdk.jmc.common.item.IMemberAccessor;
import org.openjdk.jmc.common.unit.IQuantity;
import org.openjdk.jmc.common.util.MCFrame;
import org.openjdk.jmc.flightrecorder.stacktrace.FrameSeparator;
import org.openjdk.jmc.flightrecorder.stacktrace.FrameSeparator.FrameCategorization;

public class StacktraceTreeModel {
	@SuppressWarnings("deprecation")
	private static final IMemberAccessor<IMCStackTrace, IItem> ACCESSOR_STACKTRACE = accessor(EVENT_STACKTRACE);
	private static final FrameSeparator DEFAULT_FRAME_SEPARATOR = new FrameSeparator(FrameCategorization.METHOD, false);

	/**
	 * A special marker object that indicates a frame that cannot be determined.
	 * <p>
	 * A typical case is when a stacktrace is truncated due to to Flight Recorder settings. We know
	 * that there is a frame because of a truncation flag, but there is no information about it.
	 */
	private static final IMCFrame UNKNOWN_FRAME = new MCFrame(null, null, null, IMCFrame.Type.UNKNOWN);

	/**
	 * A special marker object that indicates a hand-crafted frame at the root of the tree.
	 * <p>
	 * We need to create this frame as a parent to all branches of the tree we want to represent.
	 */
	private static final IMCFrame ROOT_FRAME = new MCFrame(null, null, null, IMCFrame.Type.UNKNOWN);

	private final Map<Integer, Node> nodes = new HashMap<>(1024);
	private final Map<Integer, Set<Integer>> childrenLookup = new HashMap<>(1024);
	private final Integer rootId;

	private final IItemCollection items;
	private final FrameSeparator frameSeparator;
	private final IAttribute<IQuantity> attribute;
	private final boolean threadRootAtTop;

	/**
	 * Builds a StacktraceTreeModel from a given collection of events with defaults: frame separator
	 * METHOD, the thread root at the top and no value attribute (uses counts).
	 *
	 * @param items
	 *            the data we want to represent.
	 */
	public StacktraceTreeModel(IItemCollection items) {
		this(items, DEFAULT_FRAME_SEPARATOR, true, null);
	}

	/**
	 * Builds a StacktraceTreeModel from a given collection of events with defaults: the thread root
	 * at the top and no value attribute (uses counts).
	 *
	 * @param items
	 *            the data we want to represent.
	 * @param frameSeparator
	 *            defines what represents a node in the tree. Defaults to METHOD.
	 */
	public StacktraceTreeModel(IItemCollection items, FrameSeparator frameSeparator) {
		this(items, frameSeparator, true, null);
	}

	/**
	 * Builds a StacktraceTreeModel from a given collection of events with no attribute specified
	 * (uses occurrences to determine node weight).
	 *
	 * @param items
	 *            the data we want to represent.
	 * @param frameSeparator
	 *            defines what represents a node in the tree. Defaults to METHOD.
	 * @param threadRootAtTop
	 *            defines how the stacks are aggregated. Defaults to true (i.e. bottom-up,
	 *            Thread.run() at the root of the tree).
	 */
	public StacktraceTreeModel(IItemCollection items, FrameSeparator frameSeparator, boolean threadRootAtTop) {
		this(items, frameSeparator, threadRootAtTop, null);
	}

	/**
	 * Builds a StacktraceTreeModel from a given collection of events.
	 *
	 * @param items
	 *            the data we want to represent.
	 * @param frameSeparator
	 *            defines what represents a node in the tree. Defaults to METHOD.
	 * @param threadRootAtTop
	 *            defines how the stacks are aggregated. Defaults to true (i.e. bottom-up,
	 *            Thread.run() at the root of the tree).
	 * @param attribute
	 *            defines what we use as node weights. If null, the weight is the number of
	 *            occurrences for the frame.
	 */
	public StacktraceTreeModel(IItemCollection items, FrameSeparator frameSeparator, boolean threadRootAtTop,
			IAttribute<IQuantity> attribute) {
		this.items = items;
		this.frameSeparator = frameSeparator;
		this.attribute = attribute;
		this.threadRootAtTop = threadRootAtTop;

		AggregatableFrame rootFrame = new AggregatableFrame(frameSeparator, ROOT_FRAME);
		this.rootId = newNodeId(null, rootFrame);
		nodes.put(rootId, new Node(rootId, rootFrame));
		childrenLookup.put(rootId, new TreeSet<>());

		for (IItemIterable iterable : items) {
			IMemberAccessor<IQuantity, IItem> accessor = getAccessor(iterable, attribute);
			iterable.forEach((item) -> addItem(item, accessor));
		}
	}

	/**
	 * @return the root node of the tree.
	 */
	public Node getRoot() {
		return nodes.get(rootId);
	}

	/**
	 * @return an unmodifiable view over the child lookup map.
	 */
	public Map<Integer, Set<Integer>> getChildrenLookup() {
		return Collections.unmodifiableMap(childrenLookup);
	}

	/**
	 * @return an unmodifiable view over the node lookup map.
	 */
	public Map<Integer, Node> getNodes() {
		return Collections.unmodifiableMap(nodes);
	}

	/**
	 * @return the source data set.
	 */
	public IItemCollection getItems() {
		return items;
	}

	private void addItem(IItem item, IMemberAccessor<IQuantity, IItem> accessor) {
		IMCStackTrace stacktrace = getStackTrace(item);
		if (stacktrace == null) {
			return;
		}
		List<? extends IMCFrame> frames = getStackTrace(item).getFrames();
		if (frames == null || frames.isEmpty()) {
			return;
		}

		// if we want a specific attribute but its accessor is not available we skip
		if (attribute != null && accessor == null) {
			return;
		}

		// if we don't request a specific attribute, we simply count occurrences
		double value = (accessor != null) ? accessor.getMember(item).doubleValue() : 1.0;

		// if the stack is zero valued for the requested attribute we prune it
		if (attribute != null && value == 0.0) {
			return;
		}

		Integer parentId = rootId;
		int processedFrames = 0;
		while (processedFrames < frames.size()) {
			int idx = threadRootAtTop ? frames.size() - 1 - processedFrames : processedFrames;

			AggregatableFrame frame;
			if (stacktrace.getTruncationState().isTruncated() && threadRootAtTop && processedFrames == 0) {
				// we have a truncated stacktrace so we can't assume anything about the bottom frame
				frame = new AggregatableFrame(frameSeparator, UNKNOWN_FRAME);
			} else {
				frame = new AggregatableFrame(frameSeparator, frames.get(idx));
			}

			int nodeId = newNodeId(parentId, frame);
			Node current = getOrCreateNode(nodeId, frame);
			current.cumulativeWeight += value;
			if (processedFrames == frames.size() - 1) {
				current.weight += value;
			}

			childrenLookup.get(parentId).add(current.getNodeId());
			if (childrenLookup.get(current.getNodeId()) == null) {
				childrenLookup.put(current.getNodeId(), new HashSet<>());
			}
			parentId = current.getNodeId();
			processedFrames++;
		}
	}

	private Node getOrCreateNode(Integer nodeId, AggregatableFrame frame) {
		Node n = nodes.get(nodeId);
		if (n == null) {
			n = new Node(nodeId, frame);
			nodes.put(nodeId, n);
		}
		return n;
	}

	private Integer newNodeId(Integer parentId, AggregatableFrame aframe) {
		// this is a naive implementation of content-addressable stacks
		// given the same ancestors and the same frame, the node will have the same id
		if (parentId == null) {
			return aframe.hashCode();
		}
		return Objects.hash(parentId, aframe.hashCode());
	}

	private IMCStackTrace getStackTrace(IItem item) {
		return ACCESSOR_STACKTRACE.getMember(item);
	}

	private static IMemberAccessor<IQuantity, IItem> getAccessor(IItemIterable iterable, IAttribute<IQuantity> attr) {
		return (attr != null) ? iterable.getType().getAccessor(attr.getKey()) : null;
	}
}
