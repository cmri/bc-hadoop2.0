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
package org.apache.hadoop.hbase.master.snapshot;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.errorhandling.ForeignException;
import org.apache.hadoop.hbase.errorhandling.TimeoutExceptionInjector;
import org.apache.hadoop.hbase.master.MasterServices;
import org.apache.hadoop.hbase.master.metrics.MasterMetrics;
import org.apache.hadoop.hbase.monitoring.MonitoredTask;
import org.apache.hadoop.hbase.monitoring.TaskMonitor;
import org.apache.hadoop.hbase.protobuf.generated.HBaseProtos.SnapshotDescription;
import org.apache.hadoop.hbase.regionserver.HRegion;
import org.apache.hadoop.hbase.snapshot.CopyRecoveredEditsTask;
import org.apache.hadoop.hbase.snapshot.ReferenceRegionHFilesTask;
import org.apache.hadoop.hbase.snapshot.SnapshotDescriptionUtils;
import org.apache.hadoop.hbase.snapshot.TableInfoCopyTask;
import org.apache.hadoop.hbase.snapshot.TakeSnapshotUtils;
import org.apache.hadoop.hbase.util.FSUtils;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.zookeeper.KeeperException;

/**
 * Take a snapshot of a disabled table.
 * <p>
 * Table must exist when taking the snapshot, or results are undefined.
 */
@InterfaceAudience.Private
@InterfaceStability.Evolving
public class DisabledTableSnapshotHandler extends TakeSnapshotHandler {
  private static final Log LOG = LogFactory.getLog(DisabledTableSnapshotHandler.class);
  private final TimeoutExceptionInjector timeoutInjector;

  /**
   * @param snapshot descriptor of the snapshot to take
   * @param masterServices master services provider
   * @throws IOException on unexpected error
   */
  public DisabledTableSnapshotHandler(SnapshotDescription snapshot,
      final MasterServices masterServices, final MasterMetrics metricsMaster) throws IOException {
    super(snapshot, masterServices, metricsMaster);

    // setup the timer
    timeoutInjector = TakeSnapshotUtils.getMasterTimerAndBindToMonitor(snapshot, conf, monitor);
  }

  // TODO consider parallelizing these operations since they are independent. Right now its just
  // easier to keep them serial though
  @Override
  public void snapshotRegions(List<Pair<HRegionInfo, ServerName>> regionsAndLocations)
      throws IOException, KeeperException {
    try {
      timeoutInjector.start();

      // 1. get all the regions hosting this table.

      // extract each pair to separate lists
      Set<String> serverNames = new HashSet<String>();
      Set<HRegionInfo> regions = new HashSet<HRegionInfo>();
      for (Pair<HRegionInfo, ServerName> p : regionsAndLocations) {
        regions.add(p.getFirst());
        serverNames.add(p.getSecond().toString());
      }

      // 2. for each region, write all the info to disk
      LOG.info("Starting to write region info and WALs for regions for offline snapshot:"
          + SnapshotDescriptionUtils.toString(snapshot));
      for (HRegionInfo regionInfo : regions) {
        // 2.1 copy the regionInfo files to the snapshot
        Path snapshotRegionDir = TakeSnapshotUtils.getRegionSnapshotDirectory(snapshot, rootDir,
          regionInfo.getEncodedName());
        HRegion.writeRegioninfoOnFilesystem(regionInfo, snapshotRegionDir, fs, conf);
        // check for error for each region
        monitor.rethrowException();

        // 2.2 for each region, copy over its recovered.edits directory
        Path regionDir = HRegion.getRegionDir(rootDir, regionInfo);
        new CopyRecoveredEditsTask(snapshot, monitor, fs, regionDir, snapshotRegionDir).call();
        monitor.rethrowException();
        status.setStatus("Completed copying recovered edits for offline snapshot of table: "
            + snapshot.getTable());

        // 2.3 reference all the files in the region
        new ReferenceRegionHFilesTask(snapshot, monitor, regionDir, fs, snapshotRegionDir).call();
        monitor.rethrowException();
        status.setStatus("Completed referencing HFiles for offline snapshot of table: " +
          snapshot.getTable());
      }

      // 3. write the table info to disk
      LOG.info("Starting to copy tableinfo for offline snapshot: " +
      SnapshotDescriptionUtils.toString(snapshot));
      TableInfoCopyTask tableInfoCopyTask = new TableInfoCopyTask(this.monitor, snapshot, fs,
          FSUtils.getRootDir(conf));
      tableInfoCopyTask.call();
      monitor.rethrowException();
      status.setStatus("Finished copying tableinfo for snapshot of table: " + snapshot.getTable());
    } catch (Exception e) {
      // make sure we capture the exception to propagate back to the client later
      String reason = "Failed snapshot " + SnapshotDescriptionUtils.toString(snapshot)
          + " due to exception:" + e.getMessage();
      ForeignException ee = new ForeignException(reason, e);
      monitor.receive(ee);
      status.abort("Snapshot of table: "+ snapshot.getTable() +" failed because " + e.getMessage());
    } finally {
      LOG.debug("Marking snapshot" + SnapshotDescriptionUtils.toString(snapshot)
          + " as finished.");

      // 6. mark the timer as finished - even if we got an exception, we don't need to time the
      // operation any further
      timeoutInjector.complete();
    }
  }
}
