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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.HRegionLocation;
import org.apache.hadoop.hbase.RegionLocations;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.ipc.RpcControllerFactory;
import org.apache.hadoop.hbase.testclassification.ClientTests;
import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.apache.hadoop.hbase.util.Bytes;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.mockito.Mockito;

/**
 * The purpose of this test is to make sure the region exception won't corrupt the results
 * of batch. The prescription is shown below.
 * 1) honor the action result rather than region exception. If the action have both of true result
 * and region exception, the action is fine as the exception is caused by other actions
 * which are in the same region.
 * 2) honor the action exception rather than region exception. If the action have both of action
 * exception and region exception, we deal with the action exception only. If we also
 * handle the region exception for the same action, it will introduce the negative count of
 * actions in progress. The AsyncRequestFuture#waitUntilDone will block forever.
 *
 * This bug can be reproduced by real use case. see TestMalformedCellFromClient(in branch-1.4+).
 * It uses the batch of RowMutations to present the bug. Given that the batch of RowMutations is
 * only supported by branch-1.4+, perhaps the branch-1.3 and branch-1.2 won't encounter this issue.
 * We still backport the fix to branch-1.3 and branch-1.2 in case we ignore some write paths.
 */
@Category({ ClientTests.class, SmallTests.class })
public class TestAsyncProcessWithRegionException {

  private static final Result EMPTY_RESULT = Result.create(null, true);
  private static final IOException IOE = new IOException("YOU CAN'T PASS");
  private static final Configuration CONF = new Configuration();
  private static final TableName DUMMY_TABLE = TableName.valueOf("DUMMY_TABLE");
  private static final byte[] GOOD_ROW = Bytes.toBytes("GOOD_ROW");
  private static final byte[] BAD_ROW = Bytes.toBytes("BAD_ROW");
  private static final byte[] BAD_ROW_WITHOUT_ACTION_EXCEPTION =
    Bytes.toBytes("BAD_ROW_WITHOUT_ACTION_EXCEPTION");
  private static final byte[] FAMILY = Bytes.toBytes("FAMILY");
  private static final ServerName SERVER_NAME = ServerName.valueOf("s1,1,1");
  private static final HRegionInfo REGION_INFO
    = new HRegionInfo(DUMMY_TABLE, HConstants.EMPTY_START_ROW, HConstants.EMPTY_END_ROW);

  private static final HRegionLocation REGION_LOCATION =
    new HRegionLocation(REGION_INFO, SERVER_NAME);

  @BeforeClass
  public static void setUpBeforeClass() {
    // disable the retry
    CONF.setInt(HConstants.HBASE_CLIENT_RETRIES_NUMBER, 1);
  }

  @Test(timeout=20000)
  public void testSuccessivePut() throws Exception {
    MyAsyncProcess ap = new MyAsyncProcess(createHConnection(), CONF);

    List<Put> puts = new ArrayList<>(1);
    puts.add(new Put(GOOD_ROW).addColumn(FAMILY, FAMILY, FAMILY));
    final int expectedSize = puts.size();
    AsyncProcess.AsyncRequestFuture arf = ap.submit(DUMMY_TABLE, puts);
    arf.waitUntilDone();
    Object[] result = arf.getResults();
    assertEquals(expectedSize, result.length);
    for (Object r : result) {
      assertEquals(Result.class, r.getClass());
    }
    assertTrue(puts.isEmpty());
    assertActionsInProgress(arf);
  }

  @Test(timeout=20000)
  public void testFailedPut() throws Exception {
    MyAsyncProcess ap = new MyAsyncProcess(createHConnection(), CONF);

    List<Put> puts = new ArrayList<>(2);
    puts.add(new Put(GOOD_ROW).addColumn(FAMILY, FAMILY, FAMILY));
    // this put should fail
    puts.add(new Put(BAD_ROW).addColumn(FAMILY, FAMILY, FAMILY));
    final int expectedSize = puts.size();

    AsyncProcess.AsyncRequestFuture arf = ap.submit(DUMMY_TABLE, puts);
    arf.waitUntilDone();
    // There is a failed puts
    assertError(arf, 1);
    Object[] result = arf.getResults();
    assertEquals(expectedSize, result.length);
    assertEquals(Result.class, result[0].getClass());
    assertTrue(result[1] instanceof IOException);
    assertTrue(puts.isEmpty());
    assertActionsInProgress(arf);
  }

