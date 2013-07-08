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
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.*;
import org.apache.hadoop.hbase.client.MetaScanner;
import org.apache.hadoop.hbase.executor.EventHandler.EventType;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.Threads;
import org.apache.hadoop.hbase.zookeeper.ZKAssign;
import org.apache.hadoop.hbase.zookeeper.ZKUtil;
import org.apache.hadoop.hbase.zookeeper.ZooKeeperWatcher;
import org.junit.After;
import org.junit.Test;
import org.junit.experimental.categories.Category;

@Category(LargeTests.class)
public class TestRestartCluster {
  private static final Log LOG = LogFactory.getLog(TestRestartCluster.class);
  private HBaseTestingUtility UTIL = new HBaseTestingUtility();

  private static final byte[] TABLENAME = Bytes.toBytes("master_transitions");
  private static final byte [][] FAMILIES = {Bytes.toBytes("a")};
  private static final byte [][] TABLES = {
      Bytes.toBytes("restartTableOne"),
      Bytes.toBytes("restartTableTwo"),
      Bytes.toBytes("restartTableThree")
  };
  private static final byte [] FAMILY = Bytes.toBytes("family");

  @After public void tearDown() throws Exception {
    UTIL.shutdownMiniCluster();
  }

  @Test (timeout=300000) public void testRestartClusterAfterKill()
  throws Exception {
    UTIL.startMiniZKCluster();
    ZooKeeperWatcher zooKeeper =
      new ZooKeeperWatcher(UTIL.getConfiguration(), "cluster1", null, true);

    // create the unassigned region, throw up a region opened state for META
    String unassignedZNode = zooKeeper.assignmentZNode;
    ZKUtil.createAndFailSilent(zooKeeper, unassignedZNode);

    ServerName sn = new ServerName(HMaster.MASTER, -1, System.currentTimeMillis());

    ZKAssign.createNodeOffline(zooKeeper, HRegionInfo.ROOT_REGIONINFO, sn);

    ZKAssign.createNodeOffline(zooKeeper, HRegionInfo.FIRST_META_REGIONINFO, sn);

    LOG.debug("Created UNASSIGNED zNode for ROOT and META regions in state " +
        EventType.M_ZK_REGION_OFFLINE);

    // start the HB cluster
    LOG.info("Starting HBase cluster...");
    UTIL.startMiniCluster(2);

    UTIL.createTable(TABLENAME, FAMILIES);
    LOG.info("Created a table, waiting for table to be available...");
    UTIL.waitTableAvailable(TABLENAME, 60*1000);

    LOG.info("Master deleted unassigned region and started up successfully.");
  }

  @Test (timeout=300000)
  public void testClusterRestart() throws Exception {
    UTIL.startMiniCluster(3);
    while (!UTIL.getMiniHBaseCluster().getMaster().isInitialized()) {
      Threads.sleep(1);
    }
    LOG.info("\n\nCreating tables");
    for(byte [] TABLE : TABLES) {
      UTIL.createTable(TABLE, FAMILY);
    }
    for(byte [] TABLE : TABLES) {
      UTIL.waitTableAvailable(TABLE, 30000);
    }

    List<HRegionInfo> allRegions =
      MetaScanner.listAllRegions(UTIL.getConfiguration());
    assertEquals(3, allRegions.size());

    LOG.info("\n\nShutting down cluster");
    UTIL.shutdownMiniHBaseCluster();

    LOG.info("\n\nSleeping a bit");
    Thread.sleep(2000);

    LOG.info("\n\nStarting cluster the second time");
    UTIL.restartHBaseCluster(3);

    // Need to use a new 'Configuration' so we make a new HConnection.
    // Otherwise we're reusing an HConnection that has gone stale because
    // the shutdown of the cluster also called shut of the connection.
    allRegions = MetaScanner.
      listAllRegions(new Configuration(UTIL.getConfiguration()));
    assertEquals(3, allRegions.size());

    LOG.info("\n\nWaiting for tables to be available");
    for(byte [] TABLE: TABLES) {
      try {
        UTIL.createTable(TABLE, FAMILY);
        assertTrue("Able to create table that should already exist", false);
      } catch(TableExistsException tee) {
        LOG.info("Table already exists as expected");
      }
      UTIL.waitTableAvailable(TABLE, 30000);
    }
  }

  @org.junit.Rule
  public org.apache.hadoop.hbase.ResourceCheckerJUnitRule cu =
    new org.apache.hadoop.hbase.ResourceCheckerJUnitRule();
}

