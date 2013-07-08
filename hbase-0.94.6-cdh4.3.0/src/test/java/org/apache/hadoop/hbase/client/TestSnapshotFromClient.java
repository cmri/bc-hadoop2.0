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
package org.apache.hadoop.hbase.client;

import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.LargeTests;
import org.apache.hadoop.hbase.TableNotFoundException;
import org.apache.hadoop.hbase.master.snapshot.SnapshotManager;
import org.apache.hadoop.hbase.protobuf.generated.HBaseProtos.SnapshotDescription;
import org.apache.hadoop.hbase.regionserver.ConstantSizeRegionSplitPolicy;
import org.apache.hadoop.hbase.snapshot.HSnapshotDescription;
import org.apache.hadoop.hbase.snapshot.SnapshotCreationException;
import org.apache.hadoop.hbase.snapshot.SnapshotTestingUtils;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.FSUtils;
import org.apache.hadoop.hbase.util.JVMClusterUtil.RegionServerThread;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Test create/using/deleting snapshots from the client
 * <p>
 * This is an end-to-end test for the snapshot utility
 */
@Category(LargeTests.class)
public class TestSnapshotFromClient {
  private static final Log LOG = LogFactory.getLog(TestSnapshotFromClient.class);
  private static final HBaseTestingUtility UTIL = new HBaseTestingUtility();
  private static final int NUM_RS = 2;
  private static final String STRING_TABLE_NAME = "test";
  private static final byte[] TEST_FAM = Bytes.toBytes("fam");
  private static final byte[] TABLE_NAME = Bytes.toBytes(STRING_TABLE_NAME);

  /**
   * Setup the config for the cluster
   * @throws Exception on failure
   */
  @BeforeClass
  public static void setupCluster() throws Exception {
    setupConf(UTIL.getConfiguration());
    UTIL.startMiniCluster(NUM_RS);
  }

  private static void setupConf(Configuration conf) {
    // disable the ui
    conf.setInt("hbase.regionsever.info.port", -1);
    // change the flush size to a small amount, regulating number of store files
    conf.setInt("hbase.hregion.memstore.flush.size", 25000);
    // so make sure we get a compaction when doing a load, but keep around some
    // files in the store
    conf.setInt("hbase.hstore.compaction.min", 10);
    conf.setInt("hbase.hstore.compactionThreshold", 10);
    // block writes if we get to 12 store files
    conf.setInt("hbase.hstore.blockingStoreFiles", 12);
    // drop the number of attempts for the hbase admin
    conf.setInt("hbase.client.retries.number", 1);
    // Enable snapshot
    conf.setBoolean(SnapshotManager.HBASE_SNAPSHOT_ENABLED, true);
    // prevent aggressive region split
    conf.set(HConstants.HBASE_REGION_SPLIT_POLICY_KEY,
      ConstantSizeRegionSplitPolicy.class.getName());
  }

  @Before
  public void setup() throws Exception {
    UTIL.createTable(TABLE_NAME, TEST_FAM);
  }

  @After
  public void tearDown() throws Exception {
    UTIL.deleteTable(TABLE_NAME);
    // and cleanup the archive directory
    try {
      UTIL.getTestFileSystem().delete(new Path(UTIL.getDefaultRootDirPath(), ".archive"), true);
    } catch (IOException e) {
      LOG.warn("Failure to delete archive directory", e);
    }
  }

  @AfterClass
  public static void cleanupTest() throws Exception {
    try {
      UTIL.shutdownMiniCluster();
    } catch (Exception e) {
      LOG.warn("failure shutting down cluster", e);
    }
  }

  /**
   * Test snapshotting not allowed .META. and -ROOT-
   * @throws Exception
   */
  @Test
  public void testMetaTablesSnapshot() throws Exception {
    HBaseAdmin admin = UTIL.getHBaseAdmin();
    byte[] snapshotName = Bytes.toBytes("metaSnapshot");

    try {
      admin.snapshot(snapshotName, HConstants.META_TABLE_NAME);
      fail("taking a snapshot of .META. should not be allowed");
    } catch (IllegalArgumentException e) {
      // expected
    }

    try {
      admin.snapshot(snapshotName, HConstants.ROOT_TABLE_NAME);
      fail("taking a snapshot of -ROOT- should not be allowed");
    } catch (IllegalArgumentException e) {
      // expected
    }
  }

