/*
 * Copyright 2013-2015 the original author or authors.
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

package org.springframework.cloud.sleuth.instrument.messaging;

import java.util.HashMap;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.IntegrationTest;
import org.springframework.boot.test.SpringApplicationConfiguration;
import org.springframework.cloud.sleuth.Sampler;
import org.springframework.cloud.sleuth.Span;
import org.springframework.cloud.sleuth.Tracer;
import org.springframework.cloud.sleuth.assertions.SleuthAssertions;
import org.springframework.cloud.sleuth.instrument.messaging.TraceChannelInterceptorTests.App;
import org.springframework.cloud.sleuth.sampler.AlwaysSampler;
import org.springframework.cloud.sleuth.trace.TestSpanContextHolder;
import org.springframework.cloud.sleuth.util.ArrayListSpanAccumulator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.core.MessagingTemplate;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.MessagingException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import static org.junit.Assert.assertNotNull;
import static org.springframework.cloud.sleuth.assertions.SleuthAssertions.then;

/**
 * @author Dave Syer
 */
@RunWith(SpringJUnit4ClassRunner.class)
@SpringApplicationConfiguration(classes = App.class)
@IntegrationTest
@DirtiesContext
public class TraceChannelInterceptorTests implements MessageHandler {

	@Autowired
	@Qualifier("channel")
	private DirectChannel channel;

	@Autowired
	private Tracer tracer;

	@Autowired
	private MessagingTemplate messagingTemplate;

	@Autowired
	private ArrayListSpanAccumulator accumulator;

	private Message<?> message;

	private Span span;

	@Override
	public void handleMessage(Message<?> message) throws MessagingException {
		this.message = message;
		this.span = TestSpanContextHolder.getCurrentSpan();
		if (message.getHeaders().containsKey("THROW_EXCEPTION")) {
			throw new RuntimeException();
		}
	}

	@Before
	public void init() {
		this.channel.subscribe(this);
		this.accumulator.getSpans().clear();
	}

	@After
	public void close() {
		TestSpanContextHolder.removeCurrentSpan();
		this.channel.unsubscribe(this);
		this.accumulator.getSpans().clear();
	}

	@Test
	public void nonExportableSpanCreation() {
		this.channel.send(MessageBuilder.withPayload("hi")
				.setHeader(Span.SAMPLED_NAME, Span.SPAN_NOT_SAMPLED).build());
		assertNotNull("message was null", this.message);

		String spanId = this.message.getHeaders().get(Span.SPAN_ID_NAME, String.class);
		then(spanId).isNotNull();
		then(TestSpanContextHolder.getCurrentSpan()).isNull();
		then(this.span.isExportable()).isFalse();
	}

	@Test
	public void parentSpanIncluded() {
		this.channel.send(MessageBuilder.withPayload("hi")
				.setHeader(Span.TRACE_ID_NAME, Span.idToHex(10L))
				.setHeader(Span.SPAN_ID_NAME, Span.idToHex(20L)).build());
		then(this.message).isNotNull();

		String spanId = this.message.getHeaders().get(Span.SPAN_ID_NAME, String.class);
		then(spanId).isNotNull();
		long traceId = Span
				.hexToId(this.message.getHeaders().get(Span.TRACE_ID_NAME, String.class));
		then(traceId).isEqualTo(10L);
		then(spanId).isNotEqualTo(20L);
		then(this.accumulator.getSpans()).hasSize(1);
	}

	// #332
	@Test
	public void shouldSendNewAndOldHeadersWhenNewHeadersWerePassed() {
		this.channel.send(MessageBuilder.withPayload("hi")
				.setHeader(TraceMessageHeaders.TRACE_ID_NAME, Span.idToHex(10L))
				.setHeader(TraceMessageHeaders.SPAN_ID_NAME, Span.idToHex(20L)).build());
		then(this.message).isNotNull();

		String newSpanId = thenNewSpanIdEqualsOldSpanId();
		thenNewTraceIdEqualsOldTraceId();
		then(newSpanId).isNotEqualTo(20L);
		then(this.accumulator.getSpans()).hasSize(1);
	}

	private String thenNewSpanIdEqualsOldSpanId() {
		String newSpanId = this.message.getHeaders().get(TraceMessageHeaders.SPAN_ID_NAME, String.class);
		then(newSpanId).isNotNull();
		String oldSpanId = this.message.getHeaders().get(Span.SPAN_ID_NAME, String.class);
		then(oldSpanId).isEqualTo(newSpanId);
		return newSpanId;
	}

