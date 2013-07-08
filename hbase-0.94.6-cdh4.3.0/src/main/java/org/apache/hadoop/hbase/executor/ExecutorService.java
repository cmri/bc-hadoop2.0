/**
 * Copyright 2010 The Apache Software Foundation
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.executor;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.lang.management.ThreadInfo;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.executor.EventHandler.EventHandlerListener;
import org.apache.hadoop.hbase.executor.EventHandler.EventType;
import org.apache.hadoop.hbase.monitoring.ThreadMonitoring;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

/**
 * This is a generic executor service. This component abstracts a
 * threadpool, a queue to which {@link EventHandler.EventType}s can be submitted,
 * and a <code>Runnable</code> that handles the object that is added to the queue.
 *
 * <p>In order to create a new service, create an instance of this class and
 * then do: <code>instance.startExecutorService("myService");</code>.  When done
 * call {@link #shutdown()}.
 *
 * <p>In order to use the service created above, call
 * {@link #submit(EventHandler)}. Register pre- and post- processing listeners
 * by registering your implementation of {@link EventHandler.EventHandlerListener}
 * with {@link #registerListener(EventHandler.EventType, EventHandler.EventHandlerListener)}.  Be sure
 * to deregister your listener when done via {@link #unregisterListener(EventHandler.EventType)}.
 */
public class ExecutorService {
  private static final Log LOG = LogFactory.getLog(ExecutorService.class);

  // hold the all the executors created in a map addressable by their names
  private final ConcurrentHashMap<String, Executor> executorMap =
    new ConcurrentHashMap<String, Executor>();

  // listeners that are called before and after an event is processed
  private ConcurrentHashMap<EventHandler.EventType, EventHandlerListener> eventHandlerListeners =
    new ConcurrentHashMap<EventHandler.EventType, EventHandlerListener>();

  // Name of the server hosting this executor service.
  private final String servername;

  /**
   * The following is a list of all executor types, both those that run in the
   * master and those that run in the regionserver.
   */
  public enum ExecutorType {

    // Master executor services
    MASTER_CLOSE_REGION        (1),
    MASTER_OPEN_REGION         (2),
    MASTER_SERVER_OPERATIONS   (3),
    MASTER_TABLE_OPERATIONS    (4),
    MASTER_RS_SHUTDOWN         (5),
    MASTER_META_SERVER_OPERATIONS (6),

    // RegionServer executor services
    RS_OPEN_REGION             (20),
    RS_OPEN_ROOT               (21),
    RS_OPEN_META               (22),
    RS_CLOSE_REGION            (23),
    RS_CLOSE_ROOT              (24),
    RS_CLOSE_META              (25);

    ExecutorType(int value) {}

    /**
     * @param serverName
     * @return Conflation of the executor type and the passed servername.
     */
    String getExecutorName(String serverName) {
      return this.toString() + "-" + serverName.replace("%", "%%");
    }
  }

  /**
   * Returns the executor service type (the thread pool instance) for the
   * passed event handler type.
   * @param type EventHandler type.
   */
  public ExecutorType getExecutorServiceType(final EventHandler.EventType type) {
    switch(type) {
      // Master executor services

      case RS_ZK_REGION_CLOSED:
      case RS_ZK_REGION_FAILED_OPEN:
        return ExecutorType.MASTER_CLOSE_REGION;

      case RS_ZK_REGION_OPENED:
        return ExecutorType.MASTER_OPEN_REGION;

      case RS_ZK_REGION_SPLIT:
      case M_SERVER_SHUTDOWN:
        return ExecutorType.MASTER_SERVER_OPERATIONS;

      case M_META_SERVER_SHUTDOWN:
        return ExecutorType.MASTER_META_SERVER_OPERATIONS;

      case C_M_DELETE_TABLE:
      case C_M_DISABLE_TABLE:
      case C_M_ENABLE_TABLE:
      case C_M_MODIFY_TABLE:
      case C_M_CREATE_TABLE:
      case C_M_SNAPSHOT_TABLE:
      case C_M_RESTORE_SNAPSHOT:
        return ExecutorType.MASTER_TABLE_OPERATIONS;

      // RegionServer executor services

      case M_RS_OPEN_REGION:
        return ExecutorType.RS_OPEN_REGION;

      case M_RS_OPEN_ROOT:
        return ExecutorType.RS_OPEN_ROOT;

      case M_RS_OPEN_META:
        return ExecutorType.RS_OPEN_META;

      case M_RS_CLOSE_REGION:
        return ExecutorType.RS_CLOSE_REGION;

      case M_RS_CLOSE_ROOT:
        return ExecutorType.RS_CLOSE_ROOT;

      case M_RS_CLOSE_META:
        return ExecutorType.RS_CLOSE_META;

      default:
        throw new RuntimeException("Unhandled event type " + type);
    }
  }

