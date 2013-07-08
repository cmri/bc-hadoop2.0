
/*
 * Copyright 2011 The Apache Software Foundation
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
package org.apache.hadoop.hbase.io.hfile;

import java.io.DataInput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PosixParser;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.io.hfile.HFile.FileInfo;
import org.apache.hadoop.hbase.regionserver.TimeRangeTracker;
import org.apache.hadoop.hbase.regionserver.metrics.SchemaMetrics;
import org.apache.hadoop.hbase.util.BloomFilter;
import org.apache.hadoop.hbase.util.BloomFilterFactory;
import org.apache.hadoop.hbase.util.ByteBloomFilter;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.FSUtils;
import org.apache.hadoop.hbase.util.Writables;

/**
 * Implements pretty-printing functionality for {@link HFile}s.
 */
public class HFilePrettyPrinter {

  private static final Log LOG = LogFactory.getLog(HFilePrettyPrinter.class);

  private Options options = new Options();

  private boolean verbose;
  private boolean printValue;
  private boolean printKey;
  private boolean shouldPrintMeta;
  private boolean printBlocks;
  private boolean printStats;
  private boolean checkRow;
  private boolean checkFamily;
  private boolean isSeekToRow = false;

  /**
   * The row which the user wants to specify and print all the KeyValues for.
   */
  private byte[] row = null;
  private Configuration conf;

  private List<Path> files = new ArrayList<Path>();
  private int count;

  private static final String FOUR_SPACES = "    ";

  public HFilePrettyPrinter() {
    options.addOption("v", "verbose", false,
        "Verbose output; emits file and meta data delimiters");
    options.addOption("p", "printkv", false, "Print key/value pairs");
    options.addOption("e", "printkey", false, "Print keys");
    options.addOption("m", "printmeta", false, "Print meta data of file");
    options.addOption("b", "printblocks", false, "Print block index meta data");
    options.addOption("k", "checkrow", false,
        "Enable row order check; looks for out-of-order keys");
    options.addOption("a", "checkfamily", false, "Enable family check");
    options.addOption("f", "file", true,
        "File to scan. Pass full-path; e.g. hdfs://a:9000/hbase/.META./12/34");
    options.addOption("w", "seekToRow", true,
      "Seek to this row and print all the kvs for this row only");
    options.addOption("r", "region", true,
        "Region to scan. Pass region name; e.g. '.META.,,1'");
    options.addOption("s", "stats", false, "Print statistics");
  }

  public boolean parseOptions(String args[]) throws ParseException,
      IOException {
    if (args.length == 0) {
      HelpFormatter formatter = new HelpFormatter();
      formatter.printHelp("HFile", options, true);
      return false;
    }
    CommandLineParser parser = new PosixParser();
    CommandLine cmd = parser.parse(options, args);

    verbose = cmd.hasOption("v");
    printValue = cmd.hasOption("p");
    printKey = cmd.hasOption("e") || printValue;
    shouldPrintMeta = cmd.hasOption("m");
    printBlocks = cmd.hasOption("b");
    printStats = cmd.hasOption("s");
    checkRow = cmd.hasOption("k");
    checkFamily = cmd.hasOption("a");

    if (cmd.hasOption("f")) {
      files.add(new Path(cmd.getOptionValue("f")));
    }

    if (cmd.hasOption("w")) {
      String key = cmd.getOptionValue("w");
      if (key != null && key.length() != 0) {
        row = key.getBytes();
        isSeekToRow = true;
      } else {
        System.err.println("Invalid row is specified.");
        System.exit(-1);
      }
    }

    if (cmd.hasOption("r")) {
      String regionName = cmd.getOptionValue("r");
      byte[] rn = Bytes.toBytes(regionName);
      byte[][] hri = HRegionInfo.parseRegionName(rn);
      Path rootDir = FSUtils.getRootDir(conf);
      Path tableDir = new Path(rootDir, Bytes.toString(hri[0]));
      String enc = HRegionInfo.encodeRegionName(rn);
      Path regionDir = new Path(tableDir, enc);
      if (verbose)
        System.out.println("region dir -> " + regionDir);
      List<Path> regionFiles = HFile.getStoreFiles(FileSystem.get(conf),
          regionDir);
      if (verbose)
        System.out.println("Number of region files found -> "
            + regionFiles.size());
      if (verbose) {
        int i = 1;
        for (Path p : regionFiles) {
          if (verbose)
            System.out.println("Found file[" + i++ + "] -> " + p);
        }
      }
      files.addAll(regionFiles);
    }

    return true;
  }

