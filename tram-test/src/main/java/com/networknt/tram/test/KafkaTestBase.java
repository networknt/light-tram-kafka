/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.networknt.tram.test;

import com.networknt.service.SingletonServiceFactory;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.rules.TemporaryFolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.concurrent.duration.FiniteDuration;

import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.fail;

/**
 * The base for the Kafka tests. It brings up:
 * <ul>
 *     <li>A ZooKeeper mini cluster</li>
 *     <li>Three Kafka Brokers (mini clusters)</li>
 * </ul>
 *
 * <p>Code in this test is based on the following GitHub repository:
 * <a href="https://github.com/sakserv/hadoop-mini-clusters">
 *   https://github.com/sakserv/hadoop-mini-clusters</a> (ASL licensed),
 * as per commit <i>bc6b2b2d5f6424d5f377aa6c0871e82a956462ef</i></p>
 */
@SuppressWarnings("serial")
public abstract class KafkaTestBase {

	protected static final Logger LOG = LoggerFactory.getLogger(KafkaTestBase.class);

	protected static final int NUMBER_OF_KAFKA_SERVERS = 3;

	protected static String brokerConnectionStrings;

	protected static Properties standardProps;

	protected static FiniteDuration timeout = new FiniteDuration(10, TimeUnit.SECONDS);

	protected static KafkaTestEnvironment kafkaServer;

	@ClassRule
	public static TemporaryFolder tempFolder = new TemporaryFolder();

	protected static Properties secureProps = new Properties();

	// ------------------------------------------------------------------------
	//  Setup and teardown of the mini clusters
	// ------------------------------------------------------------------------

	@BeforeClass
	public static void prepare() throws ClassNotFoundException {
		prepare(true);
	}

	public static void prepare(boolean hideKafkaBehindProxy) throws ClassNotFoundException {
		LOG.info("-------------------------------------------------------------------------");
		LOG.info("    Starting KafkaTestBase ");
		LOG.info("-------------------------------------------------------------------------");

		startClusters(false, hideKafkaBehindProxy);
	}

	@AfterClass
	public static void shutDownServices() throws Exception {

		LOG.info("-------------------------------------------------------------------------");
		LOG.info("    Shut down KafkaTestBase ");
		LOG.info("-------------------------------------------------------------------------");

		shutdownClusters();

		LOG.info("-------------------------------------------------------------------------");
		LOG.info("    KafkaTestBase finished");
		LOG.info("-------------------------------------------------------------------------");
	}

	protected static void startClusters(boolean secureMode, boolean hideKafkaBehindProxy) throws ClassNotFoundException {

		kafkaServer = SingletonServiceFactory.getBean(KafkaTestEnvironment.class);

		LOG.info("Starting KafkaTestBase.prepare() for Kafka " + kafkaServer.getVersion());

		kafkaServer.prepare(KafkaTestEnvironment.createConfig()
			.setKafkaServersNumber(NUMBER_OF_KAFKA_SERVERS)
			.setSecureMode(secureMode));

		standardProps = kafkaServer.getStandardProperties();

		brokerConnectionStrings = kafkaServer.getBrokerConnectionString();

		if (secureMode) {
			if (!kafkaServer.isSecureRunSupported()) {
				throw new IllegalStateException(
					"Attempting to test in secure mode but secure mode not supported by the KafkaTestEnvironment.");
			}
			secureProps = kafkaServer.getSecureProperties();
		}
	}

	protected static void shutdownClusters() throws Exception {
		if (secureProps != null) {
			secureProps.clear();
		}

		kafkaServer.shutdown();
	}

	// ------------------------------------------------------------------------
	//  Execution utilities
	// ------------------------------------------------------------------------

	protected static void createTestTopic(String topic, int numberOfPartitions, short replicationFactor) {
		kafkaServer.createTestTopic(topic, numberOfPartitions, replicationFactor);
	}

	protected static void deleteTestTopic(String topic) {
		kafkaServer.deleteTestTopic(topic);
	}

	/**
	 * We manually handle the timeout instead of using JUnit's timeout to return failure instead of timeout error.
	 * After timeout we assume that there are missing records and there is a bug, not that the test has run out of time.
	 *
	 * @param properties Properties
	 * @param topic String topic
	 * @param partition int partition
	 * @param expectedElements Set
	 * @param timeoutMillis long timeout
	 */
	protected void assertAtLeastOnceForTopic(
			Properties properties,
			String topic,
			int partition,
			Set<Integer> expectedElements,
			long timeoutMillis) {

		long startMillis = System.currentTimeMillis();
		Set<Integer> actualElements = new HashSet<>();

		// until we timeout...
		while (System.currentTimeMillis() < startMillis + timeoutMillis) {
			properties.put("key.deserializer", "org.apache.kafka.common.serialization.IntegerDeserializer");
			properties.put("value.deserializer", "org.apache.kafka.common.serialization.IntegerDeserializer");

			// query kafka for new records ...
			Collection<ConsumerRecord<Integer, Integer>> records = kafkaServer.getAllRecordsFromTopic(properties, topic, partition, 100);

			for (ConsumerRecord<Integer, Integer> record : records) {
				actualElements.add(record.value());
			}

			// succeed if we got all expectedElements
			if (actualElements.containsAll(expectedElements)) {
				return;
			}
		}

		fail(String.format("Expected to contain all of: <%s>, but was: <%s>", expectedElements, actualElements));
	}

	protected void assertExactlyOnceForTopic(
		Properties properties,
		String topic,
		int partition,
		List<Integer> expectedElements) {
		assertExactlyOnceForTopic(properties, topic, partition, expectedElements, 30_000L);
	}

	/**
	 * We manually handle the timeout instead of using JUnit's timeout to return failure instead of timeout error.
	 * After timeout we assume that there are missing records and there is a bug, not that the test has run out of time.
	 * @param properties Properties
	 * @param topic String topic
	 * @param partition int partition
	 * @param expectedElements List
	 * @param timeoutMillis long timeout
	 */
	protected void assertExactlyOnceForTopic(
			Properties properties,
			String topic,
			int partition,
			List<Integer> expectedElements,
			long timeoutMillis) {

		long startMillis = System.currentTimeMillis();
		List<Integer> actualElements = new ArrayList<>();

		Properties consumerProperties = new Properties();
		consumerProperties.putAll(properties);
		consumerProperties.put("key.deserializer", "org.apache.kafka.common.serialization.IntegerDeserializer");
		consumerProperties.put("value.deserializer", "org.apache.kafka.common.serialization.IntegerDeserializer");
		consumerProperties.put("isolation.level", "read_committed");

		// until we timeout...
		while (System.currentTimeMillis() < startMillis + timeoutMillis) {
			// query kafka for new records ...
			Collection<ConsumerRecord<Integer, Integer>> records = kafkaServer.getAllRecordsFromTopic(consumerProperties, topic, partition, 1000);

			for (ConsumerRecord<Integer, Integer> record : records) {
				actualElements.add(record.value());
			}

			// succeed if we got all expectedElements
			if (actualElements.equals(expectedElements)) {
				return;
			}
			// fail early if we already have too many elements
			if (actualElements.size() > expectedElements.size()) {
				break;
			}
		}

		fail(String.format("Expected number of elements: <%s>, but was: <%s>", expectedElements.size(), actualElements.size()));
	}
}