  /**
   * Default constructor.
   * @param servername Name of the hosting server.
   */
  public ExecutorService(final String servername) {
    super();
    this.servername = servername;
  }

  /**
   * Start an executor service with a given name. If there was a service already
   * started with the same name, this throws a RuntimeException.
   * @param name Name of the service to start.
   */
  void startExecutorService(String name, int maxThreads) {
    if (this.executorMap.get(name) != null) {
      throw new RuntimeException("An executor service with the name " + name +
        " is already running!");
    }
    Executor hbes = new Executor(name, maxThreads, this.eventHandlerListeners);
    if (this.executorMap.putIfAbsent(name, hbes) != null) {
      throw new RuntimeException("An executor service with the name " + name +
      " is already running (2)!");
    }
    LOG.debug("Starting executor service name=" + name +
      ", corePoolSize=" + hbes.threadPoolExecutor.getCorePoolSize() +
      ", maxPoolSize=" + hbes.threadPoolExecutor.getMaximumPoolSize());
  }

  boolean isExecutorServiceRunning(String name) {
    return this.executorMap.containsKey(name);
  }

  public void shutdown() {
    for(Entry<String, Executor> entry: this.executorMap.entrySet()) {
      List<Runnable> wasRunning =
        entry.getValue().threadPoolExecutor.shutdownNow();
      if (!wasRunning.isEmpty()) {
        LOG.info(entry.getValue() + " had " + wasRunning + " on shutdown");
      }
    }
    this.executorMap.clear();
  }

  Executor getExecutor(final ExecutorType type) {
    return getExecutor(type.getExecutorName(this.servername));
  }

  Executor getExecutor(String name) {
    Executor executor = this.executorMap.get(name);
    return executor;
  }


  public void startExecutorService(final ExecutorType type, final int maxThreads) {
    String name = type.getExecutorName(this.servername);
    if (isExecutorServiceRunning(name)) {
      LOG.debug("Executor service " + toString() + " already running on " +
        this.servername);
      return;
    }
    startExecutorService(name, maxThreads);
  }

  public void submit(final EventHandler eh) {
    Executor executor = getExecutor(getExecutorServiceType(eh.getEventType()));
    if (executor == null) {
      // This happens only when events are submitted after shutdown() was
      // called, so dropping them should be "ok" since it means we're
      // shutting down.
      LOG.error("Cannot submit [" + eh + "] because the executor is missing." +
        " Is this process shutting down?");
    } else {
      executor.submit(eh);
    }
  }

  /**
   * Subscribe to updates before and after processing instances of
   * {@link EventHandler.EventType}.  Currently only one listener per
   * event type.
   * @param type Type of event we're registering listener for
   * @param listener The listener to run.
   */
  public void registerListener(final EventHandler.EventType type,
      final EventHandlerListener listener) {
    this.eventHandlerListeners.put(type, listener);
  }

  /**
   * Stop receiving updates before and after processing instances of
   * {@link EventHandler.EventType}
   * @param type Type of event we're registering listener for
   * @return The listener we removed or null if we did not remove it.
   */
  public EventHandlerListener unregisterListener(final EventHandler.EventType type) {
    return this.eventHandlerListeners.remove(type);
  }

  public Map<String, ExecutorStatus> getAllExecutorStatuses() {
    Map<String, ExecutorStatus> ret = Maps.newHashMap();
    for (Map.Entry<String, Executor> e : executorMap.entrySet()) {
      ret.put(e.getKey(), e.getValue().getStatus());
    }
    return ret;
  }
  
  /**
   * Executor instance.
   */
  static class Executor {
    // how long to retain excess threads
    final long keepAliveTimeInMillis = 1000;
    // the thread pool executor that services the requests
    final TrackingThreadPoolExecutor threadPoolExecutor;
    // work queue to use - unbounded queue
    final BlockingQueue<Runnable> q = new LinkedBlockingQueue<Runnable>();
    private final String name;
    private final Map<EventHandler.EventType, EventHandlerListener> eventHandlerListeners;
    private static final AtomicLong seqids = new AtomicLong(0);
    private final long id;

    protected Executor(String name, int maxThreads,
        final Map<EventHandler.EventType, EventHandlerListener> eventHandlerListeners) {
      this.id = seqids.incrementAndGet();
      this.name = name;
      this.eventHandlerListeners = eventHandlerListeners;
      // create the thread pool executor
      this.threadPoolExecutor = new TrackingThreadPoolExecutor(
          maxThreads, maxThreads,
          keepAliveTimeInMillis, TimeUnit.MILLISECONDS, q);
      // name the threads for this threadpool
      ThreadFactoryBuilder tfb = new ThreadFactoryBuilder();
      tfb.setNameFormat(this.name + "-%d");
      this.threadPoolExecutor.setThreadFactory(tfb.build());
    }

