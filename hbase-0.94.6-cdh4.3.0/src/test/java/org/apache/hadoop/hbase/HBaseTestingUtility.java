/**
 * Copyright 2009 The Apache Software Foundation
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
package org.apache.hadoop.hbase;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Random;
import java.util.UUID;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.logging.impl.Jdk14Logger;
import org.apache.commons.logging.impl.Log4JLogger;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.client.HConnection;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.fs.HFileSystem;
import org.apache.hadoop.hbase.io.encoding.DataBlockEncoding;
import org.apache.hadoop.hbase.io.hfile.ChecksumUtil;
import org.apache.hadoop.hbase.io.hfile.Compression;
import org.apache.hadoop.hbase.io.hfile.Compression.Algorithm;
import org.apache.hadoop.hbase.io.hfile.HFile;
import org.apache.hadoop.hbase.master.HMaster;
import org.apache.hadoop.hbase.master.ServerManager;
import org.apache.hadoop.hbase.regionserver.HRegion;
import org.apache.hadoop.hbase.regionserver.HRegionServer;
import org.apache.hadoop.hbase.regionserver.InternalScanner;
import org.apache.hadoop.hbase.regionserver.MultiVersionConsistencyControl;
import org.apache.hadoop.hbase.regionserver.Store;
import org.apache.hadoop.hbase.regionserver.StoreFile;
import org.apache.hadoop.hbase.security.User;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.FSUtils;
import org.apache.hadoop.hbase.util.JVMClusterUtil;
import org.apache.hadoop.hbase.util.JVMClusterUtil.MasterThread;
import org.apache.hadoop.hbase.util.RegionSplitter;
import org.apache.hadoop.hbase.util.Threads;
import org.apache.hadoop.hbase.util.Writables;
import org.apache.hadoop.hbase.zookeeper.MiniZooKeeperCluster;
import org.apache.hadoop.hbase.zookeeper.ZKAssign;
import org.apache.hadoop.hbase.zookeeper.ZKConfig;
import org.apache.hadoop.hbase.zookeeper.ZooKeeperWatcher;
import org.apache.hadoop.hdfs.DFSClient;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.MiniMRCluster;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.KeeperException.NodeExistsException;
import org.apache.zookeeper.ZooKeeper;

/**
 * Facility for testing HBase. Replacement for
 * old HBaseTestCase and HBaseClusterTestCase functionality.
 * Create an instance and keep it around testing HBase.  This class is
 * meant to be your one-stop shop for anything you might need testing.  Manages
 * one cluster at a time only. Managed cluster can be an in-process
 * {@link MiniHBaseCluster}, or a deployed cluster of type {@link DistributedHBaseCluster}.
 * Not all methods work with the real cluster.
 * Depends on log4j being on classpath and
 * hbase-site.xml for logging and test-run configuration.  It does not set
 * logging levels nor make changes to configuration parameters.
 */
public class HBaseTestingUtility {
  private static final Log LOG = LogFactory.getLog(HBaseTestingUtility.class);
  private Configuration conf;
  private MiniZooKeeperCluster zkCluster = null;

  /**
   * The default number of regions per regionserver when creating a pre-split
   * table.
   */
  private static int DEFAULT_REGIONS_PER_SERVER = 5;

  /**
   * Set if we were passed a zkCluster.  If so, we won't shutdown zk as
   * part of general shutdown.
   */
  private boolean passedZkCluster = false;
  private MiniDFSCluster dfsCluster = null;

  private HBaseCluster hbaseCluster = null;
  private MiniMRCluster mrCluster = null;

  // Directory where we put the data for this instance of HBaseTestingUtility
  private File dataTestDir = null;

  // Directory (usually a subdirectory of dataTestDir) used by the dfs cluster
  //  if any
  private File clusterTestDir = null;

  /**
   * System property key to get test directory value.
   * Name is as it is because mini dfs has hard-codings to put test data here.
   * It should NOT be used directly in HBase, as it's a property used in
   *  mini dfs.
   *  @deprecated can be used only with mini dfs
   */
  private static final String TEST_DIRECTORY_KEY = "test.build.data";

  /**
   * System property key to get base test directory value
   */
  public static final String BASE_TEST_DIRECTORY_KEY =
    "test.build.data.basedirectory";

  /**
   * Default base directory for test output.
   */
  public static final String DEFAULT_BASE_TEST_DIRECTORY = "target/test-data";

  /** Compression algorithms to use in parameterized JUnit 4 tests */
  public static final List<Object[]> COMPRESSION_ALGORITHMS_PARAMETERIZED =
    Arrays.asList(new Object[][] {
      { Compression.Algorithm.NONE },
      { Compression.Algorithm.GZ }
    });

  /** This is for unit tests parameterized with a single boolean. */
  public static final List<Object[]> BOOLEAN_PARAMETERIZED =
      Arrays.asList(new Object[][] {
          { new Boolean(false) },
          { new Boolean(true) }
      });

  /** Compression algorithms to use in testing */
  public static final Compression.Algorithm[] COMPRESSION_ALGORITHMS ={
      Compression.Algorithm.NONE, Compression.Algorithm.GZ
    };

  /**
   * Create all combinations of Bloom filters and compression algorithms for
   * testing.
   */
  private static List<Object[]> bloomAndCompressionCombinations() {
    List<Object[]> configurations = new ArrayList<Object[]>();
    for (Compression.Algorithm comprAlgo :
         HBaseTestingUtility.COMPRESSION_ALGORITHMS) {
      for (StoreFile.BloomType bloomType : StoreFile.BloomType.values()) {
        configurations.add(new Object[] { comprAlgo, bloomType });
      }
    }
    return Collections.unmodifiableList(configurations);
  }

  public static final Collection<Object[]> BLOOM_AND_COMPRESSION_COMBINATIONS =
      bloomAndCompressionCombinations();

  public HBaseTestingUtility() {
    this(HBaseConfiguration.create());
  }

  public HBaseTestingUtility(Configuration conf) {
    this.conf = conf;

    // a hbase checksum verification failure will cause unit tests to fail
    ChecksumUtil.generateExceptionForChecksumFailureForTest(true);
    setHDFSClientRetryProperty();
  }

  private void setHDFSClientRetryProperty() {
    this.conf.setInt("hdfs.client.retries.number", 1);
    HBaseFileSystem.setRetryCounts(conf);
  }

  /**
   * Returns this classes's instance of {@link Configuration}.  Be careful how
   * you use the returned Configuration since {@link HConnection} instances
   * can be shared.  The Map of HConnections is keyed by the Configuration.  If
   * say, a Connection was being used against a cluster that had been shutdown,
   * see {@link #shutdownMiniCluster()}, then the Connection will no longer
   * be wholesome.  Rather than use the return direct, its usually best to
   * make a copy and use that.  Do
   * <code>Configuration c = new Configuration(INSTANCE.getConfiguration());</code>
   * @return Instance of Configuration.
   */
  public Configuration getConfiguration() {
    return this.conf;
  }

  public void setHBaseCluster(HBaseCluster hbaseCluster) {
    this.hbaseCluster = hbaseCluster;
  }

  /**
   * @return Where to write test data on local filesystem; usually
   * {@link #DEFAULT_BASE_TEST_DIRECTORY}
   * Should not be used by the unit tests, hence its's private.
   * Unit test will use a subdirectory of this directory.
   * @see #setupDataTestDir()
   * @see #getTestFileSystem()
   */
  private Path getBaseTestDir() {
    String PathName = System.getProperty(
      BASE_TEST_DIRECTORY_KEY, DEFAULT_BASE_TEST_DIRECTORY);

    return new Path(PathName);
  }

  /**
   * @return Where to write test data on local filesystem, specific to
   *  the test.  Useful for tests that do not use a cluster.
   * Creates it if it does not exist already.
   * @see #getTestFileSystem()
   */
  public Path getDataTestDir() {
    if (dataTestDir == null){
      setupDataTestDir();
    }
    return new Path(dataTestDir.getAbsolutePath());
  }

  /**
   * @return Where the DFS cluster will write data on the local subsystem.
   * Creates it if it does not exist already.
   * @see #getTestFileSystem()
   */
  public Path getClusterTestDir() {
    if (clusterTestDir == null){
      setupClusterTestDir();
    }
    return new Path(clusterTestDir.getAbsolutePath());
  }

  /**
   * @param subdirName
   * @return Path to a subdirectory named <code>subdirName</code> under
   * {@link #getDataTestDir()}.
   * Does *NOT* create it if it does not exist.
   */
  public Path getDataTestDir(final String subdirName) {
    return new Path(getDataTestDir(), subdirName);
  }

  /**
   * Home our data in a dir under {@link #DEFAULT_BASE_TEST_DIRECTORY}.
   * Give it a random name so can have many concurrent tests running if
   * we need to.  It needs to amend the {@link #TEST_DIRECTORY_KEY}
   * System property, as it's what minidfscluster bases
   * it data dir on.  Moding a System property is not the way to do concurrent
   * instances -- another instance could grab the temporary
   * value unintentionally -- but not anything can do about it at moment;
   * single instance only is how the minidfscluster works.
   *
   * We also create the underlying directory for
   *  hadoop.log.dir, mapred.local.dir and hadoop.tmp.dir, and set the values
   *  in the conf, and as a system property for hadoop.tmp.dir
   *
   * @return The calculated data test build directory.
   */
  private void setupDataTestDir() {
    if (dataTestDir != null) {
      LOG.warn("Data test dir already setup in " +
        dataTestDir.getAbsolutePath());
      return;
    }

    String randomStr = UUID.randomUUID().toString();
    Path testPath= new Path(getBaseTestDir(), randomStr);

    dataTestDir = new File(testPath.toString()).getAbsoluteFile();
    dataTestDir.deleteOnExit();

    createSubDirAndSystemProperty(
      "hadoop.log.dir",
      testPath, "hadoop-log-dir");

    // This is defaulted in core-default.xml to /tmp/hadoop-${user.name}, but
    //  we want our own value to ensure uniqueness on the same machine
    createSubDirAndSystemProperty(
      "hadoop.tmp.dir",
      testPath, "hadoop-tmp-dir");

    // Read and modified in org.apache.hadoop.mapred.MiniMRCluster
    createSubDir(
      "mapred.local.dir",
      testPath, "mapred-local-dir");

    createSubDirAndSystemProperty(
      "mapred.working.dir",
      testPath, "mapred-working-dir");

    createSubDir(
      "hbase.local.dir",
      testPath, "hbase-local-dir");
  }

