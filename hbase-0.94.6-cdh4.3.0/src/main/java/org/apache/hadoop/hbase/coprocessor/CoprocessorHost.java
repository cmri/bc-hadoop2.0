/*
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
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hbase.coprocessor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.Coprocessor;
import org.apache.hadoop.hbase.CoprocessorEnvironment;
import org.apache.hadoop.hbase.DoNotRetryIOException;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.Server;
import org.apache.hadoop.hbase.client.Append;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.HTableInterface;
import org.apache.hadoop.hbase.client.Increment;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Row;
import org.apache.hadoop.hbase.client.RowLock;
import org.apache.hadoop.hbase.client.RowMutations;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.coprocessor.Batch;
import org.apache.hadoop.hbase.ipc.CoprocessorProtocol;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.CoprocessorClassLoader;
import org.apache.hadoop.hbase.util.SortedCopyOnWriteSet;
import org.apache.hadoop.hbase.util.VersionInfo;

/**
 * Provides the common setup framework and runtime services for coprocessor
 * invocation from HBase services.
 * @param <E> the specific environment extension that a concrete implementation
 * provides
 */
public abstract class CoprocessorHost<E extends CoprocessorEnvironment> {
  public static final String REGION_COPROCESSOR_CONF_KEY =
    "hbase.coprocessor.region.classes";
  public static final String REGIONSERVER_COPROCESSOR_CONF_KEY =
    "hbase.coprocessor.regionserver.classes";
  public static final String USER_REGION_COPROCESSOR_CONF_KEY =
    "hbase.coprocessor.user.region.classes";
  public static final String MASTER_COPROCESSOR_CONF_KEY =
    "hbase.coprocessor.master.classes";
  public static final String WAL_COPROCESSOR_CONF_KEY =
    "hbase.coprocessor.wal.classes";

  private static final Log LOG = LogFactory.getLog(CoprocessorHost.class);
  /** Ordered set of loaded coprocessors with lock */
  protected SortedSet<E> coprocessors =
      new SortedCopyOnWriteSet<E>(new EnvironmentPriorityComparator());
  protected Configuration conf;
  // unique file prefix to use for local copies of jars when classloading
  protected String pathPrefix;
  protected volatile int loadSequence;

  public CoprocessorHost() {
    pathPrefix = UUID.randomUUID().toString();
  }

  /**
   * Not to be confused with the per-object _coprocessors_ (above),
   * coprocessorNames is static and stores the set of all coprocessors ever
   * loaded by any thread in this JVM. It is strictly additive: coprocessors are
   * added to coprocessorNames, by loadInstance() but are never removed, since
   * the intention is to preserve a history of all loaded coprocessors for
   * diagnosis in case of server crash (HBASE-4014).
   */
  private static Set<String> coprocessorNames =
      Collections.synchronizedSet(new HashSet<String>());
  public static Set<String> getLoadedCoprocessors() {
      return coprocessorNames;
  }

  /**
   * Used to create a parameter to the HServerLoad constructor so that
   * HServerLoad can provide information about the coprocessors loaded by this
   * regionserver.
   * (HBASE-4070: Improve region server metrics to report loaded coprocessors
   * to master).
   */
  public Set<String> getCoprocessors() {
    Set<String> returnValue = new TreeSet<String>();
    for(CoprocessorEnvironment e: coprocessors) {
      returnValue.add(e.getInstance().getClass().getSimpleName());
    }
    return returnValue;
  }

  /**
   * Load system coprocessors. Read the class names from configuration.
   * Called by constructor.
   */
  protected void loadSystemCoprocessors(Configuration conf, String confKey) {
    Class<?> implClass = null;

    // load default coprocessors from configure file    
    String[] defaultCPClasses = conf.getStrings(confKey);
    if (defaultCPClasses == null || defaultCPClasses.length == 0)
      return;

    int priority = Coprocessor.PRIORITY_SYSTEM;
    List<E> configured = new ArrayList<E>();
    for (String className : defaultCPClasses) {
      className = className.trim();
      if (findCoprocessor(className) != null) {
        continue;
      }
      ClassLoader cl = this.getClass().getClassLoader();
      Thread.currentThread().setContextClassLoader(cl);
      try {
        implClass = cl.loadClass(className);
        configured.add(loadInstance(implClass, Coprocessor.PRIORITY_SYSTEM, conf));
        LOG.info("System coprocessor " + className + " was loaded " +
            "successfully with priority (" + priority++ + ").");
      } catch (ClassNotFoundException e) {
        LOG.warn("Class " + className + " cannot be found. " +
            e.getMessage());
      } catch (IOException e) {
        LOG.warn("Load coprocessor " + className + " failed. " +
            e.getMessage());
      }
    }

    // add entire set to the collection for COW efficiency
    coprocessors.addAll(configured);
  }

