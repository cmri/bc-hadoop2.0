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
package org.apache.hadoop.hbase.mapreduce;

import org.apache.hadoop.hbase.util.Base64;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.TextInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.util.GenericOptionsParser;

import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.Lists;

/**
 * Tool to import data from a TSV file.
 *
 * This tool is rather simplistic - it doesn't do any quoting or
 * escaping, but is useful for many data loads.
 *
 * @see ImportTsv#usage(String)
 */
public class ImportTsv {
  final static String NAME = "importtsv";

  final static String MAPPER_CONF_KEY = "importtsv.mapper.class";
  final static String SKIP_LINES_CONF_KEY = "importtsv.skip.bad.lines";
  final static String BULK_OUTPUT_CONF_KEY = "importtsv.bulk.output";
  final static String COLUMNS_CONF_KEY = "importtsv.columns";
  final static String SEPARATOR_CONF_KEY = "importtsv.separator";
  final static String TIMESTAMP_CONF_KEY = "importtsv.timestamp";
  final static String DEFAULT_SEPARATOR = "\t";
  final static Class DEFAULT_MAPPER = TsvImporterMapper.class;
  private static HBaseAdmin hbaseAdmin;

  static class TsvParser {
    /**
     * Column families and qualifiers mapped to the TSV columns
     */
    private final byte[][] families;
    private final byte[][] qualifiers;

    private final byte separatorByte;

    private int rowKeyColumnIndex;

    private int maxColumnCount;

    // Default value must be negative
    public static final int DEFAULT_TIMESTAMP_COLUMN_INDEX = -1;

    private int timestampKeyColumnIndex = DEFAULT_TIMESTAMP_COLUMN_INDEX;

    public static String ROWKEY_COLUMN_SPEC = "HBASE_ROW_KEY";

    public static String TIMESTAMPKEY_COLUMN_SPEC = "HBASE_TS_KEY";

    /**
     * @param columnsSpecification the list of columns to parser out, comma separated.
     * The row key should be the special token TsvParser.ROWKEY_COLUMN_SPEC
     */
    public TsvParser(String columnsSpecification, String separatorStr) {
      // Configure separator
      byte[] separator = Bytes.toBytes(separatorStr);
      Preconditions.checkArgument(separator.length == 1,
        "TsvParser only supports single-byte separators");
      separatorByte = separator[0];

      // Configure columns
      ArrayList<String> columnStrings = Lists.newArrayList(
        Splitter.on(',').trimResults().split(columnsSpecification));

      maxColumnCount = columnStrings.size();
      families = new byte[maxColumnCount][];
      qualifiers = new byte[maxColumnCount][];

      for (int i = 0; i < columnStrings.size(); i++) {
        String str = columnStrings.get(i);
        if (ROWKEY_COLUMN_SPEC.equals(str)) {
          rowKeyColumnIndex = i;
          continue;
        }
        
        if (TIMESTAMPKEY_COLUMN_SPEC.equals(str)) {
          timestampKeyColumnIndex = i;
          continue;
        }
        
        String[] parts = str.split(":", 2);
        if (parts.length == 1) {
          families[i] = str.getBytes();
          qualifiers[i] = HConstants.EMPTY_BYTE_ARRAY;
        } else {
          families[i] = parts[0].getBytes();
          qualifiers[i] = parts[1].getBytes();
        }
      }
    }

    public boolean hasTimestamp() {
      return timestampKeyColumnIndex != DEFAULT_TIMESTAMP_COLUMN_INDEX;
    }

    public int getTimestampKeyColumnIndex() {
      return timestampKeyColumnIndex;
    }

    public int getRowKeyColumnIndex() {
      return rowKeyColumnIndex;
    }
    public byte[] getFamily(int idx) {
      return families[idx];
    }
    public byte[] getQualifier(int idx) {
      return qualifiers[idx];
    }

    public ParsedLine parse(byte[] lineBytes, int length)
    throws BadTsvLineException {
      // Enumerate separator offsets
      ArrayList<Integer> tabOffsets = new ArrayList<Integer>(maxColumnCount);
      for (int i = 0; i < length; i++) {
        if (lineBytes[i] == separatorByte) {
          tabOffsets.add(i);
        }
      }
      if (tabOffsets.isEmpty()) {
        throw new BadTsvLineException("No delimiter");
      }

      tabOffsets.add(length);

      if (tabOffsets.size() > maxColumnCount) {
        throw new BadTsvLineException("Excessive columns");
      } else if (tabOffsets.size() <= getRowKeyColumnIndex()) {
        throw new BadTsvLineException("No row key");
      } else if (hasTimestamp()
          && tabOffsets.size() <= getTimestampKeyColumnIndex()) {
        throw new BadTsvLineException("No timestamp");
      }
      return new ParsedLine(tabOffsets, lineBytes);
    }

    class ParsedLine {
      private final ArrayList<Integer> tabOffsets;
      private byte[] lineBytes;