  private void createSubDir(String propertyName, Path parent, String subDirName){
    Path newPath= new Path(parent, subDirName);
    File newDir = new File(newPath.toString()).getAbsoluteFile();
    newDir.deleteOnExit();
    conf.set(propertyName, newDir.getAbsolutePath());
  }

  private void createSubDirAndSystemProperty(
    String propertyName, Path parent, String subDirName){

    String sysValue = System.getProperty(propertyName);

    if (sysValue != null) {
      // There is already a value set. So we do nothing but hope
      //  that there will be no conflicts
      LOG.info("System.getProperty(\""+propertyName+"\") already set to: "+
        sysValue + " so I do NOT create it in "+dataTestDir.getAbsolutePath());
      String confValue = conf.get(propertyName);
      if (confValue != null && !confValue.endsWith(sysValue)){
       LOG.warn(
         propertyName + " property value differs in configuration and system: "+
         "Configuration="+confValue+" while System="+sysValue+
         " Erasing configuration value by system value."
       );
      }
      conf.set(propertyName, sysValue);
    } else {
      // Ok, it's not set, so we create it as a subdirectory
      createSubDir(propertyName, parent, subDirName);
      System.setProperty(propertyName, conf.get(propertyName));
    }
  }

  /**
   * Creates a directory for the DFS cluster, under the test data
   */
  private void setupClusterTestDir() {
    if (clusterTestDir != null) {
      LOG.warn("Cluster test dir already setup in " +
        clusterTestDir.getAbsolutePath());
      return;
    }

    // Using randomUUID ensures that multiple clusters can be launched by
    //  a same test, if it stops & starts them
    Path testDir = getDataTestDir("dfscluster_" + UUID.randomUUID().toString());
    clusterTestDir = new File(testDir.toString()).getAbsoluteFile();
    // Have it cleaned up on exit
    clusterTestDir.deleteOnExit();
  }

  /**
   * @throws IOException If a cluster -- zk, dfs, or hbase -- already running.
   */
  public void isRunningCluster() throws IOException {
    if (dfsCluster == null) return;
    throw new IOException("Cluster already running at " +
      this.clusterTestDir);
  }

  /**
   * Start a minidfscluster.
   * @param servers How many DNs to start.
   * @throws Exception
   * @see {@link #shutdownMiniDFSCluster()}
   * @return The mini dfs cluster created.
   */
  public MiniDFSCluster startMiniDFSCluster(int servers) throws Exception {
    return startMiniDFSCluster(servers, null);
  }

  /**
   * Start a minidfscluster.
   * This is useful if you want to run datanode on distinct hosts for things
   * like HDFS block location verification.
   * If you start MiniDFSCluster without host names, all instances of the
   * datanodes will have the same host name.
   * @param hosts hostnames DNs to run on.
   * @throws Exception
   * @see {@link #shutdownMiniDFSCluster()}
   * @return The mini dfs cluster created.
   */
  public MiniDFSCluster startMiniDFSCluster(final String hosts[])
    throws Exception {
    if ( hosts != null && hosts.length != 0) {
      return startMiniDFSCluster(hosts.length, hosts);
    } else {
      return startMiniDFSCluster(1, null);
    }
  }

  /**
   * Start a minidfscluster.
   * Can only create one.
   * @param servers How many DNs to start.
   * @param hosts hostnames DNs to run on.
   * @throws Exception
   * @see {@link #shutdownMiniDFSCluster()}
   * @return The mini dfs cluster created.
   */
  public MiniDFSCluster startMiniDFSCluster(int servers, final String hosts[])
  throws Exception {

    // Check that there is not already a cluster running
    isRunningCluster();

    // Initialize the local directory used by the MiniDFS
    if (clusterTestDir == null) {
      setupClusterTestDir();
    }

    // We have to set this property as it is used by MiniCluster
    System.setProperty(TEST_DIRECTORY_KEY, this.clusterTestDir.toString());

    // Some tests also do this:
    //  System.getProperty("test.cache.data", "build/test/cache");
    // It's also deprecated
    System.setProperty("test.cache.data", this.clusterTestDir.toString());

    // Ok, now we can start
    this.dfsCluster = new MiniDFSCluster(0, this.conf, servers, true, true,
      true, null, null, hosts, null);

    // Set this just-started cluster as our filesystem.
    FileSystem fs = this.dfsCluster.getFileSystem();
    this.conf.set("fs.defaultFS", fs.getUri().toString());
    // Do old style too just to be safe.
    this.conf.set("fs.default.name", fs.getUri().toString());

    // Wait for the cluster to be totally up
    this.dfsCluster.waitClusterUp();

    return this.dfsCluster;
  }

  /**
   * Shuts down instance created by call to {@link #startMiniDFSCluster(int)}
   * or does nothing.
   * @throws Exception
   */
  public void shutdownMiniDFSCluster() throws Exception {
    if (this.dfsCluster != null) {
      // The below throws an exception per dn, AsynchronousCloseException.
      this.dfsCluster.shutdown();
      dfsCluster = null;
    }

  }

  /**
   * Call this if you only want a zk cluster.
   * @see #startMiniZKCluster() if you want zk + dfs + hbase mini cluster.
   * @throws Exception
   * @see #shutdownMiniZKCluster()
   * @return zk cluster started.
   */
  public MiniZooKeeperCluster startMiniZKCluster() throws Exception {
    return startMiniZKCluster(1);
  }

  /**
   * Call this if you only want a zk cluster.
   * @param zooKeeperServerNum
   * @see #startMiniZKCluster() if you want zk + dfs + hbase mini cluster.
   * @throws Exception
   * @see #shutdownMiniZKCluster()
   * @return zk cluster started.
   */
  public MiniZooKeeperCluster startMiniZKCluster(int zooKeeperServerNum)
      throws Exception {
    File zkClusterFile = new File(getClusterTestDir().toString());
    return startMiniZKCluster(zkClusterFile, zooKeeperServerNum);
  }

  private MiniZooKeeperCluster startMiniZKCluster(final File dir)
    throws Exception {
    return startMiniZKCluster(dir,1);
  }

  private MiniZooKeeperCluster startMiniZKCluster(final File dir,
      int zooKeeperServerNum)
  throws Exception {
    if (this.zkCluster != null) {
      throw new IOException("Cluster already running at " + dir);
    }
    this.passedZkCluster = false;
    this.zkCluster = new MiniZooKeeperCluster(this.getConfiguration());
    int clientPort =   this.zkCluster.startup(dir,zooKeeperServerNum);
    this.conf.set(HConstants.ZOOKEEPER_CLIENT_PORT,
      Integer.toString(clientPort));
    return this.zkCluster;
  }

  /**
   * Shuts down zk cluster created by call to {@link #startMiniZKCluster(File)}
   * or does nothing.
   * @throws IOException
   * @see #startMiniZKCluster()
   */
  public void shutdownMiniZKCluster() throws IOException {
    if (this.zkCluster != null) {
      this.zkCluster.shutdown();
      this.zkCluster = null;
    }
  }

  /**
   * Start up a minicluster of hbase, dfs, and zookeeper.
   * @throws Exception
   * @return Mini hbase cluster instance created.
   * @see {@link #shutdownMiniDFSCluster()}
   */
  public MiniHBaseCluster startMiniCluster() throws Exception {
    return startMiniCluster(1, 1);
  }

  /**
   * Start up a minicluster of hbase, optionally dfs, and zookeeper.
   * Modifies Configuration.  Homes the cluster data directory under a random
   * subdirectory in a directory under System property test.build.data.
   * Directory is cleaned up on exit.
   * @param numSlaves Number of slaves to start up.  We'll start this many
   * datanodes and regionservers.  If numSlaves is > 1, then make sure
   * hbase.regionserver.info.port is -1 (i.e. no ui per regionserver) otherwise
   * bind errors.
   * @throws Exception
   * @see {@link #shutdownMiniCluster()}
   * @return Mini hbase cluster instance created.
   */
  public MiniHBaseCluster startMiniCluster(final int numSlaves)
  throws Exception {
    return startMiniCluster(1, numSlaves);
  }


  /**
   * start minicluster
   * @throws Exception
   * @see {@link #shutdownMiniCluster()}
   * @return Mini hbase cluster instance created.
   */
  public MiniHBaseCluster startMiniCluster(final int numMasters,
    final int numSlaves)
  throws Exception {
    return startMiniCluster(numMasters, numSlaves, null);
  }