  /**
   * Load a coprocessor implementation into the host
   * @param path path to implementation jar
   * @param className the main class name
   * @param priority chaining priority
   * @param conf configuration for coprocessor
   * @throws java.io.IOException Exception
   */
  @SuppressWarnings("deprecation")
  public E load(Path path, String className, int priority,
      Configuration conf) throws IOException {
    Class<?> implClass = null;
    LOG.debug("Loading coprocessor class " + className + " with path " + 
        path + " and priority " + priority);

    ClassLoader cl = null;
    if (path == null) {
      try {
        implClass = getClass().getClassLoader().loadClass(className);
      } catch (ClassNotFoundException e) {
        throw new IOException("No jar path specified for " + className);
      }
    } else {
      cl = CoprocessorClassLoader.getClassLoader(
        path, getClass().getClassLoader(), pathPrefix, conf);
      try {
        implClass = cl.loadClass(className);
      } catch (ClassNotFoundException e) {
        throw new IOException("Cannot load external coprocessor class " + className, e);
      }
    }

    //load custom code for coprocessor
    Thread currentThread = Thread.currentThread();
    ClassLoader hostClassLoader = currentThread.getContextClassLoader();
    try{
      // switch temporarily to the thread classloader for custom CP
      currentThread.setContextClassLoader(cl);
      E cpInstance = loadInstance(implClass, priority, conf);
      return cpInstance;
    } finally {
      // restore the fresh (host) classloader
      currentThread.setContextClassLoader(hostClassLoader);
    }
  }

  /**
   * @param implClass Implementation class
   * @param priority priority
   * @param conf configuration
   * @throws java.io.IOException Exception
   */
  public void load(Class<?> implClass, int priority, Configuration conf)
      throws IOException {
    E env = loadInstance(implClass, priority, conf);
    coprocessors.add(env);
  }

  /**
   * @param implClass Implementation class
   * @param priority priority
   * @param conf configuration
   * @throws java.io.IOException Exception
   */
  public E loadInstance(Class<?> implClass, int priority, Configuration conf)
      throws IOException {
    // create the instance
    Coprocessor impl;
    Object o = null;
    try {
      o = implClass.newInstance();
      impl = (Coprocessor)o;
    } catch (InstantiationException e) {
      throw new IOException(e);
    } catch (IllegalAccessException e) {
      throw new IOException(e);
    }
    // create the environment
    E env = createEnvironment(implClass, impl, priority, ++loadSequence, conf);
    if (env instanceof Environment) {
      ((Environment)env).startup();
    }
    // HBASE-4014: maintain list of loaded coprocessors for later crash analysis
    // if server (master or regionserver) aborts.
    coprocessorNames.add(implClass.getName());
    return env;
  }

  /**
   * Called when a new Coprocessor class is loaded
   */
  public abstract E createEnvironment(Class<?> implClass, Coprocessor instance,
      int priority, int sequence, Configuration conf);

  public void shutdown(CoprocessorEnvironment e) {
    if (e instanceof Environment) {
      ((Environment)e).shutdown();
    } else {
      LOG.warn("Shutdown called on unknown environment: "+
          e.getClass().getName());
    }
  }

  /**
   * Find a coprocessor implementation by class name
   * @param className the class name
   * @return the coprocessor, or null if not found
   */
  public Coprocessor findCoprocessor(String className) {
    // initialize the coprocessors
    for (E env: coprocessors) {
      if (env.getInstance().getClass().getName().equals(className) ||
          env.getInstance().getClass().getSimpleName().equals(className)) {
        return env.getInstance();
      }
    }
    return null;
  }

