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
package org.apache.hadoop.hbase.master;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.Abortable;
import org.apache.hadoop.hbase.ClusterStatus;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.LargeTests;
import org.apache.hadoop.hbase.MasterNotRunningException;
import org.apache.hadoop.hbase.MiniHBaseCluster;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.executor.EventHandler.EventType;
import org.apache.hadoop.hbase.executor.RegionTransitionData;
import org.apache.hadoop.hbase.master.AssignmentManager.RegionState;
import org.apache.hadoop.hbase.regionserver.HRegion;
import org.apache.hadoop.hbase.regionserver.HRegionServer;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.FSTableDescriptors;
import org.apache.hadoop.hbase.util.JVMClusterUtil;
import org.apache.hadoop.hbase.util.JVMClusterUtil.MasterThread;
import org.apache.hadoop.hbase.util.JVMClusterUtil.RegionServerThread;
import org.apache.hadoop.hbase.util.Threads;
import org.apache.hadoop.hbase.zookeeper.ZKAssign;
import org.apache.hadoop.hbase.zookeeper.ZKTable;
import org.apache.hadoop.hbase.zookeeper.ZooKeeperWatcher;
import org.junit.Test;
import org.junit.experimental.categories.Category;

@Category(LargeTests.class)
public class TestMasterFailover {
  private static final Log LOG = LogFactory.getLog(TestMasterFailover.class);

  @Test (timeout=180000)
  public void testShouldCheckMasterFailOverWhenMETAIsInOpenedState()
      throws Exception {
    LOG.info("Starting testShouldCheckMasterFailOverWhenMETAIsInOpenedState");
    final int NUM_MASTERS = 1;
    final int NUM_RS = 2;

    Configuration conf = HBaseConfiguration.create();
    conf.setInt("hbase.master.assignment.timeoutmonitor.period", 2000);
    conf.setInt("hbase.master.assignment.timeoutmonitor.timeout", 8000);
    // Start the cluster
    HBaseTestingUtility TEST_UTIL = new HBaseTestingUtility(conf);

    TEST_UTIL.startMiniCluster(NUM_MASTERS, NUM_RS);
    MiniHBaseCluster cluster = TEST_UTIL.getHBaseCluster();

    // Find regionserver carrying meta.
    List<RegionServerThread> regionServerThreads =
      cluster.getRegionServerThreads();
    int count = -1;
    HRegion metaRegion = null;
    for (RegionServerThread regionServerThread : regionServerThreads) {
      HRegionServer regionServer = regionServerThread.getRegionServer();
      metaRegion = regionServer.getOnlineRegion(HRegionInfo.FIRST_META_REGIONINFO.getRegionName());
      count++;
      regionServer.abort("");
      if (null != metaRegion) break;
    }
    HRegionServer regionServer = cluster.getRegionServer(count);

    TEST_UTIL.shutdownMiniHBaseCluster();

    // Create a ZKW to use in the test
    ZooKeeperWatcher zkw = 
      HBaseTestingUtility.createAndForceNodeToOpenedState(TEST_UTIL, 
          metaRegion, regionServer.getServerName());

    LOG.info("Staring cluster for second time");
    TEST_UTIL.startMiniHBaseCluster(NUM_MASTERS, NUM_RS);

    HMaster master = TEST_UTIL.getHBaseCluster().getMaster();
    while (!master.isInitialized()) {
      Thread.sleep(100);
    }
    // Failover should be completed, now wait for no RIT
    log("Waiting for no more RIT");
    ZKAssign.blockUntilNoRIT(zkw);

    zkw.close();
    // Stop the cluster
    TEST_UTIL.shutdownMiniCluster();
  }

