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
package org.apache.hadoop.hbase.regionserver.handler;

import java.io.IOException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.Server;
import org.apache.hadoop.hbase.executor.EventHandler;
import org.apache.hadoop.hbase.regionserver.HRegion;
import org.apache.hadoop.hbase.regionserver.RegionServerServices;
import org.apache.hadoop.hbase.zookeeper.ZKAssign;
import org.apache.zookeeper.KeeperException;

/**
 * Handles closing of a region on a region server.
 */
public class CloseRegionHandler extends EventHandler {
  // NOTE on priorities shutting down.  There are none for close. There are some
  // for open.  I think that is right.  On shutdown, we want the meta to close
  // before root and both to close after the user regions have closed.  What
  // about the case where master tells us to shutdown a catalog region and we
  // have a running queue of user regions to close?
  private static final Log LOG = LogFactory.getLog(CloseRegionHandler.class);

  private final int FAILED = -1;
  int expectedVersion = FAILED;

  private final RegionServerServices rsServices;

  private final HRegionInfo regionInfo;

  // If true, the hosting server is aborting.  Region close process is different
  // when we are aborting.
  private final boolean abort;

  // Update zk on closing transitions. Usually true.  Its false if cluster
  // is going down.  In this case, its the rs that initiates the region
  // close -- not the master process so state up in zk will unlikely be
  // CLOSING.
  private final boolean zk;

  // This is executed after receiving an CLOSE RPC from the master.
  public CloseRegionHandler(final Server server,
      final RegionServerServices rsServices, HRegionInfo regionInfo) {
    this(server, rsServices, regionInfo, false, true, -1);
  }

  /**
   * This method used internally by the RegionServer to close out regions.
   * @param server
   * @param rsServices
   * @param regionInfo
   * @param abort If the regionserver is aborting.
   * @param zk If the close should be noted out in zookeeper.
   */
  public CloseRegionHandler(final Server server,
      final RegionServerServices rsServices,
      final HRegionInfo regionInfo, final boolean abort, final boolean zk,
      final int versionOfClosingNode) {
    this(server, rsServices,  regionInfo, abort, zk, versionOfClosingNode,
      EventType.M_RS_CLOSE_REGION);
  }

  protected CloseRegionHandler(final Server server,
      final RegionServerServices rsServices, HRegionInfo regionInfo,
      boolean abort, final boolean zk, final int versionOfClosingNode,
      EventType eventType) {
    super(server, eventType);
    this.server = server;
    this.rsServices = rsServices;
    this.regionInfo = regionInfo;
    this.abort = abort;
    this.zk = zk;
    this.expectedVersion = versionOfClosingNode;
  }

  public HRegionInfo getRegionInfo() {
    return regionInfo;
  }

  @Override
  public void process() {
    try {
      String name = regionInfo.getRegionNameAsString();
      LOG.debug("Processing close of " + name);
      String encodedRegionName = regionInfo.getEncodedName();
      // Check that this region is being served here
      HRegion region = this.rsServices.getFromOnlineRegions(encodedRegionName);
      if (region == null) {
        LOG.warn("Received CLOSE for region " + name +
            " but currently not serving");
        return;
      }

      // Close the region
      try {
        // TODO: If we need to keep updating CLOSING stamp to prevent against
        // a timeout if this is long-running, need to spin up a thread?
        if (region.close(abort) == null) {
          // This region got closed.  Most likely due to a split. So instead
          // of doing the setClosedState() below, let's just ignore cont
          // The split message will clean up the master state.
          LOG.warn("Can't close region: was already closed during close(): " +
            regionInfo.getRegionNameAsString());
          return;
        }
      } catch (Throwable t) {
        // A throwable here indicates that we couldn't successfully flush the
        // memstore before closing. So, we need to abort the server and allow
        // the master to split our logs in order to recover the data.
        server.abort("Unrecoverable exception while closing region " +
          regionInfo.getRegionNameAsString() + ", still finishing close", t);
        throw new RuntimeException(t);
      }

      this.rsServices.removeFromOnlineRegions(regionInfo.getEncodedName());

      if (this.zk) {
        if (setClosedState(this.expectedVersion, region)) {
          LOG.debug("set region closed state in zk successfully for region " +
            name + " sn name: " + this.server.getServerName());
        } else {
          LOG.debug("set region closed state in zk unsuccessfully for region " +
            name + " sn name: " + this.server.getServerName());
        }
      }

      // Done!  Region is closed on this RS
      LOG.debug("Closed region " + region.getRegionNameAsString());
    } finally {
      this.rsServices.removeFromRegionsInTransition(this.regionInfo);
    }
  }

  /**
   * Transition ZK node to CLOSED
   * @param expectedVersion
   * @return If the state is set successfully
   */
  private boolean setClosedState(final int expectedVersion, final HRegion region) {
    try {
      if (ZKAssign.transitionNodeClosed(server.getZooKeeper(), regionInfo,
          server.getServerName(), expectedVersion) == FAILED) {
        LOG.warn("Completed the CLOSE of a region but when transitioning from " +
            " CLOSING to CLOSED got a version mismatch, someone else clashed " +
            "so now unassigning");
        region.close();
        return false;
      }
    } catch (NullPointerException e) {
      // I've seen NPE when table was deleted while close was running in unit tests.
      LOG.warn("NPE during close -- catching and continuing...", e);
      return false;
    } catch (KeeperException e) {
      LOG.error("Failed transitioning node from CLOSING to CLOSED", e);
      return false;
    } catch (IOException e) {
      LOG.error("Failed to close region after failing to transition", e);
      return false;
    }
    return true;
  }

}
