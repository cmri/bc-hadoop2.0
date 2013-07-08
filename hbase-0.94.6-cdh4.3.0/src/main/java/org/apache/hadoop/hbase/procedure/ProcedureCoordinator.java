/**
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
package org.apache.hadoop.hbase.procedure;

import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.hbase.DaemonThreadFactory;
import org.apache.hadoop.hbase.errorhandling.ForeignException;
import org.apache.hadoop.hbase.errorhandling.ForeignExceptionDispatcher;

import com.google.common.collect.MapMaker;

/**
 * This is the master side of a distributed complex procedure execution.
 * <p>
 * The {@link Procedure} is generic and subclassing or customization shouldn't be
 * necessary -- any customization should happen just in {@link Subprocedure}s.
 */
@InterfaceAudience.Public
@InterfaceStability.Evolving
public class ProcedureCoordinator {
  private static final Log LOG = LogFactory.getLog(ProcedureCoordinator.class);

  final static long TIMEOUT_MILLIS_DEFAULT = 60000;
  final static long WAKE_MILLIS_DEFAULT = 500;

  private final ProcedureCoordinatorRpcs rpcs;
  private final ExecutorService pool;

  // Running procedure table.  Maps procedure name to running procedure reference
  private final ConcurrentMap<String, Procedure> procedures =
      new MapMaker().concurrencyLevel(4).weakValues().makeMap();

  /**
   * Create and start a ProcedureCoordinator.
   *
   * The rpc object registers the ProcedureCoordinator and starts any threads in this
   * constructor.
   *
   * @param rpcs
   * @param pool Used for executing procedures.
   */
  public ProcedureCoordinator(ProcedureCoordinatorRpcs rpcs, ThreadPoolExecutor pool) {
    this.rpcs = rpcs;
    this.pool = pool;
    this.rpcs.start(this);
  }

  /**
   * Default thread pool for the procedure
   */
  public static ThreadPoolExecutor defaultPool(String coordName, long keepAliveTime, int opThreads,
      long wakeFrequency) {
    return new ThreadPoolExecutor(1, opThreads, keepAliveTime, TimeUnit.SECONDS,
        new SynchronousQueue<Runnable>(),
        new DaemonThreadFactory("(" + coordName + ")-proc-coordinator-pool"));
  }

  /**
   * Shutdown the thread pools and release rpc resources
   * @throws IOException
   */
  public void close() throws IOException {
    // have to use shutdown now to break any latch waiting
    pool.shutdownNow();
    rpcs.close();
  }

  /**
   * Submit an procedure to kick off its dependent subprocedures.
   * @param proc Procedure to execute
   * @return <tt>true</tt> if the procedure was started correctly, <tt>false</tt> if the
   *         procedure or any subprocedures could not be started.  Failure could be due to
   *         submitting a procedure multiple times (or one with the same name), or some sort
   *         of IO problem.  On errors, the procedure's monitor holds a reference to the exception
   *         that caused the failure.
   */
  boolean submitProcedure(Procedure proc) {
    // if the submitted procedure was null, then we don't want to run it
    if (proc == null) {
      return false;
    }
    String procName = proc.getName();

    // make sure we aren't already running a procedure of that name
    synchronized (procedures) {
      Procedure oldProc = procedures.get(procName);
      if (oldProc != null) {
        // procedures are always eventually completed on both successful and failed execution
        if (oldProc.completedLatch.getCount() != 0) {
          LOG.warn("Procedure " + procName + " currently running.  Rejecting new request");
          return false;
        }
        LOG.debug("Procedure " + procName + " was in running list but was completed.  Accepting new attempt.");
        procedures.remove(procName);
      }
    }

    // kick off the procedure's execution in a separate thread
    Future<Void> f = null;
    try {
      synchronized (procedures) {
        f = this.pool.submit(proc);
        // if everything got started properly, we can add it known running procedures
        this.procedures.put(procName, proc);
      }
      return true;
    } catch (RejectedExecutionException e) {
      LOG.warn("Procedure " + procName + " rejected by execution pool.  Propagating error and " +
          "cancelling operation.", e);
      // the thread pool is full and we can't run the procedure
      proc.receive(new ForeignException(procName, e));

      // cancel procedure proactively
      if (f != null) {
        f.cancel(true);
      }
    }
    return false;
  }