      ParsedLine(ArrayList<Integer> tabOffsets, byte[] lineBytes) {
        this.tabOffsets = tabOffsets;
        this.lineBytes = lineBytes;
      }

      public int getRowKeyOffset() {
        return getColumnOffset(rowKeyColumnIndex);
      }
      public int getRowKeyLength() {
        return getColumnLength(rowKeyColumnIndex);
      }
      
      public long getTimestamp(long ts) throws BadTsvLineException {
        // Return ts if HBASE_TS_KEY is not configured in column spec
        if (!hasTimestamp()) {
          return ts;
        }

        String timeStampStr = Bytes.toString(lineBytes,
            getColumnOffset(timestampKeyColumnIndex),
            getColumnLength(timestampKeyColumnIndex));
        try {
          return Long.parseLong(timeStampStr);
        } catch (NumberFormatException nfe) {
          // treat this record as bad record
          throw new BadTsvLineException("Invalid timestamp " + timeStampStr);
        }
      }
      
      public int getColumnOffset(int idx) {
        if (idx > 0)
          return tabOffsets.get(idx - 1) + 1;
        else
          return 0;
      }
      public int getColumnLength(int idx) {
        return tabOffsets.get(idx) - getColumnOffset(idx);
      }
      public int getColumnCount() {
        return tabOffsets.size();
      }
      public byte[] getLineBytes() {
        return lineBytes;
      }
    }

    public static class BadTsvLineException extends Exception {
      public BadTsvLineException(String err) {
        super(err);
      }
      private static final long serialVersionUID = 1L;
    }
  }

  /**
   * Sets up the actual job.
   *
   * @param conf  The current configuration.
   * @param args  The command line parameters.
   * @return The newly created job.
   * @throws IOException When setting up the job fails.
   */
  public static Job createSubmittableJob(Configuration conf, String[] args)
  throws IOException, ClassNotFoundException {

    // Support non-XML supported characters
    // by re-encoding the passed separator as a Base64 string.
    String actualSeparator = conf.get(SEPARATOR_CONF_KEY);
    if (actualSeparator != null) {
      conf.set(SEPARATOR_CONF_KEY,
               Base64.encodeBytes(actualSeparator.getBytes()));
    }

    // See if a non-default Mapper was set
    String mapperClassName = conf.get(MAPPER_CONF_KEY);
    Class mapperClass = mapperClassName != null ?
        Class.forName(mapperClassName) : DEFAULT_MAPPER;

    String tableName = args[0];
    Path inputDir = new Path(args[1]);
    Job job = new Job(conf, NAME + "_" + tableName);
    job.setJarByClass(mapperClass);
    FileInputFormat.setInputPaths(job, inputDir);
    job.setInputFormatClass(TextInputFormat.class);
    job.setMapperClass(mapperClass);

    String hfileOutPath = conf.get(BULK_OUTPUT_CONF_KEY);
    if (hfileOutPath != null) {
      if (!doesTableExist(tableName)) {
        createTable(conf, tableName);
      }
      HTable table = new HTable(conf, tableName);
      job.setReducerClass(PutSortReducer.class);
      Path outputDir = new Path(hfileOutPath);
      FileOutputFormat.setOutputPath(job, outputDir);
      job.setMapOutputKeyClass(ImmutableBytesWritable.class);
      job.setMapOutputValueClass(Put.class);
      HFileOutputFormat.configureIncrementalLoad(job, table);
    } else {
      // No reducers.  Just write straight to table.  Call initTableReducerJob
      // to set up the TableOutputFormat.
      TableMapReduceUtil.initTableReducerJob(tableName, null, job);
      job.setNumReduceTasks(0);
    }

    TableMapReduceUtil.addDependencyJars(job);
    TableMapReduceUtil.addDependencyJars(job.getConfiguration(),
        com.google.common.base.Function.class /* Guava used by TsvParser */);
    return job;
  }

  private static boolean doesTableExist(String tableName) throws IOException {
    return hbaseAdmin.tableExists(tableName.getBytes());
  }

  private static void createTable(Configuration conf, String tableName)
      throws IOException {
    HTableDescriptor htd = new HTableDescriptor(tableName.getBytes());
    String columns[] = conf.getStrings(COLUMNS_CONF_KEY);
    Set<String> cfSet = new HashSet<String>();
    for (String aColumn : columns) {
      if (TsvParser.ROWKEY_COLUMN_SPEC.equals(aColumn)) continue;
      // we are only concerned with the first one (in case this is a cf:cq)
      cfSet.add(aColumn.split(":", 2)[0]);
    }
    for (String cf : cfSet) {
      HColumnDescriptor hcd = new HColumnDescriptor(Bytes.toBytes(cf));
      htd.addFamily(hcd);
    }
    hbaseAdmin.createTable(htd);
  }