  /**
   * Runs the command-line pretty-printer, and returns the desired command
   * exit code (zero for success, non-zero for failure).
   */
  public int run(String[] args) {
    conf = HBaseConfiguration.create();
    conf.set("fs.defaultFS",
        conf.get(org.apache.hadoop.hbase.HConstants.HBASE_DIR));
    conf.set("fs.default.name",
        conf.get(org.apache.hadoop.hbase.HConstants.HBASE_DIR));
    SchemaMetrics.configureGlobally(conf);
    try {
      if (!parseOptions(args))
        return 1;
    } catch (IOException ex) {
      LOG.error("Error parsing command-line options", ex);
      return 1;
    } catch (ParseException ex) {
      LOG.error("Error parsing command-line options", ex);
      return 1;
    }

    // iterate over all files found
    for (Path fileName : files) {
      try {
        processFile(fileName);
      } catch (IOException ex) {
        LOG.error("Error reading " + fileName, ex);
      }
    }

    if (verbose || printKey) {
      System.out.println("Scanned kv count -> " + count);
    }

    return 0;
  }

  private void processFile(Path file) throws IOException {
    if (verbose)
      System.out.println("Scanning -> " + file);
    FileSystem fs = file.getFileSystem(conf);
    if (!fs.exists(file)) {
      System.err.println("ERROR, file doesnt exist: " + file);
    }

    HFile.Reader reader = HFile.createReader(fs, file, new CacheConfig(conf));

    Map<byte[], byte[]> fileInfo = reader.loadFileInfo();

    KeyValueStatsCollector fileStats = null;

    if (verbose || printKey || checkRow || checkFamily || printStats) {
      // scan over file and read key/value's and check if requested
      HFileScanner scanner = reader.getScanner(false, false, false);
      fileStats = new KeyValueStatsCollector();
      boolean shouldScanKeysValues = false;
      if (this.isSeekToRow) {
        // seek to the first kv on this row
        shouldScanKeysValues = 
          (scanner.seekTo(KeyValue.createFirstOnRow(this.row).getKey()) != -1);
      } else {
        shouldScanKeysValues = scanner.seekTo();
      }
      if (shouldScanKeysValues)
        scanKeysValues(file, fileStats, scanner, row);
    }

    // print meta data
    if (shouldPrintMeta) {
      printMeta(reader, fileInfo);
    }

    if (printBlocks) {
      System.out.println("Block Index:");
      System.out.println(reader.getDataBlockIndexReader());
    }

    if (printStats) {
      fileStats.finish();
      System.out.println("Stats:\n" + fileStats);
    }

    reader.close();
  }

  private void scanKeysValues(Path file, KeyValueStatsCollector fileStats,
      HFileScanner scanner,  byte[] row) throws IOException {
    KeyValue pkv = null;
    do {
      KeyValue kv = scanner.getKeyValue();
      if (row != null && row.length != 0) {
        int result = Bytes.compareTo(kv.getRow(), row);
        if (result > 0) {
          break;
        } else if (result < 0) {
          continue;
        }
      }
      // collect stats
      if (printStats) {
        fileStats.collect(kv);
      }
      // dump key value
      if (printKey) {
        System.out.print("K: " + kv);
        if (printValue) {
          System.out.print(" V: " + Bytes.toStringBinary(kv.getValue()));
        }
        System.out.println();
      }
      // check if rows are in order
      if (checkRow && pkv != null) {
        if (Bytes.compareTo(pkv.getRow(), kv.getRow()) > 0) {
          System.err.println("WARNING, previous row is greater then"
              + " current row\n\tfilename -> " + file + "\n\tprevious -> "
              + Bytes.toStringBinary(pkv.getKey()) + "\n\tcurrent  -> "
              + Bytes.toStringBinary(kv.getKey()));
        }
      }
      // check if families are consistent
      if (checkFamily) {
        String fam = Bytes.toString(kv.getFamily());
        if (!file.toString().contains(fam)) {
          System.err.println("WARNING, filename does not match kv family,"
              + "\n\tfilename -> " + file + "\n\tkeyvalue -> "
              + Bytes.toStringBinary(kv.getKey()));
        }
        if (pkv != null
            && !Bytes.equals(pkv.getFamily(), kv.getFamily())) {
          System.err.println("WARNING, previous kv has different family"
              + " compared to current key\n\tfilename -> " + file
              + "\n\tprevious -> " + Bytes.toStringBinary(pkv.getKey())
              + "\n\tcurrent  -> " + Bytes.toStringBinary(kv.getKey()));
        }
      }
      pkv = kv;
      ++count;
    } while (scanner.next());
  }

  /**
   * Format a string of the form "k1=v1, k2=v2, ..." into separate lines
   * with a four-space indentation.
   */
  private static String asSeparateLines(String keyValueStr) {
    return keyValueStr.replaceAll(", ([a-zA-Z]+=)",
                                  ",\n" + FOUR_SPACES + "$1");
  }

