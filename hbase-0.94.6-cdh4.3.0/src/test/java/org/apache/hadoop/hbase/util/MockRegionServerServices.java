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
package org.apache.hadoop.hbase.util;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.catalog.CatalogTracker;
import org.apache.hadoop.hbase.fs.HFileSystem;
import org.apache.hadoop.hbase.ipc.RpcServer;
import org.apache.hadoop.hbase.regionserver.CompactionRequestor;
import org.apache.hadoop.hbase.regionserver.FlushRequester;
import org.apache.hadoop.hbase.regionserver.HRegion;
import org.apache.hadoop.hbase.regionserver.Leases;
import org.apache.hadoop.hbase.regionserver.RegionServerAccounting;
import org.apache.hadoop.hbase.regionserver.RegionServerServices;
import org.apache.hadoop.hbase.regionserver.wal.HLog;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.zookeeper.ZooKeeperWatcher;
import org.apache.zookeeper.KeeperException;

/**
 * Basic mock region server services.
 */
public class MockRegionServerServices implements RegionServerServices {
  private final Map<String, HRegion> regions = new HashMap<String, HRegion>();
  private boolean stopping = false;
  private final ConcurrentSkipListMap<byte[], Boolean> rit = 
    new ConcurrentSkipListMap<byte[], Boolean>(Bytes.BYTES_COMPARATOR);
  private HFileSystem hfs = null;

  @Override
  public boolean removeFromOnlineRegions(String encodedRegionName) {
    return this.regions.remove(encodedRegionName) != null;
  }

  @Override
  public HRegion getFromOnlineRegions(String encodedRegionName) {
    return this.regions.get(encodedRegionName);
  }

  public List<HRegion> getOnlineRegions(byte[] tableName) throws IOException {
    return null;
  }

  @Override
  public void addToOnlineRegions(HRegion r) {
    this.regions.put(r.getRegionInfo().getEncodedName(), r);
  }

  @Override
  public void postOpenDeployTasks(HRegion r, CatalogTracker ct, boolean daughter)
      throws KeeperException, IOException {
    addToOnlineRegions(r);
  }

  @Override
  public boolean isStopping() {
    return this.stopping;
  }

  @Override
  public HLog getWAL() {
    return null;
  }

  @Override
  public RpcServer getRpcServer() {
    return null;
  }

  @Override
  public FlushRequester getFlushRequester() {
    return null;
  }

  @Override
  public CompactionRequestor getCompactionRequester() {
    return null;
  }

  @Override
  public CatalogTracker getCatalogTracker() {
    return null;
  }

  @Override
  public ZooKeeperWatcher getZooKeeper() {
    return null;
  }
  
  public RegionServerAccounting getRegionServerAccounting() {
    return null;
  }

  @Override
  public ServerName getServerName() {
    return null;
  }

  @Override
  public Configuration getConfiguration() {
    return null;
  }

  @Override
  public void abort(String why, Throwable e) {
     //no-op
  }

  @Override
  public void stop(String why) {
    //no-op
  }

  @Override
  public boolean isStopped() {
    return false;
  }

  @Override
  public boolean isAborted() {
    return false;
  }

  @Override
  public HFileSystem getFileSystem() {
    return this.hfs;
  }

  public void setFileSystem(FileSystem hfs) {
    this.hfs = (HFileSystem)hfs;
  }

  @Override
  public Leases getLeases() {
    return null;
  }

  @Override
  public boolean removeFromRegionsInTransition(HRegionInfo hri) {
    // TODO Auto-generated method stub
    return false;
  }

  @Override
  public boolean containsKeyInRegionsInTransition(HRegionInfo hri) {
    // TODO Auto-generated method stub
    return false;
  }
}
