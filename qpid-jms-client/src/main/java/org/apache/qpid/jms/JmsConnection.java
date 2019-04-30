/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.qpid.jms;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import javax.jms.Connection;
import javax.jms.ConnectionConsumer;
import javax.jms.ConnectionMetaData;
import javax.jms.Destination;
import javax.jms.ExceptionListener;
import javax.jms.IllegalStateException;
import javax.jms.InvalidClientIDException;
import javax.jms.InvalidDestinationException;
import javax.jms.JMSException;
import javax.jms.Queue;
import javax.jms.QueueConnection;
import javax.jms.QueueSession;
import javax.jms.ServerSessionPool;
import javax.jms.Session;
import javax.jms.TemporaryQueue;
import javax.jms.TemporaryTopic;
import javax.jms.Topic;
import javax.jms.TopicConnection;
import javax.jms.TopicSession;

import org.apache.qpid.jms.exceptions.JmsConnectionFailedException;
import org.apache.qpid.jms.exceptions.JmsExceptionSupport;
import org.apache.qpid.jms.message.JmsInboundMessageDispatch;
import org.apache.qpid.jms.message.JmsMessage;
import org.apache.qpid.jms.message.JmsMessageFactory;
import org.apache.qpid.jms.message.JmsOutboundMessageDispatch;
import org.apache.qpid.jms.meta.JmsConnectionId;
import org.apache.qpid.jms.meta.JmsConnectionInfo;
import org.apache.qpid.jms.meta.JmsConsumerId;
import org.apache.qpid.jms.meta.JmsConsumerInfo;
import org.apache.qpid.jms.meta.JmsProducerId;
import org.apache.qpid.jms.meta.JmsProducerInfo;
import org.apache.qpid.jms.meta.JmsResource;
import org.apache.qpid.jms.meta.JmsSessionId;
import org.apache.qpid.jms.meta.JmsSessionInfo;
import org.apache.qpid.jms.meta.JmsTransactionId;
import org.apache.qpid.jms.meta.JmsTransactionInfo;
import org.apache.qpid.jms.policy.JmsDeserializationPolicy;
import org.apache.qpid.jms.policy.JmsMessageIDPolicy;
import org.apache.qpid.jms.policy.JmsPrefetchPolicy;
import org.apache.qpid.jms.policy.JmsPresettlePolicy;
import org.apache.qpid.jms.policy.JmsRedeliveryPolicy;
import org.apache.qpid.jms.provider.AsyncResult;
import org.apache.qpid.jms.provider.Provider;
import org.apache.qpid.jms.provider.ProviderClosedException;
import org.apache.qpid.jms.provider.ProviderConstants.ACK_TYPE;
import org.apache.qpid.jms.provider.ProviderFuture;
import org.apache.qpid.jms.provider.ProviderListener;
import org.apache.qpid.jms.provider.ProviderSynchronization;
import org.apache.qpid.jms.util.IdGenerator;
import org.apache.qpid.jms.util.ThreadPoolUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of a JMS Connection
 */
public class JmsConnection implements AutoCloseable, Connection, TopicConnection, QueueConnection, ProviderListener {

    private static final Logger LOG = LoggerFactory.getLogger(JmsConnection.class);

    private final IdGenerator clientIdGenerator;
    private final Map<JmsSessionId, JmsSession> sessions = new ConcurrentHashMap<JmsSessionId, JmsSession>();
    private final AtomicBoolean connected = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean failed = new AtomicBoolean();
    private final JmsConnectionInfo connectionInfo;
    private final ThreadPoolExecutor executor;

    private volatile IOException firstFailureError;
    private boolean clientIdSet;
    private ExceptionListener exceptionListener;
    private JmsMessageFactory messageFactory;
    private Provider provider;

    private final Set<JmsConnectionListener> connectionListeners =
        new CopyOnWriteArraySet<JmsConnectionListener>();
    private final Map<JmsTemporaryDestination, JmsTemporaryDestination> tempDestinations =
        new ConcurrentHashMap<JmsTemporaryDestination, JmsTemporaryDestination>();
    private final AtomicLong sessionIdGenerator = new AtomicLong();
    private final AtomicLong tempDestIdGenerator = new AtomicLong();
    private final AtomicLong transactionIdGenerator = new AtomicLong();

    private final Map<AsyncResult, AsyncResult> requests = new ConcurrentHashMap<AsyncResult, AsyncResult>();

    protected JmsConnection(final String connectionId, Provider provider, IdGenerator clientIdGenerator) throws JMSException {

        // This executor can be used for dispatching asynchronous tasks that might block or result
        // in reentrant calls to this Connection that could block.  The thread in this executor
        // will also serve as a means of preventing JVM shutdown should a client application
        // not have it's own mechanism for doing so.
        executor = new ThreadPoolExecutor(1, 1, 5, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(), new ThreadFactory() {
            @Override
            public Thread newThread(Runnable target) {
                Thread thread = new Thread(target, "QpidJMS Connection Executor: " + connectionId);
                thread.setDaemon(false);
                return thread;
            }
        });

        // We need to start the core thread in order for it to prevent JVM shutdown as our
        // single non-daemon thread.
        executor.execute(new Runnable() {
            @Override
            public void run() {
            }
        });

        this.provider = provider;
        this.provider.setProviderListener(this);
        try {
            this.provider.start();
        } catch (Exception e) {
            executor.shutdown();
            throw JmsExceptionSupport.create(e);
        }

        this.clientIdGenerator = clientIdGenerator;
        this.connectionInfo = new JmsConnectionInfo(new JmsConnectionId(connectionId));
    }

