/*
 *      Copyright (C) 2012-2014 DataStax Inc.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package com.datastax.driver.core;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import com.codahale.metrics.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.datastax.driver.core.exceptions.DriverException;
import com.datastax.driver.core.exceptions.DriverInternalError;
import com.datastax.driver.core.exceptions.NoHostAvailableException;
import com.datastax.driver.core.exceptions.ReadTimeoutException;
import com.datastax.driver.core.exceptions.UnavailableException;
import com.datastax.driver.core.exceptions.WriteTimeoutException;
import com.datastax.driver.core.policies.*;
import com.datastax.driver.core.policies.RetryPolicy.RetryDecision.Type;

/**
 * Handles a request to cassandra, dealing with host failover and retries on
 * unavailable/timeout.
 */
class RequestHandler implements Connection.ResponseCallback {
    private static final Logger logger = LoggerFactory.getLogger(RequestHandler.class);

    private final SessionManager manager;
    private final Callback callback;

    private final Iterator<Host> queryPlan;
    private final Statement statement;
    private volatile Host current;
    private volatile List<Host> triedHosts;
    private volatile HostConnectionPool currentPool;
    private final AtomicReference<QueryState> queryStateRef;

    // This represents the number of times a retry has been triggered by the RetryPolicy (this is different from
    // queryStateRef.get().retryCount, because some retries don't involve the policy, for example after an
    // OVERLOADED error).
    // This is incremented by one writer at a time, so volatile is good enough.
    private volatile int retriesByPolicy;

    private volatile ConsistencyLevel retryConsistencyLevel;

    private volatile Map<InetSocketAddress, Throwable> errors;

    private volatile boolean isCanceled;
    private volatile Connection.ResponseHandler connectionHandler;

    private final Timer.Context timerContext;
    private final long startTime;

    public RequestHandler(SessionManager manager, Callback callback, Statement statement) {
        this.manager = manager;
        this.callback = callback;

        callback.register(this);

        this.queryPlan = manager.loadBalancingPolicy().newQueryPlan(manager.poolsState.keyspace, statement);
        this.statement = statement;
        this.queryStateRef = new AtomicReference<QueryState>(QueryState.INITIAL);

        this.timerContext = metricsEnabled()
                          ? metrics().getRequestsTimer().time()
                          : null;
        this.startTime = System.nanoTime();
    }

    private boolean metricsEnabled() {
        return manager.configuration().getMetricsOptions() != null;
    }

    private Metrics metrics() {
        return manager.cluster.manager.metrics;
    }

    public void sendRequest() {
        try {
            while (queryPlan.hasNext() && !isCanceled) {
                Host host = queryPlan.next();
                logger.trace("Querying node {}", host);
                if (query(host))
                    return;
            }
            setFinalException(null, new NoHostAvailableException(errors == null ? Collections.<InetSocketAddress, Throwable>emptyMap() : errors));
        } catch (Exception e) {
            // Shouldn't happen really, but if ever the loadbalancing policy returned iterator throws, we don't want to block.
            setFinalException(null, new DriverInternalError("An unexpected error happened while sending requests", e));
        }
    }

    private boolean query(Host host) {
        currentPool = manager.pools.get(host);
        if (currentPool == null || currentPool.isClosed())
            return false;

        PooledConnection connection = null;
        try {
            connection = currentPool.borrowConnection(manager.configuration().getPoolingOptions().getPoolTimeoutMillis(), TimeUnit.MILLISECONDS);
            if (current != null) {
                if (triedHosts == null)
                    triedHosts = new ArrayList<Host>();
                triedHosts.add(current);
            }
            current = host;
            connectionHandler = connection.write(this);
            return true;
        } catch (ConnectionException e) {
            // If we have any problem with the connection, move to the next node.
            if (metricsEnabled())
                metrics().getErrorMetrics().getConnectionErrors().inc();
            if (connection != null)
                connection.release();
            logError(host.getSocketAddress(), e);
            return false;
        } catch (BusyConnectionException e) {
            // The pool shouldn't have give us a busy connection unless we've maxed up the pool, so move on to the next host.
            connection.release();
            logError(host.getSocketAddress(), e);
            return false;
        } catch (TimeoutException e) {
            // We timeout, log it but move to the next node.
            logError(host.getSocketAddress(), new DriverException("Timeout while trying to acquire available connection (you may want to increase the driver number of per-host connections)"));
            return false;
        } catch (RuntimeException e) {
            if (connection != null)
                connection.release();
            logger.error("Unexpected error while querying " + host.getAddress(), e);
            logError(host.getSocketAddress(), e);
            return false;
        }
    }