  private void printMeta(HFile.Reader reader, Map<byte[], byte[]> fileInfo)
      throws IOException {
    System.out.println("Block index size as per heapsize: "
        + reader.indexSize());
    System.out.println(asSeparateLines(reader.toString()));
    System.out.println("Trailer:\n    "
        + asSeparateLines(reader.getTrailer().toString()));
    System.out.println("Fileinfo:");
    for (Map.Entry<byte[], byte[]> e : fileInfo.entrySet()) {
      System.out.print(FOUR_SPACES + Bytes.toString(e.getKey()) + " = ");
      if (Bytes.compareTo(e.getKey(), Bytes.toBytes("MAX_SEQ_ID_KEY")) == 0) {
        long seqid = Bytes.toLong(e.getValue());
        System.out.println(seqid);
      } else if (Bytes.compareTo(e.getKey(), Bytes.toBytes("TIMERANGE")) == 0) {
        TimeRangeTracker timeRangeTracker = new TimeRangeTracker();
        Writables.copyWritable(e.getValue(), timeRangeTracker);
        System.out.println(timeRangeTracker.getMinimumTimestamp() + "...."
            + timeRangeTracker.getMaximumTimestamp());
      } else if (Bytes.compareTo(e.getKey(), FileInfo.AVG_KEY_LEN) == 0
          || Bytes.compareTo(e.getKey(), FileInfo.AVG_VALUE_LEN) == 0) {
        System.out.println(Bytes.toInt(e.getValue()));
      } else {
        System.out.println(Bytes.toStringBinary(e.getValue()));
      }
    }

    System.out.println("Mid-key: " + Bytes.toStringBinary(reader.midkey()));

    // Printing general bloom information
    DataInput bloomMeta = reader.getGeneralBloomFilterMetadata();
    BloomFilter bloomFilter = null;
    if (bloomMeta != null)
      bloomFilter = BloomFilterFactory.createFromMeta(bloomMeta, reader);

    System.out.println("Bloom filter:");
    if (bloomFilter != null) {
      System.out.println(FOUR_SPACES + bloomFilter.toString().replaceAll(
          ByteBloomFilter.STATS_RECORD_SEP, "\n" + FOUR_SPACES));
    } else {
      System.out.println(FOUR_SPACES + "Not present");
    }

    // Printing delete bloom information
    bloomMeta = reader.getDeleteBloomFilterMetadata();
    bloomFilter = null;
    if (bloomMeta != null)
      bloomFilter = BloomFilterFactory.createFromMeta(bloomMeta, reader);

    System.out.println("Delete Family Bloom filter:");
    if (bloomFilter != null) {
      System.out.println(FOUR_SPACES
          + bloomFilter.toString().replaceAll(ByteBloomFilter.STATS_RECORD_SEP,
              "\n" + FOUR_SPACES));
    } else {
      System.out.println(FOUR_SPACES + "Not present");
    }
  }

  private static class LongStats {
    private long min = Long.MAX_VALUE;
    private long max = Long.MIN_VALUE;
    private long sum = 0;
    private long count = 0;

    void collect(long d) {
      if (d < min) min = d;
      if (d > max) max = d;
      sum += d;
      count++;
    }

    public String toString() {
      return "count: " + count +
        "\tmin: " + min +
        "\tmax: " + max +
        "\tmean: " + ((double)sum/count);
    }
  }

  private static class KeyValueStatsCollector {
    LongStats keyLen = new LongStats();
    LongStats valLen = new LongStats();
    LongStats rowSizeBytes = new LongStats();
    LongStats rowSizeCols = new LongStats();

    long curRowBytes = 0;
    long curRowCols = 0;

    byte[] biggestRow = null;

    private KeyValue prevKV = null;
    private long maxRowBytes = 0;

    public void collect(KeyValue kv) {
      keyLen.collect(kv.getKeyLength());
      valLen.collect(kv.getValueLength());
      if (prevKV != null &&
          KeyValue.COMPARATOR.compareRows(prevKV, kv) != 0) {
        // new row
        collectRow();
      }
      curRowBytes += kv.getLength();
      curRowCols++;
      prevKV = kv;
    }

    private void collectRow() {
      rowSizeBytes.collect(curRowBytes);
      rowSizeCols.collect(curRowCols);

      if (curRowBytes > maxRowBytes && prevKV != null) {
        biggestRow = prevKV.getRow();
      }

      curRowBytes = 0;
      curRowCols = 0;
    }

    public void finish() {
      if (curRowCols > 0) {
        collectRow();
      }
    }

    @Override
    public String toString() {
      if (prevKV == null)
        return "no data available for statistics";

      return
        "Key length: " + keyLen + "\n" +
        "Val length: " + valLen + "\n" +
        "Row size (bytes): " + rowSizeBytes + "\n" +
        "Row size (columns): " + rowSizeCols + "\n" +
        "Key of biggest row: " + Bytes.toStringBinary(biggestRow);
    }
  }
}
