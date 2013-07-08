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

package org.apache.hadoop.hbase;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.util.ReflectionUtils;

import java.io.IOException;

/**
 * Facility for <strong>integration/system</strong> tests. This extends {@link HBaseTestingUtility}
 * and adds-in the functionality needed by integration and system tests. This class understands
 * distributed and pseudo-distributed/local cluster deployments, and abstracts those from the tests
 * in this module.
 * <p>
 * IntegrationTestingUtility is constructed and used by the integration tests, but the tests
 * themselves should not assume a particular deployment. They can rely on the methods in this
 * class and HBaseCluster. Before the testing begins, the test should initialize the cluster by
 * calling {@link #initializeCluster(int)}.
 * <p>
 * The cluster that is used defaults to a mini cluster, but it can be forced to use a distributed
 * cluster by calling {@link #setUseDistributedCluster(Configuration)}. This method is invoked by
 * test drivers (maven, IntegrationTestsDriver, etc) before initializing the cluster
 * via {@link #initializeCluster(int)}. Individual tests should not directly call
 * {@link #setUseDistributedCluster(Configuration)}.
 */
public class IntegrationTestingUtility extends HBaseTestingUtility {

  public IntegrationTestingUtility() {
    this(HBaseConfiguration.create());
  }

  public IntegrationTestingUtility(Configuration conf) {
    super(conf);
  }

  /**
   * Configuration that controls whether this utility assumes a running/deployed cluster.
   * This is different than "hbase.cluster.distributed" since that parameter indicates whether the
   * cluster is in an actual distributed environment, while this shows that there is a
   * deployed (distributed or pseudo-distributed) cluster running, and we do not need to
   * start a mini-cluster for tests.
   */
  public static final String IS_DISTRIBUTED_CLUSTER = "hbase.test.cluster.distributed";

  /**
   * Initializes the state of the cluster. It starts a new in-process mini cluster, OR
   * if we are given an already deployed distributed cluster it initializes the state.
   * @param numSlaves Number of slaves to start up if we are booting a mini cluster. Otherwise
   * we check whether this many nodes are available and throw an exception if not.
   */
  public void initializeCluster(int numSlaves) throws Exception {
    if (isDistributedCluster()) {
      createDistributedHBaseCluster();
      checkNodeCount(numSlaves);
    } else {
      startMiniCluster(numSlaves);
    }
  }

  /**
   * Checks whether we have more than numSlaves nodes. Throws an
   * exception otherwise.
   */
  public void checkNodeCount(int numSlaves) throws Exception {
    HBaseCluster cluster = getHBaseClusterInterface();
    if (cluster.getClusterStatus().getServers().size() < numSlaves) {
      throw new Exception("Cluster does not have enough nodes:" + numSlaves);
    }
  }

  /**
   * Restores the cluster to the initial state if it is a distributed cluster, otherwise, shutdowns the
   * mini cluster.
   */
  public void restoreCluster() throws IOException {
    if (isDistributedCluster()) {
      getHBaseClusterInterface().restoreInitialStatus();
    } else {
      getMiniHBaseCluster().shutdown();
    }
  }

  /**
   * Sets the configuration property to use a distributed cluster for the integration tests. Test drivers
   * should use this to enforce cluster deployment.
   */
  public static void setUseDistributedCluster(Configuration conf) {
    conf.setBoolean(IS_DISTRIBUTED_CLUSTER, true);
    System.setProperty(IS_DISTRIBUTED_CLUSTER, "true");
  }

  /**
   * @return whether we are interacting with a distributed cluster as opposed to and in-process mini
   * cluster or a local cluster.
   * @see IntegrationTestingUtility#setUseDistributedCluster(Configuration)
   */
  private boolean isDistributedCluster() {
    Configuration conf = getConfiguration();
    boolean isDistributedCluster = false;
    isDistributedCluster = Boolean.parseBoolean(System.getProperty(IS_DISTRIBUTED_CLUSTER, "false"));
    if (!isDistributedCluster) {
      isDistributedCluster = conf.getBoolean(IS_DISTRIBUTED_CLUSTER, false);
    }
    return isDistributedCluster;
  }

  private void createDistributedHBaseCluster() throws IOException {
    Configuration conf = getConfiguration();
    Class<? extends ClusterManager> clusterManagerClass = conf.getClass(
      HConstants.HBASE_CLUSTER_MANAGER_CLASS, HBaseClusterManager.class,
      ClusterManager.class);
    ClusterManager clusterManager = ReflectionUtils.newInstance(
      clusterManagerClass, conf);
    setHBaseCluster(new DistributedHBaseCluster(conf, clusterManager));
    getHBaseAdmin();
  }

}