    private void logError(InetSocketAddress address, Throwable exception) {
        logger.debug("Error querying {}, trying next host (error is: {})", address, exception.toString());
        if (errors == null)
            errors = new HashMap<InetSocketAddress, Throwable>();
        errors.put(address, exception);
    }

    private void retry(final boolean retryCurrent, ConsistencyLevel newConsistencyLevel) {
        queryStateRef.set(queryStateRef.get().startNext());

        final Host h = current;
        this.retryConsistencyLevel = newConsistencyLevel;

        // We should not retry on the current thread as this will be an IO thread.
        manager.executor().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    if (retryCurrent) {
                        if (query(h))
                            return;
                    }
                    sendRequest();
                } catch (Exception e) {
                    setFinalException(null, new DriverInternalError("Unexpected exception while retrying query", e));
                }
            }
        });
    }

    public void cancel() {
        isCanceled = true;
        if (connectionHandler != null)
            connectionHandler.cancelHandler();
    }

    @Override
    public Message.Request request() {

        Message.Request request = callback.request();
        if (retryConsistencyLevel != null && retryConsistencyLevel != consistencyOf(request))
            request = manager.makeRequestMessage(statement, retryConsistencyLevel, serialConsistencyOf(request), pagingStateOf(request));
        return request;
    }

    private ConsistencyLevel consistencyOf(Message.Request request) {
        switch (request.type) {
            case QUERY:   return ((Requests.Query)request).options.consistency;
            case EXECUTE: return ((Requests.Execute)request).options.consistency;
            case BATCH:   return ((Requests.Batch)request).consistency;
            default:      return null;
        }
    }

    private ConsistencyLevel serialConsistencyOf(Message.Request request) {
        switch (request.type) {
            case QUERY:   return ((Requests.Query)request).options.serialConsistency;
            case EXECUTE: return ((Requests.Execute)request).options.serialConsistency;
            default:      return null;
        }
    }

    private ByteBuffer pagingStateOf(Message.Request request) {
        switch (request.type) {
            case QUERY:   return ((Requests.Query)request).options.pagingState;
            case EXECUTE: return ((Requests.Execute)request).options.pagingState;
            default:      return null;
        }
    }

    private void setFinalResult(Connection connection, Message.Response response) {
        try {
            if (timerContext != null)
                timerContext.stop();

            ExecutionInfo info = current.defaultExecutionInfo;
            if (triedHosts != null) {
                triedHosts.add(current);
                info = new ExecutionInfo(triedHosts);
            }
            if (retryConsistencyLevel != null)
                info = info.withAchievedConsistency(retryConsistencyLevel);
            callback.onSet(connection, response, info, statement, System.nanoTime() - startTime);
        } catch (Exception e) {
            callback.onException(connection, new DriverInternalError("Unexpected exception while setting final result from " + response, e), System.nanoTime() - startTime, retryCount());
        }
    }

    private void setFinalException(Connection connection, Exception exception) {
        try {
            if (timerContext != null)
                timerContext.stop();
        } finally {
            callback.onException(connection, exception, System.nanoTime() - startTime, retryCount());
        }
    }

    @Override
    public void onSet(Connection connection, Message.Response response, long latency, int retryCount) {
        QueryState queryState = queryStateRef.get();
        if (!queryState.isInProgressAt(retryCount) ||
            !queryStateRef.compareAndSet(queryState, queryState.complete())) {
            logger.debug("onSet triggered but the response was completed by another thread, cancelling (retryCount = {}, queryState = {}, queryStateRef = {})",
                         retryCount, queryState, queryStateRef.get());
            return;
        }

        Host queriedHost = current;
        try {
            if (connection instanceof PooledConnection)
                ((PooledConnection)connection).release();

            switch (response.type) {
                case RESULT:
                    setFinalResult(connection, response);
                    break;
                case ERROR:
                    Responses.Error err = (Responses.Error)response;
                    RetryPolicy.RetryDecision retry = null;
                    RetryPolicy retryPolicy = statement.getRetryPolicy() == null
                                            ? manager.configuration().getPolicies().getRetryPolicy()
                                            : statement.getRetryPolicy();
                    switch (err.code) {
                        case READ_TIMEOUT:
                            assert err.infos instanceof ReadTimeoutException;
                            if (metricsEnabled())
                                metrics().getErrorMetrics().getReadTimeouts().inc();

                            ReadTimeoutException rte = (ReadTimeoutException)err.infos;
                            retry = retryPolicy.onReadTimeout(statement,
                                                              rte.getConsistencyLevel(),
                                                              rte.getRequiredAcknowledgements(),
                                                              rte.getReceivedAcknowledgements(),
                                                              rte.wasDataRetrieved(),
                                                              retriesByPolicy);

                            if (metricsEnabled()) {
                                if (retry.getType() == Type.RETRY)
                                    metrics().getErrorMetrics().getRetriesOnReadTimeout().inc();
                                if (retry.getType() == Type.IGNORE)
                                    metrics().getErrorMetrics().getIgnoresOnReadTimeout().inc();
                            }
                            break;
                        case WRITE_TIMEOUT:
                            assert err.infos instanceof WriteTimeoutException;
                            if (metricsEnabled())
                                metrics().getErrorMetrics().getWriteTimeouts().inc();

                            WriteTimeoutException wte = (WriteTimeoutException)err.infos;
                            retry = retryPolicy.onWriteTimeout(statement,
                                                               wte.getConsistencyLevel(),
                                                               wte.getWriteType(),
                                                               wte.getRequiredAcknowledgements(),
                                                               wte.getReceivedAcknowledgements(),
                                                               retriesByPolicy);

                            if (metricsEnabled()) {
                                if (retry.getType() == Type.RETRY)
                                    metrics().getErrorMetrics().getRetriesOnWriteTimeout().inc();
                                if (retry.getType() == Type.IGNORE)
                                    metrics().getErrorMetrics().getIgnoresOnWriteTimeout().inc();
                            }
                            break;
                        case UNAVAILABLE:
                            assert err.infos instanceof UnavailableException;
                            if (metricsEnabled())
                                metrics().getErrorMetrics().getUnavailables().inc();

                            UnavailableException ue = (UnavailableException)err.infos;
                            retry = retryPolicy.onUnavailable(statement,
                                                              ue.getConsistencyLevel(),
                                                              ue.getRequiredReplicas(),
                                                              ue.getAliveReplicas(),
                                                              retriesByPolicy);

                            if (metricsEnabled()) {
                                if (retry.getType() == Type.RETRY)
                                    metrics().getErrorMetrics().getRetriesOnUnavailable().inc();
                                if (retry.getType() == Type.IGNORE)
                                    metrics().getErrorMetrics().getIgnoresOnUnavailable().inc();
                            }
                            break;
                        case OVERLOADED:
                            // Try another node
                            logger.warn("Host {} is overloaded, trying next host.", connection.address);
                            logError(connection.address, new DriverException("Host overloaded"));
                            if (metricsEnabled())
                                metrics().getErrorMetrics().getOthers().inc();
                            retry(false, null);
                            return;
                        case SERVER_ERROR:
                            // Defunct connection and try another node
                            logger.warn("{} replied with server error ({}), trying next host.", connection.address, err.message);
                            DriverException exception = new DriverException("Host replied with server error: " + err.message);
                            logError(connection.address, exception);
                            connection.defunct(exception);
                            if (metricsEnabled())
                                metrics().getErrorMetrics().getOthers().inc();
                            retry(false, null);
                            return;
                        case IS_BOOTSTRAPPING:
                            // Try another node
                            logger.error("Query sent to {} but it is bootstrapping. This shouldn't happen but trying next host.", connection.address);
                            logError(connection.address, new DriverException("Host is bootstrapping"));
                            if (metricsEnabled())
                                metrics().getErrorMetrics().getOthers().inc();
                            retry(false, null);
                            return;
                        case UNPREPARED:
                            assert err.infos instanceof MD5Digest;
                            MD5Digest id = (MD5Digest)err.infos;
                            PreparedStatement toPrepare = manager.cluster.manager.preparedQueries.get(id);
                            if (toPrepare == null) {
                                // This shouldn't happen
                                String msg = String.format("Tried to execute unknown prepared query %s", id);
                                logger.error(msg);
                                setFinalException(connection, new DriverInternalError(msg));
                                return;
                            }

                            logger.info("Query {} is not prepared on {}, preparing before retrying executing. "
                                      + "Seeing this message a few times is fine, but seeing it a lot may be source of performance problems",
                                        toPrepare.getQueryString(), connection.address);
                            String currentKeyspace = connection.keyspace();
                            String prepareKeyspace = toPrepare.getQueryKeyspace();
                            // This shouldn't happen in normal use, because a user shouldn't try to execute
                            // a prepared statement with the wrong keyspace set. However, if it does, we'd rather
                            // prepare the query correctly and let the query executing return a meaningful error message
                            if (prepareKeyspace != null && (currentKeyspace == null || !currentKeyspace.equals(prepareKeyspace)))
                            {
                                logger.debug("Setting keyspace for prepared query to {}", prepareKeyspace);
                                connection.setKeyspace(prepareKeyspace);
                            }

                            queryStateRef.set(queryStateRef.get().startNext());
                            try {
                                connection.write(prepareAndRetry(toPrepare.getQueryString()));
                            } finally {
                                // Always reset the previous keyspace if needed
                                if (connection.keyspace() == null || !connection.keyspace().equals(currentKeyspace))
                                {
                                    logger.debug("Setting back keyspace post query preparation to {}", currentKeyspace);
                                    connection.setKeyspace(currentKeyspace);
                                }
                            }
                            // we're done for now, the prepareAndRetry callback will handle the rest
                            return;
                        default:
                            if (metricsEnabled())
                                metrics().getErrorMetrics().getOthers().inc();
                            break;
                    }

                    if (retry == null)
                        setFinalResult(connection, response);
                    else {
                        switch (retry.getType()) {
                            case RETRY:
                                ++retriesByPolicy;
                                if (logger.isDebugEnabled())
                                    logger.debug("Doing retry {} for query {} at consistency {}", retriesByPolicy, statement, retry.getRetryConsistencyLevel());
                                if (metricsEnabled())
                                    metrics().getErrorMetrics().getRetries().inc();
                                retry(true, retry.getRetryConsistencyLevel());
                                break;
                            case RETHROW:
                                setFinalResult(connection, response);
                                break;
                            case IGNORE:
                                if (metricsEnabled())
                                    metrics().getErrorMetrics().getIgnores().inc();
                                setFinalResult(connection, new Responses.Result.Void());
                                break;
                        }
                    }
                    break;
                default:
                    setFinalResult(connection, response);
                    break;
            }
        } catch (Exception e) {
            setFinalException(connection, e);
        } finally {
            if (queriedHost != null)
                manager.cluster.manager.reportLatency(queriedHost, latency);
        }
    }

    private Connection.ResponseCallback prepareAndRetry(final String toPrepare) {
        return new Connection.ResponseCallback() {

            @Override
            public Message.Request request() {
                return new Requests.Prepare(toPrepare);
            }

            @Override
            public int retryCount() {
                return RequestHandler.this.retryCount();
            }

            @Override
            public void onSet(Connection connection, Message.Response response, long latency, int retryCount) {
                QueryState queryState = queryStateRef.get();
                if (!queryState.isInProgressAt(retryCount) ||
                    !queryStateRef.compareAndSet(queryState, queryState.complete())) {
                    logger.debug("onSet triggered but the response was completed by another thread, cancelling (retryCount = {}, queryState = {}, queryStateRef = {})",
                                 retryCount, queryState, queryStateRef.get());
                    return;
                }

                // TODO should we check the response ?
                switch (response.type) {
                    case RESULT:
                        if (((Responses.Result)response).kind == Responses.Result.Kind.PREPARED) {
                            logger.debug("Scheduling retry now that query is prepared");
                            retry(true, null);
                        } else {
                            logError(connection.address, new DriverException("Got unexpected response to prepare message: " + response));
                            retry(false, null);
                        }
                        break;
                    case ERROR:
                        logError(connection.address, new DriverException("Error preparing query, got " + response));
                        if (metricsEnabled())
                            metrics().getErrorMetrics().getOthers().inc();
                        retry(false, null);
                        break;
                    default:
                        // Something's wrong, so we return but we let setFinalResult propagate the exception
                        RequestHandler.this.setFinalResult(connection, response);
                        break;
                }
            }

            @Override
            public void onException(Connection connection, Exception exception, long latency, int retryCount) {
                RequestHandler.this.onException(connection, exception, latency, retryCount);
            }

            @Override
            public boolean onTimeout(Connection connection, long latency, int retryCount) {
                QueryState queryState = queryStateRef.get();
                if (!queryState.isInProgressAt(retryCount) ||
                    !queryStateRef.compareAndSet(queryState, queryState.complete())) {
                    logger.debug("onTimeout triggered but the response was completed by another thread, cancelling (retryCount = {}, queryState = {}, queryStateRef = {})",
                                 retryCount, queryState, queryStateRef.get());
                    return false;
                }
                logError(connection.address, new DriverException("Timeout waiting for response to prepare message"));
                retry(false, null);
                return true;
            }
        };
    }

    @Override
    public void onException(Connection connection, Exception exception, long latency, int retryCount) {
        QueryState queryState = queryStateRef.get();
        if (!queryState.isInProgressAt(retryCount) ||
            !queryStateRef.compareAndSet(queryState, queryState.complete())) {
            logger.debug("onException triggered but the response was completed by another thread, cancelling (retryCount = {}, queryState = {}, queryStateRef = {})",
                         retryCount, queryState, queryStateRef.get());
            return;
        }

        Host queriedHost = current;
        try {
            if (connection instanceof PooledConnection)
                ((PooledConnection)connection).release();

            if (exception instanceof ConnectionException) {
                if (metricsEnabled())
                    metrics().getErrorMetrics().getConnectionErrors().inc();
                ConnectionException ce = (ConnectionException)exception;
                logError(ce.address, ce);
                retry(false, null);
                return;
            }
            setFinalException(connection, exception);
        } catch (Exception e) {
            // This shouldn't happen, but if it does, we want to signal the callback, not let him hang indefinitively
            setFinalException(null, new DriverInternalError("An unexpected error happened while handling exception " + exception, e));
        } finally {
            if (queriedHost != null)
                manager.cluster.manager.reportLatency(queriedHost, latency);
        }
    }

    @Override
    public boolean onTimeout(Connection connection, long latency, int retryCount) {
        QueryState queryState = queryStateRef.get();
        if (!queryState.isInProgressAt(retryCount) ||
            !queryStateRef.compareAndSet(queryState, queryState.complete())) {
            logger.debug("onTimeout triggered but the response was completed by another thread, cancelling (retryCount = {}, queryState = {}, queryStateRef = {})",
                         retryCount, queryState, queryStateRef.get());
            return false;
        }

        Host queriedHost = current;
        try {
            // If a query times out, we consider that the host is unstable, so we defunct
            // the connection to mark it down.
            DriverException timeoutException = new DriverException("Timed out waiting for server response");
            connection.defunct(timeoutException);

            logError(connection.address, timeoutException);
            retry(false, null);
        } catch (Exception e) {
            // This shouldn't happen, but if it does, we want to signal the callback, not let him hang indefinitively
            setFinalException(null, new DriverInternalError("An unexpected error happened while handling timeout", e));
        } finally {
            if (queriedHost != null)
                manager.cluster.manager.reportLatency(queriedHost, latency);
        }
        return true;
    }

    @Override
    public int retryCount() {
        return queryStateRef.get().retryCount;
    }

    interface Callback extends Connection.ResponseCallback {
        public void onSet(Connection connection, Message.Response response, ExecutionInfo info, Statement statement, long latency);
        public void register(RequestHandler handler);
    }

    // This is used to prevent races between request completion (either success or error) and timeout.
    // A retry is in progress once we have written the request to the connection and until we get back a response or a timeout.
    // The count increments on each retry.
    static class QueryState {
        static QueryState INITIAL = new QueryState(0, true);

        final int retryCount;
        final boolean inProgress;

        private QueryState(int count, boolean inProgress) {
            this.retryCount = count;
            this.inProgress = inProgress;
        }

        boolean isInProgressAt(int retryCount) {
            return inProgress && this.retryCount == retryCount;
        }

        QueryState complete() {
            assert inProgress;
            return new QueryState(retryCount, false);
        }

        QueryState startNext() {
            assert !inProgress;
            return new QueryState(retryCount + 1, true);
        }

        @Override
        public String toString() {
            return String.format("QueryState(count=%d, inProgress=%s)", retryCount, inProgress);
        }
    }
}
