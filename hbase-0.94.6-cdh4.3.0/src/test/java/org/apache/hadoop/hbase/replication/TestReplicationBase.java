/*
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
package org.apache.hadoop.hbase.replication;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.replication.ReplicationAdmin;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.zookeeper.MiniZooKeeperCluster;
import org.apache.hadoop.hbase.zookeeper.ZooKeeperWatcher;
import org.junit.AfterClass;
import org.junit.BeforeClass;

/**
 * This class is only a base for other integration-level replication tests.
 * Do not add tests here.
 * TestReplicationSmallTests is where tests that don't require bring machines up/down should go
 * All other tests should have their own classes and extend this one
 */
public class TestReplicationBase {

  private static final Log LOG = LogFactory.getLog(TestReplicationBase.class);

  protected static Configuration conf1 = HBaseConfiguration.create();
  protected static Configuration conf2;
  protected static Configuration CONF_WITH_LOCALFS;

  protected static ZooKeeperWatcher zkw1;
  protected static ZooKeeperWatcher zkw2;

  protected static ReplicationAdmin admin;

  protected static HTable htable1;
  protected static HTable htable2;

  protected static HBaseTestingUtility utility1;
  protected static HBaseTestingUtility utility2;
  protected static final int NB_ROWS_IN_BATCH = 100;
  protected static final int NB_ROWS_IN_BIG_BATCH =
      NB_ROWS_IN_BATCH * 10;
  protected static final long SLEEP_TIME = 1000;
  protected static final int NB_RETRIES = 15;
  protected static final int NB_RETRIES_FOR_BIG_BATCH = 30;

  protected static final byte[] tableName = Bytes.toBytes("test");
  protected static final byte[] famName = Bytes.toBytes("f");
  protected static final byte[] row = Bytes.toBytes("row");
  protected static final byte[] noRepfamName = Bytes.toBytes("norep");

  /**
   * @throws java.lang.Exception
   */
  @BeforeClass
  public static void setUpBeforeClass() throws Exception {
    conf1.set(HConstants.ZOOKEEPER_ZNODE_PARENT, "/1");
    // smaller log roll size to trigger more events
    conf1.setFloat("hbase.regionserver.logroll.multiplier", 0.0003f);
    conf1.setInt("replication.source.size.capacity", 1024);
    conf1.setLong("replication.source.sleepforretries", 100);
    conf1.setInt("hbase.regionserver.maxlogs", 10);
    conf1.setLong("hbase.master.logcleaner.ttl", 10);
    conf1.setInt("zookeeper.recovery.retry", 1);
    conf1.setInt("zookeeper.recovery.retry.intervalmill", 10);
    conf1.setBoolean(HConstants.REPLICATION_ENABLE_KEY, true);
    conf1.setBoolean("dfs.support.append", true);
    conf1.setLong(HConstants.THREAD_WAKE_FREQUENCY, 100);
    conf1.setInt("replication.stats.thread.period.seconds", 5);
    conf1.setInt("replication.stats.thread.period.seconds", 5);

    utility1 = new HBaseTestingUtility(conf1);
    utility1.startMiniZKCluster();
    MiniZooKeeperCluster miniZK = utility1.getZkCluster();
    // Have to reget conf1 in case zk cluster location different
    // than default
    conf1 = utility1.getConfiguration();  
    zkw1 = new ZooKeeperWatcher(conf1, "cluster1", null, true);
    admin = new ReplicationAdmin(conf1);
    LOG.info("Setup first Zk");

    // Base conf2 on conf1 so it gets the right zk cluster.
    conf2 = HBaseConfiguration.create(conf1);
    conf2.set(HConstants.ZOOKEEPER_ZNODE_PARENT, "/2");
    conf2.setInt("hbase.client.retries.number", 6);
    conf2.setBoolean(HConstants.REPLICATION_ENABLE_KEY, true);
    conf2.setBoolean("dfs.support.append", true);

    utility2 = new HBaseTestingUtility(conf2);
    utility2.setZkCluster(miniZK);
    zkw2 = new ZooKeeperWatcher(conf2, "cluster2", null, true);

    admin.addPeer("2", utility2.getClusterKey());
    setIsReplication(true);

    LOG.info("Setup second Zk");
    CONF_WITH_LOCALFS = HBaseConfiguration.create(conf1);
    utility1.startMiniCluster(2);
    utility2.startMiniCluster(2);

    HTableDescriptor table = new HTableDescriptor(tableName);
    HColumnDescriptor fam = new HColumnDescriptor(famName);
    fam.setScope(HConstants.REPLICATION_SCOPE_GLOBAL);
    table.addFamily(fam);
    fam = new HColumnDescriptor(noRepfamName);
    table.addFamily(fam);
    HBaseAdmin admin1 = new HBaseAdmin(conf1);
    HBaseAdmin admin2 = new HBaseAdmin(conf2);
    admin1.createTable(table, HBaseTestingUtility.KEYS_FOR_HBA_CREATE_TABLE);
    admin2.createTable(table);
    htable1 = new HTable(conf1, tableName);
    htable1.setWriteBufferSize(1024);
    htable2 = new HTable(conf2, tableName);
  }

  protected static void setIsReplication(boolean rep) throws Exception {
    LOG.info("Set rep " + rep);
    admin.setReplicating(rep);
    Thread.sleep(SLEEP_TIME);
  }

  /**
   * @throws java.lang.Exception
   */
  @AfterClass
  public static void tearDownAfterClass() throws Exception {
    utility2.shutdownMiniCluster();
    utility1.shutdownMiniCluster();
  }


}