  /**
   * Simple test of master failover.
   * <p>
   * Starts with three masters.  Kills a backup master.  Then kills the active
   * master.  Ensures the final master becomes active and we can still contact
   * the cluster.
   * @throws Exception
   */
  @Test (timeout=240000)
  public void testSimpleMasterFailover() throws Exception {

    final int NUM_MASTERS = 3;
    final int NUM_RS = 3;

    // Create config to use for this cluster
    Configuration conf = HBaseConfiguration.create();

    // Start the cluster
    HBaseTestingUtility TEST_UTIL = new HBaseTestingUtility(conf);
    TEST_UTIL.startMiniCluster(NUM_MASTERS, NUM_RS);
    MiniHBaseCluster cluster = TEST_UTIL.getHBaseCluster();

    // get all the master threads
    List<MasterThread> masterThreads = cluster.getMasterThreads();

    // wait for each to come online
    for (MasterThread mt : masterThreads) {
      assertTrue(mt.isAlive());
    }

    // verify only one is the active master and we have right number
    int numActive = 0;
    int activeIndex = -1;
    ServerName activeName = null;
    HMaster active = null;
    for (int i = 0; i < masterThreads.size(); i++) {
      if (masterThreads.get(i).getMaster().isActiveMaster()) {
        numActive++;
        activeIndex = i;
        active = masterThreads.get(activeIndex).getMaster();
        activeName = active.getServerName();
      }
    }
    assertEquals(1, numActive);
    assertEquals(NUM_MASTERS, masterThreads.size());
    LOG.info("Active master " + activeName);

    // Check that ClusterStatus reports the correct active and backup masters
    assertNotNull(active);
    ClusterStatus status = active.getClusterStatus();
    assertTrue(status.getMaster().equals(activeName));
    assertEquals(2, status.getBackupMastersSize());
    assertEquals(2, status.getBackupMasters().size());

    // attempt to stop one of the inactive masters
    int backupIndex = (activeIndex == 0 ? 1 : activeIndex - 1);
    HMaster master = cluster.getMaster(backupIndex);
    LOG.debug("\n\nStopping a backup master: " + master.getServerName() + "\n");
    cluster.stopMaster(backupIndex, false);
    cluster.waitOnMaster(backupIndex);

    // Verify still one active master and it's the same
    for (int i = 0; i < masterThreads.size(); i++) {
      if (masterThreads.get(i).getMaster().isActiveMaster()) {
        assertTrue(activeName.equals(masterThreads.get(i).getMaster().getServerName()));
        activeIndex = i;
        active = masterThreads.get(activeIndex).getMaster();
      }
    }
    assertEquals(1, numActive);
    assertEquals(2, masterThreads.size());
    int rsCount = masterThreads.get(activeIndex).getMaster().getClusterStatus().getServersSize();
    LOG.info("Active master " + active.getServerName() + " managing " + rsCount +  " regions servers");
    assertEquals(3, rsCount);

    // Check that ClusterStatus reports the correct active and backup masters
    assertNotNull(active);
    status = active.getClusterStatus();
    assertTrue(status.getMaster().equals(activeName));
    assertEquals(1, status.getBackupMastersSize());
    assertEquals(1, status.getBackupMasters().size());

    // kill the active master
    LOG.debug("\n\nStopping the active master " + active.getServerName() + "\n");
    cluster.stopMaster(activeIndex, false);
    cluster.waitOnMaster(activeIndex);

    // wait for an active master to show up and be ready
    assertTrue(cluster.waitForActiveAndReadyMaster());

    LOG.debug("\n\nVerifying backup master is now active\n");
    // should only have one master now
    assertEquals(1, masterThreads.size());

    // and he should be active
    active = masterThreads.get(0).getMaster();
    assertNotNull(active);
    status = active.getClusterStatus();
    ServerName mastername = status.getMaster();
    assertTrue(mastername.equals(active.getServerName()));
    assertTrue(active.isActiveMaster());
    assertEquals(0, status.getBackupMastersSize());
    assertEquals(0, status.getBackupMasters().size());
    int rss = status.getServersSize();
    LOG.info("Active master " + mastername.getServerName() + " managing " +
      rss +  " region servers");
    assertEquals(3, rss);

    // Stop the cluster
    TEST_UTIL.shutdownMiniCluster();
  }