  @Test(timeout=20000)
  public void testFailedPutWithoutActionException() throws Exception {
    MyAsyncProcess ap = new MyAsyncProcess(createHConnection(), CONF);

    List<Put> puts = new ArrayList<>(3);
    puts.add(new Put(GOOD_ROW).addColumn(FAMILY, FAMILY, FAMILY));
    // this put should fail
    puts.add(new Put(BAD_ROW).addColumn(FAMILY, FAMILY, FAMILY));
    // this put should fail, and it won't have action exception
    puts.add(new Put(BAD_ROW_WITHOUT_ACTION_EXCEPTION).addColumn(FAMILY, FAMILY, FAMILY));
    final int expectedSize = puts.size();

    AsyncProcess.AsyncRequestFuture arf = ap.submit(DUMMY_TABLE, puts);
    arf.waitUntilDone();
    // There are two failed puts
    assertError(arf, 2);
    Object[] result = arf.getResults();
    assertEquals(expectedSize, result.length);
    assertEquals(Result.class, result[0].getClass());
    assertTrue(result[1] instanceof IOException);
    assertTrue(result[2] instanceof IOException);
    assertTrue(puts.isEmpty());
    assertActionsInProgress(arf);
  }

  private static void assertError(AsyncProcess.AsyncRequestFuture arf, int expectedCountOfFailure) {
    assertTrue(arf.hasError());
    RetriesExhaustedWithDetailsException e = arf.getErrors();
    List<Throwable> errors = e.getCauses();
    assertEquals(expectedCountOfFailure, errors.size());
    for (Throwable t : errors) {
      assertTrue(t instanceof IOException);
    }
  }

  private static void assertActionsInProgress(AsyncProcess.AsyncRequestFuture arf) {
    if (arf instanceof AsyncProcess.AsyncRequestFutureImpl) {
      assertEquals(0, ((AsyncProcess.AsyncRequestFutureImpl) arf).getActionsInProgress());
    }
  }

  private static ClusterConnection createHConnection() throws IOException {
    ClusterConnection hc = Mockito.mock(ClusterConnection.class);
    NonceGenerator ng = Mockito.mock(NonceGenerator.class);
    Mockito.when(ng.getNonceGroup()).thenReturn(HConstants.NO_NONCE);
    Mockito.when(hc.getNonceGenerator()).thenReturn(ng);
    Mockito.when(hc.getConfiguration()).thenReturn(CONF);
    Mockito.when(hc.getConnectionConfiguration()).thenReturn(new ConnectionConfiguration(CONF));
    setMockLocation(hc, GOOD_ROW, new RegionLocations(REGION_LOCATION));
    setMockLocation(hc, BAD_ROW, new RegionLocations(REGION_LOCATION));
    Mockito
      .when(hc.locateRegions(Mockito.eq(DUMMY_TABLE), Mockito.anyBoolean(), Mockito.anyBoolean()))
      .thenReturn(Collections.singletonList(REGION_LOCATION));
    return hc;
  }

  private static void setMockLocation(ClusterConnection hc, byte[] row, RegionLocations result)
    throws IOException {
    Mockito.when(hc.locateRegion(Mockito.eq(DUMMY_TABLE), Mockito.eq(row), Mockito.anyBoolean(),
      Mockito.anyBoolean(), Mockito.anyInt())).thenReturn(result);
    Mockito.when(hc.locateRegion(Mockito.eq(DUMMY_TABLE), Mockito.eq(row), Mockito.anyBoolean(),
      Mockito.anyBoolean())).thenReturn(result);
  }

  private static class MyAsyncProcess extends AsyncProcess {

    MyAsyncProcess(ClusterConnection hc, Configuration conf) {
      super(hc, conf, Executors.newFixedThreadPool(5),
        new RpcRetryingCallerFactory(conf), false, new RpcControllerFactory(conf),
        HConstants.DEFAULT_HBASE_RPC_TIMEOUT);
    }

    public AsyncRequestFuture submit(TableName tableName, List<? extends Row> rows)
      throws InterruptedIOException {
      return super.submit(tableName, rows, true, null, true);

    }

    @Override
    protected RpcRetryingCaller<MultiResponse> createCaller(PayloadCarryingServerCallable callable,
      int rpcTimeout) {
      MultiServerCallable<Row> callable1 = (MultiServerCallable<Row>) callable;
      final MultiResponse mr = new MultiResponse();
      for (Map.Entry<byte[], List<Action<Row>>> entry : callable1.getMulti().actions.entrySet()) {
        byte[] regionName = entry.getKey();
        for (Action<Row> action : entry.getValue()) {
          if (Bytes.equals(action.getAction().getRow(), GOOD_ROW)) {
            mr.add(regionName, action.getOriginalIndex(), EMPTY_RESULT);
          } else if (Bytes.equals(action.getAction().getRow(), BAD_ROW)) {
            mr.add(regionName, action.getOriginalIndex(), IOE);
          }
        }
      }
      mr.addException(REGION_INFO.getRegionName(), IOE);

      return new RpcRetryingCaller<MultiResponse>(100, 500, 0) {
        @Override
        public MultiResponse callWithoutRetries(RetryingCallable<MultiResponse> callable,
          int callTimeout) {
          try {
            // sleep one second in order for threadpool to start another thread instead of reusing
            // existing one.
            Thread.sleep(1000);
          } catch (InterruptedException e) {
            // pass
          }
          return mr;
        }
      };
    }
  }
}