  /**
   * Start up a minicluster of hbase, optionally dfs, and zookeeper.
   * Modifies Configuration.  Homes the cluster data directory under a random
   * subdirectory in a directory under System property test.build.data.
   * Directory is cleaned up on exit.
   * @param numMasters Number of masters to start up.  We'll start this many
   * hbase masters.  If numMasters > 1, you can find the active/primary master
   * with {@link MiniHBaseCluster#getMaster()}.
   * @param numSlaves Number of slaves to start up.  We'll start this many
   * regionservers. If dataNodeHosts == null, this also indicates the number of
   * datanodes to start. If dataNodeHosts != null, the number of datanodes is
   * based on dataNodeHosts.length.
   * If numSlaves is > 1, then make sure
   * hbase.regionserver.info.port is -1 (i.e. no ui per regionserver) otherwise
   * bind errors.
   * @param dataNodeHosts hostnames DNs to run on.
   * This is useful if you want to run datanode on distinct hosts for things
   * like HDFS block location verification.
   * If you start MiniDFSCluster without host names,
   * all instances of the datanodes will have the same host name.
   * @throws Exception
   * @see {@link #shutdownMiniCluster()}
   * @return Mini hbase cluster instance created.
   */
  public MiniHBaseCluster startMiniCluster(final int numMasters,
    final int numSlaves, final String[] dataNodeHosts)
  throws Exception {
    int numDataNodes = numSlaves;
    if ( dataNodeHosts != null && dataNodeHosts.length != 0) {
      numDataNodes = dataNodeHosts.length;
    }

    LOG.info("Starting up minicluster with " + numMasters + " master(s) and " +
        numSlaves + " regionserver(s) and " + numDataNodes + " datanode(s)");

    // If we already put up a cluster, fail.
    isRunningCluster();

    // Bring up mini dfs cluster. This spews a bunch of warnings about missing
    // scheme. Complaints are 'Scheme is undefined for build/test/data/dfs/name1'.
    startMiniDFSCluster(numDataNodes, dataNodeHosts);

    // Start up a zk cluster.
    if (this.zkCluster == null) {
      startMiniZKCluster(clusterTestDir);
    }

    // Start the MiniHBaseCluster
    return startMiniHBaseCluster(numMasters, numSlaves);
  }

  /**
   * Starts up mini hbase cluster.  Usually used after call to
   * {@link #startMiniCluster(int, int)} when doing stepped startup of clusters.
   * Usually you won't want this.  You'll usually want {@link #startMiniCluster()}.
   * @param numMasters
   * @param numSlaves
   * @return Reference to the hbase mini hbase cluster.
   * @throws IOException
   * @throws InterruptedException
   * @see {@link #startMiniCluster()}
   */
  public MiniHBaseCluster startMiniHBaseCluster(final int numMasters,
      final int numSlaves)
  throws IOException, InterruptedException {
    // Now do the mini hbase cluster.  Set the hbase.rootdir in config.
    createRootDir();

    // These settings will make the server waits until this exact number of
    // regions servers are connected.
    if (conf.getInt(ServerManager.WAIT_ON_REGIONSERVERS_MINTOSTART, -1) == -1) {
      conf.setInt(ServerManager.WAIT_ON_REGIONSERVERS_MINTOSTART, numSlaves);
    }
    if (conf.getInt(ServerManager.WAIT_ON_REGIONSERVERS_MAXTOSTART, -1) == -1) {
      conf.setInt(ServerManager.WAIT_ON_REGIONSERVERS_MAXTOSTART, numSlaves);
    }

    Configuration c = new Configuration(this.conf);
    this.hbaseCluster = new MiniHBaseCluster(c, numMasters, numSlaves);
    // Don't leave here till we've done a successful scan of the .META.
    HTable t = new HTable(c, HConstants.META_TABLE_NAME);
    ResultScanner s = t.getScanner(new Scan());
    while (s.next() != null) {
      continue;
    }
    s.close();
    t.close();

    getHBaseAdmin(); // create immediately the hbaseAdmin
    LOG.info("Minicluster is up");
    return (MiniHBaseCluster)this.hbaseCluster;
  }

  /**
   * Starts the hbase cluster up again after shutting it down previously in a
   * test.  Use this if you want to keep dfs/zk up and just stop/start hbase.
   * @param servers number of region servers
   * @throws IOException
   */
  public void restartHBaseCluster(int servers) throws IOException, InterruptedException {
    this.hbaseCluster = new MiniHBaseCluster(this.conf, servers);
    // Don't leave here till we've done a successful scan of the .META.
    HTable t = new HTable(new Configuration(this.conf), HConstants.META_TABLE_NAME);
    ResultScanner s = t.getScanner(new Scan());
    while (s.next() != null) {
      // do nothing
    }
    LOG.info("HBase has been restarted");
    s.close();
    t.close();
  }

  /**
   * @return Current mini hbase cluster. Only has something in it after a call
   * to {@link #startMiniCluster()}.
   * @see #startMiniCluster()
   */
  public MiniHBaseCluster getMiniHBaseCluster() {
    if (this.hbaseCluster instanceof MiniHBaseCluster) {
      return (MiniHBaseCluster)this.hbaseCluster;
    }
    throw new RuntimeException(hbaseCluster + " not an instance of " +
                               MiniHBaseCluster.class.getName());
  }

  /**
   * Stops mini hbase, zk, and hdfs clusters.
   * @throws IOException
   * @see {@link #startMiniCluster(int)}
   */
  public void shutdownMiniCluster() throws Exception {
    LOG.info("Shutting down minicluster");
    shutdownMiniHBaseCluster();
    if (!this.passedZkCluster){
      shutdownMiniZKCluster();
    }
    shutdownMiniDFSCluster();

    // Clean up our directory.
    if (this.clusterTestDir != null && this.clusterTestDir.exists()) {
      // Need to use deleteDirectory because File.delete required dir is empty.
      if (!FSUtils.deleteDirectory(FileSystem.getLocal(this.conf),
          new Path(this.clusterTestDir.toString()))) {
        LOG.warn("Failed delete of " + this.clusterTestDir.toString());
      }
      this.clusterTestDir = null;
    }
    LOG.info("Minicluster is down");
  }

  /**
   * Shutdown HBase mini cluster.  Does not shutdown zk or dfs if running.
   * @throws IOException
   */
  public void shutdownMiniHBaseCluster() throws IOException {
    if (hbaseAdmin != null) {
      hbaseAdmin.close();
      hbaseAdmin = null;
    }
    // unset the configuration for MIN and MAX RS to start
    conf.setInt(ServerManager.WAIT_ON_REGIONSERVERS_MINTOSTART, -1);
    conf.setInt(ServerManager.WAIT_ON_REGIONSERVERS_MAXTOSTART, -1);
    if (this.hbaseCluster != null) {
      this.hbaseCluster.shutdown();
      // Wait till hbase is down before going on to shutdown zk.
      this.hbaseCluster.waitUntilShutDown();
      this.hbaseCluster = null;
    }
  }

  /**
   * Returns the path to the default root dir the minicluster uses.
   * Note: this does not cause the root dir to be created.
   * @return Fully qualified path for the default hbase root dir
   * @throws IOException
   */
  public Path getDefaultRootDirPath() throws IOException {
	FileSystem fs = FileSystem.get(this.conf);
	return new Path(fs.makeQualified(fs.getHomeDirectory()),"hbase");
  }

  /**
   * Creates an hbase rootdir in user home directory.  Also creates hbase
   * version file.  Normally you won't make use of this method.  Root hbasedir
   * is created for you as part of mini cluster startup.  You'd only use this
   * method if you were doing manual operation.
   * @return Fully qualified path to hbase root dir
   * @throws IOException
   */
  public Path createRootDir() throws IOException {
    FileSystem fs = FileSystem.get(this.conf);
    Path hbaseRootdir = getDefaultRootDirPath();
    this.conf.set(HConstants.HBASE_DIR, hbaseRootdir.toString());
    fs.mkdirs(hbaseRootdir);
    FSUtils.setVersion(fs, hbaseRootdir);
    return hbaseRootdir;
  }

  /**
   * Flushes all caches in the mini hbase cluster
   * @throws IOException
   */
  public void flush() throws IOException {
    getMiniHBaseCluster().flushcache();
  }

  /**
   * Flushes all caches in the mini hbase cluster
   * @throws IOException
   */
  public void flush(byte [] tableName) throws IOException {
    getMiniHBaseCluster().flushcache(tableName);
  }

  /**
   * Compact all regions in the mini hbase cluster
   * @throws IOException
   */
  public void compact(boolean major) throws IOException {
    getMiniHBaseCluster().compact(major);
  }

  /**
   * Compact all of a table's reagion in the mini hbase cluster
   * @throws IOException
   */
  public void compact(byte [] tableName, boolean major) throws IOException {
    getMiniHBaseCluster().compact(tableName, major);
  }


  /**
   * Create a table.
   * @param tableName
   * @param family
   * @return An HTable instance for the created table.
   * @throws IOException
   */
  public HTable createTable(byte[] tableName, byte[] family)
  throws IOException{
    return createTable(tableName, new byte[][]{family});
  }

  /**
   * Create a table.
   * @param tableName
   * @param families
   * @return An HTable instance for the created table.
   * @throws IOException
   */
  public HTable createTable(byte[] tableName, byte[][] families)
  throws IOException {
    return createTable(tableName, families,
        new Configuration(getConfiguration()));
  }

  public HTable createTable(byte[] tableName, byte[][] families,
      int numVersions, byte[] startKey, byte[] endKey, int numRegions)
  throws IOException{
    HTableDescriptor desc = new HTableDescriptor(tableName);
    for (byte[] family : families) {
      HColumnDescriptor hcd = new HColumnDescriptor(family)
          .setMaxVersions(numVersions);
      desc.addFamily(hcd);
    }
    getHBaseAdmin().createTable(desc, startKey, endKey, numRegions);
    return new HTable(getConfiguration(), tableName);
  }

  /**
   * Create a table.
   * @param tableName
   * @param families
   * @param c Configuration to use
   * @return An HTable instance for the created table.
   * @throws IOException
   */
  public HTable createTable(byte[] tableName, byte[][] families,
      final Configuration c)
  throws IOException {
    HTableDescriptor desc = new HTableDescriptor(tableName);
    for(byte[] family : families) {
      desc.addFamily(new HColumnDescriptor(family));
    }
    getHBaseAdmin().createTable(desc);
    return new HTable(c, tableName);
  }

