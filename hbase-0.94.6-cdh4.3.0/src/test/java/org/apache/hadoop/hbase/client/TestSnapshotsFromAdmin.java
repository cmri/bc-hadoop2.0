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

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.SmallTests;
import org.apache.hadoop.hbase.ipc.HMasterInterface;
import org.apache.hadoop.hbase.protobuf.generated.HBaseProtos.SnapshotDescription;
import org.apache.hadoop.hbase.snapshot.HSnapshotDescription;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.mockito.Mockito;

import com.google.protobuf.RpcController;

/**
 * Test snapshot logic from the client
 */
@Category(SmallTests.class)
public class TestSnapshotsFromAdmin {

  private static final Log LOG = LogFactory.getLog(TestSnapshotsFromAdmin.class);

  /**
   * Test that the logic for doing 'correct' back-off based on exponential increase and the max-time
   * passed from the server ensures the correct overall waiting for the snapshot to finish.
   * @throws Exception
   */
  @Test(timeout = 10000)
  public void testBackoffLogic() throws Exception {
    final int maxWaitTime = 7500;
    final int numRetries = 10;
    final int pauseTime = 500;
    // calculate the wait time, if we just do straight backoff (ignoring the expected time from
    // master)
    long ignoreExpectedTime = 0;
    for (int i = 0; i < 6; i++) {
      ignoreExpectedTime += HConstants.RETRY_BACKOFF[i] * pauseTime;
    }
    // the correct wait time, capping at the maxTime/tries + fudge room
    final long time = pauseTime * 3 + ((maxWaitTime / numRetries) * 3) + 300;
    assertTrue("Capped snapshot wait time isn't less that the uncapped backoff time "
        + "- further testing won't prove anything.", time < ignoreExpectedTime);

    // setup the mocks
    HConnectionManager.HConnectionImplementation mockConnection = Mockito
        .mock(HConnectionManager.HConnectionImplementation.class);
    Configuration conf = HBaseConfiguration.create();
    // setup the conf to match the expected properties
    conf.setInt("hbase.client.retries.number", numRetries);
    conf.setLong("hbase.client.pause", pauseTime);
    // mock the master admin to our mock
    HMasterInterface mockMaster = Mockito.mock(HMasterInterface.class);
    Mockito.when(mockConnection.getConfiguration()).thenReturn(conf);
    Mockito.when(mockConnection.getMaster()).thenReturn(mockMaster);
    // set the max wait time for the snapshot to complete
    Mockito
        .when(
          mockMaster.snapshot(
            Mockito.any(HSnapshotDescription.class))).thenReturn((long)maxWaitTime);
    // first five times, we return false, last we get success
    Mockito.when(
      mockMaster.isSnapshotDone(
        Mockito.any(HSnapshotDescription.class))).thenReturn(false, false,
          false, false, false, true);

    // setup the admin and run the test
    HBaseAdmin admin = new HBaseAdmin(mockConnection);
    String snapshot = "snasphot";
    String table = "table";
    // get start time
    long start = System.currentTimeMillis();
    admin.snapshot(snapshot, table);
    long finish = System.currentTimeMillis();
    long elapsed = (finish - start);
    assertTrue("Elapsed time:" + elapsed + " is more than expected max:" + time, elapsed <= time);
  }

  /**
   * Make sure that we validate the snapshot name and the table name before we pass anything across
   * the wire
   * @throws IOException on failure
   */
  @Test
  public void testValidateSnapshotName() throws IOException {
    HConnectionManager.HConnectionImplementation mockConnection = Mockito
        .mock(HConnectionManager.HConnectionImplementation.class);
    Configuration conf = HBaseConfiguration.create();
    Mockito.when(mockConnection.getConfiguration()).thenReturn(conf);
    HBaseAdmin admin = new HBaseAdmin(mockConnection);
    SnapshotDescription.Builder builder = SnapshotDescription.newBuilder();
    // check that invalid snapshot names fail
    failSnapshotStart(admin, builder.setName(".snapshot").build());
    failSnapshotStart(admin, builder.setName("-snapshot").build());
    failSnapshotStart(admin, builder.setName("snapshot fails").build());
    failSnapshotStart(admin, builder.setName("snap$hot").build());
    // check the table name also get verified
    failSnapshotStart(admin, builder.setName("snapshot").setTable(".table").build());
    failSnapshotStart(admin, builder.setName("snapshot").setTable("-table").build());
    failSnapshotStart(admin, builder.setName("snapshot").setTable("table fails").build());
    failSnapshotStart(admin, builder.setName("snapshot").setTable("tab%le").build());
  }

  private void failSnapshotStart(HBaseAdmin admin, SnapshotDescription snapshot) throws IOException {
    try {
      admin.snapshot(snapshot);
      fail("Snapshot should not have succeed with name:" + snapshot.getName());
    } catch (IllegalArgumentException e) {
      LOG.debug("Correctly failed to start snapshot:" + e.getMessage());
    }
  }
}
