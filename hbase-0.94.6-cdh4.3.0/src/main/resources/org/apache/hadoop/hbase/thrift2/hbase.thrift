/*
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

// NOTE: The "required" and "optional" keywords for the service methods are purely for documentation

namespace java org.apache.hadoop.hbase.thrift2.generated
namespace cpp apache.hadoop.hbase.thrift2
namespace rb Apache.Hadoop.Hbase.Thrift2
namespace py hbase
namespace perl Hbase

struct TTimeRange {
  1: required i64 minStamp,
  2: required i64 maxStamp
}

/**
 * Addresses a single cell or multiple cells
 * in a HBase table by column family and optionally
 * a column qualifier and timestamp
 */
struct TColumn {
  1: required binary family,
  2: optional binary qualifier,
  3: optional i64 timestamp
}

/**
 * Represents a single cell and its value.
 */
struct TColumnValue {
  1: required binary family,
  2: required binary qualifier,
  3: required binary value,
  4: optional i64 timestamp
}

/**
 * Represents a single cell and the amount to increment it by
 */ 
struct TColumnIncrement {
  1: required binary family,
  2: required binary qualifier,
  3: optional i64 amount = 1
}

/**
 * if no Result is found, row and columnValues will not be set.
 */
struct TResult {
  1: optional binary row,
  2: required list<TColumnValue> columnValues
}

/**
 * Specify type of delete:
 *  - DELETE_COLUMN means exactly one version will be removed,
 *  - DELETE_COLUMNS means previous versions will also be removed.
 */
enum TDeleteType {
  DELETE_COLUMN = 0,
  DELETE_COLUMNS = 1
}

/**
 * Used to perform Get operations on a single row.
 *
 * The scope can be further narrowed down by specifying a list of
 * columns or column families.
 *
 * To get everything for a row, instantiate a Get object with just the row to get.
 * To further define the scope of what to get you can add a timestamp or time range
 * with an optional maximum number of versions to return.
 *
 * If you specify a time range and a timestamp the range is ignored.
 * Timestamps on TColumns are ignored.
 *
 * TODO: Filter, Locks
 */
struct TGet {
  1: required binary row,
  2: optional list<TColumn> columns,

  3: optional i64 timestamp,
  4: optional TTimeRange timeRange,

  5: optional i32 maxVersions,
}

/**
 * Used to perform Put operations for a single row.
 *
 * Add column values to this object and they'll be added.
 * You can provide a default timestamp if the column values
 * don't have one. If you don't provide a default timestamp
 * the current time is inserted.
 *
 * You can also specify if this Put should be written
 * to the write-ahead Log (WAL) or not. It defaults to true.
 */
struct TPut {
  1: required binary row,
  2: required list<TColumnValue> columnValues
  3: optional i64 timestamp,
  4: optional bool writeToWal = 1
}

/**
 * Used to perform Delete operations on a single row.
 *
 * The scope can be further narrowed down by specifying a list of
 * columns or column families as TColumns.
 *
 * Specifying only a family in a TColumn will delete the whole family.
 * If a timestamp is specified all versions with a timestamp less than
 * or equal to this will be deleted. If no timestamp is specified the
 * current time will be used.
 *
 * Specifying a family and a column qualifier in a TColumn will delete only
 * this qualifier. If a timestamp is specified only versions equal
 * to this timestamp will be deleted. If no timestamp is specified the
 * most recent version will be deleted.  To delete all previous versions,
 * specify the DELETE_COLUMNS TDeleteType.
 *
 * The top level timestamp is only used if a complete row should be deleted
 * (i.e. no columns are passed) and if it is specified it works the same way
 * as if you had added a TColumn for every column family and this timestamp
 * (i.e. all versions older than or equal in all column families will be deleted)
 *
 */
struct TDelete {
  1: required binary row,
  2: optional list<TColumn> columns,
  3: optional i64 timestamp,
  4: optional TDeleteType deleteType = 1,
  5: optional bool writeToWal = 1
}

/**
 * Used to perform Increment operations for a single row.
 * 
 * You can specify if this Increment should be written
 * to the write-ahead Log (WAL) or not. It defaults to true.
 */
struct TIncrement {
  1: required binary row,
  2: required list<TColumnIncrement> columns,
  3: optional bool writeToWal = 1
}

/**
 * Any timestamps in the columns are ignored, use timeRange to select by timestamp.
 * Max versions defaults to 1.
 */
struct TScan {
  1: optional binary startRow,
  2: optional binary stopRow,
  3: optional list<TColumn> columns
  4: optional i32 caching,
  5: optional i32 maxVersions=1,
  6: optional TTimeRange timeRange,
}

//
// Exceptions
//

/**
 * A TIOError exception signals that an error occurred communicating
 * to the HBase master or a HBase region server. Also used to return
 * more general HBase error conditions.
 */
exception TIOError {
  1: optional string message
}

/**
 * A TIllegalArgument exception indicates an illegal or invalid
 * argument was passed into a procedure.
 */