  /**
   * The connection to the rest of the procedure group (members and coordinator) has been
   * broken/lost/failed. This should fail any interested procedures, but not attempt to notify other
   * members since we cannot reach them anymore.
   * @param message description of the error
   * @param cause the actual cause of the failure
   */
  void rpcConnectionFailure(final String message, final IOException cause) {
    Collection<Procedure> toNotify = procedures.values();

    for (Procedure proc : toNotify) {
      if (proc == null) {
        continue;
      }
      // notify the elements, if they aren't null
      proc.receive(new ForeignException(proc.getName(), cause));
    }
  }

  /**
   * Abort the procedure with the given name
   * @param procName name of the procedure to abort
   * @param reason serialized information about the abort
   */
  public void abortProcedure(String procName, ForeignException reason) {
    // if we know about the Procedure, notify it
    synchronized(procedures) {
      Procedure proc = procedures.get(procName);
      if (proc == null) {
        return;
      }
      proc.receive(reason);
    }
  }

  /**
   * Exposed for hooking with unit tests.
   * @param procName
   * @param procArgs
   * @param expectedMembers
   * @return
   */
  Procedure createProcedure(ForeignExceptionDispatcher fed, String procName, byte[] procArgs,
      List<String> expectedMembers) {
    // build the procedure
    return new Procedure(this, fed, WAKE_MILLIS_DEFAULT, TIMEOUT_MILLIS_DEFAULT,
        procName, procArgs, expectedMembers);
  }

  /**
   * Kick off the named procedure
   * @param procName name of the procedure to start
   * @param procArgs arguments for the procedure
   * @param expectedMembers expected members to start
   * @return handle to the running procedure, if it was started correctly, <tt>null</tt> otherwise
   * @throws RejectedExecutionException if there are no more available threads to run the procedure
   */
  public Procedure startProcedure(ForeignExceptionDispatcher fed, String procName, byte[] procArgs,
      List<String> expectedMembers) throws RejectedExecutionException {
    Procedure proc = createProcedure(fed, procName, procArgs, expectedMembers);
    if (!this.submitProcedure(proc)) {
      LOG.error("Failed to submit procedure '" + procName + "'");
      return null;
    }
    return proc;
  }

  /**
   * Notification that the procedure had the specified member acquired its part of the barrier
   * via {@link Subprocedure#acquireBarrier()}.
   * @param procName name of the procedure that acquired
   * @param member name of the member that acquired
   */
  void memberAcquiredBarrier(String procName, final String member) {
    Procedure proc = procedures.get(procName);
    if (proc != null) {
      proc.barrierAcquiredByMember(member);
    }
  }

  /**
   * Notification that the procedure had another member finished executing its in-barrier subproc
   * via {@link Subprocedure#insideBarrier()}.
   * @param procName name of the subprocedure that finished
   * @param member name of the member that executed and released its barrier
   */
  void memberFinishedBarrier(String procName, final String member) {
    Procedure proc = procedures.get(procName);
    if (proc != null) {
      proc.barrierReleasedByMember(member);
    }
  }

  /**
   * @return the rpcs implementation for all current procedures
   */
  ProcedureCoordinatorRpcs getRpcs() {
    return rpcs;
  }

  /**
   * Returns the procedure.  This Procedure is a live instance so should not be modified but can
   * be inspected.
   * @param name Name of the procedure
   * @return Procedure or null if not present any more
   */
  public Procedure getProcedure(String name) {
    return procedures.get(name);
  }

  /**
   * @return Return set of all procedure names.
   */
  public Set<String> getProcedureNames() {
    return new HashSet<String>(procedures.keySet());
  }
}