  /**
   * Retrieves the set of classloaders used to instantiate Coprocessor classes defined in external
   * jar files.
   * @return A set of ClassLoader instances
   */
  Set<ClassLoader> getExternalClassLoaders() {
    Set<ClassLoader> externalClassLoaders = new HashSet<ClassLoader>();
    final ClassLoader systemClassLoader = this.getClass().getClassLoader();
    for (E env : coprocessors) {
      ClassLoader cl = env.getInstance().getClass().getClassLoader();
      if (cl != systemClassLoader ){
        //do not include system classloader
        externalClassLoaders.add(cl);
      }
    }
    return externalClassLoaders;
  }

  /**
   * Find a coprocessor environment by class name
   * @param className the class name
   * @return the coprocessor, or null if not found
   */
  public CoprocessorEnvironment findCoprocessorEnvironment(String className) {
    // initialize the coprocessors
    for (E env: coprocessors) {
      if (env.getInstance().getClass().getName().equals(className) ||
          env.getInstance().getClass().getSimpleName().equals(className)) {
        return env;
      }
    }
    return null;
  }

  /**
   * Environment priority comparator.
   * Coprocessors are chained in sorted order.
   */
  static class EnvironmentPriorityComparator
      implements Comparator<CoprocessorEnvironment> {
    public int compare(final CoprocessorEnvironment env1,
        final CoprocessorEnvironment env2) {
      if (env1.getPriority() < env2.getPriority()) {
        return -1;
      } else if (env1.getPriority() > env2.getPriority()) {
        return 1;
      }
      if (env1.getLoadSequence() < env2.getLoadSequence()) {
        return -1;
      } else if (env1.getLoadSequence() > env2.getLoadSequence()) {
        return 1;
      }
      return 0;
    }
  }

  /**
   * Encapsulation of the environment of each coprocessor
   */
  public static class Environment implements CoprocessorEnvironment {

    /**
     * A wrapper for HTable. Can be used to restrict privilege.
     *
     * Currently it just helps to track tables opened by a Coprocessor and
     * facilitate close of them if it is aborted.
     *
     * We also disallow row locking.
     *
     * There is nothing now that will stop a coprocessor from using HTable
     * objects directly instead of this API, but in the future we intend to
     * analyze coprocessor implementations as they are loaded and reject those
     * which attempt to use objects and methods outside the Environment
     * sandbox.
     */
    class HTableWrapper implements HTableInterface {

      private byte[] tableName;
      private HTable table;

      public HTableWrapper(byte[] tableName) throws IOException {
        this.tableName = tableName;
        this.table = new HTable(conf, tableName);
        openTables.add(this);
      }

      void internalClose() throws IOException {
        table.close();
      }

      public Configuration getConfiguration() {
        return table.getConfiguration();
      }

      public void close() throws IOException {
        try {
          internalClose();
        } finally {
          openTables.remove(this);
        }
      }

      public Result getRowOrBefore(byte[] row, byte[] family)
          throws IOException {
        return table.getRowOrBefore(row, family);
      }

      public Result get(Get get) throws IOException {
        return table.get(get);
      }

      public boolean exists(Get get) throws IOException {
        return table.exists(get);
      }

      public void put(Put put) throws IOException {
        table.put(put);
      }

      public void put(List<Put> puts) throws IOException {
        table.put(puts);
      }

      public void delete(Delete delete) throws IOException {
        table.delete(delete);
      }

      public void delete(List<Delete> deletes) throws IOException {
        table.delete(deletes);
      }

      public boolean checkAndPut(byte[] row, byte[] family, byte[] qualifier,
          byte[] value, Put put) throws IOException {
        return table.checkAndPut(row, family, qualifier, value, put);
      }

      public boolean checkAndDelete(byte[] row, byte[] family, byte[] qualifier,
          byte[] value, Delete delete) throws IOException {
        return table.checkAndDelete(row, family, qualifier, value, delete);
      }

      public long incrementColumnValue(byte[] row, byte[] family,
          byte[] qualifier, long amount) throws IOException {
        return table.incrementColumnValue(row, family, qualifier, amount);
      }

      public long incrementColumnValue(byte[] row, byte[] family,
          byte[] qualifier, long amount, boolean writeToWAL)
          throws IOException {
        return table.incrementColumnValue(row, family, qualifier, amount,
          writeToWAL);
      }

      @Override
      public Result append(Append append) throws IOException {
        return table.append(append);
      }

      @Override
      public Result increment(Increment increment) throws IOException {
        return table.increment(increment);
      }

