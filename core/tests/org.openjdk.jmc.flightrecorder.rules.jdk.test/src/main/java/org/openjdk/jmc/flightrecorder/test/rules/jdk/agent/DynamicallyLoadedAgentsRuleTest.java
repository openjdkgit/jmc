/*
 * Copyright (c) 2024, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2024, Datadog, Inc. All rights reserved.
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
package org.openjdk.jmc.flightrecorder.test.rules.jdk.agent;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.RunnableFuture;

import org.junit.Assert;
import org.junit.Test;
import org.openjdk.jmc.common.item.IItemCollection;
import org.openjdk.jmc.common.util.IPreferenceValueProvider;
import org.openjdk.jmc.flightrecorder.rules.IResult;
import org.openjdk.jmc.flightrecorder.rules.ResultProvider;
import org.openjdk.jmc.flightrecorder.rules.ResultToolkit;
import org.openjdk.jmc.flightrecorder.rules.Severity;
import org.openjdk.jmc.flightrecorder.rules.jdk.agent.DynamicallyLoadedAgentsRule;
import org.openjdk.jmc.flightrecorder.test.rules.jdk.MockEventCollection;
import org.openjdk.jmc.flightrecorder.test.rules.jdk.TestEvent;

public class DynamicallyLoadedAgentsRuleTest {
	private final static JavaAgentTestEvent JAVA_DYNAMIC_EVENT = new JavaAgentTestEvent(true,
			"/my/agent/path/java-agent.jar", "-my -options");
	private final static JavaAgentTestEvent JAVA_NON_DYNAMIC_EVENT = new JavaAgentTestEvent(false,
			"/my/agent/path/java-agent.jar", "-my -options");
	private final static NativeAgentTestEvent NATIVE_DYNAMIC_EVENT = new NativeAgentTestEvent(true,
			"/my/agent/path/native-agent.jar", "-some -other -options");
	private final static NativeAgentTestEvent NATIVE_NON_DYNAMIC_EVENT = new NativeAgentTestEvent(false,
			"/my/agent/path/native-agent.jar", "-some -other -options");

	@Test
	public void noAgentEvents() {
		TestEvent[] testEvents = new TestEvent[] {};
		testDynamicallyLoadedAgentsRule(testEvents,
				"An acceptable number of java and native agents (0 total) were dynamically loaded!");
	}

	@Test
	public void oneJavaDynamicAgent() {
		TestEvent[] testEvents = new TestEvent[] {JAVA_DYNAMIC_EVENT};
		testDynamicallyLoadedAgentsRule(testEvents,
				"You have 1 dynamically loaded agent(s) active (1 java, 0 native). Go to the agents page to verify that loading these agents was intentional. Investigate their origin in the agent page, and remove the agents that are not necessary. Note that allowing for dynamically loaded agents can be a security risk.");
	}

	@Test
	public void oneJavaNonDynamicAgent() {
		TestEvent[] testEvents = new TestEvent[] {JAVA_NON_DYNAMIC_EVENT};
		testDynamicallyLoadedAgentsRule(testEvents,
				"An acceptable number of java and native agents (0 total) were dynamically loaded!");
	}

	@Test
	public void oneNativeDynamicAgent() {
		TestEvent[] testEvents = new TestEvent[] {NATIVE_DYNAMIC_EVENT};
		testDynamicallyLoadedAgentsRule(testEvents,
				"You have 1 dynamically loaded agent(s) active (0 java, 1 native). Go to the agents page to verify that loading these agents was intentional. Investigate their origin in the agent page, and remove the agents that are not necessary. Note that allowing for dynamically loaded agents can be a security risk.");
	}

	@Test
	public void oneNativeNonDynamicAgent() {
		TestEvent[] testEvents = new TestEvent[] {NATIVE_NON_DYNAMIC_EVENT};
		testDynamicallyLoadedAgentsRule(testEvents,
				"An acceptable number of java and native agents (0 total) were dynamically loaded!");
	}

	@Test
	public void manyAgents() {
		TestEvent[] testEvents = new TestEvent[] {JAVA_DYNAMIC_EVENT, JAVA_NON_DYNAMIC_EVENT, NATIVE_DYNAMIC_EVENT,
				NATIVE_NON_DYNAMIC_EVENT};
		testDynamicallyLoadedAgentsRule(testEvents,
				"You have 2 dynamically loaded agent(s) active (1 java, 1 native). Go to the agents page to verify that loading these agents was intentional. Investigate their origin in the agent page, and remove the agents that are not necessary. Note that allowing for dynamically loaded agents can be a security risk.");
	}

	private void testDynamicallyLoadedAgentsRule(TestEvent[] testEvents, String descriptionExpected) {
		IItemCollection events = new MockEventCollection(testEvents);
		DynamicallyLoadedAgentsRule dynamicAgentsRule = new DynamicallyLoadedAgentsRule();
		RunnableFuture<IResult> future = dynamicAgentsRule.createEvaluation(events,
				IPreferenceValueProvider.DEFAULT_VALUES, new ResultProvider());
		try {
			future.run();
			IResult res = future.get();
			String message;
			if (res.getSeverity() == Severity.OK) {
				message = ResultToolkit.populateMessage(res, res.getSummary(), false);
			} else {
				message = ResultToolkit.populateMessage(res, res.getExplanation(), false);
			}
			Assert.assertEquals(descriptionExpected, message);
		} catch (InterruptedException | ExecutionException e) {
			e.printStackTrace();
		}
	}

}