  /**
   * Create a table.
   * @param tableName
   * @param families
   * @param c Configuration to use
   * @param numVersions
   * @return An HTable instance for the created table.
   * @throws IOException
   */
  public HTable createTable(byte[] tableName, byte[][] families,
      final Configuration c, int numVersions)
  throws IOException {
    HTableDescriptor desc = new HTableDescriptor(tableName);
    for(byte[] family : families) {
      HColumnDescriptor hcd = new HColumnDescriptor(family)
          .setMaxVersions(numVersions);
      desc.addFamily(hcd);
    }
    getHBaseAdmin().createTable(desc);
    return new HTable(c, tableName);
  }

  /**
   * Create a table.
   * @param tableName
   * @param family
   * @param numVersions
   * @return An HTable instance for the created table.
   * @throws IOException
   */
  public HTable createTable(byte[] tableName, byte[] family, int numVersions)
  throws IOException {
    return createTable(tableName, new byte[][]{family}, numVersions);
  }

  /**
   * Create a table.
   * @param tableName
   * @param families
   * @param numVersions
   * @return An HTable instance for the created table.
   * @throws IOException
   */
  public HTable createTable(byte[] tableName, byte[][] families,
      int numVersions)
  throws IOException {
    HTableDescriptor desc = new HTableDescriptor(tableName);
    for (byte[] family : families) {
      HColumnDescriptor hcd = new HColumnDescriptor(family)
          .setMaxVersions(numVersions);
      desc.addFamily(hcd);
    }
    getHBaseAdmin().createTable(desc);
    return new HTable(new Configuration(getConfiguration()), tableName);
  }

  /**
   * Create a table.
   * @param tableName
   * @param families
   * @param numVersions
   * @return An HTable instance for the created table.
   * @throws IOException
   */
  public HTable createTable(byte[] tableName, byte[][] families,
    int numVersions, int blockSize) throws IOException {
    HTableDescriptor desc = new HTableDescriptor(tableName);
    for (byte[] family : families) {
      HColumnDescriptor hcd = new HColumnDescriptor(family)
          .setMaxVersions(numVersions)
          .setBlocksize(blockSize);
      desc.addFamily(hcd);
    }
    getHBaseAdmin().createTable(desc);
    return new HTable(new Configuration(getConfiguration()), tableName);
  }

  /**
   * Create a table.
   * @param tableName
   * @param families
   * @param numVersions
   * @return An HTable instance for the created table.
   * @throws IOException
   */
  public HTable createTable(byte[] tableName, byte[][] families,
      int[] numVersions)
  throws IOException {
    HTableDescriptor desc = new HTableDescriptor(tableName);
    int i = 0;
    for (byte[] family : families) {
      HColumnDescriptor hcd = new HColumnDescriptor(family)
          .setMaxVersions(numVersions[i]);
      desc.addFamily(hcd);
      i++;
    }
    getHBaseAdmin().createTable(desc);
    return new HTable(new Configuration(getConfiguration()), tableName);
  }

  /**
   * Drop an existing table
   * @param tableName existing table
   */
  public void deleteTable(byte[] tableName) throws IOException {
    try {
      getHBaseAdmin().disableTable(tableName);
    } catch (TableNotEnabledException e) {
      LOG.debug("Table: " + Bytes.toString(tableName) + " already disabled, so just deleting it.");
    }
    getHBaseAdmin().deleteTable(tableName);
  }

  /**
   * Provide an existing table name to truncate
   * @param tableName existing table
   * @return HTable to that new table
   * @throws IOException
   */
  public HTable truncateTable(byte [] tableName) throws IOException {
    HTable table = new HTable(getConfiguration(), tableName);
    Scan scan = new Scan();
    ResultScanner resScan = table.getScanner(scan);
    for(Result res : resScan) {
      Delete del = new Delete(res.getRow());
      table.delete(del);
    }
    resScan = table.getScanner(scan);
    resScan.close();
    return table;
  }

  /**
   * Load table with rows from 'aaa' to 'zzz'.
   * @param t Table
   * @param f Family
   * @return Count of rows loaded.
   * @throws IOException
   */
  public int loadTable(final HTable t, final byte[] f) throws IOException {
    t.setAutoFlush(false);
    byte[] k = new byte[3];
    int rowCount = 0;
    for (byte b1 = 'a'; b1 <= 'z'; b1++) {
      for (byte b2 = 'a'; b2 <= 'z'; b2++) {
        for (byte b3 = 'a'; b3 <= 'z'; b3++) {
          k[0] = b1;
          k[1] = b2;
          k[2] = b3;
          Put put = new Put(k);
          put.add(f, null, k);
          t.put(put);
          rowCount++;
        }
      }
    }
    t.flushCommits();
    return rowCount;
  }

  /**
   * Load table of multiple column families with rows from 'aaa' to 'zzz'.
   * @param t Table
   * @param f Array of Families to load
   * @return Count of rows loaded.
   * @throws IOException
   */
  public int loadTable(final HTable t, final byte[][] f) throws IOException {
    t.setAutoFlush(false);
    byte[] k = new byte[3];
    int rowCount = 0;
    for (byte b1 = 'a'; b1 <= 'z'; b1++) {
      for (byte b2 = 'a'; b2 <= 'z'; b2++) {
        for (byte b3 = 'a'; b3 <= 'z'; b3++) {
          k[0] = b1;
          k[1] = b2;
          k[2] = b3;
          Put put = new Put(k);
          for (int i = 0; i < f.length; i++) {
            put.add(f[i], null, k);
          }
          t.put(put);
          rowCount++;
        }
      }
    }
    t.flushCommits();
    return rowCount;
  }

  /**
   * Load region with rows from 'aaa' to 'zzz'.
   * @param r Region
   * @param f Family
   * @return Count of rows loaded.
   * @throws IOException
   */
  public int loadRegion(final HRegion r, final byte[] f)
  throws IOException {
    return loadRegion(r, f, false);
  }
  
  /**
   * Load region with rows from 'aaa' to 'zzz'.
   * @param r Region
   * @param f Family
   * @param flush flush the cache if true
   * @return Count of rows loaded.
   * @throws IOException
   */
  public int loadRegion(final HRegion r, final byte[] f, final boolean flush)
      throws IOException {
    byte[] k = new byte[3];
    int rowCount = 0;
    for (byte b1 = 'a'; b1 <= 'z'; b1++) {
      for (byte b2 = 'a'; b2 <= 'z'; b2++) {
        for (byte b3 = 'a'; b3 <= 'z'; b3++) {
          k[0] = b1;
          k[1] = b2;
          k[2] = b3;
          Put put = new Put(k);
          put.add(f, null, k);
          if (r.getLog() == null) put.setWriteToWAL(false);
          r.put(put);
          rowCount++;
        }
      }
      if (flush) {
        r.flushcache();
      }
    }
    return rowCount;
  }

  /**
   * Return the number of rows in the given table.
   */
  public int countRows(final HTable table) throws IOException {
    Scan scan = new Scan();
    ResultScanner results = table.getScanner(scan);
    int count = 0;
    for (@SuppressWarnings("unused") Result res : results) {
      count++;
    }
    results.close();
    return count;
  }

  public int countRows(final HTable table, final byte[]... families) throws IOException {
    Scan scan = new Scan();
    for (byte[] family: families) {
      scan.addFamily(family);
    }
    ResultScanner results = table.getScanner(scan);
    int count = 0;
    for (@SuppressWarnings("unused") Result res : results) {
      count++;
    }
    results.close();
    return count;
  }

  /**
   * Return an md5 digest of the entire contents of a table.
   */
  public String checksumRows(final HTable table) throws Exception {
    Scan scan = new Scan();
    ResultScanner results = table.getScanner(scan);
    MessageDigest digest = MessageDigest.getInstance("MD5");
    for (Result res : results) {
      digest.update(res.getRow());
    }
    results.close();
    return digest.toString();
  }

  /**
   * Creates many regions names "aaa" to "zzz".
   *
   * @param table  The table to use for the data.
   * @param columnFamily  The family to insert the data into.
   * @return count of regions created.
   * @throws IOException When creating the regions fails.
   */
  public int createMultiRegions(HTable table, byte[] columnFamily)
  throws IOException {
    return createMultiRegions(table, columnFamily, true);
  }

  public static final byte[][] KEYS = {
    HConstants.EMPTY_BYTE_ARRAY, Bytes.toBytes("bbb"),
    Bytes.toBytes("ccc"), Bytes.toBytes("ddd"), Bytes.toBytes("eee"),
    Bytes.toBytes("fff"), Bytes.toBytes("ggg"), Bytes.toBytes("hhh"),
    Bytes.toBytes("iii"), Bytes.toBytes("jjj"), Bytes.toBytes("kkk"),
    Bytes.toBytes("lll"), Bytes.toBytes("mmm"), Bytes.toBytes("nnn"),
    Bytes.toBytes("ooo"), Bytes.toBytes("ppp"), Bytes.toBytes("qqq"),
    Bytes.toBytes("rrr"), Bytes.toBytes("sss"), Bytes.toBytes("ttt"),
    Bytes.toBytes("uuu"), Bytes.toBytes("vvv"), Bytes.toBytes("www"),
    Bytes.toBytes("xxx"), Bytes.toBytes("yyy")
  };

  public static final byte[][] KEYS_FOR_HBA_CREATE_TABLE = {
      Bytes.toBytes("bbb"),
      Bytes.toBytes("ccc"), Bytes.toBytes("ddd"), Bytes.toBytes("eee"),
      Bytes.toBytes("fff"), Bytes.toBytes("ggg"), Bytes.toBytes("hhh"),
      Bytes.toBytes("iii"), Bytes.toBytes("jjj"), Bytes.toBytes("kkk"),
      Bytes.toBytes("lll"), Bytes.toBytes("mmm"), Bytes.toBytes("nnn"),
      Bytes.toBytes("ooo"), Bytes.toBytes("ppp"), Bytes.toBytes("qqq"),
      Bytes.toBytes("rrr"), Bytes.toBytes("sss"), Bytes.toBytes("ttt"),
      Bytes.toBytes("uuu"), Bytes.toBytes("vvv"), Bytes.toBytes("www"),
      Bytes.toBytes("xxx"), Bytes.toBytes("yyy"), Bytes.toBytes("zzz")
  };


