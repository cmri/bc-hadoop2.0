/**
 * Copyright 2007 The Apache Software Foundation
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
package org.apache.hadoop.hbase;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.EOFException;
import java.io.IOException;
import java.util.Arrays;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.KeyValue.KVComparator;
import org.apache.hadoop.hbase.migration.HRegionInfo090x;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.FSTableDescriptors;
import org.apache.hadoop.hbase.util.JenkinsHash;
import org.apache.hadoop.hbase.util.MD5Hash;
import org.apache.hadoop.io.VersionedWritable;
import org.apache.hadoop.io.WritableComparable;

/**
 * HRegion information.
 * Contains HRegion id, start and end keys, a reference to this
 * HRegions' table descriptor, etc.
 */
public class HRegionInfo extends VersionedWritable
implements WritableComparable<HRegionInfo> {
  // VERSION == 0 when HRegionInfo had an HTableDescriptor inside it.
  public static final byte VERSION_PRE_092 = 0;
  public static final byte VERSION = 1;
  private static final Log LOG = LogFactory.getLog(HRegionInfo.class);

  /**
   * The new format for a region name contains its encodedName at the end.
   * The encoded name also serves as the directory name for the region
   * in the filesystem.
   *
   * New region name format:
   *    &lt;tablename>,,&lt;startkey>,&lt;regionIdTimestamp>.&lt;encodedName>.
   * where,
   *    &lt;encodedName> is a hex version of the MD5 hash of
   *    &lt;tablename>,&lt;startkey>,&lt;regionIdTimestamp>
   * 
   * The old region name format:
   *    &lt;tablename>,&lt;startkey>,&lt;regionIdTimestamp>
   * For region names in the old format, the encoded name is a 32-bit
   * JenkinsHash integer value (in its decimal notation, string form). 
   *<p>
   * **NOTE**
   *
   * ROOT, the first META region, and regions created by an older
   * version of HBase (0.20 or prior) will continue to use the
   * old region name format.
   */

  /** Separator used to demarcate the encodedName in a region name
   * in the new format. See description on new format above. 
   */ 
  private static final int ENC_SEPARATOR = '.';
  public  static final int MD5_HEX_LENGTH   = 32;

  /** A non-capture group so that this can be embedded. */
  public static final String ENCODED_REGION_NAME_REGEX = "(?:[a-f0-9]+)";

  /**
   * Does region name contain its encoded name?
   * @param regionName region name
   * @return boolean indicating if this a new format region
   *         name which contains its encoded name.
   */
  private static boolean hasEncodedName(final byte[] regionName) {
    // check if region name ends in ENC_SEPARATOR
    if ((regionName.length >= 1)
        && (regionName[regionName.length - 1] == ENC_SEPARATOR)) {
      // region name is new format. it contains the encoded name.
      return true; 
    }
    return false;
  }
  
  /**
   * @param regionName
   * @return the encodedName
   */
  public static String encodeRegionName(final byte [] regionName) {
    String encodedName;
    if (hasEncodedName(regionName)) {
      // region is in new format:
      // <tableName>,<startKey>,<regionIdTimeStamp>/encodedName/
      encodedName = Bytes.toString(regionName,
          regionName.length - MD5_HEX_LENGTH - 1,
          MD5_HEX_LENGTH);
    } else {
      // old format region name. ROOT and first META region also 
      // use this format.EncodedName is the JenkinsHash value.
      int hashVal = Math.abs(JenkinsHash.getInstance().hash(regionName,
        regionName.length, 0));
      encodedName = String.valueOf(hashVal);
    }
    return encodedName;
  }

  /**
   * Use logging.
   * @param encodedRegionName The encoded regionname.
   * @return <code>-ROOT-</code> if passed <code>70236052</code> or
   * <code>.META.</code> if passed </code>1028785192</code> else returns
   * <code>encodedRegionName</code>
   */
  public static String prettyPrint(final String encodedRegionName) {
    if (encodedRegionName.equals("70236052")) {
      return encodedRegionName + "/-ROOT-";
    } else if (encodedRegionName.equals("1028785192")) {
      return encodedRegionName + "/.META.";
    }
    return encodedRegionName;
  }

  /** delimiter used between portions of a region name */
  public static final int DELIMITER = ',';

  /** HRegionInfo for root region */
  public static final HRegionInfo ROOT_REGIONINFO =
    new HRegionInfo(0L, Bytes.toBytes("-ROOT-"));

  /** HRegionInfo for first meta region */
  public static final HRegionInfo FIRST_META_REGIONINFO =
    new HRegionInfo(1L, Bytes.toBytes(".META."));

  private byte [] endKey = HConstants.EMPTY_BYTE_ARRAY;
  // This flag is in the parent of a split while the parent is still referenced
  // by daughter regions.  We USED to set this flag when we disabled a table
  // but now table state is kept up in zookeeper as of 0.90.0 HBase.
  private boolean offLine = false;
  private long regionId = -1;
  private transient byte [] regionName = HConstants.EMPTY_BYTE_ARRAY;
  private String regionNameStr = "";
  private boolean split = false;
  private byte [] startKey = HConstants.EMPTY_BYTE_ARRAY;
  private int hashCode = -1;
  //TODO: Move NO_HASH to HStoreFile which is really the only place it is used.
  public static final String NO_HASH = null;
  private volatile String encodedName = NO_HASH;
  private byte [] encodedNameAsBytes = null;

  // Current TableName
  private byte[] tableName = null;

  private void setHashCode() {
    int result = Arrays.hashCode(this.regionName);
    result ^= this.regionId;
    result ^= Arrays.hashCode(this.startKey);
    result ^= Arrays.hashCode(this.endKey);
    result ^= Boolean.valueOf(this.offLine).hashCode();
    result ^= Arrays.hashCode(this.tableName);
    this.hashCode = result;
  }


  /**
   * Private constructor used constructing HRegionInfo for the catalog root and
   * first meta regions
   */
  private HRegionInfo(long regionId, byte[] tableName) {
    super();
    this.regionId = regionId;
    this.tableName = tableName.clone();
    // Note: Root & First Meta regions names are still in old format
    this.regionName = createRegionName(tableName, null,
                                       regionId, false);
    this.regionNameStr = Bytes.toStringBinary(this.regionName);
    setHashCode();
  }

  /** Default constructor - creates empty object */
  public HRegionInfo() {
    super();
  }

  /**
   * Used only for migration
   * @param other HRegionInfoForMigration
   */
  public HRegionInfo(HRegionInfo090x other) {
    super();
    this.endKey = other.getEndKey();
    this.offLine = other.isOffline();
    this.regionId = other.getRegionId();
    this.regionName = other.getRegionName();
    this.regionNameStr = Bytes.toStringBinary(this.regionName);
    this.split = other.isSplit();
    this.startKey = other.getStartKey();
    this.hashCode = other.hashCode();
    this.encodedName = other.getEncodedName();
    this.tableName = other.getTableDesc().getName();
  }

  public HRegionInfo(final byte[] tableName) {
    this(tableName, null, null);
  }

  /**
   * Construct HRegionInfo with explicit parameters
   *
   * @param tableName the table name
   * @param startKey first key in region
   * @param endKey end of key range
   * @throws IllegalArgumentException
   */
  public HRegionInfo(final byte[] tableName, final byte[] startKey,
                     final byte[] endKey)
  throws IllegalArgumentException {
    this(tableName, startKey, endKey, false);
  }


  /**
   * Construct HRegionInfo with explicit parameters
   *
   * @param tableName the table descriptor
   * @param startKey first key in region
   * @param endKey end of key range
   * @param split true if this region has split and we have daughter regions
   * regions that may or may not hold references to this region.
   * @throws IllegalArgumentException
   */
  public HRegionInfo(final byte[] tableName, final byte[] startKey,
                     final byte[] endKey, final boolean split)
  throws IllegalArgumentException {
    this(tableName, startKey, endKey, split, System.currentTimeMillis());
  }


  /**
   * Construct HRegionInfo with explicit parameters
   *
   * @param tableName the table descriptor
   * @param startKey first key in region
   * @param endKey end of key range
   * @param split true if this region has split and we have daughter regions
   * regions that may or may not hold references to this region.
   * @param regionid Region id to use.
   * @throws IllegalArgumentException
   */
  public HRegionInfo(final byte[] tableName, final byte[] startKey,
                     final byte[] endKey, final boolean split, final long regionid)
  throws IllegalArgumentException {

    super();
    if (tableName == null) {
      throw new IllegalArgumentException("tableName cannot be null");
    }
    this.tableName = tableName.clone();
    this.offLine = false;
    this.regionId = regionid;

    this.regionName = createRegionName(this.tableName, startKey, regionId, true);

    this.regionNameStr = Bytes.toStringBinary(this.regionName);
    this.split = split;
    this.endKey = endKey == null? HConstants.EMPTY_END_ROW: endKey.clone();
    this.startKey = startKey == null?
      HConstants.EMPTY_START_ROW: startKey.clone();
    this.tableName = tableName.clone();
    setHashCode();
  }

  /**
   * Costruct a copy of another HRegionInfo
   *
   * @param other
   */
  public HRegionInfo(HRegionInfo other) {
    super();
    this.endKey = other.getEndKey();
    this.offLine = other.isOffline();
    this.regionId = other.getRegionId();
    this.regionName = other.getRegionName();
    this.regionNameStr = Bytes.toStringBinary(this.regionName);
    this.split = other.isSplit();
    this.startKey = other.getStartKey();
    this.hashCode = other.hashCode();
    this.encodedName = other.getEncodedName();
    this.tableName = other.tableName;
  }


  /**
   * Make a region name of passed parameters.
   * @param tableName
   * @param startKey Can be null
   * @param regionid Region id (Usually timestamp from when region was created).
   * @param newFormat should we create the region name in the new format
   *                  (such that it contains its encoded name?).
   * @return Region name made of passed tableName, startKey and id
   */
  public static byte [] createRegionName(final byte [] tableName,
      final byte [] startKey, final long regionid, boolean newFormat) {
    return createRegionName(tableName, startKey, Long.toString(regionid), newFormat);
  }

  /**
   * Make a region name of passed parameters.
   * @param tableName
   * @param startKey Can be null
   * @param id Region id (Usually timestamp from when region was created).
   * @param newFormat should we create the region name in the new format
   *                  (such that it contains its encoded name?).
   * @return Region name made of passed tableName, startKey and id
   */
  public static byte [] createRegionName(final byte [] tableName,
      final byte [] startKey, final String id, boolean newFormat) {
    return createRegionName(tableName, startKey, Bytes.toBytes(id), newFormat);
  }

  /**
   * Make a region name of passed parameters.
   * @param tableName
   * @param startKey Can be null
   * @param id Region id (Usually timestamp from when region was created).
   * @param newFormat should we create the region name in the new format
   *                  (such that it contains its encoded name?).
   * @return Region name made of passed tableName, startKey and id
   */
  public static byte [] createRegionName(final byte [] tableName,
      final byte [] startKey, final byte [] id, boolean newFormat) {
    byte [] b = new byte [tableName.length + 2 + id.length +
       (startKey == null? 0: startKey.length) +
       (newFormat ? (MD5_HEX_LENGTH + 2) : 0)];

    int offset = tableName.length;
    System.arraycopy(tableName, 0, b, 0, offset);
    b[offset++] = DELIMITER;
    if (startKey != null && startKey.length > 0) {
      System.arraycopy(startKey, 0, b, offset, startKey.length);
      offset += startKey.length;
    }
    b[offset++] = DELIMITER;
    System.arraycopy(id, 0, b, offset, id.length);
    offset += id.length;

    if (newFormat) {
      //
      // Encoded name should be built into the region name.
      //
      // Use the region name thus far (namely, <tablename>,<startKey>,<id>)
      // to compute a MD5 hash to be used as the encoded name, and append
      // it to the byte buffer.
      //
      String md5Hash = MD5Hash.getMD5AsHex(b, 0, offset);
      byte [] md5HashBytes = Bytes.toBytes(md5Hash);

      if (md5HashBytes.length != MD5_HEX_LENGTH) {
        LOG.error("MD5-hash length mismatch: Expected=" + MD5_HEX_LENGTH +
                  "; Got=" + md5HashBytes.length); 
      }

      // now append the bytes '.<encodedName>.' to the end
      b[offset++] = ENC_SEPARATOR;
      System.arraycopy(md5HashBytes, 0, b, offset, MD5_HEX_LENGTH);
      offset += MD5_HEX_LENGTH;
      b[offset++] = ENC_SEPARATOR;
    }
    
    return b;
  }

  /**
   * Gets the table name from the specified region name.
   * @param regionName
   * @return Table name.
   */
  public static byte [] getTableName(byte [] regionName) {
    int offset = -1;
    for (int i = 0; i < regionName.length; i++) {
      if (regionName[i] == DELIMITER) {
        offset = i;
        break;
      }
    }
    byte [] tableName = new byte[offset];
    System.arraycopy(regionName, 0, tableName, 0, offset);
    return tableName;
  }

  /**
   * Separate elements of a regionName.
   * @param regionName
   * @return Array of byte[] containing tableName, startKey and id
   * @throws IOException
   */
  public static byte [][] parseRegionName(final byte [] regionName)
  throws IOException {
    int offset = -1;
    for (int i = 0; i < regionName.length; i++) {
      if (regionName[i] == DELIMITER) {
        offset = i;
        break;
      }
    }
    if(offset == -1) throw new IOException("Invalid regionName format");
    byte [] tableName = new byte[offset];
    System.arraycopy(regionName, 0, tableName, 0, offset);
    offset = -1;
    for (int i = regionName.length - 1; i > 0; i--) {
      if(regionName[i] == DELIMITER) {
        offset = i;
        break;
      }
    }
    if(offset == -1) throw new IOException("Invalid regionName format");
    byte [] startKey = HConstants.EMPTY_BYTE_ARRAY;
    if(offset != tableName.length + 1) {
      startKey = new byte[offset - tableName.length - 1];
      System.arraycopy(regionName, tableName.length + 1, startKey, 0,
          offset - tableName.length - 1);
    }
    byte [] id = new byte[regionName.length - offset - 1];
    System.arraycopy(regionName, offset + 1, id, 0,
        regionName.length - offset - 1);
    byte [][] elements = new byte[3][];
    elements[0] = tableName;
    elements[1] = startKey;
    elements[2] = id;
    return elements;
  }

  /** @return the regionId */
  public long getRegionId(){
    return regionId;
  }

  /**
   * @return the regionName as an array of bytes.
   * @see #getRegionNameAsString()
   */
  public byte [] getRegionName(){
    return regionName;
  }

  /**
   * @return Region name as a String for use in logging, etc.
   */
  public String getRegionNameAsString() {
    if (hasEncodedName(this.regionName)) {
      // new format region names already have their encoded name.
      return this.regionNameStr;
    }

    // old format. regionNameStr doesn't have the region name.
    //
    //
    return this.regionNameStr + "." + this.getEncodedName();
  }

  /** @return the encoded region name */
  public synchronized String getEncodedName() {
    if (this.encodedName == NO_HASH) {
      this.encodedName = encodeRegionName(this.regionName);
    }
    return this.encodedName;
  }

  public synchronized byte [] getEncodedNameAsBytes() {
    if (this.encodedNameAsBytes == null) {
      this.encodedNameAsBytes = Bytes.toBytes(getEncodedName());
    }
    return this.encodedNameAsBytes;
  }

  /** @return the startKey */
  public byte [] getStartKey(){
    return startKey;
  }
  
  /** @return the endKey */
  public byte [] getEndKey(){
    return endKey;
  }

  /**
   * Get current table name of the region
   * @return byte array of table name
   */
  public byte[] getTableName() {
    if (tableName == null || tableName.length == 0) {
      tableName = getTableName(getRegionName());
    }
    return tableName;
  }

  /**
   * Get current table name as string
   * @return string representation of current table
   */
  public String getTableNameAsString() {
    return Bytes.toString(tableName);
  }

  /**
   * Returns true if the given inclusive range of rows is fully contained
   * by this region. For example, if the region is foo,a,g and this is
   * passed ["b","c"] or ["a","c"] it will return true, but if this is passed
   * ["b","z"] it will return false.
   * @throws IllegalArgumentException if the range passed is invalid (ie end < start)
   */
  public boolean containsRange(byte[] rangeStartKey, byte[] rangeEndKey) {
    if (Bytes.compareTo(rangeStartKey, rangeEndKey) > 0) {
      throw new IllegalArgumentException(
      "Invalid range: " + Bytes.toStringBinary(rangeStartKey) +
      " > " + Bytes.toStringBinary(rangeEndKey));
    }

    boolean firstKeyInRange = Bytes.compareTo(rangeStartKey, startKey) >= 0;
    boolean lastKeyInRange =
      Bytes.compareTo(rangeEndKey, endKey) < 0 ||
      Bytes.equals(endKey, HConstants.EMPTY_BYTE_ARRAY);
    return firstKeyInRange && lastKeyInRange;
  }
  
  /**
   * Return true if the given row falls in this region.
   */
  public boolean containsRow(byte[] row) {
    return Bytes.compareTo(row, startKey) >= 0 &&
      (Bytes.compareTo(row, endKey) < 0 ||
       Bytes.equals(endKey, HConstants.EMPTY_BYTE_ARRAY));
  }

  /**
   * @return the tableDesc
   * @deprecated Do not use; expensive call
   *         use HRegionInfo.getTableNameAsString() in place of
   *         HRegionInfo.getTableDesc().getNameAsString()
   */
   @Deprecated
  public HTableDescriptor getTableDesc() {
    Configuration c = HBaseConfiguration.create();
    c.set("fs.defaultFS", c.get(HConstants.HBASE_DIR));
    c.set("fs.default.name", c.get(HConstants.HBASE_DIR));
    FileSystem fs;
    try {
      fs = FileSystem.get(c);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    FSTableDescriptors fstd =
      new FSTableDescriptors(fs, new Path(c.get(HConstants.HBASE_DIR)));
    try {
      return fstd.get(this.tableName);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * @param newDesc new table descriptor to use
   * @deprecated Do not use; expensive call
   */
  @Deprecated
  public void setTableDesc(HTableDescriptor newDesc) {
    Configuration c = HBaseConfiguration.create();
    FileSystem fs;
    try {
      fs = FileSystem.get(c);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    FSTableDescriptors fstd =
      new FSTableDescriptors(fs, new Path(c.get(HConstants.HBASE_DIR)));
    try {
      fstd.add(newDesc);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /** @return true if this is the root region */
  public boolean isRootRegion() {
    return Bytes.equals(tableName, HRegionInfo.ROOT_REGIONINFO.getTableName());
  }

  /** @return true if this region is from a table that is a meta table,
   * either <code>.META.</code> or <code>-ROOT-</code>
   */
  public boolean isMetaTable() {
    return isRootRegion() || isMetaRegion();
  }

  /** @return true if this region is a meta region */
  public boolean isMetaRegion() {
     return Bytes.equals(tableName, HRegionInfo.FIRST_META_REGIONINFO.getTableName());
  }

  /**
   * @return True if has been split and has daughters.
   */
  public boolean isSplit() {
    return this.split;
  }

  /**
   * @param split set split status
   */
  public void setSplit(boolean split) {
    this.split = split;
  }

  /**
   * @return True if this region is offline.
   */
  public boolean isOffline() {
    return this.offLine;
  }

  /**
   * The parent of a region split is offline while split daughters hold
   * references to the parent. Offlined regions are closed.
   * @param offLine Set online/offline status.
   */
  public void setOffline(boolean offLine) {
    this.offLine = offLine;
  }


  /**
   * @return True if this is a split parent region.
   */
  public boolean isSplitParent() {
    if (!isSplit()) return false;
    if (!isOffline()) {
      LOG.warn("Region is split but NOT offline: " + getRegionNameAsString());
    }
    return true;
  }

  /**
   * @see java.lang.Object#toString()
   */
  @Override
  public String toString() {
    return "{" + HConstants.NAME + " => '" +
      this.regionNameStr
      + "', STARTKEY => '" +
      Bytes.toStringBinary(this.startKey) + "', ENDKEY => '" +
      Bytes.toStringBinary(this.endKey) +
      "', ENCODED => " + getEncodedName() + "," +
      (isOffline()? " OFFLINE => true,": "") +
      (isSplit()? " SPLIT => true,": "") + "}";
  }

  /**
   * @see java.lang.Object#equals(java.lang.Object)
   */
  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null) {
      return false;
    }
    if (!(o instanceof HRegionInfo)) {
      return false;
    }
    return this.compareTo((HRegionInfo)o) == 0;
  }

  /**
   * @see java.lang.Object#hashCode()
   */
  @Override
  public int hashCode() {
    return this.hashCode;
  }

  /** @return the object version number */
  @Override
  public byte getVersion() {
    return VERSION;
  }

  //
  // Writable
  //

  @Override
  public void write(DataOutput out) throws IOException {
    super.write(out);
    Bytes.writeByteArray(out, endKey);
    out.writeBoolean(offLine);
    out.writeLong(regionId);
    Bytes.writeByteArray(out, regionName);
    out.writeBoolean(split);
    Bytes.writeByteArray(out, startKey);
    Bytes.writeByteArray(out, tableName);
    out.writeInt(hashCode);
  }

  @Override
  public void readFields(DataInput in) throws IOException {
    // Read the single version byte.  We don't ask the super class do it
    // because freaks out if its not the current classes' version.  This method
    // can deserialize version 0 and version 1 of HRI.
    byte version = in.readByte();
    if (version == 0) {
      // This is the old HRI that carried an HTD.  Migrate it.  The below
      // was copied from the old 0.90 HRI readFields.
      this.endKey = Bytes.readByteArray(in);
      this.offLine = in.readBoolean();
      this.regionId = in.readLong();
      this.regionName = Bytes.readByteArray(in);
      this.regionNameStr = Bytes.toStringBinary(this.regionName);
      this.split = in.readBoolean();
      this.startKey = Bytes.readByteArray(in);
      try {
        HTableDescriptor htd = new HTableDescriptor();
        htd.readFields(in);
        this.tableName = htd.getName();
      } catch(EOFException eofe) {
         throw new IOException("HTD not found in input buffer", eofe);
      }
      this.hashCode = in.readInt();
    } else if (getVersion() == version) {
      this.endKey = Bytes.readByteArray(in);
      this.offLine = in.readBoolean();
      this.regionId = in.readLong();
      this.regionName = Bytes.readByteArray(in);
      this.regionNameStr = Bytes.toStringBinary(this.regionName);
      this.split = in.readBoolean();
      this.startKey = Bytes.readByteArray(in);
      this.tableName = Bytes.readByteArray(in);
      this.hashCode = in.readInt();
    } else {
      throw new IOException("Non-migratable/unknown version=" + getVersion());
    }
  }

  //
  // Comparable
  //

  public int compareTo(HRegionInfo o) {
    if (o == null) {
      return 1;
    }

    // Are regions of same table?
    int result = Bytes.compareTo(this.tableName, o.tableName);
    if (result != 0) {
      return result;
    }

    // Compare start keys.
    result = Bytes.compareTo(this.startKey, o.startKey);
    if (result != 0) {
      return result;
    }

    // Compare end keys.
    result = Bytes.compareTo(this.endKey, o.endKey);

    if (result != 0) {
      if (this.getStartKey().length != 0
              && this.getEndKey().length == 0) {
          return 1; // this is last region
      }
      if (o.getStartKey().length != 0
              && o.getEndKey().length == 0) {
          return -1; // o is the last region
      }
      return result;
    }

    // regionId is usually milli timestamp -- this defines older stamps
    // to be "smaller" than newer stamps in sort order.
    if (this.regionId > o.regionId) {
      return 1;
    } else if (this.regionId < o.regionId) {
      return -1;
    }

    if (this.offLine == o.offLine)
      return 0;
    if (this.offLine == true) return -1;
        
    return 1;
  }

  /**
   * @return Comparator to use comparing {@link KeyValue}s.
   */
  public KVComparator getComparator() {
    return isRootRegion()? KeyValue.ROOT_COMPARATOR: isMetaRegion()?
      KeyValue.META_COMPARATOR: KeyValue.COMPARATOR;
  }
}