	// #332
	@Test
	public void shouldSendNewAndOldHeadersWhenOldHeadersWerePassed() {
		this.channel.send(MessageBuilder.withPayload("hi")
				.setHeader(Span.TRACE_ID_NAME, Span.idToHex(10L))
				.setHeader(Span.SPAN_ID_NAME, Span.idToHex(20L)).build());
		then(this.message).isNotNull();

		String newSpanId = thenNewSpanIdEqualsOldSpanId();
		thenNewTraceIdEqualsOldTraceId();
		then(newSpanId).isNotEqualTo(20L);
		then(this.accumulator.getSpans()).hasSize(1);
	}

	private void thenNewTraceIdEqualsOldTraceId() {
		long traceId = Span
				.hexToId(this.message.getHeaders().get(TraceMessageHeaders.TRACE_ID_NAME, String.class));
		then(traceId).isEqualTo(10L);
		long oldTraceId = Span
				.hexToId(this.message.getHeaders().get(Span.TRACE_ID_NAME, String.class));
		then(oldTraceId).isEqualTo(traceId);
	}

	@Test
	public void spanCreation() {
		this.channel.send(MessageBuilder.withPayload("hi").build());
		then(this.message).isNotNull();

		String spanId = this.message.getHeaders().get(Span.SPAN_ID_NAME, String.class);
		then(spanId).isNotNull();

		String traceId = this.message.getHeaders().get(Span.TRACE_ID_NAME, String.class);
		then(traceId).isNotNull();
		then(TestSpanContextHolder.getCurrentSpan()).isNull();
	}

	@Test
	public void shouldLogClientReceivedClientSentEventWhenTheMessageIsSentAndReceived() {
		this.channel.send(MessageBuilder.withPayload("hi").build());

		then(this.span.logs()).extracting("event").contains(Span.CLIENT_SEND, Span.CLIENT_RECV);
	}

	@Test
	public void shouldLogServerReceivedServerSentEventWhenTheMessageIsPropagatedToTheNextListener() {
		this.channel.send(MessageBuilder.withPayload("hi").setHeader("X-Message-Sent", true).build());

		then(this.span.logs()).extracting("event").contains(Span.SERVER_RECV, Span.SERVER_SEND);
	}

	@Test
	public void headerCreation() {
		Span span = this.tracer.createSpan("http:testSendMessage", new AlwaysSampler());
		this.channel.send(MessageBuilder.withPayload("hi").build());
		this.tracer.close(span);
		then(this.message).isNotNull();

		String spanId = this.message.getHeaders().get(Span.SPAN_ID_NAME, String.class);
		then(spanId).isNotNull();

		String traceId = this.message.getHeaders().get(Span.TRACE_ID_NAME, String.class);
		then(traceId).isNotNull();
		then(TestSpanContextHolder.getCurrentSpan()).isNull();
	}

	// TODO: Refactor to parametrized test together with sending messages via channel
	@Test
	public void headerCreationViaMessagingTemplate() {
		Span span = this.tracer.createSpan("http:testSendMessage", new AlwaysSampler());
		this.messagingTemplate.send(MessageBuilder.withPayload("hi").build());

		this.tracer.close(span);
		then(this.message).isNotNull();

		String spanId = this.message.getHeaders().get(Span.SPAN_ID_NAME, String.class);
		then(spanId).isNotNull();

		String traceId = this.message.getHeaders().get(Span.TRACE_ID_NAME, String.class);
		then(traceId).isNotNull();
		then(TestSpanContextHolder.getCurrentSpan()).isNull();
	}

	@Test
	public void shouldCloseASpanWhenExceptionOccurred() {
		Span span = this.tracer.createSpan("http:testSendMessage", new AlwaysSampler());
		Map<String, String> errorHeaders = new HashMap<>();
		errorHeaders.put("THROW_EXCEPTION", "TRUE");

		try {
			this.messagingTemplate.send(MessageBuilder.withPayload("hi").copyHeaders(errorHeaders).build());
			SleuthAssertions.fail("Exception should occur");
		} catch (RuntimeException e) {}

		then(this.message).isNotNull();
		this.tracer.close(span);
		then(TestSpanContextHolder.getCurrentSpan()).isNull();
	}

	@Configuration
	@EnableAutoConfiguration
	static class App {

		@Bean
		ArrayListSpanAccumulator arrayListSpanAccumulator() {
			return new ArrayListSpanAccumulator();
		}

		@Bean
		public DirectChannel channel() {
			return new DirectChannel();
		}

		@Bean
		public MessagingTemplate messagingTemplate() {
			return new MessagingTemplate(channel());
		}

		@Bean
		Sampler alwaysSampler() {
			return new AlwaysSampler();
		}

	}
}
