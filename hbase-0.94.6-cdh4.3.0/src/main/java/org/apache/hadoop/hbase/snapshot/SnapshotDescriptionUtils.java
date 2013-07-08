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

import java.io.IOException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.protobuf.generated.HBaseProtos.SnapshotDescription;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManager;
import org.apache.hadoop.hbase.util.FSUtils;

/**
 * Utility class to help manage {@link SnapshotDescription SnapshotDesriptions}.
 * <p>
 * Snapshots are laid out on disk like this:
 *
 * <pre>
 * /hbase/.snapshots
 *          /.tmp                <---- working directory
 *          /[snapshot name]     <----- completed snapshot
 * </pre>
 *
 * A completed snapshot named 'completed' then looks like (multiple regions, servers, files, etc.
 * signified by '...' on the same directory depth).
 *
 * <pre>
 * /hbase/.snapshots/completed
 *                   .snapshotinfo          <--- Description of the snapshot
 *                   .tableinfo             <--- Copy of the tableinfo
 *                    /.logs
 *                        /[server_name]
 *                            /... [log files]
 *                         ...
 *                   /[region name]           <---- All the region's information
 *                   .regioninfo              <---- Copy of the HRegionInfo
 *                      /[column family name]
 *                          /[hfile name]     <--- name of the hfile in the real region
 *                          ...
 *                      ...
 *                    ...
 * </pre>
 *
 * Utility methods in this class are useful for getting the correct locations for different parts of
 * the snapshot, as well as moving completed snapshots into place (see
 * {@link #completeSnapshot}, and writing the
 * {@link SnapshotDescription} to the working snapshot directory.
 */
public class SnapshotDescriptionUtils {

  /**
   * Filter that only accepts completed snapshot directories
   */
  public static class CompletedSnaphotDirectoriesFilter extends FSUtils.DirFilter {

    /**
     * @param fs
     */
    public CompletedSnaphotDirectoriesFilter(FileSystem fs) {
      super(fs);
    }

    @Override
    public boolean accept(Path path) {
      // only accept directories that aren't the tmp directory
      if (super.accept(path)) {
        return !path.getName().equals(SNAPSHOT_TMP_DIR_NAME);
      }
      return false;
    }

  }

  private static final Log LOG = LogFactory.getLog(SnapshotDescriptionUtils.class);
  /**
   * Version of the fs layout for a snapshot. Future snapshots may have different file layouts,
   * which we may need to read in differently.
   */
  public static final int SNAPSHOT_LAYOUT_VERSION = 0;

  // snapshot directory constants
  /**
   * The file contains the snapshot basic information and it is under the directory of a snapshot.
   */
  public static final String SNAPSHOTINFO_FILE = ".snapshotinfo";

  /** Temporary directory under the snapshot directory to store in-progress snapshots */
  public static final String SNAPSHOT_TMP_DIR_NAME = ".tmp";
  // snapshot operation values
  /** Default value if no start time is specified */
  public static final long NO_SNAPSHOT_START_TIME_SPECIFIED = 0;

  public static final String MASTER_SNAPSHOT_TIMEOUT_MILLIS = "hbase.snapshot.master.timeout.millis";

  /** By default, wait 60 seconds for a snapshot to complete */
  public static final long DEFAULT_MAX_WAIT_TIME = 60000;

  private SnapshotDescriptionUtils() {
    // private constructor for utility class
  }

  /**
   * Check to make sure that the description of the snapshot requested is valid
   * @param snapshot description of the snapshot
   * @throws IllegalArgumentException if the name of the snapshot or the name of the table to
   *           snapshot are not valid names.
   */
  public static void assertSnapshotRequestIsValid(SnapshotDescription snapshot)
      throws IllegalArgumentException {
    // FIXME these method names is really bad - trunk will probably change
    // .META. and -ROOT- snapshots are not allowed
    if (HTableDescriptor.isMetaTable(Bytes.toBytes(snapshot.getTable()))) {
      throw new IllegalArgumentException(".META. and -ROOT- snapshots are not allowed");
    }
    // make sure the snapshot name is valid
    HTableDescriptor.isLegalTableName(Bytes.toBytes(snapshot.getName()));
    // make sure the table name is valid
    HTableDescriptor.isLegalTableName(Bytes.toBytes(snapshot.getTable()));
  }

