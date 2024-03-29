package com.networknt.tram.producer;

import com.networknt.config.Config;
import org.apache.kafka.clients.consumer.ConsumerGroupMetadata;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.clients.producer.internals.TransactionalRequestResult;
import org.apache.kafka.common.*;
import org.apache.kafka.common.errors.ProducerFencedException;
import org.apache.kafka.common.requests.FindCoordinatorRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Wrapper around KafkaProducer that allows to resume transactions in case of node failure, which allows to implement
 * two phase commit algorithm for exactly-once semantic TramKafkaProducer.
 *
 * <p>For happy path usage is exactly the same as {@link org.apache.kafka.clients.producer.KafkaProducer}. User is
 * expected to call:
 *
 * <ul>
 *     <li>{@link TramKafkaProducer#initTransactions()}</li>
 *     <li>{@link TramKafkaProducer#beginTransaction()}</li>
 *     <li>{@link TramKafkaProducer#send(org.apache.kafka.clients.producer.ProducerRecord)}</li>
 *     <li>{@link TramKafkaProducer#flush()}</li>
 *     <li>{@link TramKafkaProducer#commitTransaction()}</li>
 * </ul>
 *
 * <p>To actually implement two phase commit, it must be possible to always commit a transaction after pre-committing
 * it (here, pre-commit is just a {@link TramKafkaProducer#flush()}). In case of some failure between
 * {@link TramKafkaProducer#flush()} and {@link TramKafkaProducer#commitTransaction()} this class allows to resume
 * interrupted transaction and commit if after a restart:
 *
 * <ul>
 *     <li>{@link TramKafkaProducer#initTransactions()}</li>
 *     <li>{@link TramKafkaProducer#beginTransaction()}</li>
 *     <li>{@link TramKafkaProducer#send(org.apache.kafka.clients.producer.ProducerRecord)}</li>
 *     <li>{@link TramKafkaProducer#flush()}</li>
 *     <li>{@link TramKafkaProducer#getProducerId()}</li>
 *     <li>{@link TramKafkaProducer#getEpoch()}</li>
 *     <li>node failure... restore producerId and epoch from state</li>
 *     <li>{@link TramKafkaProducer#resumeTransaction(long, short)}</li>
 *     <li>{@link TramKafkaProducer#commitTransaction()}</li>
 * </ul>
 *
 * <p>{@link TramKafkaProducer#resumeTransaction(long, short)} replaces {@link TramKafkaProducer#initTransactions()}
 * as a way to obtain the producerId and epoch counters. It has to be done, because otherwise
 * {@link TramKafkaProducer#initTransactions()} would automatically abort all on going transactions.
 *
 * <p>Second way this implementation differs from the reference {@link org.apache.kafka.clients.producer.KafkaProducer}
 * is that this one actually flushes new partitions on {@link TramKafkaProducer#flush()} instead of on
 * {@link TramKafkaProducer#commitTransaction()}.
 *
 * <p>The last one minor difference is that it allows to obtain the producerId and epoch counters via
 * {@link TramKafkaProducer#getProducerId()} and {@link TramKafkaProducer#getEpoch()} methods (which are unfortunately
 * private fields).
 *
 */

public class TramKafkaProducer<K, V> implements Producer<K, V> {
    private static final Logger LOG = LoggerFactory.getLogger(TramKafkaProducer.class);

    private final KafkaProducer<K, V> kafkaProducer;

    private final String transactionalId;

    public TramKafkaProducer(Properties properties) {
        transactionalId = properties.getProperty(ProducerConfig.TRANSACTIONAL_ID_CONFIG);
        kafkaProducer = new KafkaProducer<>(properties);
    }

    public TramKafkaProducer() {
        Properties properties = new Properties();
        KafkaProducerConfig config = (KafkaProducerConfig)Config.getInstance().getJsonObjectConfig(KafkaProducerConfig.CONFIG_NAME, KafkaProducerConfig.class);
        properties.setProperty("bootstrap.servers", config.getBootstrapServers());
        properties.put("transactional.id", config.getTransactionId());
        properties.put("key.serializer", config.getKeySerializer());
        properties.put("value.serializer", config.getValueSerializer());
        transactionalId = properties.getProperty(ProducerConfig.TRANSACTIONAL_ID_CONFIG);
        kafkaProducer = new KafkaProducer<>(properties);
    }

    // -------------------------------- Simple proxy method calls --------------------------------

    @Override
    public void initTransactions() {
        kafkaProducer.initTransactions();
    }

    @Override
    public void beginTransaction() throws ProducerFencedException {
        kafkaProducer.beginTransaction();
    }

    @Override
    public void commitTransaction() throws ProducerFencedException {
        kafkaProducer.commitTransaction();
    }

    @Override
    public void abortTransaction() throws ProducerFencedException {
        kafkaProducer.abortTransaction();
    }

    @Override
    public void sendOffsetsToTransaction(Map<TopicPartition, OffsetAndMetadata> offsets, String consumerGroupId) throws ProducerFencedException {
        kafkaProducer.sendOffsetsToTransaction(offsets, consumerGroupId);
    }

    @Override
    public void sendOffsetsToTransaction(Map<TopicPartition, OffsetAndMetadata> map, ConsumerGroupMetadata consumerGroupMetadata) throws ProducerFencedException {
        kafkaProducer.sendOffsetsToTransaction(map, consumerGroupMetadata);
    }

    @Override
    public Future<RecordMetadata> send(ProducerRecord<K, V> record) {
        return kafkaProducer.send(record);
    }

    @Override
    public Future<RecordMetadata> send(ProducerRecord<K, V> record, Callback callback) {
        return kafkaProducer.send(record, callback);
    }

    @Override
    public List<PartitionInfo> partitionsFor(String topic) {
        return kafkaProducer.partitionsFor(topic);
    }

    @Override
    public Map<MetricName, ? extends Metric> metrics() {
        return kafkaProducer.metrics();
    }

    @Override
    public void close() {
        kafkaProducer.close();
    }

    @Override
    public void close(Duration duration) {
        kafkaProducer.close(duration);
    }


    // -------------------------------- New methods or methods with changed behaviour --------------------------------

    @Override
    public void flush() {
        kafkaProducer.flush();
        if (transactionalId != null) {
            flushNewPartitions();
        }
    }

    /**
     * Instead of obtaining producerId and epoch from the transaction coordinator, re-use previously obtained ones,
     * so that we can resume transaction after a restart. Implementation of this method is based on
     * {@link org.apache.kafka.clients.producer.KafkaProducer#initTransactions}.
     * @param producerId long producer id
     * @param epoch short epoch
     */
    public void resumeTransaction(long producerId, short epoch) {
        LOG.info("Attempting to resume transaction {} with producerId {} and epoch {}", transactionalId, producerId, epoch);

        Object transactionManager = getValue(kafkaProducer, "transactionManager");
        synchronized (transactionManager) {
            Object nextSequence = getValue(transactionManager, "nextSequence");

            invoke(transactionManager, "transitionTo", getEnum("org.apache.kafka.clients.producer.internals.TransactionManager$State.INITIALIZING"));
            invoke(nextSequence, "clear");

            Object producerIdAndEpoch = getValue(transactionManager, "producerIdAndEpoch");
            setValue(producerIdAndEpoch, "producerId", producerId);
            setValue(producerIdAndEpoch, "epoch", epoch);

            invoke(transactionManager, "transitionTo", getEnum("org.apache.kafka.clients.producer.internals.TransactionManager$State.READY"));

            invoke(transactionManager, "transitionTo", getEnum("org.apache.kafka.clients.producer.internals.TransactionManager$State.IN_TRANSACTION"));
            setValue(transactionManager, "transactionStarted", true);
        }
    }

    public String getTransactionalId() {
        return transactionalId;
    }

    public long getProducerId() {
        Object transactionManager = getValue(kafkaProducer, "transactionManager");
        Object producerIdAndEpoch = getValue(transactionManager, "producerIdAndEpoch");
        return (long) getValue(producerIdAndEpoch, "producerId");
    }

    public short getEpoch() {
        Object transactionManager = getValue(kafkaProducer, "transactionManager");
        Object producerIdAndEpoch = getValue(transactionManager, "producerIdAndEpoch");
        return (short) getValue(producerIdAndEpoch, "epoch");
    }

    public int getTransactionCoordinatorId() {
        Object transactionManager = getValue(kafkaProducer, "transactionManager");
        Node node = (Node) invoke(transactionManager, "coordinator", FindCoordinatorRequest.CoordinatorType.TRANSACTION);
        return node.id();
    }

    /**
     * Besides committing {@link org.apache.kafka.clients.producer.KafkaProducer#commitTransaction} is also adding new
     * partitions to the transaction. flushNewPartitions method is moving this logic to pre-commit/flush, to make
     * resumeTransaction simpler. Otherwise resumeTransaction would require to restore state of the not yet added/"in-flight"
     * partitions.
     */
    private void flushNewPartitions() {
        LOG.info("Flushing new partitions");
        TransactionalRequestResult result = enqueueNewPartitions();
        Object sender = getValue(kafkaProducer, "sender");
        invoke(sender, "wakeup");
        result.await();
    }

    private TransactionalRequestResult enqueueNewPartitions() {
        Object transactionManager = getValue(kafkaProducer, "transactionManager");
        synchronized (transactionManager) {
            Object txnRequestHandler = invoke(transactionManager, "addPartitionsToTransactionHandler");
            invoke(transactionManager, "enqueueRequest", new Class[]{txnRequestHandler.getClass().getSuperclass()}, new Object[]{txnRequestHandler});
            TransactionalRequestResult result = (TransactionalRequestResult) getValue(txnRequestHandler, txnRequestHandler.getClass().getSuperclass(), "result");
            return result;
        }
    }

    private static Enum<?> getEnum(String enumFullName) {
        String[] x = enumFullName.split("\\.(?=[^\\.]+$)");
        if (x.length == 2) {
            String enumClassName = x[0];
            String enumName = x[1];
            try {
                Class<Enum> cl = (Class<Enum>) Class.forName(enumClassName);
                return Enum.valueOf(cl, enumName);
            } catch (ClassNotFoundException e) {
                throw new RuntimeException("Incompatible KafkaProducer version", e);
            }
        }
        return null;
    }

    private static Object invoke(Object object, String methodName, Object... args) {
        Class<?>[] argTypes = new Class[args.length];
        for (int i = 0; i < args.length; i++) {
            argTypes[i] = args[i].getClass();
        }
        return invoke(object, methodName, argTypes, args);
    }

    private static Object invoke(Object object, String methodName, Class<?>[] argTypes, Object[] args) {
        try {
            Method method = object.getClass().getDeclaredMethod(methodName, argTypes);
            method.setAccessible(true);
            return method.invoke(object, args);
        } catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException e) {
            throw new RuntimeException("Incompatible KafkaProducer version", e);
        }
    }

    private static Object getValue(Object object, String fieldName) {
        return getValue(object, object.getClass(), fieldName);
    }

    private static Object getValue(Object object, Class<?> clazz, String fieldName) {
        try {
            Field field = clazz.getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(object);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException("Incompatible KafkaProducer version", e);
        }
    }

    private static void setValue(Object object, String fieldName, Object value) {
        try {
            Field field = object.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(object, value);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException("Incompatible KafkaProducer version", e);
        }
    }
}