  /**
   * Complex test of master failover that tests as many permutations of the
   * different possible states that regions in transition could be in within ZK.
   * <p>
   * This tests the proper handling of these states by the failed-over master
   * and includes a thorough testing of the timeout code as well.
   * <p>
   * Starts with a single master and three regionservers.
   * <p>
   * Creates two tables, enabledTable and disabledTable, each containing 5
   * regions.  The disabledTable is then disabled.
   * <p>
   * After reaching steady-state, the master is killed.  We then mock several
   * states in ZK.
   * <p>
   * After mocking them, we will startup a new master which should become the
   * active master and also detect that it is a failover.  The primary test
   * passing condition will be that all regions of the enabled table are
   * assigned and all the regions of the disabled table are not assigned.
   * <p>
   * The different scenarios to be tested are below:
   * <p>
   * <b>ZK State:  OFFLINE</b>
   * <p>A node can get into OFFLINE state if</p>
   * <ul>
   * <li>An RS fails to open a region, so it reverts the state back to OFFLINE
   * <li>The Master is assigning the region to a RS before it sends RPC
   * </ul>
   * <p>We will mock the scenarios</p>
   * <ul>
   * <li>Master has assigned an enabled region but RS failed so a region is
   *     not assigned anywhere and is sitting in ZK as OFFLINE</li>
   * <li>This seems to cover both cases?</li>
   * </ul>
   * <p>
   * <b>ZK State:  CLOSING</b>
   * <p>A node can get into CLOSING state if</p>
   * <ul>
   * <li>An RS has begun to close a region
   * </ul>
   * <p>We will mock the scenarios</p>
   * <ul>
   * <li>Region of enabled table was being closed but did not complete
   * <li>Region of disabled table was being closed but did not complete
   * </ul>
   * <p>
   * <b>ZK State:  CLOSED</b>
   * <p>A node can get into CLOSED state if</p>
   * <ul>
   * <li>An RS has completed closing a region but not acknowledged by master yet
   * </ul>
   * <p>We will mock the scenarios</p>
   * <ul>
   * <li>Region of a table that should be enabled was closed on an RS
   * <li>Region of a table that should be disabled was closed on an RS
   * </ul>
   * <p>
   * <b>ZK State:  OPENING</b>
   * <p>A node can get into OPENING state if</p>
   * <ul>
   * <li>An RS has begun to open a region
   * </ul>
   * <p>We will mock the scenarios</p>
   * <ul>
   * <li>RS was opening a region of enabled table but never finishes
   * </ul>
   * <p>
   * <b>ZK State:  OPENED</b>
   * <p>A node can get into OPENED state if</p>
   * <ul>
   * <li>An RS has finished opening a region but not acknowledged by master yet
   * </ul>
   * <p>We will mock the scenarios</p>
   * <ul>
   * <li>Region of a table that should be enabled was opened on an RS
   * <li>Region of a table that should be disabled was opened on an RS
   * </ul>
   * @throws Exception
   */
  @Test (timeout=180000)
  public void testMasterFailoverWithMockedRIT() throws Exception {

    final int NUM_MASTERS = 1;
    final int NUM_RS = 3;

    // Create config to use for this cluster
    Configuration conf = HBaseConfiguration.create();
    // Need to drop the timeout much lower
    conf.setInt("hbase.master.assignment.timeoutmonitor.period", 2000);
    conf.setInt("hbase.master.assignment.timeoutmonitor.timeout", 4000);
    conf.setInt(ServerManager.WAIT_ON_REGIONSERVERS_MINTOSTART, 3);
    conf.setInt(ServerManager.WAIT_ON_REGIONSERVERS_MAXTOSTART, 3);

    // Start the cluster
    HBaseTestingUtility TEST_UTIL = new HBaseTestingUtility(conf);
    TEST_UTIL.startMiniCluster(NUM_MASTERS, NUM_RS);
    MiniHBaseCluster cluster = TEST_UTIL.getHBaseCluster();
    log("Cluster started");

    // Create a ZKW to use in the test
    ZooKeeperWatcher zkw = HBaseTestingUtility.getZooKeeperWatcher(TEST_UTIL);

    // get all the master threads
    List<MasterThread> masterThreads = cluster.getMasterThreads();
    assertEquals(1, masterThreads.size());

    // only one master thread, let's wait for it to be initialized
    assertTrue(cluster.waitForActiveAndReadyMaster());
    HMaster master = masterThreads.get(0).getMaster();
    assertTrue(master.isActiveMaster());
    assertTrue(master.isInitialized());

    // disable load balancing on this master
    master.balanceSwitch(false);

    // create two tables in META, each with 10 regions
    byte [] FAMILY = Bytes.toBytes("family");
    byte [][] SPLIT_KEYS = new byte [][] {
        new byte[0], Bytes.toBytes("aaa"), Bytes.toBytes("bbb"),
        Bytes.toBytes("ccc"), Bytes.toBytes("ddd"), Bytes.toBytes("eee"),
        Bytes.toBytes("fff"), Bytes.toBytes("ggg"), Bytes.toBytes("hhh"),
        Bytes.toBytes("iii"), Bytes.toBytes("jjj")
    };

    byte [] enabledTable = Bytes.toBytes("enabledTable");
    HTableDescriptor htdEnabled = new HTableDescriptor(enabledTable);
    htdEnabled.addFamily(new HColumnDescriptor(FAMILY));

    FileSystem filesystem = FileSystem.get(conf);
    Path rootdir = filesystem.makeQualified(
        new Path(conf.get(HConstants.HBASE_DIR)));
    // Write the .tableinfo
    FSTableDescriptors.createTableDescriptor(filesystem, rootdir, htdEnabled);

    HRegionInfo hriEnabled = new HRegionInfo(htdEnabled.getName(), null, null);
    createRegion(hriEnabled, rootdir, conf, htdEnabled);

    List<HRegionInfo> enabledRegions = TEST_UTIL.createMultiRegionsInMeta(
        TEST_UTIL.getConfiguration(), htdEnabled, SPLIT_KEYS);

    byte [] disabledTable = Bytes.toBytes("disabledTable");
    HTableDescriptor htdDisabled = new HTableDescriptor(disabledTable);
    htdDisabled.addFamily(new HColumnDescriptor(FAMILY));
    // Write the .tableinfo
    FSTableDescriptors.createTableDescriptor(filesystem, rootdir, htdDisabled);
    HRegionInfo hriDisabled = new HRegionInfo(htdDisabled.getName(), null, null);
    createRegion(hriDisabled, rootdir, conf, htdDisabled);
    List<HRegionInfo> disabledRegions = TEST_UTIL.createMultiRegionsInMeta(
        TEST_UTIL.getConfiguration(), htdDisabled, SPLIT_KEYS);

    log("Regions in META have been created");

    // at this point we only expect 2 regions to be assigned out (catalogs)
    assertEquals(2, cluster.countServedRegions());

    // Let's just assign everything to first RS
    HRegionServer hrs = cluster.getRegionServer(0);
    ServerName serverName = hrs.getServerName();
    HRegionInfo closingRegion = enabledRegions.remove(0);
    // we'll need some regions to already be assigned out properly on live RS
    List<HRegionInfo> enabledAndAssignedRegions = new ArrayList<HRegionInfo>();
    enabledAndAssignedRegions.add(enabledRegions.remove(0));
    enabledAndAssignedRegions.add(enabledRegions.remove(0));
    enabledAndAssignedRegions.add(closingRegion);

    List<HRegionInfo> disabledAndAssignedRegions = new ArrayList<HRegionInfo>();
    disabledAndAssignedRegions.add(disabledRegions.remove(0));
    disabledAndAssignedRegions.add(disabledRegions.remove(0));

    // now actually assign them
    for (HRegionInfo hri : enabledAndAssignedRegions) {
      master.assignmentManager.regionPlans.put(hri.getEncodedName(),
          new RegionPlan(hri, null, serverName));
      master.assignRegion(hri);
    }
    for (HRegionInfo hri : disabledAndAssignedRegions) {
      master.assignmentManager.regionPlans.put(hri.getEncodedName(),
          new RegionPlan(hri, null, serverName));
      master.assignRegion(hri);
    }

    // wait for no more RIT
    log("Waiting for assignment to finish");
    ZKAssign.blockUntilNoRIT(zkw);
    log("Assignment completed");

    // Stop the master
    log("Aborting master");
    cluster.abortMaster(0);
    cluster.waitOnMaster(0);
    log("Master has aborted");

    /*
     * Now, let's start mocking up some weird states as described in the method
     * javadoc.
     */

    List<HRegionInfo> regionsThatShouldBeOnline = new ArrayList<HRegionInfo>();
    List<HRegionInfo> regionsThatShouldBeOffline = new ArrayList<HRegionInfo>();

    log("Beginning to mock scenarios");

    // Disable the disabledTable in ZK
    ZKTable zktable = new ZKTable(zkw);
    zktable.setDisabledTable(Bytes.toString(disabledTable));

    /*
     *  ZK = OFFLINE
     */

    // Region that should be assigned but is not and is in ZK as OFFLINE
    HRegionInfo region = enabledRegions.remove(0);
    regionsThatShouldBeOnline.add(region);
    ZKAssign.createNodeOffline(zkw, region, serverName);

    /*
     * ZK = CLOSING
     */
    regionsThatShouldBeOnline.add(closingRegion);
    ZKAssign.createNodeClosing(zkw, closingRegion, serverName);

    /*
     * ZK = CLOSED
     */

    // Region of enabled table closed but not ack
    region = enabledRegions.remove(0);
    regionsThatShouldBeOnline.add(region);
    int version = ZKAssign.createNodeClosing(zkw, region, serverName);
    ZKAssign.transitionNodeClosed(zkw, region, serverName, version);

    // Region of disabled table closed but not ack
    region = disabledRegions.remove(0);
    regionsThatShouldBeOffline.add(region);
    version = ZKAssign.createNodeClosing(zkw, region, serverName);
    ZKAssign.transitionNodeClosed(zkw, region, serverName, version);

    /*
     * ZK = OPENING
     */

    // RS was opening a region of enabled table but never finishes
    region = enabledRegions.remove(0);
    regionsThatShouldBeOnline.add(region);
    ZKAssign.createNodeOffline(zkw, region, serverName);
    ZKAssign.transitionNodeOpening(zkw, region, serverName);

    /*
     * ZK = OPENED
     */

    // Region of enabled table was opened on RS
    region = enabledRegions.remove(0);
    regionsThatShouldBeOnline.add(region);
    ZKAssign.createNodeOffline(zkw, region, serverName);
    hrs.openRegion(region);
    while (true) {
      RegionTransitionData rtd = ZKAssign.getData(zkw, region.getEncodedName());
      if (rtd != null && rtd.getEventType() == EventType.RS_ZK_REGION_OPENED) {
        break;
      }
      Thread.sleep(100);
    }

    // Region of disable table was opened on RS
    region = disabledRegions.remove(0);
    regionsThatShouldBeOffline.add(region);
    ZKAssign.createNodeOffline(zkw, region, serverName);
    hrs.openRegion(region);
    while (true) {
      RegionTransitionData rtd = ZKAssign.getData(zkw, region.getEncodedName());
      if (rtd != null && rtd.getEventType() == EventType.RS_ZK_REGION_OPENED) {
        break;
      }
      Thread.sleep(100);
    }

    /*
     * ZK = NONE
     */

    /*
     * DONE MOCKING
     */

    log("Done mocking data up in ZK");

    // Start up a new master
    log("Starting up a new master");
    master = cluster.startMaster().getMaster();
    log("Waiting for master to be ready");
    cluster.waitForActiveAndReadyMaster();
    log("Master is ready");

    // Failover should be completed, now wait for no RIT
    log("Waiting for no more RIT");
    ZKAssign.blockUntilNoRIT(zkw);
    log("No more RIT in ZK, now doing final test verification");

    // Grab all the regions that are online across RSs
    Set<HRegionInfo> onlineRegions = new TreeSet<HRegionInfo>();
    for (JVMClusterUtil.RegionServerThread rst :
      cluster.getRegionServerThreads()) {
      onlineRegions.addAll(rst.getRegionServer().getOnlineRegions());
    }

    // Now, everything that should be online should be online
    for (HRegionInfo hri : regionsThatShouldBeOnline) {
      assertTrue(onlineRegions.contains(hri));
    }

    // Everything that should be offline should not be online
    for (HRegionInfo hri : regionsThatShouldBeOffline) {
      assertFalse(onlineRegions.contains(hri));
    }

    log("Done with verification, all passed, shutting down cluster");

    // Done, shutdown the cluster
    TEST_UTIL.shutdownMiniCluster();
  }