  /**
   * @param conf {@link Configuration} from which to check for the timeout
   * @param type type of snapshot being taken
   * @param defaultMaxWaitTime Default amount of time to wait, if none is in the configuration
   * @return the max amount of time the master should wait for a snapshot to complete
   */
  public static long getMaxMasterTimeout(Configuration conf, SnapshotDescription.Type type,
      long defaultMaxWaitTime) {
    String confKey;
    switch (type) {
    case DISABLED:
    default:
      confKey = MASTER_SNAPSHOT_TIMEOUT_MILLIS;
    }
    return conf.getLong(confKey, defaultMaxWaitTime);
  }

  /**
   * Get the snapshot root directory. All the snapshots are kept under this directory, i.e.
   * ${hbase.rootdir}/.snapshot
   * @param rootDir hbase root directory
   * @return the base directory in which all snapshots are kept
   */
  public static Path getSnapshotRootDir(final Path rootDir) {
    return new Path(rootDir, HConstants.SNAPSHOT_DIR_NAME);
  }

  /**
   * Get the directory for a specified snapshot. This directory is a sub-directory of snapshot root
   * directory and all the data files for a snapshot are kept under this directory.
   * @param snapshot snapshot being taken
   * @param rootDir hbase root directory
   * @return the final directory for the completed snapshot
   */
  public static Path getCompletedSnapshotDir(final SnapshotDescription snapshot, final Path rootDir) {
    return getCompletedSnapshotDir(snapshot.getName(), rootDir);
  }

  /**
   * Get the directory for a completed snapshot. This directory is a sub-directory of snapshot root
   * directory and all the data files for a snapshot are kept under this directory.
   * @param snapshotName name of the snapshot being taken
   * @param rootDir hbase root directory
   * @return the final directory for the completed snapshot
   */
  public static Path getCompletedSnapshotDir(final String snapshotName, final Path rootDir) {
    return getCompletedSnapshotDir(getSnapshotsDir(rootDir), snapshotName);
  }

  /**
   * Get the general working directory for snapshots - where they are built, where they are
   * temporarily copied on export, etc.
   * @param rootDir root directory of the HBase installation
   * @return Path to the snapshot tmp directory, relative to the passed root directory
   */
  public static Path getWorkingSnapshotDir(final Path rootDir) {
    return new Path(getSnapshotsDir(rootDir), SNAPSHOT_TMP_DIR_NAME);
  }

  /**
   * Get the directory to build a snapshot, before it is finalized
   * @param snapshot snapshot that will be built
   * @param rootDir root directory of the hbase installation
   * @return {@link Path} where one can build a snapshot
   */
  public static Path getWorkingSnapshotDir(SnapshotDescription snapshot, final Path rootDir) {
    return getCompletedSnapshotDir(getWorkingSnapshotDir(rootDir), snapshot.getName());
  }

  /**
   * Get the directory to build a snapshot, before it is finalized
   * @param snapshotName name of the snapshot
   * @param rootDir root directory of the hbase installation
   * @return {@link Path} where one can build a snapshot
   */
  public static Path getWorkingSnapshotDir(String snapshotName, final Path rootDir) {
    return getCompletedSnapshotDir(getWorkingSnapshotDir(rootDir), snapshotName);
  }

  /**
   * Get the directory to store the snapshot instance
   * @param snapshotsDir hbase-global directory for storing all snapshots
   * @param snapshotName name of the snapshot to take
   * @return
   */
  private static final Path getCompletedSnapshotDir(final Path snapshotsDir, String snapshotName) {
    return new Path(snapshotsDir, snapshotName);
  }

  /**
   * @param rootDir hbase root directory
   * @return the directory for all completed snapshots;
   */
  public static final Path getSnapshotsDir(Path rootDir) {
    return new Path(rootDir, HConstants.SNAPSHOT_DIR_NAME);
  }

