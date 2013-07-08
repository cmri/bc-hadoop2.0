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
package org.apache.hadoop.hbase.snapshot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.SmallTests;
import org.apache.hadoop.hbase.catalog.CatalogTracker;
import org.apache.hadoop.hbase.client.HConnection;
import org.apache.hadoop.hbase.client.HConnectionTestingUtility;
import org.apache.hadoop.hbase.errorhandling.ForeignExceptionDispatcher;
import org.apache.hadoop.hbase.io.HFileLink;
import org.apache.hadoop.hbase.monitoring.MonitoredTask;
import org.apache.hadoop.hbase.protobuf.generated.HBaseProtos.SnapshotDescription;
import org.apache.hadoop.hbase.regionserver.HRegion;
import org.apache.hadoop.hbase.regionserver.StoreFile;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.FSTableDescriptors;
import org.apache.hadoop.hbase.util.FSUtils;
import org.apache.hadoop.hbase.util.MD5Hash;
import org.junit.*;
import org.junit.experimental.categories.Category;
import org.mockito.Mockito;

/**
 * Test the restore/clone operation from a file-system point of view.
 */
@Category(SmallTests.class)
public class TestRestoreSnapshotHelper {
  final Log LOG = LogFactory.getLog(getClass());

  private final static HBaseTestingUtility TEST_UTIL = new HBaseTestingUtility();
  private final static String TEST_FAMILY = "cf";
  private final static String TEST_HFILE = "abc";

  private Configuration conf;
  private Path archiveDir;
  private FileSystem fs;
  private Path rootDir;

  @Before
  public void setup() throws Exception {
    rootDir = TEST_UTIL.getDataTestDir("testRestore");
    archiveDir = new Path(rootDir, HConstants.HFILE_ARCHIVE_DIRECTORY);
    fs = TEST_UTIL.getTestFileSystem();
    conf = TEST_UTIL.getConfiguration();
    FSUtils.setRootDir(conf, rootDir);
  }

  @After
  public void tearDown() throws Exception {
    fs.delete(TEST_UTIL.getDataTestDir(), true);
  }

  @Test
  public void testRestore() throws IOException {
    HTableDescriptor htd = createTableDescriptor("testtb");

    Path snapshotDir = new Path(rootDir, "snapshot");
    createSnapshot(rootDir, snapshotDir, htd);

    // Test clone a snapshot
    HTableDescriptor htdClone = createTableDescriptor("testtb-clone");
    testRestore(snapshotDir, htd.getNameAsString(), htdClone);
    verifyRestore(rootDir, htd, htdClone);

    // Test clone a clone ("link to link")
    Path cloneDir = HTableDescriptor.getTableDir(rootDir, htdClone.getName());
    HTableDescriptor htdClone2 = createTableDescriptor("testtb-clone2");
    testRestore(cloneDir, htdClone.getNameAsString(), htdClone2);
    verifyRestore(rootDir, htd, htdClone2);
  }

  private void verifyRestore(final Path rootDir, final HTableDescriptor sourceHtd,
      final HTableDescriptor htdClone) throws IOException {
    String[] files = getHFiles(HTableDescriptor.getTableDir(rootDir, htdClone.getName()));
    assertEquals(2, files.length);
    assertTrue(files[0] + " should be a HFileLink", HFileLink.isHFileLink(files[0]));
    assertTrue(files[1] + " should be a Referene", StoreFile.isReference(files[1]));
    assertEquals(sourceHtd.getNameAsString(), HFileLink.getReferencedTableName(files[0]));
    assertEquals(TEST_HFILE, HFileLink.getReferencedHFileName(files[0]));
    Path refPath = getReferredToFile(files[1]);
    assertTrue(refPath.getName() + " should be a HFileLink", HFileLink.isHFileLink(refPath.getName()));
    assertEquals(files[0], refPath.getName());
  }

