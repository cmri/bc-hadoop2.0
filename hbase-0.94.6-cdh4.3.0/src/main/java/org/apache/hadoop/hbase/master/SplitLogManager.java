/**
 * Copyright 2011 The Apache Software Foundation
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
package org.apache.hadoop.hbase.master;

import static org.apache.hadoop.hbase.zookeeper.ZKSplitLog.Counters.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.Chore;
import org.apache.hadoop.hbase.HBaseFileSystem;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.Stoppable;
import org.apache.hadoop.hbase.master.SplitLogManager.TaskFinisher.Status;
import org.apache.hadoop.hbase.monitoring.MonitoredTask;
import org.apache.hadoop.hbase.monitoring.TaskMonitor;
import org.apache.hadoop.hbase.regionserver.SplitLogWorker;
import org.apache.hadoop.hbase.regionserver.wal.HLogSplitter;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManager;
import org.apache.hadoop.hbase.util.FSUtils;
import org.apache.hadoop.hbase.util.Threads;
import org.apache.hadoop.hbase.zookeeper.ZKSplitLog;
import org.apache.hadoop.hbase.zookeeper.ZKSplitLog.TaskState;
import org.apache.hadoop.hbase.zookeeper.ZKUtil;
import org.apache.hadoop.hbase.zookeeper.ZooKeeperListener;
import org.apache.hadoop.hbase.zookeeper.ZooKeeperWatcher;
import org.apache.hadoop.util.StringUtils;
import org.apache.zookeeper.AsyncCallback;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.data.Stat;

import com.google.common.base.Strings;

import static org.apache.hadoop.hbase.master.SplitLogManager.ResubmitDirective.*;
import static org.apache.hadoop.hbase.master.SplitLogManager.TerminationStatus.*;

/**
 * Distributes the task of log splitting to the available region servers.
 * Coordination happens via zookeeper. For every log file that has to be split a
 * znode is created under /hbase/splitlog. SplitLogWorkers race to grab a task.
 *
 * SplitLogManager monitors the task znodes that it creates using the
 * timeoutMonitor thread. If a task's progress is slow then
 * resubmit(String, boolean) will take away the task from the owner
 * {@link SplitLogWorker} and the task will be
 * upforgrabs again. When the task is done then the task's znode is deleted by
 * SplitLogManager.
 *
 * Clients call {@link #splitLogDistributed(Path)} to split a region server's
 * log files. The caller thread waits in this method until all the log files
 * have been split.
 *
 * All the zookeeper calls made by this class are asynchronous. This is mainly
 * to help reduce response time seen by the callers.
 *
 * There is race in this design between the SplitLogManager and the
 * SplitLogWorker. SplitLogManager might re-queue a task that has in reality
 * already been completed by a SplitLogWorker. We rely on the idempotency of
 * the log splitting task for correctness.
 *
 * It is also assumed that every log splitting task is unique and once
 * completed (either with success or with error) it will be not be submitted
 * again. If a task is resubmitted then there is a risk that old "delete task"
 * can delete the re-submission.
 */
public class SplitLogManager extends ZooKeeperListener {
  private static final Log LOG = LogFactory.getLog(SplitLogManager.class);

  private final Stoppable stopper;
  private final MasterServices master;
  private final String serverName;
  private final TaskFinisher taskFinisher;
  private FileSystem fs;
  private Configuration conf;

  private long zkretries;
  private long resubmit_threshold;
  private long timeout;
  private long unassignedTimeout;
  private long lastNodeCreateTime = Long.MAX_VALUE;
  public boolean ignoreZKDeleteForTesting = false;

  private final ConcurrentMap<String, Task> tasks =
    new ConcurrentHashMap<String, Task>();
  private TimeoutMonitor timeoutMonitor;

  private Set<String> deadWorkers = null;
  private final Object deadWorkersLock = new Object();

  private Set<String> failedDeletions = null;

  /**
   * Wrapper around {@link #SplitLogManager(ZooKeeperWatcher, Configuration,
   * Stoppable, String, TaskFinisher)} that provides a task finisher for
   * copying recovered edits to their final destination. The task finisher
   * has to be robust because it can be arbitrarily restarted or called
   * multiple times.
   * 
   * @param zkw
   * @param conf
   * @param stopper
   * @param serverName
   */
  public SplitLogManager(ZooKeeperWatcher zkw, final Configuration conf,
      Stoppable stopper, MasterServices master, String serverName) {
    this(zkw, conf, stopper, master, serverName, new TaskFinisher() {
      @Override
      public Status finish(String workerName, String logfile) {
        try {
          HLogSplitter.finishSplitLogFile(logfile, conf);
        } catch (IOException e) {
          LOG.warn("Could not finish splitting of log file " + logfile, e);
          return Status.ERR;
        }
        return Status.DONE;
      }
    });
  }