  /**
   * Creates many regions names "aaa" to "zzz".
   *
   * @param table  The table to use for the data.
   * @param columnFamily  The family to insert the data into.
   * @param cleanupFS  True if a previous region should be remove from the FS  
   * @return count of regions created.
   * @throws IOException When creating the regions fails.
   */
  public int createMultiRegions(HTable table, byte[] columnFamily, boolean cleanupFS)
  throws IOException {
    return createMultiRegions(getConfiguration(), table, columnFamily, KEYS, cleanupFS);
  }

  /**
   * Creates the specified number of regions in the specified table.
   * @param c
   * @param table
   * @param family
   * @param numRegions
   * @return
   * @throws IOException
   */
  public int createMultiRegions(final Configuration c, final HTable table,
      final byte [] family, int numRegions)
  throws IOException {
    if (numRegions < 3) throw new IOException("Must create at least 3 regions");
    byte [] startKey = Bytes.toBytes("aaaaa");
    byte [] endKey = Bytes.toBytes("zzzzz");
    byte [][] splitKeys = Bytes.split(startKey, endKey, numRegions - 3);
    byte [][] regionStartKeys = new byte[splitKeys.length+1][];
    for (int i=0;i<splitKeys.length;i++) {
      regionStartKeys[i+1] = splitKeys[i];
    }
    regionStartKeys[0] = HConstants.EMPTY_BYTE_ARRAY;
    return createMultiRegions(c, table, family, regionStartKeys);
  }

  public int createMultiRegions(final Configuration c, final HTable table,
      final byte[] columnFamily, byte [][] startKeys) throws IOException {
    return createMultiRegions(c, table, columnFamily, startKeys, true);
  }
  
  public int createMultiRegions(final Configuration c, final HTable table,
          final byte[] columnFamily, byte [][] startKeys, boolean cleanupFS)
  throws IOException {
    Arrays.sort(startKeys, Bytes.BYTES_COMPARATOR);
    HTable meta = new HTable(c, HConstants.META_TABLE_NAME);
    HTableDescriptor htd = table.getTableDescriptor();
    if(!htd.hasFamily(columnFamily)) {
      HColumnDescriptor hcd = new HColumnDescriptor(columnFamily);
      htd.addFamily(hcd);
    }
    // remove empty region - this is tricky as the mini cluster during the test
    // setup already has the "<tablename>,,123456789" row with an empty start
    // and end key. Adding the custom regions below adds those blindly,
    // including the new start region from empty to "bbb". lg
    List<byte[]> rows = getMetaTableRows(htd.getName());
    String regionToDeleteInFS = table
        .getRegionsInRange(Bytes.toBytes(""), Bytes.toBytes("")).get(0)
        .getRegionInfo().getEncodedName();
    List<HRegionInfo> newRegions = new ArrayList<HRegionInfo>(startKeys.length);
    // add custom ones
    int count = 0;
    for (int i = 0; i < startKeys.length; i++) {
      int j = (i + 1) % startKeys.length;
      HRegionInfo hri = new HRegionInfo(table.getTableName(),
        startKeys[i], startKeys[j]);
      Put put = new Put(hri.getRegionName());
      put.add(HConstants.CATALOG_FAMILY, HConstants.REGIONINFO_QUALIFIER,
        Writables.getBytes(hri));
      meta.put(put);
      LOG.info("createMultiRegions: inserted " + hri.toString());
      newRegions.add(hri);
      count++;
    }
    // see comment above, remove "old" (or previous) single region
    for (byte[] row : rows) {
      LOG.info("createMultiRegions: deleting meta row -> " +
        Bytes.toStringBinary(row));
      meta.delete(new Delete(row));
    }
    if (cleanupFS) {
      // see HBASE-7417 - this confused TestReplication
      // remove the "old" region from FS
      Path tableDir = new Path(getDefaultRootDirPath().toString()
          + System.getProperty("file.separator") + htd.getNameAsString()
          + System.getProperty("file.separator") + regionToDeleteInFS);
      getDFSCluster().getFileSystem().delete(tableDir);
    }
    // flush cache of regions
    HConnection conn = table.getConnection();
    conn.clearRegionCache();
    // assign all the new regions IF table is enabled.
    HBaseAdmin admin = getHBaseAdmin();
    if (admin.isTableEnabled(table.getTableName())) {
      for(HRegionInfo hri : newRegions) {
        admin.assign(hri.getRegionName());
      }
    }

    meta.close();

    return count;
  }

  /**
   * Create rows in META for regions of the specified table with the specified
   * start keys.  The first startKey should be a 0 length byte array if you
   * want to form a proper range of regions.
   * @param conf
   * @param htd
   * @param startKeys
   * @return list of region info for regions added to meta
   * @throws IOException
   */
  public List<HRegionInfo> createMultiRegionsInMeta(final Configuration conf,
      final HTableDescriptor htd, byte [][] startKeys)
  throws IOException {
    HTable meta = new HTable(conf, HConstants.META_TABLE_NAME);
    Arrays.sort(startKeys, Bytes.BYTES_COMPARATOR);
    List<HRegionInfo> newRegions = new ArrayList<HRegionInfo>(startKeys.length);
    // add custom ones
    for (int i = 0; i < startKeys.length; i++) {
      int j = (i + 1) % startKeys.length;
      HRegionInfo hri = new HRegionInfo(htd.getName(), startKeys[i],
          startKeys[j]);
      Put put = new Put(hri.getRegionName());
      put.add(HConstants.CATALOG_FAMILY, HConstants.REGIONINFO_QUALIFIER,
        Writables.getBytes(hri));
      meta.put(put);
      LOG.info("createMultiRegionsInMeta: inserted " + hri.toString());
      newRegions.add(hri);
    }

    meta.close();
    return newRegions;
  }

  /**
   * Returns all rows from the .META. table.
   *
   * @throws IOException When reading the rows fails.
   */
  public List<byte[]> getMetaTableRows() throws IOException {
    // TODO: Redo using MetaReader class
    HTable t = new HTable(new Configuration(this.conf), HConstants.META_TABLE_NAME);
    List<byte[]> rows = new ArrayList<byte[]>();
    ResultScanner s = t.getScanner(new Scan());
    for (Result result : s) {
      LOG.info("getMetaTableRows: row -> " +
        Bytes.toStringBinary(result.getRow()));
      rows.add(result.getRow());
    }
    s.close();
    t.close();
    return rows;
  }

  /**
   * Returns all rows from the .META. table for a given user table
   *
   * @throws IOException When reading the rows fails.
   */
  public List<byte[]> getMetaTableRows(byte[] tableName) throws IOException {
    // TODO: Redo using MetaReader.
    HTable t = new HTable(new Configuration(this.conf), HConstants.META_TABLE_NAME);
    List<byte[]> rows = new ArrayList<byte[]>();
    ResultScanner s = t.getScanner(new Scan());
    for (Result result : s) {
      byte[] val = result.getValue(HConstants.CATALOG_FAMILY, HConstants.REGIONINFO_QUALIFIER);
      if (val == null) {
        LOG.error("No region info for row " + Bytes.toString(result.getRow()));
        // TODO figure out what to do for this new hosed case.
        continue;
      }
      HRegionInfo info = Writables.getHRegionInfo(val);
      if (Bytes.compareTo(info.getTableName(), tableName) == 0) {
        LOG.info("getMetaTableRows: row -> " +
            Bytes.toStringBinary(result.getRow()) + info);
        rows.add(result.getRow());
      }
    }
    s.close();
    t.close();
    return rows;
  }

  /**
   * Tool to get the reference to the region server object that holds the
   * region of the specified user table.
   * It first searches for the meta rows that contain the region of the
   * specified table, then gets the index of that RS, and finally retrieves
   * the RS's reference.
   * @param tableName user table to lookup in .META.
   * @return region server that holds it, null if the row doesn't exist
   * @throws IOException
   */
  public HRegionServer getRSForFirstRegionInTable(byte[] tableName)
      throws IOException {
    List<byte[]> metaRows = getMetaTableRows(tableName);
    if (metaRows == null || metaRows.isEmpty()) {
      return null;
    }
    LOG.debug("Found " + metaRows.size() + " rows for table " +
      Bytes.toString(tableName));
    byte [] firstrow = metaRows.get(0);
    LOG.debug("FirstRow=" + Bytes.toString(firstrow));
    int index = getMiniHBaseCluster().getServerWith(firstrow);
    return getMiniHBaseCluster().getRegionServerThreads().get(index).getRegionServer();
  }

  /**
   * Starts a <code>MiniMRCluster</code> with a default number of
   * <code>TaskTracker</code>'s.
   *
   * @throws IOException When starting the cluster fails.
   */
  public void startMiniMapReduceCluster() throws IOException {
    startMiniMapReduceCluster(2);
  }

