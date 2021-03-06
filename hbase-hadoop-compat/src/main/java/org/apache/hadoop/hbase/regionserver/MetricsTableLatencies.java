/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.regionserver;

/**
 * Latency metrics for a specific table in a RegionServer.
 */
public interface MetricsTableLatencies {

  /**
   * The name of the metrics
   */
  String METRICS_NAME = "TableLatencies";

  /**
   * The name of the metrics context that metrics will be under.
   */
  String METRICS_CONTEXT = "regionserver";

  /**
   * Description
   */
  String METRICS_DESCRIPTION = "Metrics about Tables on a single HBase RegionServer";

  /**
   * The name of the metrics context that metrics will be under in jmx
   */
  String METRICS_JMX_CONTEXT = "RegionServer,sub=" + METRICS_NAME;

  String GET_TIME = "getTime";
  String SCAN_TIME = "scanTime";
  String SCAN_SIZE = "scanSize";
  String PUT_TIME = "putTime";
  String DELETE_TIME = "deleteTime";
  String INCREMENT_TIME = "incrementTime";
  String APPEND_TIME = "appendTime";

  /**
   * Update the Put time histogram
   *
   * @param tableName The table the metric is for
   * @param t time it took
   */
  void updatePut(String tableName, long t);

  /**
   * Update the Delete time histogram
   *
   * @param tableName The table the metric is for
   * @param t time it took
   */
  void updateDelete(String tableName, long t);

  /**
   * Update the Get time histogram .
   *
   * @param tableName The table the metric is for
   * @param t time it took
   */
  void updateGet(String tableName, long t);

  /**
   * Update the Increment time histogram.
   *
   * @param tableName The table the metric is for
   * @param t time it took
   */
  void updateIncrement(String tableName, long t);

  /**
   * Update the Append time histogram.
   *
   * @param tableName The table the metric is for
   * @param t time it took
   */
  void updateAppend(String tableName, long t);

  /**
   * Update the scan size.
   *
   * @param tableName The table the metric is for
   * @param scanSize size of the scan
   */
  void updateScanSize(String tableName, long scanSize);

  /**
   * Update the scan time.
   *
   * @param tableName The table the metric is for
   * @param t time it took
   */
  void updateScanTime(String tableName, long t);
}