  /**
   * Its OK to construct this object even when region-servers are not online. It
   * does lookup the orphan tasks in zk but it doesn't block waiting for them
   * to be done.
   *
   * @param zkw
   * @param conf
   * @param stopper
   * @param serverName
   * @param tf task finisher 
   */
  public SplitLogManager(ZooKeeperWatcher zkw, Configuration conf,
      Stoppable stopper, MasterServices master, String serverName, TaskFinisher tf) {
    super(zkw);
    this.taskFinisher = tf;
    this.conf = conf;
    this.stopper = stopper;
    this.master = master;
    this.zkretries = conf.getLong("hbase.splitlog.zk.retries",
        ZKSplitLog.DEFAULT_ZK_RETRIES);
    this.resubmit_threshold = conf.getLong("hbase.splitlog.max.resubmit",
        ZKSplitLog.DEFAULT_MAX_RESUBMIT);
    this.timeout = conf.getInt("hbase.splitlog.manager.timeout",
        ZKSplitLog.DEFAULT_TIMEOUT);
    this.unassignedTimeout =
      conf.getInt("hbase.splitlog.manager.unassigned.timeout",
        ZKSplitLog.DEFAULT_UNASSIGNED_TIMEOUT);
    LOG.info("timeout = " + timeout);
    LOG.info("unassigned timeout = " + unassignedTimeout);
    LOG.info("resubmit threshold = " + this.resubmit_threshold);

    this.serverName = serverName;
    this.timeoutMonitor = new TimeoutMonitor(
        conf.getInt("hbase.splitlog.manager.timeoutmonitor.period",
            1000),
        stopper);

    this.failedDeletions = Collections.synchronizedSet(new HashSet<String>());
  }

  public void finishInitialization(boolean masterRecovery) {
    if (!masterRecovery) {
      Threads.setDaemonThreadRunning(timeoutMonitor.getThread(), serverName
          + ".splitLogManagerTimeoutMonitor");
    }
    // Watcher can be null during tests with Mock'd servers.
    if (this.watcher != null) {
      this.watcher.registerListener(this);
      lookForOrphans();
    }
  }

  private FileStatus[] getFileList(List<Path> logDirs) throws IOException {
    List<FileStatus> fileStatus = new ArrayList<FileStatus>();
    for (Path hLogDir : logDirs) {
      this.fs = hLogDir.getFileSystem(conf);
      if (!fs.exists(hLogDir)) {
        LOG.warn(hLogDir + " doesn't exist. Nothing to do!");
        continue;
      }
      // TODO filter filenames?
      FileStatus[] logfiles = FSUtils.listStatus(fs, hLogDir, null);
      if (logfiles == null || logfiles.length == 0) {
        LOG.info(hLogDir + " is empty dir, no logs to split");
      } else {
        for (FileStatus status : logfiles)
          fileStatus.add(status);
      }
    }
    FileStatus[] a = new FileStatus[fileStatus.size()];
    return fileStatus.toArray(a);
  }

  /**
   * @param logDir
   *            one region sever hlog dir path in .logs
   * @throws IOException
   *             if there was an error while splitting any log file
   * @return cumulative size of the logfiles split
   * @throws IOException 
   */
  public long splitLogDistributed(final Path logDir) throws IOException {
    List<Path> logDirs = new ArrayList<Path>();
    logDirs.add(logDir);
    return splitLogDistributed(logDirs);
  }
  /**
   * The caller will block until all the log files of the given region server
   * have been processed - successfully split or an error is encountered - by an
   * available worker region server. This method must only be called after the
   * region servers have been brought online.
   *
   * @param logDirs
   * @throws IOException
   *          if there was an error while splitting any log file
   * @return cumulative size of the logfiles split
   */
  public long splitLogDistributed(final List<Path> logDirs) throws IOException {
    MonitoredTask status = TaskMonitor.get().createStatus(
          "Doing distributed log split in " + logDirs);
    FileStatus[] logfiles = getFileList(logDirs);
    status.setStatus("Checking directory contents...");
    LOG.debug("Scheduling batch of logs to split");
    tot_mgr_log_split_batch_start.incrementAndGet();
    LOG.info("started splitting logs in " + logDirs);
    long t = EnvironmentEdgeManager.currentTimeMillis();
    long totalSize = 0;
    TaskBatch batch = new TaskBatch();
    for (FileStatus lf : logfiles) {
      // TODO If the log file is still being written to - which is most likely
      // the case for the last log file - then its length will show up here
      // as zero. The size of such a file can only be retrieved after
      // recover-lease is done. totalSize will be under in most cases and the
      // metrics that it drives will also be under-reported.
      totalSize += lf.getLen();
      if (enqueueSplitTask(lf.getPath().toString(), batch) == false) {
        throw new IOException("duplicate log split scheduled for "
            + lf.getPath());
      }
    }
    waitForSplittingCompletion(batch, status);
    if (batch.done != batch.installed) {
      batch.isDead = true;
      tot_mgr_log_split_batch_err.incrementAndGet();
      LOG.warn("error while splitting logs in " + logDirs +
      " installed = " + batch.installed + " but only " + batch.done + " done");
      String msg = "error or interrupted while splitting logs in "
        + logDirs + " Task = " + batch;
      status.abort(msg);
      throw new IOException(msg);
    }
    for(Path logDir: logDirs){
      status.setStatus("Cleaning up log directory...");
      try {
        if (fs.exists(logDir) && !HBaseFileSystem.deleteFileFromFileSystem(fs, logDir)) {
          LOG.warn("Unable to delete log src dir. Ignoring. " + logDir);
        }
      } catch (IOException ioe) {
        FileStatus[] files = fs.listStatus(logDir);
        if (files != null && files.length > 0) {
          LOG.warn("returning success without actually splitting and " + 
              "deleting all the log files in path " + logDir);
        } else {
          LOG.warn("Unable to delete log src dir. Ignoring. " + logDir, ioe);
        }
      }
      tot_mgr_log_split_batch_success.incrementAndGet();
    }
    String msg = "finished splitting (more than or equal to) " + totalSize +
        " bytes in " + batch.installed + " log files in " + logDirs + " in " +
        (EnvironmentEdgeManager.currentTimeMillis() - t) + "ms";
    status.markComplete(msg);
    LOG.info(msg);
    return totalSize;
  }