      public void flushCommits() throws IOException {
        table.flushCommits();
      }

      public boolean isAutoFlush() {
        return table.isAutoFlush();
      }

      public ResultScanner getScanner(Scan scan) throws IOException {
        return table.getScanner(scan);
      }

      public ResultScanner getScanner(byte[] family) throws IOException {
        return table.getScanner(family);
      }

      public ResultScanner getScanner(byte[] family, byte[] qualifier)
          throws IOException {
        return table.getScanner(family, qualifier);
      }

      public HTableDescriptor getTableDescriptor() throws IOException {
        return table.getTableDescriptor();
      }

      public byte[] getTableName() {
        return tableName;
      }

      /**
       * @deprecated {@link RowLock} and associated operations are deprecated.
       */
      public RowLock lockRow(byte[] row) throws IOException {
        throw new RuntimeException(
          "row locking is not allowed within the coprocessor environment");
      }

      /**
       * @deprecated {@link RowLock} and associated operations are deprecated.
       */
      public void unlockRow(RowLock rl) throws IOException {
        throw new RuntimeException(
          "row locking is not allowed within the coprocessor environment");
      }

      @Override
      public void batch(List<? extends Row> actions, Object[] results)
          throws IOException, InterruptedException {
        table.batch(actions, results);
      }

      @Override
      public Object[] batch(List<? extends Row> actions)
          throws IOException, InterruptedException {
        return table.batch(actions);
      }

      @Override
      public Result[] get(List<Get> gets) throws IOException {
        return table.get(gets);
      }

      @Override
      public <T extends CoprocessorProtocol, R> void coprocessorExec(Class<T> protocol,
          byte[] startKey, byte[] endKey, Batch.Call<T, R> callable,
          Batch.Callback<R> callback) throws IOException, Throwable {
        table.coprocessorExec(protocol, startKey, endKey, callable, callback);
      }

      @Override
      public <T extends CoprocessorProtocol, R> Map<byte[], R> coprocessorExec(
          Class<T> protocol, byte[] startKey, byte[] endKey, Batch.Call<T, R> callable)
          throws IOException, Throwable {
        return table.coprocessorExec(protocol, startKey, endKey, callable);
      }

      @Override
      public <T extends CoprocessorProtocol> T coprocessorProxy(Class<T> protocol,
          byte[] row) {
        return table.coprocessorProxy(protocol, row);
      }

      @Override
      public void mutateRow(RowMutations rm) throws IOException {
        table.mutateRow(rm);
      }

      @Override
      public void setAutoFlush(boolean autoFlush) {
        table.setAutoFlush(autoFlush);
      }

      @Override
      public void setAutoFlush(boolean autoFlush, boolean clearBufferOnFail) {
        table.setAutoFlush(autoFlush, clearBufferOnFail);
      }

      @Override
      public long getWriteBufferSize() {
         return table.getWriteBufferSize();
      }

      @Override
      public void setWriteBufferSize(long writeBufferSize) throws IOException {
        table.setWriteBufferSize(writeBufferSize);
      }
    }

    /** The coprocessor */
    public Coprocessor impl;
    /** Chaining priority */
    protected int priority = Coprocessor.PRIORITY_USER;
    /** Current coprocessor state */
    Coprocessor.State state = Coprocessor.State.UNINSTALLED;
    /** Accounting for tables opened by the coprocessor */
    protected List<HTableInterface> openTables =
      Collections.synchronizedList(new ArrayList<HTableInterface>());
    private int seq;
    private Configuration conf;

    /**
     * Constructor
     * @param impl the coprocessor instance
     * @param priority chaining priority
     */
    public Environment(final Coprocessor impl, final int priority,
        final int seq, final Configuration conf) {
      this.impl = impl;
      this.priority = priority;
      this.state = Coprocessor.State.INSTALLED;
      this.seq = seq;
      this.conf = conf;
    }

    /** Initialize the environment */
    public void startup() {
      if (state == Coprocessor.State.INSTALLED ||
          state == Coprocessor.State.STOPPED) {
        state = Coprocessor.State.STARTING;
        try {
          impl.start(this);
          state = Coprocessor.State.ACTIVE;
        } catch (IOException ioe) {
          LOG.error("Error starting coprocessor "+impl.getClass().getName(), ioe);
        }
      } else {
        LOG.warn("Not starting coprocessor "+impl.getClass().getName()+
            " because not inactive (state="+state.toString()+")");
      }
    }