  /**
   * Test snapshotting a table that is offline
   * @throws Exception
   */
  @Test
  public void testOfflineTableSnapshot() throws Exception {
    HBaseAdmin admin = UTIL.getHBaseAdmin();
    // make sure we don't fail on listing snapshots
    SnapshotTestingUtils.assertNoSnapshots(admin);

    // put some stuff in the table
    HTable table = new HTable(UTIL.getConfiguration(), TABLE_NAME);
    UTIL.loadTable(table, TEST_FAM);

    // get the name of all the regionservers hosting the snapshotted table
    Set<String> snapshotServers = new HashSet<String>();
    List<RegionServerThread> servers = UTIL.getMiniHBaseCluster().getLiveRegionServerThreads();
    for (RegionServerThread server : servers) {
      if (server.getRegionServer().getOnlineRegions(TABLE_NAME).size() > 0) {
        snapshotServers.add(server.getRegionServer().getServerName().toString());
      }
    }

    LOG.debug("FS state before disable:");
    FSUtils.logFileSystemState(UTIL.getTestFileSystem(),
      FSUtils.getRootDir(UTIL.getConfiguration()), LOG);
    // XXX if this is flakey, might want to consider using the async version and looping as
    // disableTable can succeed and still timeout.
    admin.disableTable(TABLE_NAME);

    LOG.debug("FS state before snapshot:");
    FSUtils.logFileSystemState(UTIL.getTestFileSystem(),
      FSUtils.getRootDir(UTIL.getConfiguration()), LOG);

    // take a snapshot of the disabled table
    byte[] snapshot = Bytes.toBytes("offlineTableSnapshot");
    admin.snapshot(snapshot, TABLE_NAME);
    LOG.debug("Snapshot completed.");

    // make sure we have the snapshot
    List<SnapshotDescription> snapshots = SnapshotTestingUtils.assertOneSnapshotThatMatches(admin,
      snapshot, TABLE_NAME);

    // make sure its a valid snapshot
    FileSystem fs = UTIL.getHBaseCluster().getMaster().getMasterFileSystem().getFileSystem();
    Path rootDir = UTIL.getHBaseCluster().getMaster().getMasterFileSystem().getRootDir();
    LOG.debug("FS state after snapshot:");
    FSUtils.logFileSystemState(UTIL.getTestFileSystem(),
      FSUtils.getRootDir(UTIL.getConfiguration()), LOG);

    SnapshotTestingUtils.confirmSnapshotValid(snapshots.get(0), TABLE_NAME, TEST_FAM, rootDir,
      admin, fs, false, new Path(rootDir, HConstants.HREGION_LOGDIR_NAME), snapshotServers);

    admin.deleteSnapshot(snapshot);
    snapshots = admin.listSnapshots();
    SnapshotTestingUtils.assertNoSnapshots(admin);
  }

  @Test
  public void testSnapshotFailsOnNonExistantTable() throws Exception {
    HBaseAdmin admin = UTIL.getHBaseAdmin();
    // make sure we don't fail on listing snapshots
    SnapshotTestingUtils.assertNoSnapshots(admin);
    String tableName = "_not_a_table";

    // make sure the table doesn't exist
    boolean fail = false;
    do {
    try {
      admin.getTableDescriptor(Bytes.toBytes(tableName));
      fail = true;
          LOG.error("Table:" + tableName + " already exists, checking a new name");
      tableName = tableName+"!";
    } catch (TableNotFoundException e) {
      fail = false;
      }
    } while (fail);

    // snapshot the non-existant table
    try {
      admin.snapshot("fail", tableName);
      fail("Snapshot succeeded even though there is not table.");
    } catch (SnapshotCreationException e) {
      LOG.info("Correctly failed to snapshot a non-existant table:" + e.getMessage());
    }
  }
}