  /**
   * Complex test of master failover that tests as many permutations of the
   * different possible states that regions in transition could be in within ZK
   * pointing to an RS that has died while no master is around to process it.
   * <p>
   * This tests the proper handling of these states by the failed-over master
   * and includes a thorough testing of the timeout code as well.
   * <p>
   * Starts with a single master and two regionservers.
   * <p>
   * Creates two tables, enabledTable and disabledTable, each containing 5
   * regions.  The disabledTable is then disabled.
   * <p>
   * After reaching steady-state, the master is killed.  We then mock several
   * states in ZK.  And one of the RS will be killed.
   * <p>
   * After mocking them and killing an RS, we will startup a new master which
   * should become the active master and also detect that it is a failover.  The
   * primary test passing condition will be that all regions of the enabled
   * table are assigned and all the regions of the disabled table are not
   * assigned.
   * <p>
   * The different scenarios to be tested are below:
   * <p>
   * <b>ZK State:  CLOSING</b>
   * <p>A node can get into CLOSING state if</p>
   * <ul>
   * <li>An RS has begun to close a region
   * </ul>
   * <p>We will mock the scenarios</p>
   * <ul>
   * <li>Region was being closed but the RS died before finishing the close
   * </ul>
   * <b>ZK State:  OPENED</b>
   * <p>A node can get into OPENED state if</p>
   * <ul>
   * <li>An RS has finished opening a region but not acknowledged by master yet
   * </ul>
   * <p>We will mock the scenarios</p>
   * <ul>
   * <li>Region of a table that should be enabled was opened by a now-dead RS
   * <li>Region of a table that should be disabled was opened by a now-dead RS
   * </ul>
   * <p>
   * <b>ZK State:  NONE</b>
   * <p>A region could not have a transition node if</p>
   * <ul>
   * <li>The server hosting the region died and no master processed it
   * </ul>
   * <p>We will mock the scenarios</p>
   * <ul>
   * <li>Region of enabled table was on a dead RS that was not yet processed
   * <li>Region of disabled table was on a dead RS that was not yet processed
   * </ul>
   * @throws Exception
   */
  @Test (timeout=180000)
  public void testMasterFailoverWithMockedRITOnDeadRS() throws Exception {

    final int NUM_MASTERS = 1;
    final int NUM_RS = 2;

    // Create config to use for this cluster
    Configuration conf = HBaseConfiguration.create();
    // Need to drop the timeout much lower
    conf.setInt("hbase.master.assignment.timeoutmonitor.period", 2000);
    conf.setInt("hbase.master.assignment.timeoutmonitor.timeout", 4000);
    conf.setInt(ServerManager.WAIT_ON_REGIONSERVERS_MINTOSTART, 1);
    conf.setInt(ServerManager.WAIT_ON_REGIONSERVERS_MAXTOSTART, 2);

    // Create and start the cluster
    HBaseTestingUtility TEST_UTIL = new HBaseTestingUtility(conf);
    TEST_UTIL.startMiniCluster(NUM_MASTERS, NUM_RS);
    MiniHBaseCluster cluster = TEST_UTIL.getHBaseCluster();
    log("Cluster started");

    // Create a ZKW to use in the test
    ZooKeeperWatcher zkw = new ZooKeeperWatcher(TEST_UTIL.getConfiguration(),
        "unittest", new Abortable() {

          @Override
          public void abort(String why, Throwable e) {
            LOG.error("Fatal ZK Error: " + why, e);
            org.junit.Assert.assertFalse("Fatal ZK error", true);
          }

          @Override
          public boolean isAborted() {
            return false;
          }

    });

    // get all the master threads
    List<MasterThread> masterThreads = cluster.getMasterThreads();
    assertEquals(1, masterThreads.size());

    // only one master thread, let's wait for it to be initialized
    assertTrue(cluster.waitForActiveAndReadyMaster());
    HMaster master = masterThreads.get(0).getMaster();
    assertTrue(master.isActiveMaster());
    assertTrue(master.isInitialized());

    // disable load balancing on this master
    master.balanceSwitch(false);

    // create two tables in META, each with 10 regions
    byte [] FAMILY = Bytes.toBytes("family");
    byte [][] SPLIT_KEYS = new byte [][] {
        new byte[0], Bytes.toBytes("aaa"), Bytes.toBytes("bbb"),
        Bytes.toBytes("ccc"), Bytes.toBytes("ddd"), Bytes.toBytes("eee"),
        Bytes.toBytes("fff"), Bytes.toBytes("ggg"), Bytes.toBytes("hhh"),
        Bytes.toBytes("iii"), Bytes.toBytes("jjj")
    };

    byte [] enabledTable = Bytes.toBytes("enabledTable");
    HTableDescriptor htdEnabled = new HTableDescriptor(enabledTable);
    htdEnabled.addFamily(new HColumnDescriptor(FAMILY));
    FileSystem filesystem = FileSystem.get(conf);
    Path rootdir = filesystem.makeQualified(
           new Path(conf.get(HConstants.HBASE_DIR)));
    // Write the .tableinfo
    FSTableDescriptors.createTableDescriptor(filesystem, rootdir, htdEnabled);
    HRegionInfo hriEnabled = new HRegionInfo(htdEnabled.getName(),
        null, null);
    createRegion(hriEnabled, rootdir, conf, htdEnabled);

    List<HRegionInfo> enabledRegions = TEST_UTIL.createMultiRegionsInMeta(
        TEST_UTIL.getConfiguration(), htdEnabled, SPLIT_KEYS);

    byte [] disabledTable = Bytes.toBytes("disabledTable");
    HTableDescriptor htdDisabled = new HTableDescriptor(disabledTable);
    htdDisabled.addFamily(new HColumnDescriptor(FAMILY));
    // Write the .tableinfo
    FSTableDescriptors.createTableDescriptor(filesystem, rootdir, htdDisabled);
    HRegionInfo hriDisabled = new HRegionInfo(htdDisabled.getName(), null, null);
    createRegion(hriDisabled, rootdir, conf, htdDisabled);

    List<HRegionInfo> disabledRegions = TEST_UTIL.createMultiRegionsInMeta(
        TEST_UTIL.getConfiguration(), htdDisabled, SPLIT_KEYS);

    log("Regions in META have been created");

    // at this point we only expect 2 regions to be assigned out (catalogs)
    assertEquals(2, cluster.countServedRegions());

    // The first RS will stay online
    List<RegionServerThread> regionservers =
      cluster.getRegionServerThreads();
    HRegionServer hrs = regionservers.get(0).getRegionServer();

    // The second RS is going to be hard-killed
    RegionServerThread hrsDeadThread = regionservers.get(1);
    HRegionServer hrsDead = hrsDeadThread.getRegionServer();
    ServerName deadServerName = hrsDead.getServerName();

    // we'll need some regions to already be assigned out properly on live RS
    List<HRegionInfo> enabledAndAssignedRegions = new ArrayList<HRegionInfo>();
    enabledAndAssignedRegions.add(enabledRegions.remove(0));
    enabledAndAssignedRegions.add(enabledRegions.remove(0));
    List<HRegionInfo> disabledAndAssignedRegions = new ArrayList<HRegionInfo>();
    disabledAndAssignedRegions.add(disabledRegions.remove(0));
    disabledAndAssignedRegions.add(disabledRegions.remove(0));

    // now actually assign them
    for (HRegionInfo hri : enabledAndAssignedRegions) {
      master.assignmentManager.regionPlans.put(hri.getEncodedName(),
          new RegionPlan(hri, null, hrs.getServerName()));
      master.assignRegion(hri);
    }
    for (HRegionInfo hri : disabledAndAssignedRegions) {
      master.assignmentManager.regionPlans.put(hri.getEncodedName(),
          new RegionPlan(hri, null, hrs.getServerName()));
      master.assignRegion(hri);
    }

    assertTrue(" Table must be enabled.", master.getAssignmentManager()
        .getZKTable().isEnabledTable("enabledTable"));
    // we also need regions assigned out on the dead server
    List<HRegionInfo> enabledAndOnDeadRegions = new ArrayList<HRegionInfo>();
    enabledAndOnDeadRegions.add(enabledRegions.remove(0));
    enabledAndOnDeadRegions.add(enabledRegions.remove(0));
    List<HRegionInfo> disabledAndOnDeadRegions = new ArrayList<HRegionInfo>();
    disabledAndOnDeadRegions.add(disabledRegions.remove(0));
    disabledAndOnDeadRegions.add(disabledRegions.remove(0));

    // set region plan to server to be killed and trigger assign
    for (HRegionInfo hri : enabledAndOnDeadRegions) {
      master.assignmentManager.regionPlans.put(hri.getEncodedName(),
          new RegionPlan(hri, null, deadServerName));
      master.assignRegion(hri);
    }
    for (HRegionInfo hri : disabledAndOnDeadRegions) {
      master.assignmentManager.regionPlans.put(hri.getEncodedName(),
          new RegionPlan(hri, null, deadServerName));
      master.assignRegion(hri);
    }

    // wait for no more RIT
    log("Waiting for assignment to finish");
    ZKAssign.blockUntilNoRIT(zkw);
    log("Assignment completed");

    // Stop the master
    log("Aborting master");
    cluster.abortMaster(0);
    cluster.waitOnMaster(0);
    log("Master has aborted");

    /*
     * Now, let's start mocking up some weird states as described in the method
     * javadoc.
     */

    List<HRegionInfo> regionsThatShouldBeOnline = new ArrayList<HRegionInfo>();
    List<HRegionInfo> regionsThatShouldBeOffline = new ArrayList<HRegionInfo>();

    log("Beginning to mock scenarios");

    // Disable the disabledTable in ZK
    ZKTable zktable = new ZKTable(zkw);
    zktable.setDisabledTable(Bytes.toString(disabledTable));

    assertTrue(" The enabled table should be identified on master fail over.",
        zktable.isEnabledTable("enabledTable"));

    /*
     * ZK = CLOSING
     */

    // Region of enabled table being closed on dead RS but not finished
    HRegionInfo region = enabledAndOnDeadRegions.remove(0);
    regionsThatShouldBeOnline.add(region);
    ZKAssign.createNodeClosing(zkw, region, deadServerName);
    LOG.debug("\n\nRegion of enabled table was CLOSING on dead RS\n" +
        region + "\n\n");

    // Region of disabled table being closed on dead RS but not finished
    region = disabledAndOnDeadRegions.remove(0);
    regionsThatShouldBeOffline.add(region);
    ZKAssign.createNodeClosing(zkw, region, deadServerName);
    LOG.debug("\n\nRegion of disabled table was CLOSING on dead RS\n" +
        region + "\n\n");

    /*
     * ZK = CLOSED
     */

    // Region of enabled on dead server gets closed but not ack'd by master
    region = enabledAndOnDeadRegions.remove(0);
    regionsThatShouldBeOnline.add(region);
    int version = ZKAssign.createNodeClosing(zkw, region, deadServerName);
    ZKAssign.transitionNodeClosed(zkw, region, deadServerName, version);
    LOG.debug("\n\nRegion of enabled table was CLOSED on dead RS\n" +
        region + "\n\n");

    // Region of disabled on dead server gets closed but not ack'd by master
    region = disabledAndOnDeadRegions.remove(0);
    regionsThatShouldBeOffline.add(region);
    version = ZKAssign.createNodeClosing(zkw, region, deadServerName);
    ZKAssign.transitionNodeClosed(zkw, region, deadServerName, version);
    LOG.debug("\n\nRegion of disabled table was CLOSED on dead RS\n" +
        region + "\n\n");

    /*
     * ZK = OPENING
     */

    // RS was opening a region of enabled table then died
    region = enabledRegions.remove(0);
    regionsThatShouldBeOnline.add(region);
    ZKAssign.createNodeOffline(zkw, region, deadServerName);
    ZKAssign.transitionNodeOpening(zkw, region, deadServerName);
    LOG.debug("\n\nRegion of enabled table was OPENING on dead RS\n" +
        region + "\n\n");

    // RS was opening a region of disabled table then died
    region = disabledRegions.remove(0);
    regionsThatShouldBeOffline.add(region);
    ZKAssign.createNodeOffline(zkw, region, deadServerName);
    ZKAssign.transitionNodeOpening(zkw, region, deadServerName);
    LOG.debug("\n\nRegion of disabled table was OPENING on dead RS\n" +
        region + "\n\n");

    /*
     * ZK = OPENED
     */

    // Region of enabled table was opened on dead RS
    region = enabledRegions.remove(0);
    regionsThatShouldBeOnline.add(region);
    ZKAssign.createNodeOffline(zkw, region, deadServerName);
    hrsDead.openRegion(region);
    while (true) {
      RegionTransitionData rtd = ZKAssign.getData(zkw, region.getEncodedName());
      if (rtd != null && rtd.getEventType() == EventType.RS_ZK_REGION_OPENED) {
        break;
      }
      Thread.sleep(100);
    }
    LOG.debug("\n\nRegion of enabled table was OPENED on dead RS\n" +
        region + "\n\n");

    // Region of disabled table was opened on dead RS
    region = disabledRegions.remove(0);
    regionsThatShouldBeOffline.add(region);
    ZKAssign.createNodeOffline(zkw, region, deadServerName);
    hrsDead.openRegion(region);
    while (true) {
      RegionTransitionData rtd = ZKAssign.getData(zkw, region.getEncodedName());
      if (rtd != null && rtd.getEventType() == EventType.RS_ZK_REGION_OPENED) {
        break;
      }
      Thread.sleep(100);
    }
    LOG.debug("\n\nRegion of disabled table was OPENED on dead RS\n" +
        region + "\n\n");

    /*
     * ZK = NONE
     */

    // Region of enabled table was open at steady-state on dead RS
    region = enabledRegions.remove(0);
    regionsThatShouldBeOnline.add(region);
    ZKAssign.createNodeOffline(zkw, region, deadServerName);
    hrsDead.openRegion(region);
    while (true) {
      RegionTransitionData rtd = ZKAssign.getData(zkw, region.getEncodedName());
      if (rtd != null && rtd.getEventType() == EventType.RS_ZK_REGION_OPENED) {
        ZKAssign.deleteOpenedNode(zkw, region.getEncodedName());
        break;
      }
      Thread.sleep(100);
    }
    LOG.debug("\n\nRegion of enabled table was open at steady-state on dead RS"
        + "\n" + region + "\n\n");

    // Region of disabled table was open at steady-state on dead RS
    region = disabledRegions.remove(0);
    regionsThatShouldBeOffline.add(region);
    ZKAssign.createNodeOffline(zkw, region, deadServerName);
    hrsDead.openRegion(region);
    while (true) {
      RegionTransitionData rtd = ZKAssign.getData(zkw, region.getEncodedName());
      if (rtd != null && rtd.getEventType() == EventType.RS_ZK_REGION_OPENED) {
        ZKAssign.deleteOpenedNode(zkw, region.getEncodedName());
        break;
      }
      Thread.sleep(100);
    }
    LOG.debug("\n\nRegion of disabled table was open at steady-state on dead RS"
      + "\n" + region + "\n\n");

    /*
     * DONE MOCKING
     */

    log("Done mocking data up in ZK");

    // Kill the RS that had a hard death
    log("Killing RS " + deadServerName);
    hrsDead.abort("Killing for unit test");
    log("RS " + deadServerName + " killed");

    // Start up a new master.  Wait until regionserver is completely down
    // before starting new master because of hbase-4511.
    while (hrsDeadThread.isAlive()) {
      Threads.sleep(10);
    }
    log("Starting up a new master");
    master = cluster.startMaster().getMaster();
    log("Waiting for master to be ready");
    assertTrue(cluster.waitForActiveAndReadyMaster());
    log("Master is ready");

    // Let's add some weird states to master in-memory state

    // After HBASE-3181, we need to have some ZK state if we're PENDING_OPEN
    // b/c it is impossible for us to get into this state w/o a zk node
    // this is not true of PENDING_CLOSE

    // PENDING_OPEN and enabled
    region = enabledRegions.remove(0);
    regionsThatShouldBeOnline.add(region);
    master.assignmentManager.regionsInTransition.put(region.getEncodedName(),
        new RegionState(region, RegionState.State.PENDING_OPEN, 0, null));
    ZKAssign.createNodeOffline(zkw, region, master.getServerName());
    // PENDING_OPEN and disabled
    region = disabledRegions.remove(0);
    regionsThatShouldBeOffline.add(region);
    master.assignmentManager.regionsInTransition.put(region.getEncodedName(),
        new RegionState(region, RegionState.State.PENDING_OPEN, 0, null));
    ZKAssign.createNodeOffline(zkw, region, master.getServerName());
    // This test is bad.  It puts up a PENDING_CLOSE but doesn't say what
    // server we were PENDING_CLOSE against -- i.e. an entry in
    // AssignmentManager#regions.  W/o a server, we NPE trying to resend close.
    // In past, there was wonky logic that had us reassign region if no server
    // at tail of the unassign.  This was removed.  Commenting out for now.
    // TODO: Remove completely.
    /*
    // PENDING_CLOSE and enabled
    region = enabledRegions.remove(0);
    LOG.info("Setting PENDING_CLOSE enabled " + region.getEncodedName());
    regionsThatShouldBeOnline.add(region);
    master.assignmentManager.regionsInTransition.put(region.getEncodedName(),
      new RegionState(region, RegionState.State.PENDING_CLOSE, 0));
    // PENDING_CLOSE and disabled
    region = disabledRegions.remove(0);
    LOG.info("Setting PENDING_CLOSE disabled " + region.getEncodedName());
    regionsThatShouldBeOffline.add(region);
    master.assignmentManager.regionsInTransition.put(region.getEncodedName(),
      new RegionState(region, RegionState.State.PENDING_CLOSE, 0));
      */

    // Failover should be completed, now wait for no RIT
    log("Waiting for no more RIT");
    ZKAssign.blockUntilNoRIT(zkw);
    log("No more RIT in ZK");
    long now = System.currentTimeMillis();
    final long maxTime = 120000;
    boolean done = master.assignmentManager.waitUntilNoRegionsInTransition(maxTime);
    if (!done) {
      LOG.info("rit=" + master.assignmentManager.getRegionsInTransition());
    }
    long elapsed = System.currentTimeMillis() - now;
    assertTrue("Elapsed=" + elapsed + ", maxTime=" + maxTime + ", done=" + done,
      elapsed < maxTime);
    log("No more RIT in RIT map, doing final test verification");

    // Grab all the regions that are online across RSs
    Set<HRegionInfo> onlineRegions = new TreeSet<HRegionInfo>();
    for (JVMClusterUtil.RegionServerThread rst :
        cluster.getRegionServerThreads()) {
      try {
        onlineRegions.addAll(rst.getRegionServer().getOnlineRegions());
      } catch (org.apache.hadoop.hbase.regionserver.RegionServerStoppedException e) {
        LOG.info("Got RegionServerStoppedException", e);
      }
    }

    // Now, everything that should be online should be online
    for (HRegionInfo hri : regionsThatShouldBeOnline) {
      assertTrue("region=" + hri.getRegionNameAsString(), onlineRegions.contains(hri));
    }

    // Everything that should be offline should not be online
    for (HRegionInfo hri : regionsThatShouldBeOffline) {
      assertFalse(onlineRegions.contains(hri));
    }

    log("Done with verification, all passed, shutting down cluster");

    // Done, shutdown the cluster
    TEST_UTIL.shutdownMiniCluster();
  }

  HRegion createRegion(final HRegionInfo  hri, final Path rootdir, final Configuration c,
      final HTableDescriptor htd)
  throws IOException {
    HRegion r = HRegion.createHRegion(hri, rootdir, c, htd);
    // The above call to create a region will create an hlog file.  Each
    // log file create will also create a running thread to do syncing.  We need
    // to close out this log else we will have a running thread trying to sync
    // the file system continuously which is ugly when dfs is taken away at the
    // end of the test.
    HRegion.closeHRegion(r);
    return r;
  }

  // TODO: Next test to add is with testing permutations of the RIT or the RS
  //       killed are hosting ROOT and META regions.

  private void log(String string) {
    LOG.info("\n\n" + string + " \n\n");
  }

  @org.junit.Rule
  public org.apache.hadoop.hbase.ResourceCheckerJUnitRule cu =
    new org.apache.hadoop.hbase.ResourceCheckerJUnitRule();
}