    /** Clean up the environment */
    protected void shutdown() {
      if (state == Coprocessor.State.ACTIVE) {
        state = Coprocessor.State.STOPPING;
        try {
          impl.stop(this);
          state = Coprocessor.State.STOPPED;
        } catch (IOException ioe) {
          LOG.error("Error stopping coprocessor "+impl.getClass().getName(), ioe);
        }
      } else {
        LOG.warn("Not stopping coprocessor "+impl.getClass().getName()+
            " because not active (state="+state.toString()+")");
      }
      // clean up any table references
      for (HTableInterface table: openTables) {
        try {
          ((HTableWrapper)table).internalClose();
        } catch (IOException e) {
          // nothing can be done here
          LOG.warn("Failed to close " +
              Bytes.toStringBinary(table.getTableName()), e);
        }
      }
    }

    @Override
    public Coprocessor getInstance() {
      return impl;
    }

    @Override
    public int getPriority() {
      return priority;
    }

    @Override
    public int getLoadSequence() {
      return seq;
    }

    /** @return the coprocessor environment version */
    @Override
    public int getVersion() {
      return Coprocessor.VERSION;
    }

    /** @return the HBase release */
    @Override
    public String getHBaseVersion() {
      return VersionInfo.getVersion();
    }

    @Override
    public Configuration getConfiguration() {
      return conf;
    }

    /**
     * Open a table from within the Coprocessor environment
     * @param tableName the table name
     * @return an interface for manipulating the table
     * @exception java.io.IOException Exception
     */
    @Override
    public HTableInterface getTable(byte[] tableName) throws IOException {
      return new HTableWrapper(tableName);
    }
  }

  protected void abortServer(final String service,
      final Server server,
      final CoprocessorEnvironment environment,
      final Throwable e) {
    String coprocessorName = (environment.getInstance()).toString();
    server.abort("Aborting service: " + service + " running on : "
            + server.getServerName() + " because coprocessor: "
            + coprocessorName + " threw an exception.", e);
  }

  protected void abortServer(final CoprocessorEnvironment environment,
                             final Throwable e) {
    String coprocessorName = (environment.getInstance()).toString();
    LOG.error("The coprocessor: " + coprocessorName + " threw an unexpected " +
        "exception: " + e + ", but there's no specific implementation of " +
        " abortServer() for this coprocessor's environment.");
  }


  /**
   * This is used by coprocessor hooks which are declared to throw IOException
   * (or its subtypes). For such hooks, we should handle throwable objects
   * depending on the Throwable's type. Those which are instances of
   * IOException should be passed on to the client. This is in conformance with
   * the HBase idiom regarding IOException: that it represents a circumstance
   * that should be passed along to the client for its own handling. For
   * example, a coprocessor that implements access controls would throw a
   * subclass of IOException, such as AccessDeniedException, in its preGet()
   * method to prevent an unauthorized client's performing a Get on a particular
   * table.
   * @param env Coprocessor Environment
   * @param e Throwable object thrown by coprocessor.
   * @exception IOException Exception
   */
  protected void handleCoprocessorThrowable(final CoprocessorEnvironment env,
                                            final Throwable e)
      throws IOException {
    if (e instanceof IOException) {
      throw (IOException)e;
    }
    // If we got here, e is not an IOException. A loaded coprocessor has a
    // fatal bug, and the server (master or regionserver) should remove the
    // faulty coprocessor from its set of active coprocessors. Setting
    // 'hbase.coprocessor.abortonerror' to true will cause abortServer(),
    // which may be useful in development and testing environments where
    // 'failing fast' for error analysis is desired.
    if (env.getConfiguration().getBoolean("hbase.coprocessor.abortonerror",false)) {
      // server is configured to abort.
      abortServer(env, e);
    } else {
      LOG.error("Removing coprocessor '" + env.toString() + "' from " +
          "environment because it threw:  " + e,e);
      coprocessors.remove(env);
      throw new DoNotRetryIOException("Coprocessor: '" + env.toString() +
          "' threw: '" + e + "' and has been removed" + "from the active " +
          "coprocessor set.", e);
    }
  }
}


