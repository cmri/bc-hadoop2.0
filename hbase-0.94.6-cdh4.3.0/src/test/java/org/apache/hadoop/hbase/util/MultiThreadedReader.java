/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.apache.hadoop.hbase.util;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HRegionLocation;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Result;

/** Creates multiple threads that read and verify previously written data */
public class MultiThreadedReader extends MultiThreadedAction
{
  private static final Log LOG = LogFactory.getLog(MultiThreadedReader.class);

  private Set<HBaseReaderThread> readers = new HashSet<HBaseReaderThread>();
  private final double verifyPercent;
  private volatile boolean aborted;

  private MultiThreadedWriter writer = null;

  /**
   * The number of keys verified in a sequence. This will never be larger than
   * the total number of keys in the range. The reader might also verify
   * random keys when it catches up with the writer.
   */
  private final AtomicLong numUniqueKeysVerified = new AtomicLong();

  /**
   * Default maximum number of read errors to tolerate before shutting down all
   * readers.
   */
  public static final int DEFAULT_MAX_ERRORS = 10;

  /**
   * Default "window" size between the last key written by the writer and the
   * key that we attempt to read. The lower this number, the stricter our
   * testing is. If this is zero, we always attempt to read the highest key
   * in the contiguous sequence of keys written by the writers.
   */
  public static final int DEFAULT_KEY_WINDOW = 0;

  protected AtomicLong numKeysVerified = new AtomicLong(0);
  private AtomicLong numReadErrors = new AtomicLong(0);
  private AtomicLong numReadFailures = new AtomicLong(0);

  private int maxErrors = DEFAULT_MAX_ERRORS;
  private int keyWindow = DEFAULT_KEY_WINDOW;

  public MultiThreadedReader(Configuration conf, byte[] tableName,
      byte[] columnFamily, double verifyPercent) {
    super(conf, tableName, columnFamily, "R");
    this.verifyPercent = verifyPercent;
  }

  public void linkToWriter(MultiThreadedWriter writer) {
    this.writer = writer;
    writer.setTrackInsertedKeys(true);
  }

  public void setMaxErrors(int maxErrors) {
    this.maxErrors = maxErrors;
  }

  public void setKeyWindow(int keyWindow) {
    this.keyWindow = keyWindow;
  }

  @Override
  public void start(long startKey, long endKey, int numThreads)
      throws IOException {
    super.start(startKey, endKey, numThreads);
    if (verbose) {
      LOG.debug("Reading keys [" + startKey + ", " + endKey + ")");
    }

    for (int i = 0; i < numThreads; ++i) {
      HBaseReaderThread reader = new HBaseReaderThread(i);
      readers.add(reader);
    }
    startThreads(readers);
  }

  public class HBaseReaderThread extends Thread {
    private final int readerId;
    private final HTable table;
    private final Random random = new Random();

    /** The "current" key being read. Increases from startKey to endKey. */
    private long curKey;

    /** Time when the thread started */
    private long startTimeMs;

    /** If we are ahead of the writer and reading a random key. */
    private boolean readingRandomKey;

    /**
     * @param readerId only the keys with this remainder from division by
     *          {@link #numThreads} will be read by this thread
     */
    public HBaseReaderThread(int readerId) throws IOException {
      this.readerId = readerId;
      table = new HTable(conf, tableName);
      setName(getClass().getSimpleName() + "_" + readerId);
    }

    @Override
    public void run() {
      try {
        runReader();
      } finally {
        try {
          table.close();
        } catch (IOException e) {
          LOG.error("Error closing table", e);
        }
        numThreadsWorking.decrementAndGet();
      }
    }

    private void runReader() {
      if (verbose) {
        LOG.info("Started thread #" + readerId + " for reads...");
      }

      startTimeMs = System.currentTimeMillis();
      curKey = startKey;
      while (curKey < endKey && !aborted) {
        long k = getNextKeyToRead();

        // A sanity check for the key range.
        if (k < startKey || k >= endKey) {
          numReadErrors.incrementAndGet();
          throw new AssertionError("Load tester logic error: proposed key " +
              "to read " + k + " is out of range (startKey=" + startKey +
              ", endKey=" + endKey + ")");
        }

        if (k % numThreads != readerId ||
            writer != null && writer.failedToWriteKey(k)) {
          // Skip keys that this thread should not read, as well as the keys
          // that we know the writer failed to write.
          continue;
        }

        readKey(k);
        if (k == curKey - 1 && !readingRandomKey) {
          // We have verified another unique key.
          numUniqueKeysVerified.incrementAndGet();
        }
      }
    }