  /**
   * Starts a <code>MiniMRCluster</code>.
   *
   * @param servers  The number of <code>TaskTracker</code>'s to start.
   * @throws IOException When starting the cluster fails.
   */
  public void startMiniMapReduceCluster(final int servers) throws IOException {
    LOG.info("Starting mini mapreduce cluster...");
    // These are needed for the new and improved Map/Reduce framework
    Configuration c = getConfiguration();
    String logDir = c.get("hadoop.log.dir");
    String tmpDir = c.get("hadoop.tmp.dir");
    if (logDir == null) {
      logDir = tmpDir;
    }
    System.setProperty("hadoop.log.dir", logDir);
    c.set("mapred.output.dir", tmpDir);

    // Tests were failing because this process used 6GB of virtual memory and was getting killed.
    // we up the VM usable so that processes don't get killed.
    conf.setFloat("yarn.nodemanager.vmem-pmem-ratio", 8.0f);

    mrCluster = new MiniMRCluster(servers,
      FileSystem.get(conf).getUri().toString(), 1);
    LOG.info("Mini mapreduce cluster started");
    JobConf mrClusterJobConf = mrCluster.createJobConf();

    // In hadoop2, YARN/MR2 starts a mini cluster with its own conf instance and updates settings.
    // Our HBase MR jobs need several of these settings in order to properly run.  So we copy the
    // necessary config properties here.  YARN-129 required adding a few properties.
    c.set("mapred.job.tracker", mrClusterJobConf.get("mapred.job.tracker"));
    /* this for mrv2 support */
    conf.set("mapreduce.framework.name", "yarn");
    conf.setBoolean("yarn.is.minicluster", true);
    String rmAdress = mrClusterJobConf.get("yarn.resourcemanager.address");
    if (rmAdress != null) {
      conf.set("yarn.resourcemanager.address", rmAdress);
    }
    String schedulerAdress =
      mrClusterJobConf.get("yarn.resourcemanager.scheduler.address");
    if (schedulerAdress != null) {
      conf.set("yarn.resourcemanager.scheduler.address", schedulerAdress);
    }
  }

  /**
   * Stops the previously started <code>MiniMRCluster</code>.
   */
  public void shutdownMiniMapReduceCluster() {
    LOG.info("Stopping mini mapreduce cluster...");
    if (mrCluster != null) {
      mrCluster.shutdown();
      mrCluster = null;
    }
    // Restore configuration to point to local jobtracker
    conf.set("mapred.job.tracker", "local");
    LOG.info("Mini mapreduce cluster stopped");
  }

  /**
   * Switches the logger for the given class to DEBUG level.
   *
   * @param clazz  The class for which to switch to debug logging.
   */
  public void enableDebug(Class<?> clazz) {
    Log l = LogFactory.getLog(clazz);
    if (l instanceof Log4JLogger) {
      ((Log4JLogger) l).getLogger().setLevel(org.apache.log4j.Level.DEBUG);
    } else if (l instanceof Jdk14Logger) {
      ((Jdk14Logger) l).getLogger().setLevel(java.util.logging.Level.ALL);
    }
  }

  /**
   * Expire the Master's session
   * @throws Exception
   */
  public void expireMasterSession() throws Exception {
    HMaster master = getMiniHBaseCluster().getMaster();
    expireSession(master.getZooKeeper(), false);
  }

  /**
   * Expire a region server's session
   * @param index which RS
   * @throws Exception
   */
  public void expireRegionServerSession(int index) throws Exception {
    HRegionServer rs = getMiniHBaseCluster().getRegionServer(index);
    expireSession(rs.getZooKeeper(), false);
    decrementMinRegionServerCount();
  }

  private void decrementMinRegionServerCount() {
    // decrement the count for this.conf, for newly spwaned master
    // this.hbaseCluster shares this configuration too
    decrementMinRegionServerCount(getConfiguration());

    // each master thread keeps a copy of configuration
    for (MasterThread master : getHBaseCluster().getMasterThreads()) {
      decrementMinRegionServerCount(master.getMaster().getConfiguration());
    }
  }

  private void decrementMinRegionServerCount(Configuration conf) {
    int currentCount = conf.getInt(
        ServerManager.WAIT_ON_REGIONSERVERS_MINTOSTART, -1);
    if (currentCount != -1) {
      conf.setInt(ServerManager.WAIT_ON_REGIONSERVERS_MINTOSTART,
          Math.max(currentCount - 1, 1));
    }
  }

   /**
    * Expire a ZooKeeper session as recommended in ZooKeeper documentation
    * http://wiki.apache.org/hadoop/ZooKeeper/FAQ#A4
    * There are issues when doing this:
    * [1] http://www.mail-archive.com/dev@zookeeper.apache.org/msg01942.html
    * [2] https://issues.apache.org/jira/browse/ZOOKEEPER-1105
    *
    * @param nodeZK - the ZK to make expiry
    * @param checkStatus - true to check if the we can create a HTable with the
    *                    current configuration.
    */
  public void expireSession(ZooKeeperWatcher nodeZK, boolean checkStatus)
    throws Exception {
    Configuration c = new Configuration(this.conf);
    String quorumServers = ZKConfig.getZKQuorumServersString(c);
    int sessionTimeout = 500;
    ZooKeeper zk = nodeZK.getRecoverableZooKeeper().getZooKeeper();
    byte[] password = zk.getSessionPasswd();
    long sessionID = zk.getSessionId();

    // Expiry seems to be asynchronous (see comment from P. Hunt in [1]),
    //  so we create a first watcher to be sure that the
    //  event was sent. We expect that if our watcher receives the event
    //  other watchers on the same machine will get is as well.
    // When we ask to close the connection, ZK does not close it before
    //  we receive all the events, so don't have to capture the event, just
    //  closing the connection should be enough.
    ZooKeeper monitor = new ZooKeeper(quorumServers,
      1000, new org.apache.zookeeper.Watcher(){
      @Override
      public void process(WatchedEvent watchedEvent) {
        LOG.info("Monitor ZKW received event="+watchedEvent);
      }
    } , sessionID, password);

    // Making it expire
    ZooKeeper newZK = new ZooKeeper(quorumServers,
        sessionTimeout, EmptyWatcher.instance, sessionID, password);
    newZK.close();
    LOG.info("ZK Closed Session 0x" + Long.toHexString(sessionID));

     // Now closing & waiting to be sure that the clients get it.
     monitor.close();

    if (checkStatus) {
      new HTable(new Configuration(conf), HConstants.META_TABLE_NAME).close();
    }
  }

  /**
   * Get the Mini HBase cluster.
   *
   * @return hbase cluster
   * @see #getHBaseClusterInterface()
   */
  public MiniHBaseCluster getHBaseCluster() {
    return getMiniHBaseCluster();
  }

  /**
   * Returns the HBaseCluster instance.
   * <p>Returned object can be any of the subclasses of HBaseCluster, and the
   * tests referring this should not assume that the cluster is a mini cluster or a
   * distributed one. If the test only works on a mini cluster, then specific
   * method {@link #getMiniHBaseCluster()} can be used instead w/o the
   * need to type-cast.
   */
  public HBaseCluster getHBaseClusterInterface() {
    //implementation note: we should rename this method as #getHBaseCluster(),
    //but this would require refactoring 90+ calls.
    return hbaseCluster;
  }

  /**
   * Returns a HBaseAdmin instance.
   * This instance is shared between HBaseTestingUtility intance users.
   * Don't close it, it will be closed automatically when the
   * cluster shutdowns
   *
   * @return The HBaseAdmin instance.
   * @throws IOException
   */
  public synchronized HBaseAdmin getHBaseAdmin()
  throws IOException {
    if (hbaseAdmin == null){
      hbaseAdmin = new HBaseAdmin(new Configuration(getConfiguration()));
    }
    return hbaseAdmin;
  }
  private HBaseAdmin hbaseAdmin = null;

  /**
   * Closes the named region.
   *
   * @param regionName  The region to close.
   * @throws IOException
   */
  public void closeRegion(String regionName) throws IOException {
    closeRegion(Bytes.toBytes(regionName));
  }

  /**
   * Closes the named region.
   *
   * @param regionName  The region to close.
   * @throws IOException
   */
  public void closeRegion(byte[] regionName) throws IOException {
    getHBaseAdmin().closeRegion(regionName, null);
  }

  /**
   * Closes the region containing the given row.
   *
   * @param row  The row to find the containing region.
   * @param table  The table to find the region.
   * @throws IOException
   */
  public void closeRegionByRow(String row, HTable table) throws IOException {
    closeRegionByRow(Bytes.toBytes(row), table);
  }

  /**
   * Closes the region containing the given row.
   *
   * @param row  The row to find the containing region.
   * @param table  The table to find the region.
   * @throws IOException
   */
  public void closeRegionByRow(byte[] row, HTable table) throws IOException {
    HRegionLocation hrl = table.getRegionLocation(row);
    closeRegion(hrl.getRegionInfo().getRegionName());
  }

  public MiniZooKeeperCluster getZkCluster() {
    return zkCluster;
  }

  public void setZkCluster(MiniZooKeeperCluster zkCluster) {
    this.passedZkCluster = true;
    this.zkCluster = zkCluster;
    conf.setInt(HConstants.ZOOKEEPER_CLIENT_PORT, zkCluster.getClientPort());
  }

  public MiniDFSCluster getDFSCluster() {
    return dfsCluster;
  }

  public void setDFSCluster(MiniDFSCluster cluster) throws IOException {
    if (dfsCluster != null && dfsCluster.isClusterUp()) {
      throw new IOException("DFSCluster is already running! Shut it down first.");
    }
    this.dfsCluster = cluster;
  }

  public FileSystem getTestFileSystem() throws IOException {
    return HFileSystem.get(conf);
  }

  /**
   * @return True if we removed the test dir
   * @throws IOException
   */
  public boolean cleanupTestDir() throws IOException {
    if (dataTestDir == null ){
      return false;
    } else {
      boolean ret = deleteDir(getDataTestDir());
      dataTestDir = null;
      return ret;
    }
  }

  /**
   * @param subdir Test subdir name.
   * @return True if we removed the test dir
   * @throws IOException
   */
  public boolean cleanupTestDir(final String subdir) throws IOException {
    if (dataTestDir == null){
      return false;
    }
    return deleteDir(getDataTestDir(subdir));
  }

  /**
   * @param dir Directory to delete
   * @return True if we deleted it.
   * @throws IOException
   */
  public boolean deleteDir(final Path dir) throws IOException {
    FileSystem fs = getTestFileSystem();
    if (fs.exists(dir)) {
      return fs.delete(getDataTestDir(), true);
    }
    return false;
  }