  /*
   * @param errorMsg Error message.  Can be null.
   */
  private static void usage(final String errorMsg) {
    if (errorMsg != null && errorMsg.length() > 0) {
      System.err.println("ERROR: " + errorMsg);
    }
    String usage = 
      "Usage: " + NAME + " -Dimporttsv.columns=a,b,c <tablename> <inputdir>\n" +
      "\n" +
      "Imports the given input directory of TSV data into the specified table.\n" +
      "\n" +
      "The column names of the TSV data must be specified using the -Dimporttsv.columns\n" +
      "option. This option takes the form of comma-separated column names, where each\n" +
      "column name is either a simple column family, or a columnfamily:qualifier. The special\n" +
      "column name HBASE_ROW_KEY is used to designate that this column should be used\n" +
      "as the row key for each imported record. You must specify exactly one column\n" +
      "to be the row key, and you must specify a column name for every column that exists in the\n" +
      "input data. Another special column HBASE_TS_KEY designates that this column should be\n" +
      "used as timestamp for each record. Unlike HBASE_ROW_KEY, HBASE_TS_KEY is optional.\n" +
      "You must specify atmost one column as timestamp key for each imported record.\n" +
      "Record with invalid timestamps (blank, non-numeric) will be treated as bad record.\n" +
      "Note: if you use this option, then 'importtsv.timestamp' option will be ignored.\n" +
      "\n" +
      "By default importtsv will load data directly into HBase. To instead generate\n" +
      "HFiles of data to prepare for a bulk data load, pass the option:\n" +
      "  -D" + BULK_OUTPUT_CONF_KEY + "=/path/for/output\n" +
      "  Note: if you do not use this option, then the target table must already exist in HBase\n" +
      "\n" +
      "Other options that may be specified with -D include:\n" +
      "  -D" + SKIP_LINES_CONF_KEY + "=false - fail if encountering an invalid line\n" +
      "  '-D" + SEPARATOR_CONF_KEY + "=|' - eg separate on pipes instead of tabs\n" +
      "  -D" + TIMESTAMP_CONF_KEY + "=currentTimeAsLong - use the specified timestamp for the import\n" +
      "  -D" + MAPPER_CONF_KEY + "=my.Mapper - A user-defined Mapper to use instead of " + DEFAULT_MAPPER.getName() + "\n" +
      "For performance consider the following options:\n" +
      "  -Dmapred.map.tasks.speculative.execution=false\n" +
      "  -Dmapred.reduce.tasks.speculative.execution=false";

    System.err.println(usage);
  }

  /**
   * Used only by test method
   * @param conf
   */
  static void createHbaseAdmin(Configuration conf) throws IOException {
    hbaseAdmin = new HBaseAdmin(conf);
  }

  /**
   * Main entry point.
   *
   * @param args  The command line parameters.
   * @throws Exception When running the job fails.
   */
  public static void main(String[] args) throws Exception {
    Configuration conf = HBaseConfiguration.create();
    String[] otherArgs = new GenericOptionsParser(conf, args).getRemainingArgs();
    if (otherArgs.length < 2) {
      usage("Wrong number of arguments: " + otherArgs.length);
      System.exit(-1);
    }

    // Make sure columns are specified
    String columns[] = conf.getStrings(COLUMNS_CONF_KEY);
    if (columns == null) {
      usage("No columns specified. Please specify with -D" +
            COLUMNS_CONF_KEY+"=...");
      System.exit(-1);
    }

    // Make sure they specify exactly one column as the row key
    int rowkeysFound=0;
    for (String col : columns) {
      if (col.equals(TsvParser.ROWKEY_COLUMN_SPEC)) rowkeysFound++;
    }
    if (rowkeysFound != 1) {
      usage("Must specify exactly one column as " + TsvParser.ROWKEY_COLUMN_SPEC);
      System.exit(-1);
    }

    // Make sure we have at most one column as the timestamp key
    int tskeysFound = 0;
    for (String col : columns) {
      if (col.equals(TsvParser.TIMESTAMPKEY_COLUMN_SPEC))
        tskeysFound++;
    }
    if (tskeysFound > 1) {
      usage("Must specify at most one column as "
          + TsvParser.TIMESTAMPKEY_COLUMN_SPEC);
      System.exit(-1);
    }
    
    // Make sure one or more columns are specified excluding rowkey and
    // timestamp key
    if (columns.length - (rowkeysFound + tskeysFound) < 1) {
      usage("One or more columns in addition to the row key and timestamp(optional) are required");
      System.exit(-1);
    }

    // If timestamp option is not specified, use current system time.
    long timstamp = conf
        .getLong(TIMESTAMP_CONF_KEY, System.currentTimeMillis());

    // Set it back to replace invalid timestamp (non-numeric) with current
    // system time
    conf.setLong(TIMESTAMP_CONF_KEY, timstamp); 
    
    hbaseAdmin = new HBaseAdmin(conf);
    Job job = createSubmittableJob(conf, otherArgs);
    System.exit(job.waitForCompletion(true) ? 0 : 1);
  }

}