  /**
   * Convert the passed snapshot description into a 'full' snapshot description based on default
   * parameters, if none have been supplied. This resolves any 'optional' parameters that aren't
   * supplied to their default values.
   * @param snapshot general snapshot descriptor
   * @param conf Configuration to read configured snapshot defaults if snapshot is not complete
   * @return a valid snapshot description
   * @throws IllegalArgumentException if the {@link SnapshotDescription} is not a complete
   *           {@link SnapshotDescription}.
   */
  public static SnapshotDescription validate(SnapshotDescription snapshot, Configuration conf)
      throws IllegalArgumentException {
    if (!snapshot.hasTable()) {
      throw new IllegalArgumentException(
        "Descriptor doesn't apply to a table, so we can't build it.");
    }

    // set the creation time, if one hasn't been set
    long time = snapshot.getCreationTime();
    if (time == SnapshotDescriptionUtils.NO_SNAPSHOT_START_TIME_SPECIFIED) {
      time = EnvironmentEdgeManager.currentTimeMillis();
      LOG.debug("Creation time not specified, setting to:" + time + " (current time:"
          + EnvironmentEdgeManager.currentTimeMillis() + ").");
      SnapshotDescription.Builder builder = snapshot.toBuilder();
      builder.setCreationTime(time);
      snapshot = builder.build();
    }
    return snapshot;
  }

  /**
   * Write the snapshot description into the working directory of a snapshot
   * @param snapshot description of the snapshot being taken
   * @param workingDir working directory of the snapshot
   * @param fs {@link FileSystem} on which the snapshot should be taken
   * @throws IOException if we can't reach the filesystem and the file cannot be cleaned up on
   *           failure
   */
  public static void writeSnapshotInfo(SnapshotDescription snapshot, Path workingDir, FileSystem fs)
      throws IOException {
    FsPermission perms = FSUtils.getFilePermissions(fs, fs.getConf(),
      HConstants.DATA_FILE_UMASK_KEY);
    Path snapshotInfo = new Path(workingDir, SnapshotDescriptionUtils.SNAPSHOTINFO_FILE);
    try {
      FSDataOutputStream out = FSUtils.create(fs, snapshotInfo, perms, true);
      try {
        snapshot.writeTo(out);
      } finally {
        out.close();
      }
    } catch (IOException e) {
      // if we get an exception, try to remove the snapshot info
      if (!fs.delete(snapshotInfo, false)) {
        String msg = "Couldn't delete snapshot info file: " + snapshotInfo;
        LOG.error(msg);
        throw new IOException(msg);
      }
    }
  }

  /**
   * Read in the {@link SnapshotDescription} stored for the snapshot in the passed directory
   * @param fs filesystem where the snapshot was taken
   * @param snapshotDir directory where the snapshot was stored
   * @return the stored snapshot description
   * @throws CorruptedSnapshotException if the snapshot cannot be read
   */
  public static SnapshotDescription readSnapshotInfo(FileSystem fs, Path snapshotDir)
      throws CorruptedSnapshotException {
    Path snapshotInfo = new Path(snapshotDir, SNAPSHOTINFO_FILE);
    try {
      FSDataInputStream in = null;
      try {
        in = fs.open(snapshotInfo);
        return SnapshotDescription.parseFrom(in);
      } finally {
        if (in != null) in.close();
      }
    } catch (IOException e) {
      throw new CorruptedSnapshotException("Couldn't read snapshot info from:" + snapshotInfo, e);
    }
  }

  /**
   * Move the finished snapshot to its final, publicly visible directory - this marks the snapshot
   * as 'complete'.
   * @param snapshot description of the snapshot being tabken
   * @param rootdir root directory of the hbase installation
   * @param workingDir directory where the in progress snapshot was built
   * @param fs {@link FileSystem} where the snapshot was built
   * @throws SnapshotCreationException if the snapshot could not be moved
   * @throws IOException the filesystem could not be reached
   */
  public static void completeSnapshot(SnapshotDescription snapshot, Path rootdir, Path workingDir,
      FileSystem fs) throws SnapshotCreationException, IOException {
    Path finishedDir = getCompletedSnapshotDir(snapshot, rootdir);
    LOG.debug("Snapshot is done, just moving the snapshot from " + workingDir + " to "
        + finishedDir);
    if (!fs.rename(workingDir, finishedDir)) {
      throw new SnapshotCreationException("Failed to move working directory(" + workingDir
          + ") to completed directory(" + finishedDir + ").", snapshot);
    }
  }

  /**
   * Returns a single line (no \n) representation of snapshot metadata.  Use this instead of
   * {@link SnapshotDescription#toString()}.  We don't replace SnapshotDescrpition's toString
   * because it is auto-generated by protoc.
   * @param ssd
   * @return Single line string with a summary of the snapshot parameters
   */
  public static String toString(SnapshotDescription ssd) {
    if (ssd == null) {
      return null;
    }
    return "{ ss=" + ssd.getName() + " table=" + ssd.getTable()
        + " type=" + ssd.getType() + " }";
  }
}