  public void waitTableAvailable(byte[] table, long timeoutMillis)
  throws InterruptedException, IOException {
    long startWait = System.currentTimeMillis();
    while (!getHBaseAdmin().isTableAvailable(table)) {
      assertTrue("Timed out waiting for table to become available " +
        Bytes.toStringBinary(table),
        System.currentTimeMillis() - startWait < timeoutMillis);
      Thread.sleep(200);
    }
  }

  public void waitTableEnabled(byte[] table, long timeoutMillis)
  throws InterruptedException, IOException {
    long startWait = System.currentTimeMillis();
    while (!getHBaseAdmin().isTableAvailable(table) &&
           !getHBaseAdmin().isTableEnabled(table)) {
      assertTrue("Timed out waiting for table to become available and enabled " +
         Bytes.toStringBinary(table),
         System.currentTimeMillis() - startWait < timeoutMillis);
      Thread.sleep(200);
    }
  }

  /**
   * Make sure that at least the specified number of region servers
   * are running
   * @param num minimum number of region servers that should be running
   * @return true if we started some servers
   * @throws IOException
   */
  public boolean ensureSomeRegionServersAvailable(final int num)
      throws IOException {
    boolean startedServer = false;
    MiniHBaseCluster hbaseCluster = getMiniHBaseCluster();
    for (int i=hbaseCluster.getLiveRegionServerThreads().size(); i<num; ++i) {
      LOG.info("Started new server=" + hbaseCluster.startRegionServer());
      startedServer = true;
    }

    return startedServer;
  }

  /**
   * Make sure that at least the specified number of region servers
   * are running. We don't count the ones that are currently stopping or are
   * stopped.
   * @param num minimum number of region servers that should be running
   * @return true if we started some servers
   * @throws IOException
   */
  public boolean ensureSomeNonStoppedRegionServersAvailable(final int num)
    throws IOException {
    boolean startedServer = ensureSomeRegionServersAvailable(num);

    int nonStoppedServers = 0;
    for (JVMClusterUtil.RegionServerThread rst :
      getMiniHBaseCluster().getRegionServerThreads()) {

      HRegionServer hrs = rst.getRegionServer();
      if (hrs.isStopping() || hrs.isStopped()) {
        LOG.info("A region server is stopped or stopping:"+hrs);
      } else {
        nonStoppedServers++;
      }
    }
    for (int i=nonStoppedServers; i<num; ++i) {
      LOG.info("Started new server=" + getMiniHBaseCluster().startRegionServer());
      startedServer = true;
    }
    return startedServer;
  }


  /**
   * This method clones the passed <code>c</code> configuration setting a new
   * user into the clone.  Use it getting new instances of FileSystem.  Only
   * works for DistributedFileSystem.
   * @param c Initial configuration
   * @param differentiatingSuffix Suffix to differentiate this user from others.
   * @return A new configuration instance with a different user set into it.
   * @throws IOException
   */
  public static User getDifferentUser(final Configuration c,
    final String differentiatingSuffix)
  throws IOException {
    FileSystem currentfs = FileSystem.get(c);
    if (!(currentfs instanceof DistributedFileSystem)) {
      return User.getCurrent();
    }
    // Else distributed filesystem.  Make a new instance per daemon.  Below
    // code is taken from the AppendTestUtil over in hdfs.
    String username = User.getCurrent().getName() +
      differentiatingSuffix;
    User user = User.createUserForTesting(c, username,
        new String[]{"supergroup"});
    return user;
  }

  /**
   * Set maxRecoveryErrorCount in DFSClient.  In 0.20 pre-append its hard-coded to 5 and
   * makes tests linger.  Here is the exception you'll see:
   * <pre>
   * 2010-06-15 11:52:28,511 WARN  [DataStreamer for file /hbase/.logs/hlog.1276627923013 block blk_928005470262850423_1021] hdfs.DFSClient$DFSOutputStream(2657): Error Recovery for block blk_928005470262850423_1021 failed  because recovery from primary datanode 127.0.0.1:53683 failed 4 times.  Pipeline was 127.0.0.1:53687, 127.0.0.1:53683. Will retry...
   * </pre>
   * @param stream A DFSClient.DFSOutputStream.
   * @param max
   * @throws NoSuchFieldException
   * @throws SecurityException
   * @throws IllegalAccessException
   * @throws IllegalArgumentException
   */
  public static void setMaxRecoveryErrorCount(final OutputStream stream,
      final int max) {
    try {
      Class<?> [] clazzes = DFSClient.class.getDeclaredClasses();
      for (Class<?> clazz: clazzes) {
        String className = clazz.getSimpleName();
        if (className.equals("DFSOutputStream")) {
          if (clazz.isInstance(stream)) {
            Field maxRecoveryErrorCountField =
              stream.getClass().getDeclaredField("maxRecoveryErrorCount");
            maxRecoveryErrorCountField.setAccessible(true);
            maxRecoveryErrorCountField.setInt(stream, max);
            break;
          }
        }
      }
    } catch (Exception e) {
      LOG.info("Could not set max recovery field", e);
    }
  }


  /**
   * Wait until <code>countOfRegion</code> in .META. have a non-empty
   * info:server.  This means all regions have been deployed, master has been
   * informed and updated .META. with the regions deployed server.
   * @param countOfRegions How many regions in .META.
   * @throws IOException
   */
  public void waitUntilAllRegionsAssigned(final int countOfRegions)
  throws IOException {
    HTable meta = new HTable(getConfiguration(), HConstants.META_TABLE_NAME);
    while (true) {
      int rows = 0;
      Scan scan = new Scan();
      scan.addColumn(HConstants.CATALOG_FAMILY, HConstants.SERVER_QUALIFIER);
      ResultScanner s = meta.getScanner(scan);
      for (Result r = null; (r = s.next()) != null;) {
        byte [] b =
          r.getValue(HConstants.CATALOG_FAMILY, HConstants.SERVER_QUALIFIER);
        if (b == null || b.length <= 0) {
          break;
        }
        rows++;
      }
      s.close();
      // If I get to here and all rows have a Server, then all have been assigned.
      if (rows == countOfRegions) {
        break;
      }
      LOG.info("Found=" + rows);
      Threads.sleep(200);
    }
  }

  /**
   * Do a small get/scan against one store. This is required because store
   * has no actual methods of querying itself, and relies on StoreScanner.
   */
  public static List<KeyValue> getFromStoreFile(Store store,
                                                Get get) throws IOException {
    MultiVersionConsistencyControl.resetThreadReadPoint();
    Scan scan = new Scan(get);
    InternalScanner scanner = (InternalScanner) store.getScanner(scan,
        scan.getFamilyMap().get(store.getFamily().getName()));

    List<KeyValue> result = new ArrayList<KeyValue>();
    scanner.next(result);
    if (!result.isEmpty()) {
      // verify that we are on the row we want:
      KeyValue kv = result.get(0);
      if (!Bytes.equals(kv.getRow(), get.getRow())) {
        result.clear();
      }
    }
    scanner.close();
    return result;
  }

  /**
   * Do a small get/scan against one store. This is required because store
   * has no actual methods of querying itself, and relies on StoreScanner.
   */
  public static List<KeyValue> getFromStoreFile(Store store,
                                                byte [] row,
                                                NavigableSet<byte[]> columns
                                                ) throws IOException {
    Get get = new Get(row);
    Map<byte[], NavigableSet<byte[]>> s = get.getFamilyMap();
    s.put(store.getFamily().getName(), columns);

    return getFromStoreFile(store,get);
  }

  /**
   * Gets a ZooKeeperWatcher.
   * @param TEST_UTIL
   */
  public static ZooKeeperWatcher getZooKeeperWatcher(
      HBaseTestingUtility TEST_UTIL) throws ZooKeeperConnectionException,
      IOException {
    ZooKeeperWatcher zkw = new ZooKeeperWatcher(TEST_UTIL.getConfiguration(),
        "unittest", new Abortable() {
          boolean aborted = false;

          @Override
          public void abort(String why, Throwable e) {
            aborted = true;
            throw new RuntimeException("Fatal ZK error, why=" + why, e);
          }

          @Override
          public boolean isAborted() {
            return aborted;
          }
        });
    return zkw;
  }

  /**
   * Creates a znode with OPENED state.
   * @param TEST_UTIL
   * @param region
   * @param serverName
   * @return
   * @throws IOException
   * @throws ZooKeeperConnectionException
   * @throws KeeperException
   * @throws NodeExistsException
   */
  public static ZooKeeperWatcher createAndForceNodeToOpenedState(
      HBaseTestingUtility TEST_UTIL, HRegion region,
      ServerName serverName) throws ZooKeeperConnectionException,
      IOException, KeeperException, NodeExistsException {
    ZooKeeperWatcher zkw = getZooKeeperWatcher(TEST_UTIL);
    ZKAssign.createNodeOffline(zkw, region.getRegionInfo(), serverName);
    int version = ZKAssign.transitionNodeOpening(zkw, region
        .getRegionInfo(), serverName);
    ZKAssign.transitionNodeOpened(zkw, region.getRegionInfo(), serverName,
        version);
    return zkw;
  }

  public static void assertKVListsEqual(String additionalMsg,
      final List<KeyValue> expected,
      final List<KeyValue> actual) {
    final int eLen = expected.size();
    final int aLen = actual.size();
    final int minLen = Math.min(eLen, aLen);

    int i;
    for (i = 0; i < minLen
        && KeyValue.COMPARATOR.compare(expected.get(i), actual.get(i)) == 0;
        ++i) {}

    if (additionalMsg == null) {
      additionalMsg = "";
    }
    if (!additionalMsg.isEmpty()) {
      additionalMsg = ". " + additionalMsg;
    }

    if (eLen != aLen || i != minLen) {
      throw new AssertionError(
          "Expected and actual KV arrays differ at position " + i + ": " +
          safeGetAsStr(expected, i) + " (length " + eLen +") vs. " +
          safeGetAsStr(actual, i) + " (length " + aLen + ")" + additionalMsg);
    }
  }

