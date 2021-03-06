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
package org.apache.hadoop.hbase.ipc;

import static org.apache.hadoop.hbase.ipc.AbstractTestIPC.SERVICE;
import static org.junit.Assert.assertEquals;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.ipc.RpcServer.BlockingServiceAndInterface;
import org.apache.hadoop.hbase.ipc.protobuf.generated.TestProtos.EmptyRequestProto;
import org.apache.hadoop.hbase.ipc.protobuf.generated.TestProtos.EmptyResponseProto;
import org.apache.hadoop.hbase.ipc.protobuf.generated.TestRpcServiceProtos;
import org.apache.hadoop.hbase.protobuf.ProtobufUtil;
import org.apache.hadoop.hbase.protobuf.generated.RPCProtos.ConnectionHeader;
import org.apache.hadoop.hbase.protobuf.generated.RPCProtos.RequestHeader;
import org.apache.hadoop.hbase.protobuf.generated.RPCProtos.ResponseHeader;
import org.apache.hadoop.hbase.security.AuthMethod;
import org.apache.hadoop.hbase.testclassification.MediumTests;
import org.apache.hadoop.hbase.testclassification.RPCTests;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import com.google.common.collect.Lists;

@Category({ RPCTests.class, MediumTests.class })
public class TestRpcServerSlowConnectionSetup {

  private RpcServer server;

  private Socket socket;

  @Before
  public void setUp() throws IOException {
    Configuration conf = HBaseConfiguration.create();
    server = new RpcServer(null, "testRpcServer",
        Lists.newArrayList(new BlockingServiceAndInterface(SERVICE, null)),
        new InetSocketAddress("localhost", 0), conf, new FifoRpcScheduler(conf, 1));
    server.start();
    socket = new Socket("localhost", server.getListenerAddress().getPort());
  }

  @After
  public void tearDown() throws IOException {
    if (socket != null) {
      socket.close();
    }
    if (server != null) {
      server.stop();
    }
  }

  @Test
  public void test() throws IOException, InterruptedException {
    int rpcHeaderLen = HConstants.RPC_HEADER.length;
    byte[] preamble = new byte[rpcHeaderLen + 2];
    System.arraycopy(HConstants.RPC_HEADER, 0, preamble, 0, rpcHeaderLen);
    preamble[rpcHeaderLen] = HConstants.RPC_CURRENT_VERSION;
    preamble[rpcHeaderLen + 1] = AuthMethod.SIMPLE.code;
    socket.getOutputStream().write(preamble, 0, rpcHeaderLen + 1);
    socket.getOutputStream().flush();
    Thread.sleep(5000);
    socket.getOutputStream().write(preamble, rpcHeaderLen + 1, 1);
    socket.getOutputStream().flush();

    ConnectionHeader header = ConnectionHeader.newBuilder()
        .setServiceName(TestRpcServiceProtos.TestProtobufRpcProto.getDescriptor().getFullName())
        .setVersionInfo(ProtobufUtil.getVersionInfo()).build();
    DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
    dos.writeInt(header.getSerializedSize());
    header.writeTo(dos);
    dos.flush();

    int callId = 10;
    RequestHeader requestHeader = RequestHeader.newBuilder().setCallId(callId).setMethodName("ping")
        .setRequestParam(true).setTimeout(1000).build();
    dos.writeInt(IPCUtil.getTotalSizeWhenWrittenDelimited(requestHeader,
      EmptyRequestProto.getDefaultInstance()));
    requestHeader.writeDelimitedTo(dos);
    EmptyRequestProto.getDefaultInstance().writeDelimitedTo(dos);
    dos.flush();

    DataInputStream dis = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
    int size = dis.readInt();
    ResponseHeader responseHeader = ResponseHeader.parseDelimitedFrom(dis);
    assertEquals(callId, responseHeader.getCallId());
    EmptyResponseProto.Builder builder = EmptyResponseProto.newBuilder();
    builder.mergeDelimitedFrom(dis);
    assertEquals(size, IPCUtil.getTotalSizeWhenWrittenDelimited(responseHeader, builder.build()));
  }
}