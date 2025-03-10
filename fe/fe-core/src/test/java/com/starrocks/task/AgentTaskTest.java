// This file is made available under Elastic License 2.0.
// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/test/java/org/apache/doris/task/AgentTaskTest.java

// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.starrocks.task;

import com.google.common.collect.Lists;
import com.google.common.collect.Range;
import com.starrocks.analysis.PartitionValue;
import com.starrocks.catalog.AggregateType;
import com.starrocks.catalog.Column;
import com.starrocks.catalog.KeysType;
import com.starrocks.catalog.PartitionKey;
import com.starrocks.catalog.PrimitiveType;
import com.starrocks.catalog.ScalarType;
import com.starrocks.common.AnalysisException;
import com.starrocks.common.MarkedCountDownLatch;
import com.starrocks.common.Pair;
import com.starrocks.thrift.TAgentTaskRequest;
import com.starrocks.thrift.TBackend;
import com.starrocks.thrift.TKeysType;
import com.starrocks.thrift.TPriority;
import com.starrocks.thrift.TPushType;
import com.starrocks.thrift.TStorageMedium;
import com.starrocks.thrift.TStorageType;
import com.starrocks.thrift.TTabletMetaType;
import com.starrocks.thrift.TTabletType;
import com.starrocks.thrift.TTaskType;
import org.apache.commons.lang3.tuple.ImmutableTriple;
import org.apache.commons.lang3.tuple.Triple;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class AgentTaskTest {

    private AgentBatchTask agentBatchTask;

    private long backendId1 = 1000L;
    private long backendId2 = 1001L;

    private long dbId = 10000L;
    private long tableId = 20000L;
    private long partitionId = 20000L;
    private long indexId1 = 30000L;
    private long indexId2 = 30001L;

    private long tabletId1 = 40000L;
    private long tabletId2 = 40001L;

    private long replicaId1 = 50000L;
    private long replicaId2 = 50001L;

    private short shortKeyNum = (short) 2;
    private int schemaHash1 = 60000;
    private int schemaHash2 = 60001;
    private long version = 1L;

    private TStorageType storageType = TStorageType.COLUMN;
    private List<Column> columns;
    private MarkedCountDownLatch<Long, Long> latch = new MarkedCountDownLatch<Long, Long>(3);

    private Range<PartitionKey> range1;
    private Range<PartitionKey> range2;

    private AgentTask createReplicaTask;
    private AgentTask dropTask;
    private AgentTask pushTask;
    private AgentTask cloneTask;
    private AgentTask rollupTask;
    private AgentTask schemaChangeTask;
    private AgentTask cancelDeleteTask;
    private AgentTask modifyEnablePersistentIndexTask1;
    private AgentTask modifyEnablePersistentIndexTask2;
    private AgentTask modifyInMemoryTask;

    @Before
    public void setUp() throws AnalysisException {
        agentBatchTask = new AgentBatchTask();

        columns = new LinkedList<Column>();
        columns.add(new Column("k1", ScalarType.createType(PrimitiveType.INT), false, null, "1", ""));
        columns.add(new Column("v1", ScalarType.createType(PrimitiveType.INT), false, AggregateType.SUM, "1", ""));

        PartitionKey pk1 = PartitionKey.createInfinityPartitionKey(Arrays.asList(columns.get(0)), false);
        PartitionKey pk2 =
                PartitionKey.createPartitionKey(Arrays.asList(new PartitionValue("10")), Arrays.asList(columns.get(0)));
        range1 = Range.closedOpen(pk1, pk2);

        PartitionKey pk3 = PartitionKey.createInfinityPartitionKey(Arrays.asList(columns.get(0)), true);
        range2 = Range.closedOpen(pk2, pk3);

        // create tasks

        // create
        createReplicaTask = new CreateReplicaTask(backendId1, dbId, tableId, partitionId,
                indexId1, tabletId1, shortKeyNum, schemaHash1,
                version, KeysType.AGG_KEYS,
                storageType, TStorageMedium.SSD,
                columns, null, 0, latch, null,
                false, false, TTabletType.TABLET_TYPE_DISK);

        // drop
        dropTask = new DropReplicaTask(backendId1, tabletId1, schemaHash1, false);

        // push
        pushTask =
                new PushTask(null, backendId1, dbId, tableId, partitionId, indexId1, tabletId1,
                        replicaId1, schemaHash1, version, 200, 80000L,
                        TPushType.LOAD_V2, null, TPriority.NORMAL, TTaskType.PUSH, -1, tabletId1);

        // clone
        cloneTask =
                new CloneTask(backendId1, dbId, tableId, partitionId, indexId1, tabletId1, schemaHash1,
                        Arrays.asList(new TBackend("host1", 8290, 8390)), TStorageMedium.HDD, -1, 3600);

        // rollup
        rollupTask =
                new CreateRollupTask(null, backendId1, dbId, tableId, partitionId, indexId2, indexId1,
                        tabletId2, tabletId1, replicaId2, shortKeyNum, schemaHash2, schemaHash1,
                        storageType, columns, null, 0, TKeysType.AGG_KEYS);

        // schemaChange
        schemaChangeTask =
                new SchemaChangeTask(null, backendId1, dbId, tableId, partitionId, indexId1,
                        tabletId1, replicaId1, columns, schemaHash2, schemaHash1,
                        shortKeyNum, storageType, null, 0, TKeysType.AGG_KEYS);
        
        // modify tablet meta
        // <tablet id, tablet schema hash, tablet in memory/ tablet enable persistent index>
        // for report handle
        List<Triple<Long, Integer, Boolean>> tabletToMeta = Lists.newArrayList();
        tabletToMeta.add(new ImmutableTriple<>(tabletId1, schemaHash1, true));
        tabletToMeta.add(new ImmutableTriple<>(tabletId2, schemaHash2, false));
        modifyEnablePersistentIndexTask1 = 
                new UpdateTabletMetaInfoTask(backendId1, tabletToMeta, TTabletMetaType.ENABLE_PERSISTENT_INDEX);
        
        // for schema change
        MarkedCountDownLatch<Long, Set<Pair<Long, Integer>>> countDownLatch = new MarkedCountDownLatch<>(1);
        Set<Pair<Long, Integer>> tabletIdWithSchemaHash = new HashSet();
        tabletIdWithSchemaHash.add(Pair.create(tabletId1, schemaHash1));
        countDownLatch.addMark(backendId1, tabletIdWithSchemaHash);
        modifyEnablePersistentIndexTask2 = 
                new UpdateTabletMetaInfoTask(backendId1, tabletIdWithSchemaHash, true, 
                                             countDownLatch, TTabletMetaType.ENABLE_PERSISTENT_INDEX);
        modifyInMemoryTask = 
                new UpdateTabletMetaInfoTask(backendId1, tabletToMeta, TTabletMetaType.INMEMORY);
    }

    @Test
    public void addTaskTest() {
        // add null
        agentBatchTask.addTask(null);
        Assert.assertEquals(0, agentBatchTask.getTaskNum());

        // normal
        agentBatchTask.addTask(createReplicaTask);
        Assert.assertEquals(1, agentBatchTask.getTaskNum());

        agentBatchTask.addTask(rollupTask);
        Assert.assertEquals(2, agentBatchTask.getTaskNum());

        List<AgentTask> allTasks = agentBatchTask.getAllTasks();
        Assert.assertEquals(2, allTasks.size());

        for (AgentTask agentTask : allTasks) {
            if (agentTask instanceof CreateReplicaTask) {
                Assert.assertEquals(createReplicaTask, agentTask);
            } else if (agentTask instanceof CreateRollupTask) {
                Assert.assertEquals(rollupTask, agentTask);
            } else {
                Assert.fail();
            }
        }
    }

    @Test
    public void toThriftTest() throws Exception {
        Class<? extends AgentBatchTask> agentBatchTaskClass = agentBatchTask.getClass();
        Class[] typeParams = new Class[] {AgentTask.class};
        Method toAgentTaskRequest = agentBatchTaskClass.getDeclaredMethod("toAgentTaskRequest", typeParams);
        toAgentTaskRequest.setAccessible(true);

        // create
        TAgentTaskRequest request = (TAgentTaskRequest) toAgentTaskRequest.invoke(agentBatchTask, createReplicaTask);
        Assert.assertEquals(TTaskType.CREATE, request.getTask_type());
        Assert.assertEquals(createReplicaTask.getSignature(), request.getSignature());
        Assert.assertNotNull(request.getCreate_tablet_req());

        // drop
        TAgentTaskRequest request2 = (TAgentTaskRequest) toAgentTaskRequest.invoke(agentBatchTask, dropTask);
        Assert.assertEquals(TTaskType.DROP, request2.getTask_type());
        Assert.assertEquals(dropTask.getSignature(), request2.getSignature());
        Assert.assertNotNull(request2.getDrop_tablet_req());

        // push
        TAgentTaskRequest request3 = (TAgentTaskRequest) toAgentTaskRequest.invoke(agentBatchTask, pushTask);
        Assert.assertEquals(TTaskType.PUSH, request3.getTask_type());
        Assert.assertEquals(pushTask.getSignature(), request3.getSignature());
        Assert.assertNotNull(request3.getPush_req());

        // clone
        TAgentTaskRequest request4 = (TAgentTaskRequest) toAgentTaskRequest.invoke(agentBatchTask, cloneTask);
        Assert.assertEquals(TTaskType.CLONE, request4.getTask_type());
        Assert.assertEquals(cloneTask.getSignature(), request4.getSignature());
        Assert.assertNotNull(request4.getClone_req());

        // rollup
        TAgentTaskRequest request5 = (TAgentTaskRequest) toAgentTaskRequest.invoke(agentBatchTask, rollupTask);
        Assert.assertEquals(TTaskType.ROLLUP, request5.getTask_type());
        Assert.assertEquals(rollupTask.getSignature(), request5.getSignature());
        Assert.assertNotNull(request5.getAlter_tablet_req());

        // schemaChange
        TAgentTaskRequest request6 = (TAgentTaskRequest) toAgentTaskRequest.invoke(agentBatchTask, schemaChangeTask);
        Assert.assertEquals(TTaskType.SCHEMA_CHANGE, request6.getTask_type());
        Assert.assertEquals(schemaChangeTask.getSignature(), request6.getSignature());
        Assert.assertNotNull(request6.getAlter_tablet_req());

        // modify enable_persistent_index
        TAgentTaskRequest request7 = (TAgentTaskRequest) toAgentTaskRequest.invoke(agentBatchTask, modifyEnablePersistentIndexTask1);
        Assert.assertEquals(TTaskType.UPDATE_TABLET_META_INFO, request7.getTask_type());
        Assert.assertEquals(modifyEnablePersistentIndexTask1.getSignature(), request7.getSignature());
        Assert.assertNotNull(request7.getUpdate_tablet_meta_info_req());

        TAgentTaskRequest request8 = (TAgentTaskRequest) toAgentTaskRequest.invoke(agentBatchTask, modifyEnablePersistentIndexTask2);
        Assert.assertEquals(TTaskType.UPDATE_TABLET_META_INFO, request8.getTask_type());
        Assert.assertEquals(modifyEnablePersistentIndexTask2.getSignature(), request8.getSignature());
        Assert.assertNotNull(request8.getUpdate_tablet_meta_info_req());

        // modify in_memory
        TAgentTaskRequest request9 = (TAgentTaskRequest) toAgentTaskRequest.invoke(agentBatchTask, modifyInMemoryTask);
        Assert.assertEquals(TTaskType.UPDATE_TABLET_META_INFO, request9.getTask_type());
        Assert.assertEquals(modifyInMemoryTask.getSignature(), request9.getSignature());
        Assert.assertNotNull(request9.getUpdate_tablet_meta_info_req());
    }

    @Test
    public void agentTaskQueueTest() {
        AgentTaskQueue.clearAllTasks();
        Assert.assertEquals(0, AgentTaskQueue.getTaskNum());

        // add
        AgentTaskQueue.addTask(createReplicaTask);
        Assert.assertEquals(1, AgentTaskQueue.getTaskNum());
        Assert.assertFalse(AgentTaskQueue.addTask(createReplicaTask));

        // get
        AgentTask task = AgentTaskQueue.getTask(backendId1, TTaskType.CREATE, createReplicaTask.getSignature());
        Assert.assertEquals(createReplicaTask, task);

        // diff
        AgentTaskQueue.addTask(rollupTask);

        Map<TTaskType, Set<Long>> runningTasks = new HashMap<TTaskType, Set<Long>>();
        List<AgentTask> diffTasks = AgentTaskQueue.getDiffTasks(backendId1, runningTasks);
        Assert.assertEquals(2, diffTasks.size());

        Set<Long> set = new HashSet<Long>();
        set.add(createReplicaTask.getSignature());
        runningTasks.put(TTaskType.CREATE, set);
        diffTasks = AgentTaskQueue.getDiffTasks(backendId1, runningTasks);
        Assert.assertEquals(1, diffTasks.size());
        Assert.assertEquals(rollupTask, diffTasks.get(0));

        // remove
        AgentTaskQueue.removeTask(backendId1, TTaskType.CREATE, createReplicaTask.getSignature());
        Assert.assertEquals(1, AgentTaskQueue.getTaskNum());
        AgentTaskQueue.removeTask(backendId1, TTaskType.ROLLUP, rollupTask.getSignature());
        Assert.assertEquals(0, AgentTaskQueue.getTaskNum());
    }

    @Test
    public void failedAgentTaskTest() {
        AgentTaskQueue.clearAllTasks();

        AgentTaskQueue.addTask(dropTask);
        Assert.assertEquals(0, dropTask.getFailedTimes());
        dropTask.failed();
        Assert.assertEquals(1, dropTask.getFailedTimes());

        Assert.assertEquals(1, AgentTaskQueue.getTaskNum());
        Assert.assertEquals(1, AgentTaskQueue.getTaskNum(backendId1, TTaskType.DROP, false));
        Assert.assertEquals(1, AgentTaskQueue.getTaskNum(-1, TTaskType.DROP, false));
        Assert.assertEquals(1, AgentTaskQueue.getTaskNum(backendId1, TTaskType.DROP, true));

        dropTask.failed();
        DropReplicaTask dropTask2 = new DropReplicaTask(backendId2, tabletId1, schemaHash1, false);
        AgentTaskQueue.addTask(dropTask2);
        dropTask2.failed();
        Assert.assertEquals(1, AgentTaskQueue.getTaskNum(backendId1, TTaskType.DROP, true));
        Assert.assertEquals(2, AgentTaskQueue.getTaskNum(-1, TTaskType.DROP, true));
    }
}