exception TIllegalArgument {
  1: optional string message
}

service THBaseService {

  /**
   * Test for the existence of columns in the table, as specified in the TGet.
   *
   * @return true if the specified TGet matches one or more keys, false if not
   */
  bool exists(
    /** the table to check on */
    1: required binary table,

    /** the TGet to check for */
    2: required TGet get
  ) throws (1:TIOError io)

  /**
   * Method for getting data from a row.
   *
   * If the row cannot be found an empty Result is returned.
   * This can be checked by the empty field of the TResult
   *
   * @return the result
   */
  TResult get(
    /** the table to get from */
    1: required binary table,

    /** the TGet to fetch */
    2: required TGet get
  ) throws (1: TIOError io)

  /**
   * Method for getting multiple rows.
   *
   * If a row cannot be found there will be a null
   * value in the result list for that TGet at the
   * same position.
   *
   * So the Results are in the same order as the TGets.
   */
  list<TResult> getMultiple(
    /** the table to get from */
    1: required binary table,

    /** a list of TGets to fetch, the Result list
        will have the Results at corresponding positions
        or null if there was an error */
    2: required list<TGet> gets
  ) throws (1: TIOError io)

  /**
   * Commit a TPut to a table.
   */
  void put(
    /** the table to put data in */
    1: required binary table,

    /** the TPut to put */
    2: required TPut put
  ) throws (1: TIOError io)

  /**
   * Atomically checks if a row/family/qualifier value matches the expected
   * value. If it does, it adds the TPut.
   *
   * @return true if the new put was executed, false otherwise
   */
  bool checkAndPut(
    /** to check in and put to */
    1: required binary table,

    /** row to check */
    2: required binary row,

    /** column family to check */
    3: required binary family,

    /** column qualifier to check */
    4: required binary qualifier,

    /** the expected value, if not provided the
        check is for the non-existence of the
        column in question */
    5: binary value,

    /** the TPut to put if the check succeeds */
    6: required TPut put
  ) throws (1: TIOError io)

  /**
   * Commit a List of Puts to the table.
   */
  void putMultiple(
    /** the table to put data in */
    1: required binary table,

    /** a list of TPuts to commit */
    2: required list<TPut> puts
  ) throws (1: TIOError io)

  /**
   * Deletes as specified by the TDelete.
   *
   * Note: "delete" is a reserved keyword and cannot be used in Thrift
   * thus the inconsistent naming scheme from the other functions.
   */
  void deleteSingle(
    /** the table to delete from */
    1: required binary table,

    /** the TDelete to delete */
    2: required TDelete deleteSingle
  ) throws (1: TIOError io)

  /**
   * Bulk commit a List of TDeletes to the table.
   *
   * This returns a list of TDeletes that were not
   * executed. So if everything succeeds you'll
   * receive an empty list.
   */
  list<TDelete> deleteMultiple(
    /** the table to delete from */
    1: required binary table,

    /** list of TDeletes to delete */
    2: required list<TDelete> deletes
  ) throws (1: TIOError io)

  /**
   * Atomically checks if a row/family/qualifier value matches the expected
   * value. If it does, it adds the delete.
   *
   * @return true if the new delete was executed, false otherwise
   */
  bool checkAndDelete(
    /** to check in and delete from */
    1: required binary table,

    /** row to check */
    2: required binary row,

    /** column family to check */
    3: required binary family,

    /** column qualifier to check */
    4: required binary qualifier,

    /** the expected value, if not provided the
        check is for the non-existence of the
        column in question */
    5: binary value,

    /** the TDelete to execute if the check succeeds */
    6: required TDelete deleteSingle
  ) throws (1: TIOError io)
  
  TResult increment(
    /** the table to increment the value on */
    1: required binary table,

    /** the TIncrement to increment */
    2: required TIncrement increment
  ) throws (1: TIOError io)

  /**
   * Get a Scanner for the provided TScan object.
   *
   * @return Scanner Id to be used with other scanner procedures
   */
  i32 openScanner(
    /** the table to get the Scanner for */
    1: required binary table,

    /** the scan object to get a Scanner for */
    2: required TScan scan,
  ) throws (1: TIOError io)

  /**
   * Grabs multiple rows from a Scanner.
   *
   * @return Between zero and numRows TResults
   */
  list<TResult> getScannerRows(
    /** the Id of the Scanner to return rows from. This is an Id returned from the openScanner function. */
    1: required i32 scannerId,

    /** number of rows to return */
    2: i32 numRows = 1
  ) throws (
    1: TIOError io,

    /** if the scannerId is invalid */
    2: TIllegalArgument ia
  )

  /**
   * Closes the scanner. Should be called if you need to close
   * the Scanner before all results are read.
   *
   * Exhausted scanners are closed automatically.
   */
  void closeScanner(
    /** the Id of the Scanner to close **/
    1: required i32 scannerId
  ) throws (
    1: TIOError io,

    /** if the scannerId is invalid */
    2: TIllegalArgument ia
  )

}