  /**
   * Add a task entry to splitlog znode if it is not already there.
   * 
   * @param taskname the path of the log to be split
   * @param batch the batch this task belongs to
   * @return true if a new entry is created, false if it is already there.
   */
  boolean enqueueSplitTask(String taskname, TaskBatch batch) {
    tot_mgr_log_split_start.incrementAndGet();
    String path = ZKSplitLog.getEncodedNodeName(watcher, taskname);
    Task oldtask = createTaskIfAbsent(path, batch);
    if (oldtask == null) {
      // publish the task in zk
      createNode(path, zkretries);
      return true;
    }
    return false;
  }

  private void waitForSplittingCompletion(TaskBatch batch, MonitoredTask status) {
    synchronized (batch) {
      while ((batch.done + batch.error) != batch.installed) {
        try {
          status.setStatus("Waiting for distributed tasks to finish. "
              + " scheduled=" + batch.installed
              + " done=" + batch.done
              + " error=" + batch.error);
          int remaining = batch.installed - (batch.done + batch.error);
          int actual = activeTasks(batch);
          if (remaining != actual) {
            LOG.warn("Expected " + remaining
              + " active tasks, but actually there are " + actual);
          }
          int remainingInZK = remainingTasksInZK();
          if (remainingInZK >= 0 && actual > remainingInZK) {
            LOG.warn("Expected at least" + actual
              + " tasks in ZK, but actually there are " + remainingInZK);
          }
          if (remainingInZK == 0 || actual == 0) {
            LOG.warn("No more task remaining (ZK or task map), splitting "
              + "should have completed. Remaining tasks in ZK " + remainingInZK
              + ", active tasks in map " + actual);
            if (remainingInZK == 0 && actual == 0) {
              return;
            }
          }
          batch.wait(100);
          if (stopper.isStopped()) {
            LOG.warn("Stopped while waiting for log splits to be completed");
            return;
          }
        } catch (InterruptedException e) {
          LOG.warn("Interrupted while waiting for log splits to be completed");
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }

  private int activeTasks(final TaskBatch batch) {
    int count = 0;
    for (Task t: tasks.values()) {
      if (t.batch == batch && t.status == TerminationStatus.IN_PROGRESS) {
        count++;
      }
    }
    return count;
  }

  private int remainingTasksInZK() {
    int count = 0;
    try {
      List<String> tasks =
        ZKUtil.listChildrenNoWatch(watcher, watcher.splitLogZNode);
      if (tasks != null) {
        for (String t: tasks) {
          if (!ZKSplitLog.isRescanNode(watcher, t)) {
            count++;
          }
        }
      }
    } catch (KeeperException ke) {
      LOG.warn("Failed to check remaining tasks", ke);
      count = -1;
    }
    return count;
  }

  private void setDone(String path, TerminationStatus status) {
    Task task = tasks.get(path);
    if (task == null) {
      if (!ZKSplitLog.isRescanNode(watcher, path)) {
        tot_mgr_unacquired_orphan_done.incrementAndGet();
        LOG.debug("unacquired orphan task is done " + path);
      }
    } else {
      synchronized (task) {
        if (task.status == IN_PROGRESS) {
          if (status == SUCCESS) {
            tot_mgr_log_split_success.incrementAndGet();
            LOG.info("Done splitting " + path);
          } else {
            tot_mgr_log_split_err.incrementAndGet();
            LOG.warn("Error splitting " + path);
          }
          task.status = status;
          if (task.batch != null) {
            synchronized (task.batch) {
              if (status == SUCCESS) {
                task.batch.done++;
              } else {
                task.batch.error++;
              }
              task.batch.notify();
            }
          }
        }
      }
    }
    // delete the task node in zk. It's an async
    // call and no one is blocked waiting for this node to be deleted. All
    // task names are unique (log.<timestamp>) there is no risk of deleting
    // a future task.
    // if a deletion fails, TimeoutMonitor will retry the same deletion later
    deleteNode(path, zkretries);
    return;
  }

  private void createNode(String path, Long retry_count) {
    ZKUtil.asyncCreate(this.watcher, path,
        TaskState.TASK_UNASSIGNED.get(serverName), new CreateAsyncCallback(),
        retry_count);
    tot_mgr_node_create_queued.incrementAndGet();
    return;
  }

  private void createNodeSuccess(String path) {
    lastNodeCreateTime = EnvironmentEdgeManager.currentTimeMillis();
    LOG.debug("put up splitlog task at znode " + path);
    getDataSetWatch(path, zkretries);
  }

  private void createNodeFailure(String path) {
    // TODO the Manager should split the log locally instead of giving up
    LOG.warn("failed to create task node" + path);
    setDone(path, FAILURE);
  }


  private void getDataSetWatch(String path, Long retry_count) {
    this.watcher.getRecoverableZooKeeper().getZooKeeper().
        getData(path, this.watcher,
        new GetDataAsyncCallback(), retry_count);
    tot_mgr_get_data_queued.incrementAndGet();
  }

  private void tryGetDataSetWatch(String path) {
    // A negative retry count will lead to ignoring all error processing.
    this.watcher.getRecoverableZooKeeper().getZooKeeper().
        getData(path, this.watcher,
        new GetDataAsyncCallback(), new Long(-1) /* retry count */);
    tot_mgr_get_data_queued.incrementAndGet();
  }

  private void getDataSetWatchSuccess(String path, byte[] data, int version) {
    if (data == null) {
      if (version == Integer.MIN_VALUE) {
        // assume all done. The task znode suddenly disappeared.
        setDone(path, SUCCESS);
        return;
      }
      tot_mgr_null_data.incrementAndGet();
      LOG.fatal("logic error - got null data " + path);
      setDone(path, FAILURE);
      return;
    }
    data = this.watcher.getRecoverableZooKeeper().removeMetaData(data);
    // LOG.debug("set watch on " + path + " got data " + new String(data));
    if (TaskState.TASK_UNASSIGNED.equals(data)) {
      LOG.debug("task not yet acquired " + path + " ver = " + version);
      handleUnassignedTask(path);
    } else if (TaskState.TASK_OWNED.equals(data)) {
      heartbeat(path, version,
          TaskState.TASK_OWNED.getWriterName(data));
    } else if (TaskState.TASK_RESIGNED.equals(data)) {
      LOG.info("task " + path + " entered state " + new String(data));
      resubmitOrFail(path, FORCE);
    } else if (TaskState.TASK_DONE.equals(data)) {
      LOG.info("task " + path + " entered state " + new String(data));
      if (taskFinisher != null && !ZKSplitLog.isRescanNode(watcher, path)) {
        if (taskFinisher.finish(TaskState.TASK_DONE.getWriterName(data),
            ZKSplitLog.getFileName(path)) == Status.DONE) {
          setDone(path, SUCCESS);
        } else {
          resubmitOrFail(path, CHECK);
        }
      } else {
        setDone(path, SUCCESS);
      }
    } else if (TaskState.TASK_ERR.equals(data)) {
      LOG.info("task " + path + " entered state " + new String(data));
      resubmitOrFail(path, CHECK);
    } else {
      LOG.fatal("logic error - unexpected zk state for path = " + path
          + " data = " + new String(data));
      setDone(path, FAILURE);
    }
  }

  private void getDataSetWatchFailure(String path) {
    LOG.warn("failed to set data watch " + path);
    setDone(path, FAILURE);
  }

  /**
   * It is possible for a task to stay in UNASSIGNED state indefinitely - say
   * SplitLogManager wants to resubmit a task. It forces the task to UNASSIGNED
   * state but it dies before it could create the RESCAN task node to signal
   * the SplitLogWorkers to pick up the task. To prevent this scenario the
   * SplitLogManager resubmits all orphan and UNASSIGNED tasks at startup.
   *
   * @param path
   */
  private void handleUnassignedTask(String path) {
    if (ZKSplitLog.isRescanNode(watcher, path)) {
      return;
    }
    Task task = findOrCreateOrphanTask(path);
    if (task.isOrphan() && (task.incarnation == 0)) {
      LOG.info("resubmitting unassigned orphan task " + path);
      // ignore failure to resubmit. The timeout-monitor will handle it later
      // albeit in a more crude fashion
      resubmit(path, task, FORCE);
    }
  }

  /**
   * Helper function to check whether to abandon retries in ZooKeeper AsyncCallback functions
   * @param statusCode integer value of a ZooKeeper exception code
   * @param action description message about the retried action
   * @return true when need to abandon retries, otherwise false
   */
  private boolean shouldAbandonRetries(int statusCode, String action) {
    if (statusCode == KeeperException.Code.SESSIONEXPIRED.intValue()) {
      LOG.error("ZK session expired. Master is expected to shut down. Abandoning retries for "
          + "action=" + action);
      return true;
    }
    return false;
  }

  private void heartbeat(String path, int new_version,
      String workerName) {
    Task task = findOrCreateOrphanTask(path);
    if (new_version != task.last_version) {
      if (task.isUnassigned()) {
        LOG.info("task " + path + " acquired by " + workerName);
      }
      task.heartbeat(EnvironmentEdgeManager.currentTimeMillis(),
          new_version, workerName);
      tot_mgr_heartbeat.incrementAndGet();
    } else {
      // duplicate heartbeats - heartbeats w/o zk node version
      // changing - are possible. The timeout thread does
      // getDataSetWatch() just to check whether a node still
      // exists or not
    }
    return;
  }

  private boolean resubmit(String path, Task task,
      ResubmitDirective directive) {
    // its ok if this thread misses the update to task.deleted. It will
    // fail later
    if (task.status != IN_PROGRESS) {
      return false;
    }
    int version;
    if (directive != FORCE) {
      // We're going to resubmit:
      // 1) immediately if the worker server is now marked as dead
      // 2) after a configurable timeout if the server is not marked as dead but has still not
      // finished the task. This allows to continue if the worker cannot actually handle it,
      // for any reason.
      final long time = EnvironmentEdgeManager.currentTimeMillis() - task.last_update;
      ServerName curWorker = null;
      if (!Strings.isNullOrEmpty(task.cur_worker_name)) {
        try {
          curWorker = ServerName.parseServerName(task.cur_worker_name);
        } catch (IllegalArgumentException ie) {
          LOG.error("Got invalid server name:" + task.cur_worker_name + " - task for path:" + path
              + " won't be resubmitted before timeout");
        }
      } else {
        LOG.error("Got empty/null server name:" + task.cur_worker_name + " - task for path:" + path
            + " won't be resubmitted before timeout");
      }
      final boolean alive =
          (master.getServerManager() != null && curWorker != null) ? master.getServerManager()
              .isServerOnline(curWorker) : true;
      if (alive && time < timeout) {
        LOG.trace("Skipping the resubmit of " + task.toString() + "  because the server "
            + task.cur_worker_name + " is not marked as dead, we waited for " + time
            + " while the timeout is " + timeout);
        return false;
      }
      if (task.unforcedResubmits >= resubmit_threshold) {
        if (!task.resubmitThresholdReached) {
          task.resubmitThresholdReached = true;
          tot_mgr_resubmit_threshold_reached.incrementAndGet();
          LOG.info("Skipping resubmissions of task " + path +
              " because threshold " + resubmit_threshold + " reached");
        }
        return false;
      }
      // race with heartbeat() that might be changing last_version
      version = task.last_version;
    } else {
      version = -1;
    }
    LOG.info("resubmitting task " + path);
    task.incarnation++;
    try {
      // blocking zk call but this is done from the timeout thread
      if (ZKUtil.setData(this.watcher, path,
          TaskState.TASK_UNASSIGNED.get(serverName),
          version) == false) {
        LOG.debug("failed to resubmit task " + path +
            " version changed");
        task.heartbeatNoDetails(EnvironmentEdgeManager.currentTimeMillis());
        return false;
      }
    } catch (NoNodeException e) {
      LOG.warn("failed to resubmit because znode doesn't exist " + path +
          " task done (or forced done by removing the znode)");
      getDataSetWatchSuccess(path, null, Integer.MIN_VALUE);
      return false;
    } catch (KeeperException.BadVersionException e) {
      LOG.debug("failed to resubmit task " + path +
          " version changed");
      task.heartbeatNoDetails(EnvironmentEdgeManager.currentTimeMillis());
      return false;
    } catch (KeeperException e) {
      tot_mgr_resubmit_failed.incrementAndGet();
      LOG.warn("failed to resubmit " + path, e);
      return false;
    }
    // don't count forced resubmits
    if (directive != FORCE) {
      task.unforcedResubmits++;
    }
    task.setUnassigned();
    createRescanNode(Long.MAX_VALUE);
    tot_mgr_resubmit.incrementAndGet();
    return true;
  }

  private void resubmitOrFail(String path, ResubmitDirective directive) {
    if (resubmit(path, findOrCreateOrphanTask(path), directive) == false) {
      setDone(path, FAILURE);
    }
  }

  private void deleteNode(String path, Long retries) {
    tot_mgr_node_delete_queued.incrementAndGet();
    // Once a task znode is ready for delete, that is it is in the TASK_DONE
    // state, then no one should be writing to it anymore. That is no one
    // will be updating the znode version any more.
    this.watcher.getRecoverableZooKeeper().getZooKeeper().
      delete(path, -1, new DeleteAsyncCallback(),
        retries);
  }

  private void deleteNodeSuccess(String path) {
    if (ignoreZKDeleteForTesting) {
      return;
    }
    Task task;
    task = tasks.remove(path);
    if (task == null) {
      if (ZKSplitLog.isRescanNode(watcher, path)) {
        tot_mgr_rescan_deleted.incrementAndGet();
      }
      tot_mgr_missing_state_in_delete.incrementAndGet();
      LOG.debug("deleted task without in memory state " + path);
      return;
    }
    synchronized (task) {
      task.status = DELETED;
      task.notify();
    }
    tot_mgr_task_deleted.incrementAndGet();
  }

  private void deleteNodeFailure(String path) {
    LOG.info("Failed to delete node " + path + " and will retry soon.");
    return;
  }

  /**
   * signal the workers that a task was resubmitted by creating the
   * RESCAN node.
   * @throws KeeperException 
   */
  private void createRescanNode(long retries) {
    // The RESCAN node will be deleted almost immediately by the
    // SplitLogManager as soon as it is created because it is being
    // created in the DONE state. This behavior prevents a buildup
    // of RESCAN nodes. But there is also a chance that a SplitLogWorker
    // might miss the watch-trigger that creation of RESCAN node provides.
    // Since the TimeoutMonitor will keep resubmitting UNASSIGNED tasks
    // therefore this behavior is safe.
    this.watcher.getRecoverableZooKeeper().getZooKeeper().
      create(ZKSplitLog.getRescanNode(watcher),
        TaskState.TASK_DONE.get(serverName), Ids.OPEN_ACL_UNSAFE,
        CreateMode.EPHEMERAL_SEQUENTIAL,
        new CreateRescanAsyncCallback(), Long.valueOf(retries));
  }

  private void createRescanSuccess(String path) {
    lastNodeCreateTime = EnvironmentEdgeManager.currentTimeMillis();
    tot_mgr_rescan.incrementAndGet();
    getDataSetWatch(path, zkretries);
  }

  private void createRescanFailure() {
    LOG.fatal("logic failure, rescan failure must not happen");
  }

  /**
   * @param path
   * @param batch
   * @return null on success, existing task on error
   */
  private Task createTaskIfAbsent(String path, TaskBatch batch) {
    Task oldtask;
    // batch.installed is only changed via this function and
    // a single thread touches batch.installed.
    Task newtask = new Task();
    newtask.batch = batch;
    oldtask = tasks.putIfAbsent(path, newtask);
    if (oldtask == null) {
      batch.installed++;
      return  null;
    }
    // new task was not used.
    synchronized (oldtask) {
      if (oldtask.isOrphan()) {
        if (oldtask.status == SUCCESS) {
          // The task is already done. Do not install the batch for this
          // task because it might be too late for setDone() to update
          // batch.done. There is no need for the batch creator to wait for
          // this task to complete.
          return (null);
        }
        if (oldtask.status == IN_PROGRESS) {
          oldtask.batch = batch;
          batch.installed++;
          LOG.debug("Previously orphan task " + path +
              " is now being waited upon");
          return null;
        }
        while (oldtask.status == FAILURE) {
          LOG.debug("wait for status of task " + path +
              " to change to DELETED");
          tot_mgr_wait_for_zk_delete.incrementAndGet();
          try {
            oldtask.wait();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("Interrupted when waiting for znode delete callback");
            // fall through to return failure
            break;
          }
        }
        if (oldtask.status != DELETED) {
          LOG.warn("Failure because previously failed task" +
              " state still present. Waiting for znode delete callback" +
              " path=" + path);
          return oldtask;
        }
        // reinsert the newTask and it must succeed this time
        Task t = tasks.putIfAbsent(path, newtask);
        if (t == null) {
          batch.installed++;
          return  null;
        }
        LOG.fatal("Logic error. Deleted task still present in tasks map");
        assert false : "Deleted task still present in tasks map";
        return t;
      }
      LOG.warn("Failure because two threads can't wait for the same task. " +
          " path=" + path);
      return oldtask;
    }
  }

  Task findOrCreateOrphanTask(String path) {
    Task orphanTask = new Task();
    Task task;
    task = tasks.putIfAbsent(path, orphanTask);
    if (task == null) {
      LOG.info("creating orphan task " + path);
      tot_mgr_orphan_task_acquired.incrementAndGet();
      task = orphanTask;
    }
    return task;
  }

  @Override
  public void nodeDataChanged(String path) {
    Task task;
    task = tasks.get(path);
    if (task != null || ZKSplitLog.isRescanNode(watcher, path)) {
      if (task != null) {
        task.heartbeatNoDetails(EnvironmentEdgeManager.currentTimeMillis());
      }
      getDataSetWatch(path, zkretries);
    }
  }

  public void stop() {
    if (timeoutMonitor != null) {
      timeoutMonitor.interrupt();
    }
  }

  private void lookForOrphans() {
    List<String> orphans;
    try {
       orphans = ZKUtil.listChildrenNoWatch(this.watcher,
          this.watcher.splitLogZNode);
      if (orphans == null) {
        LOG.warn("could not get children of " + this.watcher.splitLogZNode);
        return;
      }
    } catch (KeeperException e) {
      LOG.warn("could not get children of " + this.watcher.splitLogZNode +
          " " + StringUtils.stringifyException(e));
      return;
    }
    int rescan_nodes = 0;
    for (String path : orphans) {
      String nodepath = ZKUtil.joinZNode(watcher.splitLogZNode, path);
      if (ZKSplitLog.isRescanNode(watcher, nodepath)) {
        rescan_nodes++;
        LOG.debug("found orphan rescan node " + path);
      } else {
        LOG.info("found orphan task " + path);
      }
      getDataSetWatch(nodepath, zkretries);
    }
    LOG.info("found " + (orphans.size() - rescan_nodes) + " orphan tasks and " +
        rescan_nodes + " rescan nodes");
  }

  /**
   * Keeps track of the batch of tasks submitted together by a caller in
   * splitLogDistributed(). Clients threads use this object to wait for all
   * their tasks to be done.
   * <p>
   * All access is synchronized.
   */
  static class TaskBatch {
    int installed = 0;
    int done = 0;
    int error = 0;
    volatile boolean isDead = false;

    @Override
    public String toString() {
      return ("installed = " + installed + " done = " + done + " error = "
          + error);
    }
  }

  /**
   * in memory state of an active task.
   */
  static class Task {
    volatile long last_update;
    volatile int last_version;
    volatile String cur_worker_name;
    TaskBatch batch;
    volatile TerminationStatus status;
    volatile int incarnation;
    volatile int unforcedResubmits;
    volatile boolean resubmitThresholdReached;

    @Override
    public String toString() {
      return ("last_update = " + last_update +
          " last_version = " + last_version +
          " cur_worker_name = " + cur_worker_name +
          " status = " + status +
          " incarnation = " + incarnation +
          " resubmits = " + unforcedResubmits +
          " batch = " + batch);
    }

    Task() {
      incarnation = 0;
      last_version = -1;
      status = IN_PROGRESS;
      setUnassigned();
    }

    public boolean isOrphan() {
      return (batch == null || batch.isDead);
    }

    public boolean isUnassigned() {
      return (cur_worker_name == null);
    }

    public void heartbeatNoDetails(long time) {
      last_update = time;
    }

    public void heartbeat(long time, int version, String worker) {
      last_version = version;
      last_update = time;
      cur_worker_name = worker;
    }

    public void setUnassigned() {
      cur_worker_name = null;
      last_update = -1;
    }
  }

  void handleDeadWorker(String workerName) {
    // resubmit the tasks on the TimeoutMonitor thread. Makes it easier
    // to reason about concurrency. Makes it easier to retry.
    synchronized (deadWorkersLock) {
      if (deadWorkers == null) {
        deadWorkers = new HashSet<String>(100);
      }
      deadWorkers.add(workerName);
    }
    LOG.info("dead splitlog worker " + workerName);
  }

  void handleDeadWorkers(List<ServerName> serverNames) {
    List<String> workerNames = new ArrayList<String>(serverNames.size());
    for (ServerName serverName : serverNames) {
      workerNames.add(serverName.toString());
    }
    synchronized (deadWorkersLock) {
      if (deadWorkers == null) {
        deadWorkers = new HashSet<String>(100);
      }
      deadWorkers.addAll(workerNames);
    }
    LOG.info("dead splitlog workers " + workerNames);
  }

  /**
   * Periodically checks all active tasks and resubmits the ones that have timed
   * out
   */
  private class TimeoutMonitor extends Chore {
    public TimeoutMonitor(final int period, Stoppable stopper) {
      super("SplitLogManager Timeout Monitor", period, stopper);
    }

    @Override
    protected void chore() {
      int resubmitted = 0;
      int unassigned = 0;
      int tot = 0;
      boolean found_assigned_task = false;
      Set<String> localDeadWorkers;

      synchronized (deadWorkersLock) {
        localDeadWorkers = deadWorkers;
        deadWorkers = null;
      }

      for (Map.Entry<String, Task> e : tasks.entrySet()) {
        String path = e.getKey();
        Task task = e.getValue();
        String cur_worker = task.cur_worker_name;
        tot++;
        // don't easily resubmit a task which hasn't been picked up yet. It
        // might be a long while before a SplitLogWorker is free to pick up a
        // task. This is because a SplitLogWorker picks up a task one at a
        // time. If we want progress when there are no region servers then we
        // will have to run a SplitLogWorker thread in the Master.
        if (task.isUnassigned()) {
          unassigned++;
          continue;
        }
        found_assigned_task = true;
        if (localDeadWorkers != null && localDeadWorkers.contains(cur_worker)) {
          tot_mgr_resubmit_dead_server_task.incrementAndGet();
          if (resubmit(path, task, FORCE)) {
            resubmitted++;
          } else {
            handleDeadWorker(cur_worker);
            LOG.warn("Failed to resubmit task " + path + " owned by dead " +
                cur_worker + ", will retry.");
          }
        } else if (resubmit(path, task, CHECK)) {
          resubmitted++;
        }
      }
      if (tot > 0) {
        LOG.debug("total tasks = " + tot + " unassigned = " + unassigned);
      }
      if (resubmitted > 0) {
        LOG.info("resubmitted " + resubmitted + " out of " + tot + " tasks");
      }
      // If there are pending tasks and all of them have been unassigned for
      // some time then put up a RESCAN node to ping the workers.
      // ZKSplitlog.DEFAULT_UNASSIGNED_TIMEOUT is of the order of minutes
      // because a. it is very unlikely that every worker had a
      // transient error when trying to grab the task b. if there are no
      // workers then all tasks wills stay unassigned indefinitely and the
      // manager will be indefinitely creating RESCAN nodes. TODO may be the
      // master should spawn both a manager and a worker thread to guarantee
      // that there is always one worker in the system
      if (tot > 0 && !found_assigned_task &&
          ((EnvironmentEdgeManager.currentTimeMillis() - lastNodeCreateTime) >
          unassignedTimeout)) {
        for (Map.Entry<String, Task> e : tasks.entrySet()) {
          String path = e.getKey();
          Task task = e.getValue();
          // we have to do task.isUnassigned() check again because tasks might
          // have been asynchronously assigned. There is no locking required
          // for these checks ... it is OK even if tryGetDataSetWatch() is
          // called unnecessarily for a task
          if (task.isUnassigned() && (task.status != FAILURE)) {
            // We just touch the znode to make sure its still there
            tryGetDataSetWatch(path);
          }
        }
        createRescanNode(Long.MAX_VALUE);
        tot_mgr_resubmit_unassigned.incrementAndGet();
        LOG.debug("resubmitting unassigned task(s) after timeout");
      }

      // Retry previously failed deletes
      if (failedDeletions.size() > 0) {
        List<String> tmpPaths = new ArrayList<String>(failedDeletions);
        failedDeletions.removeAll(tmpPaths);
        for (String tmpPath : tmpPaths) {
          // deleteNode is an async call
          deleteNode(tmpPath, zkretries);
        }
      }
    }
  }

  /**
   * Asynchronous handler for zk create node results.
   * Retries on failures.
   */
  class CreateAsyncCallback implements AsyncCallback.StringCallback {
    private final Log LOG = LogFactory.getLog(CreateAsyncCallback.class);

    @Override
    public void processResult(int rc, String path, Object ctx, String name) {
      tot_mgr_node_create_result.incrementAndGet();
      if (rc != 0) {
        if (shouldAbandonRetries(rc, "Create znode " + path)) {
          createNodeFailure(path);
          return;
        }
        if (rc == KeeperException.Code.NODEEXISTS.intValue()) {
          // What if there is a delete pending against this pre-existing
          // znode? Then this soon-to-be-deleted task znode must be in TASK_DONE
          // state. Only operations that will be carried out on this node by
          // this manager are get-znode-data, task-finisher and delete-znode.
          // And all code pieces correctly handle the case of suddenly
          // disappearing task-znode.
          LOG.debug("found pre-existing znode " + path);
          tot_mgr_node_already_exists.incrementAndGet();
        } else {
          Long retry_count = (Long)ctx;
          LOG.warn("create rc =" + KeeperException.Code.get(rc) + " for " +
              path + " remaining retries=" + retry_count);
          if (retry_count == 0) {
            tot_mgr_node_create_err.incrementAndGet();
            createNodeFailure(path);
          } else {
            tot_mgr_node_create_retry.incrementAndGet();
            createNode(path, retry_count - 1);
          }
          return;
        }
      }
      createNodeSuccess(path);
    }
  }

  /**
   * Asynchronous handler for zk get-data-set-watch on node results.
   * Retries on failures.
   */
  class GetDataAsyncCallback implements AsyncCallback.DataCallback {
    private final Log LOG = LogFactory.getLog(GetDataAsyncCallback.class);

    @Override
    public void processResult(int rc, String path, Object ctx, byte[] data,
        Stat stat) {
      tot_mgr_get_data_result.incrementAndGet();
      if (rc != 0) {
        if (shouldAbandonRetries(rc, "GetData from znode " + path)) {
          return;
        }
        if (rc == KeeperException.Code.NONODE.intValue()) {
          tot_mgr_get_data_nonode.incrementAndGet();
          // The task znode has been deleted. Must be some pending delete
          // that deleted the task. Assume success because a task-znode is
          // is only deleted after TaskFinisher is successful.
          LOG.warn("task znode " + path + " vanished.");
          getDataSetWatchSuccess(path, null, Integer.MIN_VALUE);
          return;
        }
        Long retry_count = (Long) ctx;

        if (retry_count < 0) {
          LOG.warn("getdata rc = " + KeeperException.Code.get(rc) + " " +
              path + ". Ignoring error. No error handling. No retrying.");
          return;
        }
        LOG.warn("getdata rc = " + KeeperException.Code.get(rc) + " " +
            path + " remaining retries=" + retry_count);
        if (retry_count == 0) {
          tot_mgr_get_data_err.incrementAndGet();
          getDataSetWatchFailure(path);
        } else {
          tot_mgr_get_data_retry.incrementAndGet();
          getDataSetWatch(path, retry_count - 1);
        }
        return;
      }
      getDataSetWatchSuccess(path, data, stat.getVersion());
      return;
    }
  }

  /**
   * Asynchronous handler for zk delete node results.
   * Retries on failures.
   */
  class DeleteAsyncCallback implements AsyncCallback.VoidCallback {
    private final Log LOG = LogFactory.getLog(DeleteAsyncCallback.class);

    @Override
    public void processResult(int rc, String path, Object ctx) {
      tot_mgr_node_delete_result.incrementAndGet();
      if (rc != 0) {
        if (shouldAbandonRetries(rc, "Delete znode " + path)) {
          failedDeletions.add(path);
          return;
        }
        if (rc != KeeperException.Code.NONODE.intValue()) {
          tot_mgr_node_delete_err.incrementAndGet();
          Long retry_count = (Long) ctx;
          LOG.warn("delete rc=" + KeeperException.Code.get(rc) + " for " +
              path + " remaining retries=" + retry_count);
          if (retry_count == 0) {
            LOG.warn("delete failed " + path);
            failedDeletions.add(path);
            deleteNodeFailure(path);
          } else {
            deleteNode(path, retry_count - 1);
          }
          return;
        } else {
        LOG.debug(path +
            " does not exist. Either was created but deleted behind our" +
            " back by another pending delete OR was deleted" +
            " in earlier retry rounds. zkretries = " + (Long) ctx);
        }
      } else {
        LOG.debug("deleted " + path);
      }
      deleteNodeSuccess(path);
    }
  }

  /**
   * Asynchronous handler for zk create RESCAN-node results.
   * Retries on failures.
   * <p>
   * A RESCAN node is created using PERSISTENT_SEQUENTIAL flag. It is a signal
   * for all the {@link SplitLogWorker}s to rescan for new tasks.
   */
  class CreateRescanAsyncCallback implements AsyncCallback.StringCallback {
    private final Log LOG = LogFactory.getLog(CreateRescanAsyncCallback.class);

    @Override
    public void processResult(int rc, String path, Object ctx, String name) {
      if (rc != 0) {
        if (shouldAbandonRetries(rc, "CreateRescan znode " + path)) {
          return;
        }
        Long retry_count = (Long)ctx;
        LOG.warn("rc=" + KeeperException.Code.get(rc) + " for "+ path +
            " remaining retries=" + retry_count);
        if (retry_count == 0) {
          createRescanFailure();
        } else {
          createRescanNode(retry_count - 1);
        }
        return;
      }
      // path is the original arg, name is the actual name that was created
      createRescanSuccess(name);
    }
  }

  /**
   * {@link SplitLogManager} can use objects implementing this interface to
   * finish off a partially done task by {@link SplitLogWorker}. This provides
   * a serialization point at the end of the task processing. Must be
   * restartable and idempotent.
   */
  static public interface TaskFinisher {
    /**
     * status that can be returned finish()
     */
    static public enum Status {
      /**
       * task completed successfully
       */
      DONE(),
      /**
       * task completed with error
       */
      ERR();
    }
    /**
     * finish the partially done task. workername provides clue to where the
     * partial results of the partially done tasks are present. taskname is the
     * name of the task that was put up in zookeeper.
     * <p>
     * @param workerName
     * @param taskname
     * @return DONE if task completed successfully, ERR otherwise
     */
    public Status finish(String workerName, String taskname);
  }
  enum ResubmitDirective {
    CHECK(),
    FORCE();
  }
  enum TerminationStatus {
    IN_PROGRESS("in_progress"),
    SUCCESS("success"),
    FAILURE("failure"),
    DELETED("deleted");

    String statusMsg;
    TerminationStatus(String msg) {
      statusMsg = msg;
    }
    
    @Override
    public String toString() {
      return statusMsg;
    }
  }
  
  /**
   * Completes the initialization
   */
  public void finishInitialization() {
    finishInitialization(false);
  }
}
