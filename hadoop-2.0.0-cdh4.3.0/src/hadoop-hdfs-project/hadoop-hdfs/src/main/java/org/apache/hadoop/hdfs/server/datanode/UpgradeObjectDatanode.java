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
package org.apache.hadoop.hdfs.server.datanode;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.hdfs.protocol.HdfsConstants;
import org.apache.hadoop.hdfs.server.common.HdfsServerConstants;
import org.apache.hadoop.hdfs.server.common.UpgradeObject;
import org.apache.hadoop.hdfs.server.protocol.DatanodeProtocol;
import org.apache.hadoop.hdfs.server.protocol.NamespaceInfo;
import org.apache.hadoop.hdfs.server.protocol.UpgradeCommand;
import java.io.IOException;
import java.net.SocketTimeoutException;

/**
 * Base class for data-node upgrade objects.
 * Data-node upgrades are run in separate threads.
 */
@InterfaceAudience.Private
public abstract class UpgradeObjectDatanode extends UpgradeObject implements Runnable {
  private DataNode dataNode = null;
  private String bpid = null;

  @Override
  public HdfsServerConstants.NodeType getType() {
    return HdfsServerConstants.NodeType.DATA_NODE;
  }

  protected DataNode getDatanode() {
    return dataNode;
  }
  
  protected DatanodeProtocol getNamenode() throws IOException {
    return dataNode.getActiveNamenodeForBP(bpid);
  }

  void setDatanode(DataNode dataNode, String bpid) {
    this.dataNode = dataNode;
    this.bpid = bpid;
  }

  /**
   * Specifies how the upgrade is performed. 
   * @throws IOException
   */
  public abstract void doUpgrade() throws IOException;

  /**
   * Specifies what to do before the upgrade is started.
   * 
   * The default implementation checks whether the data-node missed the upgrade
   * and throws an exception if it did. This leads to the data-node shutdown.
   * 
   * Data-nodes usually start distributed upgrade when the name-node replies
   * to its heartbeat with a start upgrade command.
   * Sometimes though, e.g. when a data-node missed the upgrade and wants to
   * catchup with the rest of the cluster, it is necessary to initiate the 
   * upgrade directly on the data-node, since the name-node might not ever 
   * start it. An override of this method should then return true.
   * And the upgrade will start after data-ndoe registration but before sending
   * its first heartbeat.
   * 
   * @param nsInfo name-node versions, verify that the upgrade
   * object can talk to this name-node version if necessary.
   * 
   * @throws IOException
   * @return true if data-node itself should start the upgrade or 
   * false if it should wait until the name-node starts the upgrade.
   */
  boolean preUpgradeAction(NamespaceInfo nsInfo) throws IOException {
    int nsUpgradeVersion = nsInfo.getDistributedUpgradeVersion();
    if(nsUpgradeVersion >= getVersion())
      return false; // name-node will perform the upgrade
    // Missed the upgrade. Report problem to the name-node and throw exception
    String errorMsg = 
              "\n   Data-node missed a distributed upgrade and will shutdown."
            + "\n   " + getDescription() + "."
            + " Name-node version = " + nsInfo.getLayoutVersion() + ".";
    DataNode.LOG.fatal( errorMsg );
    String bpid = nsInfo.getBlockPoolID();
    dataNode.trySendErrorReport(bpid, DatanodeProtocol.NOTIFY, errorMsg);
    throw new IOException(errorMsg);
  }

  @Override
  public void run() {
    assert dataNode != null : "UpgradeObjectDatanode.dataNode is null";
    while(dataNode.shouldRun) {
      try {
        doUpgrade();
      } catch(Exception e) {
        DataNode.LOG.error("Exception in doUpgrade", e);
      }
      break;
    }

    // report results
    if(getUpgradeStatus() < 100) {
      DataNode.LOG.info("\n   Distributed upgrade for DataNode version " 
          + getVersion() + " to current LV " 
          + HdfsConstants.LAYOUT_VERSION + " cannot be completed.");
    }

    // Complete the upgrade by calling the manager method
    try {
      UpgradeManagerDatanode upgradeManager = 
        dataNode.getUpgradeManagerDatanode(bpid);
      if(upgradeManager != null)
        upgradeManager.completeUpgrade();
    } catch(IOException e) {
      DataNode.LOG.error("Exception in completeUpgrade", e);
    }
  }

  /**
   * Complete upgrade and return a status complete command for broadcasting.
   * 
   * Data-nodes finish upgrade at different times.
   * The data-node needs to re-confirm with the name-node that the upgrade
   * is complete while other nodes are still upgrading.
   */
  @Override
  public UpgradeCommand completeUpgrade() throws IOException {
    return new UpgradeCommand(UpgradeCommand.UC_ACTION_REPORT_STATUS,
                              getVersion(), (short)100);
  }
}