    @Override
    public void close() throws JMSException {
        boolean interrupted = Thread.interrupted();

        try {

            if (!closed.get() && !failed.get()) {
                // do not fail if already closed as specified by the JMS specification.
                doStop(false);
            }

            synchronized (this) {

                if (closed.get()) {
                    return;
                }

                closing.set(true);

                for (JmsSession session : sessions.values()) {
                    session.shutdown();
                }

                sessions.clear();
                tempDestinations.clear();

                if (isConnected() && !failed.get()) {
                    ProviderFuture request = new ProviderFuture();
                    try {
                        provider.destroy(connectionInfo, request);

                        try {
                            request.sync();
                        } catch (Exception ex) {
                            // NOTE - Spec is a bit vague here, we don't fail if already closed but
                            //        in this case we really aren't closed yet so there could be an
                            //        argument that at this point an exception is still valid.
                            if (ex.getCause() instanceof InterruptedException) {
                                throw (InterruptedException) ex.getCause();
                            }
                            LOG.debug("Failed destroying Connection resource: {}", ex.getMessage());
                        }
                    } catch(ProviderClosedException pce) {
                        LOG.debug("Ignoring provider closed exception during connection close");
                    }
                }

                connected.set(false);
                started.set(false);
                closing.set(false);
                closed.set(true);
            }
        } catch (Exception e) {
            throw JmsExceptionSupport.create(e);
        } finally {
            try {
                ThreadPoolUtils.shutdown(executor);
            } catch (Throwable e) {
                LOG.warn("Error shutting down thread pool: " + executor + ". This exception will be ignored.", e);
            }

            if (provider != null) {
                provider.close();
                provider = null;
            }

            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Called to free all Connection resources.
     */
    protected void shutdown() throws JMSException {
        shutdown(null);
    }

    /**
     * Called to free all Connection resources.
     */
    protected void shutdown(Exception cause) throws JMSException {

        // NOTE - Once ConnectionConsumer is added we must shutdown those as well.

        for (JmsSession session : sessions.values()) {
            session.shutdown(cause);
        }

        if (isConnected() && !failed.get() && !closing.get()) {
            destroyResource(connectionInfo);
        }

        tempDestinations.clear();
        started.set(false);
        connected.set(false);
    }

    @Override
    public Session createSession(boolean transacted, int acknowledgeMode) throws JMSException {
        checkClosedOrFailed();
        connect();
        int ackMode = getSessionAcknowledgeMode(transacted, acknowledgeMode);
        JmsSession result = new JmsSession(this, getNextSessionId(), ackMode);
        addSession(result.getSessionInfo(), result);
        if (started.get()) {
            result.start();
        }
        return result;
    }

    @Override
    public synchronized String getClientID() throws JMSException {
        checkClosedOrFailed();
        return this.connectionInfo.getClientId();
    }

    @Override
    public ConnectionMetaData getMetaData() throws JMSException {
        checkClosedOrFailed();
        return JmsConnectionMetaData.INSTANCE;
    }

    @Override
    public synchronized void setClientID(String clientID) throws JMSException {
        checkClosedOrFailed();

        if (clientIdSet) {
            throw new IllegalStateException("The clientID has already been set");
        }
        if (clientID == null || clientID.isEmpty()) {
            throw new InvalidClientIDException("Cannot have a null or empty clientID");
        }
        if (connected.get()) {
            throw new IllegalStateException("Cannot set the client id once connected.");
        }

        this.connectionInfo.setClientId(clientID);
        this.clientIdSet = true;

        // We weren't connected if we got this far, we should now connect to ensure the
        // configured clientID is valid.
        connect();
    }

    @Override
    public void start() throws JMSException {
        checkClosedOrFailed();
        connect();
        if (started.compareAndSet(false, true)) {
            try {
                for (JmsSession s : sessions.values()) {
                    s.start();
                }
            } catch (Exception e) {
                throw JmsExceptionSupport.create(e);
            }
        }
    }

    @Override
    public void stop() throws JMSException {
        doStop(true);
    }

    /**
     * Performs the actual connection stop with the option to disable the close check in cases
     * where we don't want an exception.
     *
     * @see #stop()
     * @param checkClosed <tt>true</tt> to check for already closed and throw
     *                    {@link java.lang.IllegalStateException} if already closed,
     *                    <tt>false</tt> to skip this check
     * @throws JMSException if the JMS provider fails to stop message delivery due to some internal error.
     */
    void doStop(boolean checkClosed) throws JMSException {
        if (checkClosed) {
            checkClosedOrFailed();
        }
        if (started.compareAndSet(true, false)) {
            synchronized(sessions) {
                for (JmsSession s : sessions.values()) {
                    s.stop();
                }
            }
        }
    }

    @Override
    public ConnectionConsumer createDurableConnectionConsumer(Topic topic, String subscriptionName,
                                                              String messageSelector, ServerSessionPool sessionPool, int maxMessages) throws JMSException {
        checkClosedOrFailed();
        connect();
        throw new JMSException("Not supported");
    }

    @Override
    public ConnectionConsumer createConnectionConsumer(Destination destination, String messageSelector,
                                                       ServerSessionPool sessionPool, int maxMessages) throws JMSException {
        checkClosedOrFailed();
        connect();
        throw new JMSException("Not supported");
    }

    @Override
    public ConnectionConsumer createConnectionConsumer(Topic topic, String messageSelector,
                                                       ServerSessionPool sessionPool, int maxMessages) throws JMSException {
        checkClosedOrFailed();
        connect();
        throw new JMSException("Not supported");
    }

    @Override
    public ConnectionConsumer createConnectionConsumer(Queue queue, String messageSelector,
                                                       ServerSessionPool sessionPool, int maxMessages) throws JMSException {
        checkClosedOrFailed();
        connect();
        throw new JMSException("Not supported");
    }

    @Override
    public TopicSession createTopicSession(boolean transacted, int acknowledgeMode) throws JMSException {
        checkClosedOrFailed();
        connect();
        int ackMode = getSessionAcknowledgeMode(transacted, acknowledgeMode);
        JmsTopicSession result = new JmsTopicSession(this, getNextSessionId(), ackMode);
        addSession(result.getSessionInfo(), result);
        if (started.get()) {
            result.start();
        }
        return result;
    }

    @Override
    public QueueSession createQueueSession(boolean transacted, int acknowledgeMode) throws JMSException {
        checkClosedOrFailed();
        connect();
        int ackMode = getSessionAcknowledgeMode(transacted, acknowledgeMode);
        JmsQueueSession result = new JmsQueueSession(this, getNextSessionId(), ackMode);
        addSession(result.getSessionInfo(), result);
        if (started.get()) {
            result.start();
        }
        return result;
    }

    public void onException(Exception ex) {
        onException(JmsExceptionSupport.create(ex));
    }

    public void onException(JMSException ex) {
        ExceptionListener listener = this.exceptionListener;
        if (listener != null) {
            listener.onException(JmsExceptionSupport.create(ex));
        }
    }

    protected int getSessionAcknowledgeMode(boolean transacted, int acknowledgeMode) throws JMSException {
        int result = acknowledgeMode;
        if (!transacted && acknowledgeMode == Session.SESSION_TRANSACTED) {
            throw new JMSException("acknowledgeMode SESSION_TRANSACTED cannot be used for an non-transacted Session");
        }

        if (transacted) {
            result = Session.SESSION_TRANSACTED;
        } else if (acknowledgeMode < Session.SESSION_TRANSACTED || acknowledgeMode > Session.DUPS_OK_ACKNOWLEDGE){
            throw new JMSException("acknowledgeMode " + acknowledgeMode + " cannot be used for an non-transacted Session");
        }

        return result;
    }

    protected void removeSession(JmsSessionInfo sessionInfo) throws JMSException {
        sessions.remove(sessionInfo.getId());
    }

    protected void addSession(JmsSessionInfo sessionInfo, JmsSession session) {
        sessions.put(sessionInfo.getId(), session);
    }

    private void connect() throws JMSException {
        if (isConnected() || closed.get()) {
            return;
        }

        synchronized(this.connectionInfo) {
            if (isConnected() || closed.get()) {
                return;
            }

            if (connectionInfo.getClientId() == null || connectionInfo.getClientId().trim().isEmpty()) {
                connectionInfo.setClientId(clientIdGenerator.generateId());
            }

            createResource(connectionInfo);
            connected.set(true);
        }
    }

    /**
     * @return a newly initialized TemporaryQueue instance.
     */
    protected TemporaryQueue createTemporaryQueue() throws JMSException {
        String destinationName = connectionInfo.getId() + ":" + tempDestIdGenerator.incrementAndGet();
        JmsTemporaryQueue queue = new JmsTemporaryQueue(destinationName);
        createResource(queue);
        tempDestinations.put(queue, queue);
        queue.setConnection(this);
        return queue;
    }

    /**
     * @return a newly initialized TemporaryTopic instance.
     */
    protected TemporaryTopic createTemporaryTopic() throws JMSException {
        String destinationName = connectionInfo.getId() + ":" + tempDestIdGenerator.incrementAndGet();
        JmsTemporaryTopic topic = new JmsTemporaryTopic(destinationName);
        createResource(topic);
        tempDestinations.put(topic, topic);
        topic.setConnection(this);
        return topic;
    }

    protected void deleteTemporaryDestination(JmsTemporaryDestination destination) throws JMSException {
        checkClosedOrFailed();

        try {
            for (JmsSession session : sessions.values()) {
                if (session.isDestinationInUse(destination)) {
                    throw new IllegalStateException("A consumer is consuming from the temporary destination");
                }
            }

            tempDestinations.remove(destination);

            destroyResource(destination);
        } catch (Exception e) {
            throw JmsExceptionSupport.create(e);
        }
    }

    protected void checkClosedOrFailed() throws JMSException {
        checkClosed();
        if (failed.get()) {
            throw new JmsConnectionFailedException(firstFailureError);
        }
    }

    protected void checkConsumeFromTemporaryDestination(JmsTemporaryDestination destination) throws JMSException {
        if (!this.equals(destination.getConnection())) {
            throw new InvalidDestinationException("Can't consume from a temporary destination created using another connection");
        }
    }

    protected boolean isTemporaryDestinationDeleted(JmsDestination destination) {
        return !tempDestinations.containsKey(destination);
    }

    protected void checkClosed() throws IllegalStateException {
        if (closed.get()) {
            throw new IllegalStateException("The Connection is closed");
        }
    }

    protected JmsSessionId getNextSessionId() {
        return new JmsSessionId(connectionInfo.getId(), sessionIdGenerator.incrementAndGet());
    }

    protected JmsTransactionId getNextTransactionId() {
        return new JmsTransactionId(connectionInfo.getId(), transactionIdGenerator.incrementAndGet());
    }

    protected synchronized boolean isExplicitClientID() {
        return clientIdSet;
    }

    //----- Provider interface methods ---------------------------------------//

    void createResource(JmsResource resource) throws JMSException {
        createResource(resource, null);
    }

    void createResource(JmsResource resource, ProviderSynchronization synchronization) throws JMSException {
        checkClosedOrFailed();

        try {
            ProviderFuture request = new ProviderFuture(synchronization);
            requests.put(request, request);
            try {
                provider.create(resource, request);
                request.sync();
            } finally {
                requests.remove(request);
            }
        } catch (Exception ex) {
            throw JmsExceptionSupport.create(ex);
        }
    }

    void startResource(JmsResource resource) throws JMSException {
        startResource(resource, null);
    }

    void startResource(JmsResource resource, ProviderSynchronization synchronization) throws JMSException {
        checkClosedOrFailed();

        try {
            ProviderFuture request = new ProviderFuture(synchronization);
            requests.put(request, request);
            try {
                provider.start(resource, request);
                request.sync();
            } finally {
                requests.remove(request);
            }
        } catch (Exception ioe) {
            throw JmsExceptionSupport.create(ioe);
        }
    }

    void stopResource(JmsResource resource) throws JMSException {
        stopResource(resource, null);
    }

    void stopResource(JmsResource resource, ProviderSynchronization synchronization) throws JMSException {
        checkClosedOrFailed();

        try {
            ProviderFuture request = new ProviderFuture(synchronization);
            requests.put(request, request);
            try {
                provider.stop(resource, request);
                request.sync();
            } finally {
                requests.remove(request);
            }
        } catch (Exception ioe) {
            throw JmsExceptionSupport.create(ioe);
        }
    }

    void destroyResource(JmsResource resource) throws JMSException {
        destroyResource(resource, null);
    }

    void destroyResource(JmsResource resource, ProviderSynchronization synchronization) throws JMSException {
        checkClosedOrFailed();

        try {
            ProviderFuture request = new ProviderFuture(synchronization);
            requests.put(request, request);
            try {
                provider.destroy(resource, request);
                request.sync();
            } finally {
                requests.remove(request);
            }
        } catch (Exception ioe) {
            throw JmsExceptionSupport.create(ioe);
        }
    }

    void send(JmsOutboundMessageDispatch envelope) throws JMSException {
        send(envelope, null);
    }

    void send(JmsOutboundMessageDispatch envelope, ProviderSynchronization synchronization) throws JMSException {
        checkClosedOrFailed();

        try {
            ProviderFuture request = new ProviderFuture(synchronization);
            requests.put(request, request);
            try {
                provider.send(envelope, request);
                request.sync();
            } finally {
                requests.remove(request);
            }
        } catch (Exception ioe) {
            throw JmsExceptionSupport.create(ioe);
        }
    }

    void acknowledge(JmsInboundMessageDispatch envelope, ACK_TYPE ackType) throws JMSException {
        acknowledge(envelope, ackType, null);
    }

    void acknowledge(JmsInboundMessageDispatch envelope, ACK_TYPE ackType, ProviderSynchronization synchronization) throws JMSException {
        checkClosedOrFailed();

        try {
            ProviderFuture request = new ProviderFuture(synchronization);
            provider.acknowledge(envelope, ackType, request);
            request.sync();
        } catch (Exception ioe) {
            throw JmsExceptionSupport.create(ioe);
        }
    }

    void acknowledge(JmsSessionId sessionId, ACK_TYPE ackType) throws JMSException {
        acknowledge(sessionId, ackType, null);
    }

    void acknowledge(JmsSessionId sessionId, ACK_TYPE ackType, ProviderSynchronization synchronization) throws JMSException {
        checkClosedOrFailed();

        try {
            ProviderFuture request = new ProviderFuture(synchronization);
            provider.acknowledge(sessionId, ackType, request);
            request.sync();
        } catch (Exception ioe) {
            throw JmsExceptionSupport.create(ioe);
        }
    }

    void unsubscribe(String name) throws JMSException {
        unsubscribe(name, null);
    }

    void unsubscribe(String name, ProviderSynchronization synchronization) throws JMSException {
        checkClosedOrFailed();

        try {
            ProviderFuture request = new ProviderFuture(synchronization);
            requests.put(request, request);
            try {
                provider.unsubscribe(name, request);
                request.sync();
            } finally {
                requests.remove(request);
            }
        } catch (Exception ioe) {
            throw JmsExceptionSupport.create(ioe);
        }
    }

    void commit(JmsTransactionInfo transactionInfo) throws JMSException {
        commit(transactionInfo, null);
    }

    void commit(JmsTransactionInfo transactionInfo, ProviderSynchronization synchronization) throws JMSException {
        checkClosedOrFailed();

        try {
            ProviderFuture request = new ProviderFuture(synchronization);
            requests.put(request, request);
            try {
                provider.commit(transactionInfo, request);
                request.sync();
            } finally {
                requests.remove(request);
            }
        } catch (Exception ioe) {
            throw JmsExceptionSupport.create(ioe);
        }
    }

    void rollback(JmsTransactionInfo transactionInfo) throws JMSException {
        rollback(transactionInfo, null);
    }

    void rollback(JmsTransactionInfo transactionInfo, ProviderSynchronization synchronization) throws JMSException {
        checkClosedOrFailed();

        try {
            ProviderFuture request = new ProviderFuture(synchronization);
            requests.put(request, request);
            try {
                provider.rollback(transactionInfo, request);
                request.sync();
            } finally {
                requests.remove(request);
            }
        } catch (Exception ioe) {
            throw JmsExceptionSupport.create(ioe);
        }
    }

    void recover(JmsSessionId sessionId) throws JMSException {
        recover(sessionId, null);
    }

    void recover(JmsSessionId sessionId, ProviderSynchronization synchronization) throws JMSException {
        checkClosedOrFailed();

        try {
            ProviderFuture request = new ProviderFuture(synchronization);
            requests.put(request, request);
            try {
                provider.recover(sessionId, request);
                request.sync();
            } finally {
                requests.remove(request);
            }
        } catch (Exception ioe) {
            throw JmsExceptionSupport.create(ioe);
        }
    }

    void pull(JmsConsumerId consumerId, long timeout) throws JMSException {
        pull(consumerId, timeout, null);
    }

    void pull(JmsConsumerId consumerId, long timeout, ProviderSynchronization synchronization) throws JMSException {
        checkClosedOrFailed();

        try {
            ProviderFuture request = new ProviderFuture(synchronization);
            requests.put(request, request);
            try {
                provider.pull(consumerId, timeout, request);
                request.sync();
            } finally {
                requests.remove(request);
            }
        } catch (Exception ioe) {
            throw JmsExceptionSupport.create(ioe);
        }
    }

    //----- Property setters and getters -------------------------------------//

    @Override
    public ExceptionListener getExceptionListener() throws JMSException {
        checkClosedOrFailed();
        return this.exceptionListener;
    }

    @Override
    public void setExceptionListener(ExceptionListener listener) throws JMSException {
        checkClosedOrFailed();
        this.exceptionListener = listener;
    }

    /**
     * Adds a JmsConnectionListener so that a client can be notified of events in
     * the underlying connection and its state.
     *
     * @param listener
     *        the new listener to add to the collection.
     */
    public void addConnectionListener(JmsConnectionListener listener) {
        connectionListeners.add(listener);
    }

    /**
     * Removes a JmsConnectionListener that was previously registered.
     *
     * @param listener
     *        the listener to remove from the collection.
     *
     * @return true if the given listener was removed from the current set.
     */
    public boolean removeConnectionListener(JmsConnectionListener listener) {
        return connectionListeners.remove(listener);
    }

    public boolean isForceAsyncSend() {
        return connectionInfo.isForceAsyncSend();
    }

    public void setForceAsyncSend(boolean forceAsyncSend) {
        connectionInfo.setForceAsyncSends(forceAsyncSend);
    }

    public boolean isForceSyncSend() {
        return connectionInfo.isForceSyncSend();
    }

    public void setForceSyncSend(boolean alwaysSyncSend) {
        connectionInfo.setForceSyncSend(alwaysSyncSend);
    }

    public String getTopicPrefix() {
        return connectionInfo.getTopicPrefix();
    }

    public void setTopicPrefix(String topicPrefix) {
        connectionInfo.setTopicPrefix(topicPrefix);
    }

    public String getQueuePrefix() {
        return connectionInfo.getQueuePrefix();
    }

    public void setQueuePrefix(String queuePrefix) {
        connectionInfo.setQueuePrefix(queuePrefix);
    }

    public boolean isValidatePropertyNames() {
        return connectionInfo.isValidatePropertyNames();
    }

    public void setValidatePropertyNames(boolean validatePropertyNames) {
        connectionInfo.setValidatePropertyNames(validatePropertyNames);
    }

    public JmsPrefetchPolicy getPrefetchPolicy() {
        return connectionInfo.getPrefetchPolicy();
    }

    public void setPrefetchPolicy(JmsPrefetchPolicy prefetchPolicy) {
        connectionInfo.setPrefetchPolicy(prefetchPolicy);
    }

    public JmsRedeliveryPolicy getRedeliveryPolicy() {
        return connectionInfo.getRedeliveryPolicy();
    }

    public void setRedeliveryPolicy(JmsRedeliveryPolicy redeliveryPolicy) {
        connectionInfo.setRedeliveryPolicy(redeliveryPolicy);
    }

    public JmsPresettlePolicy getPresettlePolicy() {
        return connectionInfo.getPresettlePolicy();
    }

    public void setPresettlePolicy(JmsPresettlePolicy presettlePolicy) {
        connectionInfo.setPresettlePolicy(presettlePolicy);
    }

    public JmsDeserializationPolicy getDeserializationPolicy() {
        return connectionInfo.getDeserializationPolicy();
    }

    public void setDeserializationPolicy(JmsDeserializationPolicy deserializationPolicy) {
        connectionInfo.setDeserializationPolicy(deserializationPolicy);
    }

    public boolean isReceiveLocalOnly() {
        return connectionInfo.isReceiveLocalOnly();
    }

    public void setReceiveLocalOnly(boolean receiveLocalOnly) {
        this.connectionInfo.setReceiveLocalOnly(receiveLocalOnly);
    }

    public boolean isReceiveNoWaitLocalOnly() {
        return connectionInfo.isReceiveNoWaitLocalOnly();
    }

    public void setReceiveNoWaitLocalOnly(boolean receiveNoWaitLocalOnly) {
        this.connectionInfo.setReceiveNoWaitLocalOnly(receiveNoWaitLocalOnly);
    }

    public boolean isLocalMessagePriority() {
        return connectionInfo.isLocalMessagePriority();
    }

    public void setLocalMessagePriority(boolean localMessagePriority) {
        this.connectionInfo.setLocalMessagePriority(localMessagePriority);
    }

    public long getCloseTimeout() {
        return connectionInfo.getCloseTimeout();
    }

    public void setCloseTimeout(long closeTimeout) {
        connectionInfo.setCloseTimeout(closeTimeout);
    }

    public long getConnectTimeout() {
        return connectionInfo.getConnectTimeout();
    }

    public void setConnectTimeout(long connectTimeout) {
        this.connectionInfo.setConnectTimeout(connectTimeout);
    }

    public long getSendTimeout() {
        return connectionInfo.getSendTimeout();
    }

    public void setSendTimeout(long sendTimeout) {
        connectionInfo.setSendTimeout(sendTimeout);
    }

    public long getRequestTimeout() {
        return connectionInfo.getRequestTimeout();
    }

    public void setRequestTimeout(long requestTimeout) {
        connectionInfo.setRequestTimeout(requestTimeout);
    }

    public URI getConfiguredURI() {
        return connectionInfo.getConfiguredURI();
    }

    void setConfiguredURI(URI uri) {
        connectionInfo.setConfiguredURI(uri);
    }

    public URI getConnectedURI() {
        return connectionInfo.getConnectedURI();
    }

    void setConnectedURI(URI connectedURI) {
        connectionInfo.setConnectedURI(connectedURI);
    }

    public String getUsername() {
        return connectionInfo.getUsername();
    }

    void setUsername(String username) {
        connectionInfo.setUsername(username);;
    }

    byte[] getEncodedUsername() {
        return connectionInfo.getEncodedUsername();
    }

    public String getPassword() {
        return connectionInfo.getPassword();
    }

    void setPassword(String password) {
        this.connectionInfo.setPassword(password);
    }

    public boolean isConnected() {
        return connected.get();
    }

    public boolean isStarted() {
        return started.get();
    }

    public boolean isClosed() {
        return closed.get();
    }

    public boolean isFailed() {
        return failed.get();
    }

    public JmsConnectionId getId() {
        return connectionInfo.getId();
    }

    public JmsMessageFactory getMessageFactory() {
        if (messageFactory == null) {
            throw new RuntimeException("Message factory should never be null");
        }
        return messageFactory;
    }

    void setMessageFactory(JmsMessageFactory factory) {
        messageFactory = factory;
    }

    public boolean isForceAsyncAcks() {
        return connectionInfo.isForceAsyncAcks();
    }

    public void setForceAsyncAcks(boolean forceAsyncAcks) {
        connectionInfo.setForceAsyncAcks(forceAsyncAcks);
    }

    public boolean isLocalMessageExpiry() {
        return connectionInfo.isLocalMessageExpiry();
    }

    public void setLocalMessageExpiry(boolean localMessageExpiry) {
        connectionInfo.setLocalMessageExpiry(localMessageExpiry);
    }

    public JmsMessageIDPolicy getMessageIDPolicy() {
        return connectionInfo.getMessageIDPolicy();
    }

    public void setMessageIDPolicy(JmsMessageIDPolicy messageIDPolicy) {
        connectionInfo.setMessageIDPolicy(messageIDPolicy);
    }

    public boolean isPopulateJMSXUserID() {
        return connectionInfo.isPopulateJMSXUserID();
    }

    public void setPopulateJMSXUserID(boolean populateJMSXUserID) {
        connectionInfo.setPopulateJMSXUserID(populateJMSXUserID);
    }

    //----- Async event handlers ---------------------------------------------//

    @Override
    public void onInboundMessage(final JmsInboundMessageDispatch envelope) {

        JmsMessage incoming = envelope.getMessage();
        if (incoming != null) {
            // Ensure incoming Messages are in readonly mode.
            incoming.setReadOnlyBody(true);
            incoming.setReadOnlyProperties(true);

            incoming.setValidatePropertyNames(isValidatePropertyNames());
        }

        JmsMessageDispatcher dispatcher = sessions.get(envelope.getConsumerId().getParentId());
        if (dispatcher != null) {
            dispatcher.onInboundMessage(envelope);
        }

        // Run the application callbacks on the connection executor to allow the provider to
        // return to its normal processing without waiting for client level processing to finish.
        if (!connectionListeners.isEmpty()) {
            for (final JmsConnectionListener listener : connectionListeners) {
                executor.submit(new Runnable() {

                    @Override
                    public void run() {
                        listener.onInboundMessage(envelope);
                    }
                });
            }
        }
    }

    @Override
    public void onConnectionInterrupted(final URI remoteURI) {
        for (JmsSession session : sessions.values()) {
            session.onConnectionInterrupted();
        }

        // Run the application callbacks on the connection executor to allow the provider to
        // return to its normal processing without waiting for client level processing to finish.
        for (final JmsConnectionListener listener : connectionListeners) {
            executor.submit(new Runnable() {

                @Override
                public void run() {
                    listener.onConnectionInterrupted(remoteURI);
                }
            });
        }
    }

    @Override
    public void onConnectionRecovery(Provider provider) throws Exception {
        LOG.debug("Connection {} is starting recovery.", connectionInfo.getId());

        ProviderFuture request = new ProviderFuture();
        provider.create(connectionInfo, request);
        request.sync();

        for (JmsTemporaryDestination tempDestination : tempDestinations.values()) {
            createResource(tempDestination);
        }

        for (JmsSession session : sessions.values()) {
            session.onConnectionRecovery(provider);
        }
    }

    @Override
    public void onConnectionRecovered(Provider provider) throws Exception {
        LOG.debug("Connection {} is finalizing recovery.", connectionInfo.getId());

        setMessageFactory(provider.getMessageFactory());
        setConnectedURI(provider.getRemoteURI());

        for (JmsSession session : sessions.values()) {
            session.onConnectionRecovered(provider);
        }
    }

    @Override
    public void onConnectionRestored(final URI remoteURI) {
        for (JmsSession session : sessions.values()) {
            session.onConnectionRestored();
        }

        // Run the application callbacks on the connection executor to allow the provider to
        // return to its normal processing without waiting for client level processing to finish.
        for (final JmsConnectionListener listener : connectionListeners) {
            executor.submit(new Runnable() {

                @Override
                public void run() {
                    listener.onConnectionRestored(remoteURI);
                }
            });
        }
    }

    @Override
    public void onConnectionEstablished(final URI remoteURI) {
        LOG.info("Connection {} connected to remote Broker: {}", connectionInfo.getId(), remoteURI);
        setMessageFactory(provider.getMessageFactory());
        setConnectedURI(provider.getRemoteURI());

        // Run the application callbacks on the connection executor to allow the provider to
        // return to its normal processing without waiting for client level processing to finish.
        for (final JmsConnectionListener listener : connectionListeners) {
            executor.submit(new Runnable() {

                @Override
                public void run() {
                    listener.onConnectionEstablished(remoteURI);
                }
            });
        }
    }

    @Override
    public void onConnectionFailure(final IOException ex) {
        providerFailed(ex);

        onProviderException(ex);

        for (AsyncResult request : requests.keySet()) {
            try {
                request.onFailure(ex);
            } catch (Exception e) {
                LOG.debug("Exception during request cleanup", e);
            }
        }

        if (!closing.get() && !closed.get()) {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    if (provider != null) {
                        try {
                            provider.close();
                        } catch (Throwable error) {
                            LOG.debug("Error while closing failed Provider: {}", error.getMessage());
                        }
                    }

                    for (AsyncResult request : requests.keySet()) {
                        try {
                            request.onFailure(ex);
                        } catch (Exception e) {
                            LOG.debug("Exception during request cleanup", e);
                        }
                    }

                    try {
                        shutdown(ex);
                    } catch (JMSException e) {
                        LOG.warn("Exception during connection cleanup, " + e, e);
                    }

                    for (JmsConnectionListener listener : connectionListeners) {
                        listener.onConnectionFailure(ex);
                    }
                }
            });
        }
    }

    @Override
    public void onResourceClosed(final JmsResource resource, final Exception cause) {
        // Closure of the Connection itself is notified via onConnectionFailure

        // Run on the connection executor to free the provider to go do more work and avoid
        // any chance of a deadlock if the code ever looped back to the provider.

        if (!closing.get() && !closed.get()) {

            // Set the failure cause indicator now to more quickly reflect the correct
            // state in the resource.  The actual shutdown and clean will be done on the
            // connection executor thread to avoid looping or stalling the provider thread.
            if (resource instanceof JmsSessionInfo) {
                JmsSession session = sessions.get(resource.getId());
                if (session != null) {
                    session.setFailureCause(cause);
                }
            } else if (resource instanceof JmsProducerInfo) {
                JmsSessionId parentId = ((JmsProducerInfo) resource).getParentId();
                JmsSession session = sessions.get(parentId);
                if (session != null) {
                    JmsMessageProducer producer = session.lookup((JmsProducerId) resource.getId());
                    if (producer != null) {
                        producer.setFailureCause(cause);
                    }
                }
            } else if (resource instanceof JmsConsumerInfo) {
                JmsSessionId parentId = ((JmsConsumerInfo) resource).getParentId();
                JmsSession session = sessions.get(parentId);
                if (session != null) {
                    JmsMessageConsumer consumer = session.lookup((JmsConsumerId) resource.getId());
                    if (consumer != null) {
                        consumer.setFailureCause(cause);
                    }
                }
            }

            executor.execute(new Runnable() {

                @Override
                public void run() {
                    if (resource instanceof JmsSessionInfo) {
                        JmsSession session = sessions.get(resource.getId());
                        if (session != null) {
                            session.sessionClosed(cause);
                            for (JmsConnectionListener listener : connectionListeners) {
                                listener.onSessionClosed(session, cause);
                            }
                        }
                    } else if (resource instanceof JmsProducerInfo) {
                        JmsSessionId parentId = ((JmsProducerInfo) resource).getParentId();
                        JmsSession session = sessions.get(parentId);
                        if (session != null) {
                            JmsMessageProducer producer = session.producerClosed((JmsProducerInfo) resource, cause);
                            if (producer != null) {
                                for (JmsConnectionListener listener : connectionListeners) {
                                    listener.onProducerClosed(producer, cause);
                                }
                            }
                        }
                    } else if (resource instanceof JmsConsumerInfo) {
                        JmsSessionId parentId = ((JmsConsumerInfo) resource).getParentId();
                        JmsSession session = sessions.get(parentId);
                        if (session != null) {
                            JmsMessageConsumer consumer = session.consumerClosed((JmsConsumerInfo) resource, cause);
                            if (consumer != null) {
                                for (JmsConnectionListener listener : connectionListeners) {
                                    listener.onConsumerClosed(consumer, cause);
                                }
                            }
                        }
                    } else {
                        LOG.info("A JMS resource has been remotely closed: {}", resource);
                    }
                }
            });
        }
    }

    @Override
    public void onProviderException(final Exception cause) {
        // Report this to any registered exception listener, let the receiver
        // decide if it should be fatal.
        onAsyncException(cause);
    }

    /**
     * Handles any asynchronous errors that occur from the JMS framework classes.
     *
     * If any listeners are registered they will be notified of the error from a thread
     * in the Connection's Executor service.
     *
     * @param error
     *        The exception that triggered this error.
     */
    public void onAsyncException(Throwable error) {
        if (!closed.get() && !closing.get()) {
            if (this.exceptionListener != null) {

                if (!(error instanceof JMSException)) {
                    error = JmsExceptionSupport.create(error);
                }
                final JMSException jmsError = (JMSException)error;

                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        JmsConnection.this.exceptionListener.onException(jmsError);
                    }
                });
            } else {
                LOG.debug("Async exception with no exception listener: {}", error, error);
            }
        }
    }

    protected void providerFailed(IOException error) {
        failed.set(true);
        if (firstFailureError == null) {
            firstFailureError = error;
        }
    }

    public Session createSession() throws JMSException {
        throw new UnsupportedOperationException("JMS 2.0 functionality not supported in this version");
    }

    public Session createSession(int sessionMode) throws JMSException {
        throw new UnsupportedOperationException("JMS 2.0 functionality not supported in this version");
    }

    public ConnectionConsumer createSharedConnectionConsumer(Topic topic, String name, String selector,
            ServerSessionPool pool, int maxMessages) throws JMSException {
        throw new UnsupportedOperationException("JMS 2.0 functionality not supported in this version");
    }

    public ConnectionConsumer createSharedDurableConnectionConsumer(Topic topic, String name, String selector,
            ServerSessionPool pool, int maxMessages) throws JMSException {
        throw new UnsupportedOperationException("JMS 2.0 functionality not supported in this version");
    }
}