  private static <T> String safeGetAsStr(List<T> lst, int i) {
    if (0 <= i && i < lst.size()) {
      return lst.get(i).toString();
    } else {
      return "<out_of_range>";
    }
  }

  public String getClusterKey() {
    return conf.get(HConstants.ZOOKEEPER_QUORUM) + ":"
        + conf.get(HConstants.ZOOKEEPER_CLIENT_PORT) + ":"
        + conf.get(HConstants.ZOOKEEPER_ZNODE_PARENT,
            HConstants.DEFAULT_ZOOKEEPER_ZNODE_PARENT);
  }

  /** Creates a random table with the given parameters */
  public HTable createRandomTable(String tableName,
      final Collection<String> families,
      final int maxVersions,
      final int numColsPerRow,
      final int numFlushes,
      final int numRegions,
      final int numRowsPerFlush)
      throws IOException, InterruptedException {

    LOG.info("\n\nCreating random table " + tableName + " with " + numRegions +
        " regions, " + numFlushes + " storefiles per region, " +
        numRowsPerFlush + " rows per flush, maxVersions=" +  maxVersions +
        "\n");

    final Random rand = new Random(tableName.hashCode() * 17L + 12938197137L);
    final int numCF = families.size();
    final byte[][] cfBytes = new byte[numCF][];
    final byte[] tableNameBytes = Bytes.toBytes(tableName);

    {
      int cfIndex = 0;
      for (String cf : families) {
        cfBytes[cfIndex++] = Bytes.toBytes(cf);
      }
    }

    final int actualStartKey = 0;
    final int actualEndKey = Integer.MAX_VALUE;
    final int keysPerRegion = (actualEndKey - actualStartKey) / numRegions;
    final int splitStartKey = actualStartKey + keysPerRegion;
    final int splitEndKey = actualEndKey - keysPerRegion;
    final String keyFormat = "%08x";
    final HTable table = createTable(tableNameBytes, cfBytes,
        maxVersions,
        Bytes.toBytes(String.format(keyFormat, splitStartKey)),
        Bytes.toBytes(String.format(keyFormat, splitEndKey)),
        numRegions);
    if (hbaseCluster != null) {
      getMiniHBaseCluster().flushcache(HConstants.META_TABLE_NAME);
    }

    for (int iFlush = 0; iFlush < numFlushes; ++iFlush) {
      for (int iRow = 0; iRow < numRowsPerFlush; ++iRow) {
        final byte[] row = Bytes.toBytes(String.format(keyFormat,
            actualStartKey + rand.nextInt(actualEndKey - actualStartKey)));

        Put put = new Put(row);
        Delete del = new Delete(row);
        for (int iCol = 0; iCol < numColsPerRow; ++iCol) {
          final byte[] cf = cfBytes[rand.nextInt(numCF)];
          final long ts = rand.nextInt();
          final byte[] qual = Bytes.toBytes("col" + iCol);
          if (rand.nextBoolean()) {
            final byte[] value = Bytes.toBytes("value_for_row_" + iRow +
                "_cf_" + Bytes.toStringBinary(cf) + "_col_" + iCol + "_ts_" +
                ts + "_random_" + rand.nextLong());
            put.add(cf, qual, ts, value);
          } else if (rand.nextDouble() < 0.8) {
            del.deleteColumn(cf, qual, ts);
          } else {
            del.deleteColumns(cf, qual, ts);
          }
        }

        if (!put.isEmpty()) {
          table.put(put);
        }

        if (!del.isEmpty()) {
          table.delete(del);
        }
      }
      LOG.info("Initiating flush #" + iFlush + " for table " + tableName);
      table.flushCommits();
      if (hbaseCluster != null) {
        getMiniHBaseCluster().flushcache(tableNameBytes);
      }
    }

    return table;
  }

  private static final int MIN_RANDOM_PORT = 0xc000;
  private static final int MAX_RANDOM_PORT = 0xfffe;

  /**
   * Returns a random port. These ports cannot be registered with IANA and are
   * intended for dynamic allocation (see http://bit.ly/dynports).
   */
  public static int randomPort() {
    return MIN_RANDOM_PORT
        + new Random().nextInt(MAX_RANDOM_PORT - MIN_RANDOM_PORT);
  }

  public static int randomFreePort() {
    int port = 0;
    do {
      port = randomPort();
      try {
        ServerSocket sock = new ServerSocket(port);
        sock.close();
      } catch (IOException ex) {
        port = 0;
      }
    } while (port == 0);
    return port;
  }

  public static void waitForHostPort(String host, int port)
      throws IOException {
    final int maxTimeMs = 10000;
    final int maxNumAttempts = maxTimeMs / HConstants.SOCKET_RETRY_WAIT_MS;
    IOException savedException = null;
    LOG.info("Waiting for server at " + host + ":" + port);
    for (int attempt = 0; attempt < maxNumAttempts; ++attempt) {
      try {
        Socket sock = new Socket(InetAddress.getByName(host), port);
        sock.close();
        savedException = null;
        LOG.info("Server at " + host + ":" + port + " is available");
        break;
      } catch (UnknownHostException e) {
        throw new IOException("Failed to look up " + host, e);
      } catch (IOException e) {
        savedException = e;
      }
      Threads.sleepWithoutInterrupt(HConstants.SOCKET_RETRY_WAIT_MS);
    }

    if (savedException != null) {
      throw savedException;
    }
  }

  /**
   * Creates a pre-split table for load testing. If the table already exists,
   * logs a warning and continues.
   * @return the number of regions the table was split into
   */
  public static int createPreSplitLoadTestTable(Configuration conf,
      byte[] tableName, byte[] columnFamily, Algorithm compression,
      DataBlockEncoding dataBlockEncoding) throws IOException {
    HTableDescriptor desc = new HTableDescriptor(tableName);
    HColumnDescriptor hcd = new HColumnDescriptor(columnFamily);
    hcd.setDataBlockEncoding(dataBlockEncoding);
    hcd.setCompressionType(compression);
    desc.addFamily(hcd);

    int totalNumberOfRegions = 0;
    try {
      HBaseAdmin admin = new HBaseAdmin(conf);

      // create a table a pre-splits regions.
      // The number of splits is set as:
      //    region servers * regions per region server).
      int numberOfServers = admin.getClusterStatus().getServers().size();
      if (numberOfServers == 0) {
        throw new IllegalStateException("No live regionservers");
      }

      totalNumberOfRegions = numberOfServers * DEFAULT_REGIONS_PER_SERVER;
      LOG.info("Number of live regionservers: " + numberOfServers + ", " +
          "pre-splitting table into " + totalNumberOfRegions + " regions " +
          "(default regions per server: " + DEFAULT_REGIONS_PER_SERVER + ")");

      byte[][] splits = new RegionSplitter.HexStringSplit().split(
          totalNumberOfRegions);

      admin.createTable(desc, splits);
      admin.close();
    } catch (MasterNotRunningException e) {
      LOG.error("Master not running", e);
      throw new IOException(e);
    } catch (TableExistsException e) {
      LOG.warn("Table " + Bytes.toStringBinary(tableName) +
          " already exists, continuing");
    }
    return totalNumberOfRegions;
  }

  public static int getMetaRSPort(Configuration conf) throws IOException {
    HTable table = new HTable(conf, HConstants.META_TABLE_NAME);
    HRegionLocation hloc = table.getRegionLocation(Bytes.toBytes(""));
    table.close();
    return hloc.getPort();
  }

  public HRegion createTestRegion(String tableName, HColumnDescriptor hcd)
      throws IOException {
    HTableDescriptor htd = new HTableDescriptor(tableName);
    htd.addFamily(hcd);
    HRegionInfo info =
        new HRegionInfo(Bytes.toBytes(tableName), null, null, false);
    HRegion region =
        HRegion.createHRegion(info, getDataTestDir(), getConfiguration(), htd);
    return region;
  }

  /**
   * Create a set of column descriptors with the combination of compression,
   * encoding, bloom codecs available.
   * @return the list of column descriptors
   */
  public static List<HColumnDescriptor> generateColumnDescriptors() {
    return generateColumnDescriptors("");
  }

  /**
   * Create a set of column descriptors with the combination of compression,
   * encoding, bloom codecs available.
   * @param prefix family names prefix
   * @return the list of column descriptors
   */
  public static List<HColumnDescriptor> generateColumnDescriptors(final String prefix) {
    List<HColumnDescriptor> htds = new ArrayList<HColumnDescriptor>();
    long familyId = 0;
    for (Compression.Algorithm compressionType: getSupportedCompressionAlgorithms()) {
      for (DataBlockEncoding encodingType: DataBlockEncoding.values()) {
        for (StoreFile.BloomType bloomType: StoreFile.BloomType.values()) {
          String name = String.format("%s-cf-!@#&-%d!@#", prefix, familyId);
          HColumnDescriptor htd = new HColumnDescriptor(name);
          htd.setCompressionType(compressionType);
          htd.setDataBlockEncoding(encodingType);
          htd.setBloomFilterType(bloomType);
          htds.add(htd);
          familyId++;
        }
      }
    }
    return htds;
  }

  /**
   * Get supported compression algorithms.
   * @return supported compression algorithms.
   */
  public static Compression.Algorithm[] getSupportedCompressionAlgorithms() {
    String[] allAlgos = HFile.getSupportedCompressionAlgorithms();
    List<Compression.Algorithm> supportedAlgos = new ArrayList<Compression.Algorithm>();
    for (String algoName : allAlgos) {
      try {
        Compression.Algorithm algo = Compression.getCompressionAlgorithmByName(algoName);
        algo.getCompressor();
        supportedAlgos.add(algo);
      } catch (Throwable t) {
        // this algo is not available
      }
    }
    return supportedAlgos.toArray(new Compression.Algorithm[0]);
  }
}
