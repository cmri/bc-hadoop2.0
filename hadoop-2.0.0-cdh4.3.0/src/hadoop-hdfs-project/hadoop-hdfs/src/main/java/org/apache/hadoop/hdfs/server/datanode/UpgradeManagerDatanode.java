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

import java.io.IOException;

import org.apache.hadoop.hdfs.protocol.HdfsConstants;
import org.apache.hadoop.hdfs.server.common.HdfsServerConstants;
import org.apache.hadoop.hdfs.server.common.UpgradeManager;
import org.apache.hadoop.hdfs.server.protocol.DatanodeProtocol;
import org.apache.hadoop.hdfs.server.protocol.NamespaceInfo;
import org.apache.hadoop.hdfs.server.protocol.UpgradeCommand;
import org.apache.hadoop.util.Daemon;

/**
 * Upgrade manager for data-nodes.
 *
 * Distributed upgrades for a data-node are performed in a separate thread.
 * The upgrade starts when the data-node receives the start upgrade command
 * from the namenode. At that point the manager finds a respective upgrade
 * object and starts a daemon in order to perform the upgrade defined by the 
 * object.
 */
class UpgradeManagerDatanode extends UpgradeManager {
  DataNode dataNode = null;
  Daemon upgradeDaemon = null;
  String bpid = null;

  UpgradeManagerDatanode(DataNode dataNode, String bpid) {
    super();
    this.dataNode = dataNode;
    this.bpid = bpid;
  }

  @Override
  public HdfsServerConstants.NodeType getType() {
    return HdfsServerConstants.NodeType.DATA_NODE;
  }

  synchronized void initializeUpgrade(NamespaceInfo nsInfo) throws IOException {
    if( ! super.initializeUpgrade())
      return; // distr upgrade is not needed
    DataNode.LOG.info("\n   Distributed upgrade for DataNode " 
        + dataNode.getDisplayName() 
        + " version " + getUpgradeVersion() + " to current LV " 
        + HdfsConstants.LAYOUT_VERSION + " is initialized.");
    UpgradeObjectDatanode curUO = (UpgradeObjectDatanode)currentUpgrades.first();
    curUO.setDatanode(dataNode, this.bpid);
    upgradeState = curUO.preUpgradeAction(nsInfo);
    // upgradeState is true if the data-node should start the upgrade itself
  }

  /**
   * Start distributed upgrade.
   * Instantiates distributed upgrade objects.
   * 
   * @return true if distributed upgrade is required or false otherwise
   * @throws IOException
   */
  @Override
  public synchronized boolean startUpgrade() throws IOException {
    if(upgradeState) {  // upgrade is already in progress
      assert currentUpgrades != null : 
        "UpgradeManagerDatanode.currentUpgrades is null.";
      UpgradeObjectDatanode curUO = (UpgradeObjectDatanode)currentUpgrades.first();
      curUO.startUpgrade();
      return true;
    }
    if(broadcastCommand != null) {
      if(broadcastCommand.getVersion() > this.getUpgradeVersion()) {
        // stop broadcasting, the cluster moved on
        // start upgrade for the next version
        broadcastCommand = null;
      } else {
        // the upgrade has been finished by this data-node,
        // but the cluster is still running it, 
        // reply with the broadcast command
        assert currentUpgrades == null : 
          "UpgradeManagerDatanode.currentUpgrades is not null.";
        assert upgradeDaemon == null : 
          "UpgradeManagerDatanode.upgradeDaemon is not null.";
        DatanodeProtocol nn = dataNode.getActiveNamenodeForBP(bpid);
        nn.processUpgradeCommand(broadcastCommand);
        return true;
      }
    }
    if(currentUpgrades == null)
      currentUpgrades = getDistributedUpgrades();
    if(currentUpgrades == null) {
      DataNode.LOG.info("\n   Distributed upgrade for DataNode version " 
          + getUpgradeVersion() + " to current LV " 
          + HdfsConstants.LAYOUT_VERSION + " cannot be started. "
          + "The upgrade object is not defined.");
      return false;
    }
    upgradeState = true;
    UpgradeObjectDatanode curUO = (UpgradeObjectDatanode)currentUpgrades.first();
    curUO.setDatanode(dataNode, this.bpid);
    curUO.startUpgrade();
    upgradeDaemon = new Daemon(curUO);
    upgradeDaemon.start();
    DataNode.LOG.info("\n   Distributed upgrade for DataNode " 
        + dataNode.getDisplayName() 
        + " version " + getUpgradeVersion() + " to current LV " 
        + HdfsConstants.LAYOUT_VERSION + " is started.");
    return true;
  }

  synchronized void processUpgradeCommand(UpgradeCommand command
                                          ) throws IOException {
    assert command.getAction() == UpgradeCommand.UC_ACTION_START_UPGRADE :
      "Only start upgrade action can be processed at this time.";
    this.upgradeVersion = command.getVersion();
    // Start distributed upgrade
    if(startUpgrade()) // upgrade started
      return;
    throw new IOException(
        "Distributed upgrade for DataNode " + dataNode.getDisplayName() 
        + " version " + getUpgradeVersion() + " to current LV " 
        + HdfsConstants.LAYOUT_VERSION + " cannot be started. "
        + "The upgrade object is not defined.");
  }

  @Override
  public synchronized void completeUpgrade() throws IOException {
    assert currentUpgrades != null : 
      "UpgradeManagerDatanode.currentUpgrades is null.";
    UpgradeObjectDatanode curUO = (UpgradeObjectDatanode)currentUpgrades.first();
    broadcastCommand = curUO.completeUpgrade();
    upgradeState = false;
    currentUpgrades = null;
    upgradeDaemon = null;
    DataNode.LOG.info("\n   Distributed upgrade for DataNode " 
        + dataNode.getDisplayName()
        + " version " + getUpgradeVersion() + " to current LV " 
        + HdfsConstants.LAYOUT_VERSION + " is complete.");
  }

  synchronized void shutdownUpgrade() {
    if(upgradeDaemon != null)
      upgradeDaemon.interrupt();
  }
}