    /**
     * Should only be used for the concurrent writer/reader workload. The
     * maximum key we are allowed to read, subject to the "key window"
     * constraint.
     */
    private long maxKeyWeCanRead() {
      long insertedUpToKey = writer.insertedUpToKey();
      if (insertedUpToKey >= endKey - 1) {
        // The writer has finished writing our range, so we can read any
        // key in the range.
        return endKey - 1;
      }
      return Math.min(endKey - 1, writer.insertedUpToKey() - keyWindow);
    }

    private long getNextKeyToRead() {
      readingRandomKey = false;
      if (writer == null || curKey <= maxKeyWeCanRead()) {
        return curKey++;
      }

      // We caught up with the writer. See if we can read any keys at all.
      long maxKeyToRead;
      while ((maxKeyToRead = maxKeyWeCanRead()) < startKey) {
        // The writer has not written sufficient keys for us to be able to read
        // anything at all. Sleep a bit. This should only happen in the
        // beginning of a load test run.
        Threads.sleepWithoutInterrupt(50);
      }

      if (curKey <= maxKeyToRead) {
        // The writer wrote some keys, and we are now allowed to read our
        // current key.
        return curKey++;
      }

      // startKey <= maxKeyToRead <= curKey - 1. Read one of the previous keys.
      // Don't increment the current key -- we still have to try reading it
      // later. Set a flag to make sure that we don't count this key towards
      // the set of unique keys we have verified.
      readingRandomKey = true;
      return startKey + Math.abs(random.nextLong())
          % (maxKeyToRead - startKey + 1);
    }

    private Get readKey(long keyToRead) {
      Get get = new Get(
          LoadTestKVGenerator.md5PrefixedKey(keyToRead).getBytes());
      get.addFamily(columnFamily);

      try {
        if (verbose) {
          LOG.info("[" + readerId + "] " + "Querying key " + keyToRead
              + ", cf " + Bytes.toStringBinary(columnFamily));
        }
        queryKey(get, random.nextInt(100) < verifyPercent);
      } catch (IOException e) {
        numReadFailures.addAndGet(1);
        LOG.debug("[" + readerId + "] FAILED read, key = " + (keyToRead + "")
            + ", time from start: "
            + (System.currentTimeMillis() - startTimeMs) + " ms");
      }
      return get;
    }

    public void queryKey(Get get, boolean verify) throws IOException {
      String rowKey = Bytes.toString(get.getRow());

      // read the data
      long start = System.currentTimeMillis();
      Result result = table.get(get);
      totalOpTimeMs.addAndGet(System.currentTimeMillis() - start);
      numKeys.addAndGet(1);

      // if we got no data report error
      if (result.isEmpty()) {
         HRegionLocation hloc = table.getRegionLocation(
             Bytes.toBytes(rowKey));
        LOG.info("Key = " + rowKey + ", RegionServer: "
            + hloc.getHostname());
        numReadErrors.addAndGet(1);
        LOG.error("No data returned, tried to get actions for key = "
            + rowKey + (writer == null ? "" : ", keys inserted by writer: " +
                writer.numKeys.get() + ")"));

         if (numReadErrors.get() > maxErrors) {
          LOG.error("Aborting readers -- found more than " + maxErrors
              + " errors\n");
           aborted = true;
         }
      }

      if (result.getFamilyMap(columnFamily) != null) {
        // increment number of columns read
        numCols.addAndGet(result.getFamilyMap(columnFamily).size());

        if (verify) {
          // verify the result
          List<KeyValue> keyValues = result.list();
          for (KeyValue kv : keyValues) {
            String qual = new String(kv.getQualifier());

            // if something does not look right report it
            if (!LoadTestKVGenerator.verify(rowKey, qual, kv.getValue())) {
              numReadErrors.addAndGet(1);
              LOG.error("Error checking data for key = " + rowKey
                  + ", actionId = " + qual);
            }
          }
          numKeysVerified.addAndGet(1);
        }
      }
    }

  }

  public long getNumReadFailures() {
    return numReadFailures.get();
  }

  public long getNumReadErrors() {
    return numReadErrors.get();
  }

  public long getNumKeysVerified() {
    return numKeysVerified.get();
  }

  public long getNumUniqueKeysVerified() {
    return numUniqueKeysVerified.get();
  }

  @Override
  protected String progressInfo() {
    StringBuilder sb = new StringBuilder();
    appendToStatus(sb, "verified", numKeysVerified.get());
    appendToStatus(sb, "READ FAILURES", numReadFailures.get());
    appendToStatus(sb, "READ ERRORS", numReadErrors.get());
    return sb.toString();
  }

}