    /**
     * Submit the event to the queue for handling.
     * @param event
     */
    void submit(final EventHandler event) {
      // If there is a listener for this type, make sure we call the before
      // and after process methods.
      EventHandlerListener listener =
        this.eventHandlerListeners.get(event.getEventType());
      if (listener != null) {
        event.setListener(listener);
      }
      this.threadPoolExecutor.execute(event);
    }
    
    public String toString() {
      return getClass().getSimpleName() + "-" + id + "-" + name;
    }

    public ExecutorStatus getStatus() {
      List<EventHandler> queuedEvents = Lists.newArrayList();
      for (Runnable r : q) {
        if (!(r instanceof EventHandler)) {
          LOG.warn("Non-EventHandler " + r + " queued in " + name);
          continue;
        }
        queuedEvents.add((EventHandler)r);
      }
      
      List<RunningEventStatus> running = Lists.newArrayList();
      for (Map.Entry<Thread, Runnable> e :
          threadPoolExecutor.getRunningTasks().entrySet()) {
        Runnable r = e.getValue();
        if (!(r instanceof EventHandler)) {
          LOG.warn("Non-EventHandler " + r + " running in " + name);
          continue;
        }
        running.add(new RunningEventStatus(e.getKey(), (EventHandler)r));
      }
      
      return new ExecutorStatus(this, queuedEvents, running);
    }
  }
 
  /**
   * A subclass of ThreadPoolExecutor that keeps track of the Runnables that
   * are executing at any given point in time.
   */
  static class TrackingThreadPoolExecutor extends ThreadPoolExecutor {
    private ConcurrentMap<Thread, Runnable> running = Maps.newConcurrentMap(); 
      
    public TrackingThreadPoolExecutor(int corePoolSize, int maximumPoolSize,
        long keepAliveTime, TimeUnit unit, BlockingQueue<Runnable> workQueue) {
      super(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue);
    }

    @Override
    protected void afterExecute(Runnable r, Throwable t) {
      super.afterExecute(r, t);
      running.remove(Thread.currentThread());
    }

    @Override
    protected void beforeExecute(Thread t, Runnable r) {
      Runnable oldPut = running.put(t, r);
      assert oldPut == null : "inconsistency for thread " + t;
      super.beforeExecute(t, r);
    }
   
    /**
     * @return a map of the threads currently running tasks
     * inside this executor. Each key is an active thread,
     * and the value is the task that is currently running.
     * Note that this is not a stable snapshot of the map.
     */
    public ConcurrentMap<Thread, Runnable> getRunningTasks() {
      return running;
    }
  }

  /**
   * A snapshot of the status of a particular executor. This includes
   * the contents of the executor's pending queue, as well as the
   * threads and events currently being processed.
   *
   * This is a consistent snapshot that is immutable once constructed.
   */
  public static class ExecutorStatus {
    final Executor executor;
    final List<EventHandler> queuedEvents;
    final List<RunningEventStatus> running;

    ExecutorStatus(Executor executor,
        List<EventHandler> queuedEvents,
        List<RunningEventStatus> running) {
      this.executor = executor;
      this.queuedEvents = queuedEvents;
      this.running = running;
    }
   
    /**
     * Dump a textual representation of the executor's status
     * to the given writer.
     *
     * @param out the stream to write to
     * @param indent a string prefix for each line, used for indentation
     */
    public void dumpTo(Writer out, String indent) throws IOException {
      out.write(indent + "Status for executor: " + executor + "\n");
      out.write(indent + "=======================================\n");
      out.write(indent + queuedEvents.size() + " events queued, " +
          running.size() + " running\n");
      if (!queuedEvents.isEmpty()) {
        out.write(indent + "Queued:\n");
        for (EventHandler e : queuedEvents) {
          out.write(indent + "  " + e + "\n");
        }
        out.write("\n");
      }
      if (!running.isEmpty()) {
        out.write(indent + "Running:\n");
        for (RunningEventStatus stat : running) {
          out.write(indent + "  Running on thread '" +
              stat.threadInfo.getThreadName() +
              "': " + stat.event + "\n");
          out.write(ThreadMonitoring.formatThreadInfo(
              stat.threadInfo, indent + "  "));
          out.write("\n");
        }
      }
      out.flush();
    }
  }

  /**
   * The status of a particular event that is in the middle of being
   * handled by an executor.
   */
  public static class RunningEventStatus {
    final ThreadInfo threadInfo;
    final EventHandler event;

    public RunningEventStatus(Thread t, EventHandler event) {
      this.threadInfo = ThreadMonitoring.getThreadInfo(t);
      this.event = event;
    }
  }
}