  /**
   * Execute the restore operation
   * @param snapshotDir The snapshot directory to use as "restore source"
   * @param sourceTableName The name of the snapshotted table
   * @param htdClone The HTableDescriptor of the table to restore/clone.
   */
  public void testRestore(final Path snapshotDir, final String sourceTableName,
      final HTableDescriptor htdClone) throws IOException {
    LOG.debug("pre-restore table=" + htdClone.getNameAsString() + " snapshot=" + snapshotDir);
    FSUtils.logFileSystemState(fs, rootDir, LOG);

    FSTableDescriptors.createTableDescriptor(htdClone, conf);
    RestoreSnapshotHelper helper = getRestoreHelper(rootDir, snapshotDir, sourceTableName, htdClone);
    helper.restoreHdfsRegions();

    LOG.debug("post-restore table=" + htdClone.getNameAsString() + " snapshot=" + snapshotDir);
    FSUtils.logFileSystemState(fs, rootDir, LOG);
  }

  /**
   * Initialize the restore helper, based on the snapshot and table information provided.
   */
  private RestoreSnapshotHelper getRestoreHelper(final Path rootDir, final Path snapshotDir,
      final String sourceTableName, final HTableDescriptor htdClone) throws IOException {
    CatalogTracker catalogTracker = Mockito.mock(CatalogTracker.class);
    HTableDescriptor tableDescriptor = Mockito.mock(HTableDescriptor.class);
    ForeignExceptionDispatcher monitor = Mockito.mock(ForeignExceptionDispatcher.class);
    MonitoredTask status = Mockito.mock(MonitoredTask.class);

    SnapshotDescription sd = SnapshotDescription.newBuilder()
      .setName("snapshot").setTable(sourceTableName).build();

    return new RestoreSnapshotHelper(conf, fs, sd, snapshotDir,
      htdClone, HTableDescriptor.getTableDir(rootDir, htdClone.getName()), monitor, status);
  }

  private void createSnapshot(final Path rootDir, final Path snapshotDir, final HTableDescriptor htd)
      throws IOException {
    // First region, simple with one plain hfile.
    HRegion r0 = HRegion.createHRegion(new HRegionInfo(htd.getName()), archiveDir,
        conf, htd, null, true, true);
    Path storeFile = new Path(new Path(r0.getRegionDir(), TEST_FAMILY), TEST_HFILE);
    fs.createNewFile(storeFile);
    r0.close();

    // Second region, used to test the split case.
    // This region contains a reference to the hfile in the first region.
    HRegion r1 = HRegion.createHRegion(new HRegionInfo(htd.getName()), archiveDir,
        conf, htd, null, true, true);
    fs.createNewFile(new Path(new Path(r1.getRegionDir(), TEST_FAMILY),
        storeFile.getName() + '.' + r0.getRegionInfo().getEncodedName()));
    r1.close();

    Path tableDir = HTableDescriptor.getTableDir(archiveDir, htd.getName());
    FileUtil.copy(fs, tableDir, fs, snapshotDir, false, conf);
  }

  private HTableDescriptor createTableDescriptor(final String tableName) {
    HTableDescriptor htd = new HTableDescriptor(tableName);
    htd.addFamily(new HColumnDescriptor(TEST_FAMILY));
    return htd;
  }

  private Path getReferredToFile(final String referenceName) {
    Path fakeBasePath = new Path(new Path("table", "region"), "cf");
    return StoreFile.getReferredToFile(new Path(fakeBasePath, referenceName));
  }

  private String[] getHFiles(final Path tableDir) throws IOException {
    List<String> files = new ArrayList<String>();
    for (Path regionDir: FSUtils.getRegionDirs(fs, tableDir)) {
      for (Path familyDir: FSUtils.getFamilyDirs(fs, regionDir)) {
        for (FileStatus file: FSUtils.listStatus(fs, familyDir)) {
          files.add(file.getPath().getName());
        }
      }
    }
    Collections.sort(files);
    return files.toArray(new String[files.size()]);
  }
}
