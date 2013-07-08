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
package org.apache.hadoop.hdfs.server.namenode;

import java.io.IOException;

import org.apache.hadoop.hdfs.protocol.HdfsConstants;
import org.apache.hadoop.hdfs.protocol.HdfsConstants.UpgradeAction;
import org.apache.hadoop.hdfs.server.common.HdfsServerConstants;
import org.apache.hadoop.hdfs.server.common.IncorrectVersionException;
import org.apache.hadoop.hdfs.server.common.UpgradeManager;
import org.apache.hadoop.hdfs.server.common.UpgradeStatusReport;
import org.apache.hadoop.hdfs.server.protocol.UpgradeCommand;

/**
 * Upgrade manager for name-nodes.
 *
 * Distributed upgrades for a name-node starts when the safe mode conditions 
 * are met and the name-node is about to exit it.
 * At this point the name-node enters manual safe mode which will remain
 * on until the upgrade is completed.
 * After that the name-nodes processes upgrade commands from data-nodes
 * and updates its status.
 */
class UpgradeManagerNamenode extends UpgradeManager {
  @Override
  public HdfsServerConstants.NodeType getType() {
    return HdfsServerConstants.NodeType.NAME_NODE;
  }

  private final FSNamesystem namesystem;

  UpgradeManagerNamenode(FSNamesystem namesystem) {
    this.namesystem = namesystem;    
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
    if(!upgradeState) {
      initializeUpgrade();
      if(!upgradeState) return false;
      // write new upgrade state into disk
      namesystem.getFSImage().getStorage().writeAll();
    }
    assert currentUpgrades != null : "currentUpgrades is null";
    this.broadcastCommand = currentUpgrades.first().startUpgrade();
    NameNode.LOG.info("\n   Distributed upgrade for NameNode version " 
        + getUpgradeVersion() + " to current LV " 
        + HdfsConstants.LAYOUT_VERSION + " is started.");
    return true;
  }

  synchronized UpgradeCommand processUpgradeCommand(UpgradeCommand command
                                                    ) throws IOException {
    if(NameNode.LOG.isDebugEnabled()) {
      NameNode.LOG.debug("\n   Distributed upgrade for NameNode version " 
          + getUpgradeVersion() + " to current LV " 
          + HdfsConstants.LAYOUT_VERSION + " is processing upgrade command: "
          + command.getAction() + " status = " + getUpgradeStatus() + "%");
    }
    if(currentUpgrades == null) {
      NameNode.LOG.info("Ignoring upgrade command: " 
          + command.getAction() + " version " + command.getVersion()
          + ". No distributed upgrades are currently running on the NameNode");
      return null;
    }
    UpgradeObjectNamenode curUO = (UpgradeObjectNamenode)currentUpgrades.first();
    if(command.getVersion() != curUO.getVersion())
      throw new IncorrectVersionException(command.getVersion(), 
          "UpgradeCommand", curUO.getVersion());
    UpgradeCommand reply = curUO.processUpgradeCommand(command);
    if(curUO.getUpgradeStatus() < 100) {
      return reply;
    }
    // current upgrade is done
    curUO.completeUpgrade();
    NameNode.LOG.info("\n   Distributed upgrade for NameNode version " 
        + curUO.getVersion() + " to current LV " 
        + HdfsConstants.LAYOUT_VERSION + " is complete.");
    // proceede with the next one
    currentUpgrades.remove(curUO);
    if(currentUpgrades.isEmpty()) { // all upgrades are done
      completeUpgrade();
    } else {  // start next upgrade
      curUO = (UpgradeObjectNamenode)currentUpgrades.first();
      this.broadcastCommand = curUO.startUpgrade();
    }
    return reply;
  }

  @Override
  public synchronized void completeUpgrade() throws IOException {
    // set and write new upgrade state into disk
    setUpgradeState(false, HdfsConstants.LAYOUT_VERSION);
    namesystem.getFSImage().getStorage().writeAll();
    currentUpgrades = null;
    broadcastCommand = null;
    namesystem.leaveSafeMode(false);
  }

  synchronized UpgradeStatusReport distributedUpgradeProgress
                                  (UpgradeAction action) throws IOException {
    boolean isFinalized = false;
    if(currentUpgrades == null) { // no upgrades are in progress
      FSImage fsimage = namesystem.getFSImage();
      isFinalized = fsimage.isUpgradeFinalized();
      if(isFinalized) // upgrade is finalized
        return null;  // nothing to report
      return new UpgradeStatusReport(fsimage.getStorage().getLayoutVersion(),
                                     (short)101, isFinalized);
    }
    UpgradeObjectNamenode curUO = (UpgradeObjectNamenode)currentUpgrades.first();
    boolean details = false;
    switch(action) {
    case GET_STATUS:
      break;
    case DETAILED_STATUS:
      details = true;
      break;
    case FORCE_PROCEED:
      curUO.forceProceed();
    }
    return curUO.getUpgradeStatusReport(details);
  }
}
