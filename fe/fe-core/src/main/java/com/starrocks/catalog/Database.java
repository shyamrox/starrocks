// This file is made available under Elastic License 2.0.
// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/main/java/org/apache/doris/catalog/Database.java

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

package com.starrocks.catalog;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.starrocks.catalog.Table.TableType;
import com.starrocks.cluster.ClusterNamespace;
import com.starrocks.common.DdlException;
import com.starrocks.common.FeConstants;
import com.starrocks.common.FeMetaVersion;
import com.starrocks.common.Pair;
import com.starrocks.common.UserException;
import com.starrocks.common.io.Text;
import com.starrocks.common.io.Writable;
import com.starrocks.common.util.DebugUtil;
import com.starrocks.common.util.QueryableReentrantReadWriteLock;
import com.starrocks.common.util.Util;
import com.starrocks.persist.CreateTableInfo;
import com.starrocks.system.SystemInfoService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.zip.Adler32;

/**
 * Internal representation of db-related metadata. Owned by Catalog instance.
 * Not thread safe.
 * <p/>
 * The static initialization method loadDb is the only way to construct a Db
 * object.
 * <p/>
 * Tables are stored in a map from the table name to the table object. They may
 * be loaded 'eagerly' at construction or 'lazily' on first reference. Tables
 * are accessed via getTable which may trigger a metadata read in two cases: *
 * if the table has never been loaded * if the table loading failed on the
 * previous attempt
 */
public class Database extends MetaObject implements Writable {
    private static final Logger LOG = LogManager.getLogger(Database.class);

    // empirical value.
    // assume that the time a lock is held by thread is less then 100ms
    public static final long TRY_LOCK_TIMEOUT_MS = 100L;

    private long id;
    private String fullQualifiedName;
    private String clusterName;
    private QueryableReentrantReadWriteLock rwLock;

    // table family group map
    private Map<Long, Table> idToTable;
    private Map<String, Table> nameToTable;

    // user define function
    private ConcurrentMap<String, ImmutableList<Function>> name2Function = Maps.newConcurrentMap();

    private volatile long dataQuotaBytes;

    private volatile long replicaQuotaSize;

    public enum DbState {
        NORMAL, LINK, MOVE
    }

    private String attachDbName;
    private DbState dbState;

    public Database() {
        this(0, null);
    }

    public Database(long id, String name) {
        this.id = id;
        this.fullQualifiedName = name;
        if (this.fullQualifiedName == null) {
            this.fullQualifiedName = "";
        }
        this.rwLock = new QueryableReentrantReadWriteLock(true);
        this.idToTable = new ConcurrentHashMap<>();
        this.nameToTable = new ConcurrentHashMap<>();
        this.dataQuotaBytes = FeConstants.default_db_data_quota_bytes;
        this.replicaQuotaSize = FeConstants.default_db_replica_quota_size;
        this.dbState = DbState.NORMAL;
        this.attachDbName = "";
        this.clusterName = "";
    }

    public void readLock() {
        this.rwLock.readLock().lock();
    }

    public boolean tryReadLock(long timeout, TimeUnit unit) {
        try {
            if (!this.rwLock.readLock().tryLock(timeout, unit)) {
                Thread owner = rwLock.getOwner();
                if (owner != null) {
                    LOG.warn("database lock is held by: {}", Util.dumpThread(owner, 50));
                }
                return false;
            }
            return true;
        } catch (InterruptedException e) {
            LOG.warn("failed to try read lock at db[" + id + "]", e);
            return false;
        }
    }

    public void readUnlock() {
        this.rwLock.readLock().unlock();
    }

    public void writeLock() {
        this.rwLock.writeLock().lock();
    }

    public boolean tryWriteLock(long timeout, TimeUnit unit) {
        try {
            if (!this.rwLock.writeLock().tryLock(timeout, unit)) {
                Thread owner = rwLock.getOwner();
                if (owner != null) {
                    LOG.warn("database lock is held by: {}", Util.dumpThread(owner, 50));
                }
                return false;
            }
            return true;
        } catch (InterruptedException e) {
            LOG.warn("failed to try write lock at db[" + id + "]", e);
            return false;
        }
    }

    public void writeUnlock() {
        this.rwLock.writeLock().unlock();
    }

    public boolean isWriteLockHeldByCurrentThread() {
        return this.rwLock.writeLock().isHeldByCurrentThread();
    }

    public long getId() {
        return id;
    }

    public String getFullName() {
        return fullQualifiedName;
    }

    public void setNameWithLock(String newName) {
        writeLock();
        try {
            this.fullQualifiedName = newName;
        } finally {
            writeUnlock();
        }
    }

    public void setDataQuotaWithLock(long newQuota) {
        Preconditions.checkArgument(newQuota >= 0L);
        LOG.info("database[{}] set quota from {} to {}", fullQualifiedName, dataQuotaBytes, newQuota);
        writeLock();
        try {
            this.dataQuotaBytes = newQuota;
        } finally {
            writeUnlock();
        }
    }

    public void setReplicaQuotaWithLock(long newQuota) {
        Preconditions.checkArgument(newQuota >= 0L);
        LOG.info("database[{}] set replica quota from {} to {}", fullQualifiedName, replicaQuotaSize, newQuota);
        writeLock();
        try {
            this.replicaQuotaSize = newQuota;
        } finally {
            writeUnlock();
        }
    }

    public long getDataQuota() {
        return dataQuotaBytes;
    }

    public long getReplicaQuota() {
        return replicaQuotaSize;
    }

    public long getUsedDataQuotaWithLock() {
        long usedDataQuota = 0;
        readLock();
        try {
            for (Table table : this.idToTable.values()) {
                if (table.getType() != TableType.OLAP) {
                    continue;
                }

                OlapTable olapTable = (OlapTable) table;
                usedDataQuota = usedDataQuota + olapTable.getDataSize();
            }
            return usedDataQuota;
        } finally {
            readUnlock();
        }
    }

    public long getReplicaQuotaLeftWithLock() {
        long usedReplicaQuota = 0;
        readLock();
        try {
            for (Table table : this.idToTable.values()) {
                if (table.getType() != TableType.OLAP) {
                    continue;
                }

                OlapTable olapTable = (OlapTable) table;
                usedReplicaQuota = usedReplicaQuota + olapTable.getReplicaCount();
            }

            long leftReplicaQuota = replicaQuotaSize - usedReplicaQuota;
            return Math.max(leftReplicaQuota, 0L);
        } finally {
            readUnlock();
        }
    }

    public void checkDataSizeQuota() throws DdlException {
        Pair<Double, String> quotaUnitPair = DebugUtil.getByteUint(dataQuotaBytes);
        String readableQuota = DebugUtil.DECIMAL_FORMAT_SCALE_3.format(quotaUnitPair.first) + " "
                + quotaUnitPair.second;
        long usedDataQuota = getUsedDataQuotaWithLock();
        long leftDataQuota = Math.max(dataQuotaBytes - usedDataQuota, 0);

        Pair<Double, String> leftQuotaUnitPair = DebugUtil.getByteUint(leftDataQuota);
        String readableLeftQuota = DebugUtil.DECIMAL_FORMAT_SCALE_3.format(leftQuotaUnitPair.first) + " "
                + leftQuotaUnitPair.second;

        LOG.info("database[{}] data quota: left bytes: {} / total: {}",
                fullQualifiedName, readableLeftQuota, readableQuota);

        if (leftDataQuota <= 0L) {
            throw new DdlException("Database[" + fullQualifiedName
                    + "] data size exceeds quota[" + readableQuota + "]");
        }
    }

    public void checkReplicaQuota() throws DdlException {
        long leftReplicaQuota = getReplicaQuotaLeftWithLock();
        LOG.info("database[{}] replica quota: left number: {} / total: {}",
                fullQualifiedName, leftReplicaQuota, replicaQuotaSize);

        if (leftReplicaQuota <= 0L) {
            throw new DdlException("Database[" + fullQualifiedName
                    + "] replica number exceeds quota[" + replicaQuotaSize + "]");
        }
    }

    public void checkQuota() throws DdlException {
        checkDataSizeQuota();
        checkReplicaQuota();
    }

    // return false if table already exists
    public boolean createTableWithLock(Table table, boolean isReplay) {
        writeLock();
        try {
            String tableName = table.getName();
            if (nameToTable.containsKey(tableName)) {
                return false;
            } else {
                idToTable.put(table.getId(), table);
                nameToTable.put(table.getName(), table);

                if (!isReplay) {
                    // Write edit log
                    CreateTableInfo info = new CreateTableInfo(fullQualifiedName, table);
                    Catalog.getCurrentCatalog().getEditLog().logCreateTable(info);
                }

                table.onCreate();
            }
            return true;
        } finally {
            writeUnlock();
        }
    }

    public boolean createTable(Table table) {
        boolean result = true;
        String tableName = table.getName();
        if (nameToTable.containsKey(tableName)) {
            result = false;
        } else {
            idToTable.put(table.getId(), table);
            nameToTable.put(table.getName(), table);
        }
        return result;
    }

    public void dropTableWithLock(String tableName) {
        writeLock();
        try {
            Table table = this.nameToTable.get(tableName);
            if (table != null) {
                this.nameToTable.remove(tableName);
                this.idToTable.remove(table.getId());
            }
        } finally {
            writeUnlock();
        }
    }

    public void dropTable(String tableName) {
        Table table = this.nameToTable.get(tableName);
        if (table != null) {
            this.nameToTable.remove(tableName);
            this.idToTable.remove(table.getId());
        }
    }

    public List<Table> getTables() {
        return new ArrayList<Table>(idToTable.values());
    }

    public List<Table> getViews() {
        List<Table> views = new ArrayList<>();
        for (Table table : idToTable.values()) {
            if (TableType.VIEW == table.getType()) {
                views.add(table);
            }
        }
        return views;
    }

    public Set<String> getTableNamesWithLock() {
        readLock();
        try {
            return new HashSet<>(this.nameToTable.keySet());
        } finally {
            readUnlock();
        }
    }

    public Table getTable(String tableName) {
        if (nameToTable.containsKey(tableName)) {
            return nameToTable.get(tableName);
        }
        return null;
    }

    /**
     * This is a thread-safe method when idToTable is a concurrent hash map
     *
     * @param tableId
     * @return
     */
    public Table getTable(long tableId) {
        return idToTable.get(tableId);
    }

    public int getMaxReplicationNum() {
        int ret = 0;
        readLock();
        try {
            for (Table table : idToTable.values()) {
                if (table.getType() != TableType.OLAP) {
                    continue;
                }
                OlapTable olapTable = (OlapTable) table;
                for (Partition partition : olapTable.getAllPartitions()) {
                    short replicationNum = olapTable.getPartitionInfo().getReplicationNum(partition.getId());
                    if (ret < replicationNum) {
                        ret = replicationNum;
                    }
                }
            }
        } finally {
            readUnlock();
        }
        return ret;
    }

    public static Database read(DataInput in) throws IOException {
        Database db = new Database();
        db.readFields(in);
        return db;
    }

    @Override
    public int getSignature(int signatureVersion) {
        Adler32 adler32 = new Adler32();
        adler32.update(signatureVersion);
        String charsetName = "UTF-8";
        try {
            adler32.update(this.fullQualifiedName.getBytes(charsetName));
        } catch (UnsupportedEncodingException e) {
            LOG.error("encoding error", e);
            return -1;
        }
        return Math.abs((int) adler32.getValue());
    }

    @Override
    public void write(DataOutput out) throws IOException {
        super.write(out);

        out.writeLong(id);
        Text.writeString(out, fullQualifiedName);
        // write tables
        int numTables = nameToTable.size();
        out.writeInt(numTables);
        for (Map.Entry<String, Table> entry : nameToTable.entrySet()) {
            entry.getValue().write(out);
        }

        out.writeLong(dataQuotaBytes);
        Text.writeString(out, clusterName);
        Text.writeString(out, dbState.name());
        Text.writeString(out, attachDbName);

        // write functions
        out.writeInt(name2Function.size());
        for (Entry<String, ImmutableList<Function>> entry : name2Function.entrySet()) {
            Text.writeString(out, entry.getKey());
            out.writeInt(entry.getValue().size());
            for (Function function : entry.getValue()) {
                function.write(out);
            }
        }

        out.writeLong(replicaQuotaSize);
    }

    public void readFields(DataInput in) throws IOException {
        super.readFields(in);

        id = in.readLong();
        if (Catalog.getCurrentCatalogJournalVersion() < FeMetaVersion.VERSION_30) {
            fullQualifiedName = ClusterNamespace.getFullName(SystemInfoService.DEFAULT_CLUSTER, Text.readString(in));
        } else {
            fullQualifiedName = Text.readString(in);
        }
        // read groups
        int numTables = in.readInt();
        for (int i = 0; i < numTables; ++i) {
            Table table = Table.read(in);
            nameToTable.put(table.getName(), table);
            idToTable.put(table.getId(), table);
        }

        // read quota
        dataQuotaBytes = in.readLong();
        if (Catalog.getCurrentCatalogJournalVersion() < FeMetaVersion.VERSION_30) {
            clusterName = SystemInfoService.DEFAULT_CLUSTER;
        } else {
            clusterName = Text.readString(in);
            dbState = DbState.valueOf(Text.readString(in));
            attachDbName = Text.readString(in);
        }

        if (Catalog.getCurrentCatalogJournalVersion() >= FeMetaVersion.VERSION_47) {
            int numEntries = in.readInt();
            for (int i = 0; i < numEntries; ++i) {
                String name = Text.readString(in);
                ImmutableList.Builder<Function> builder = ImmutableList.builder();
                int numFunctions = in.readInt();
                for (int j = 0; j < numFunctions; ++j) {
                    builder.add(Function.read(in));
                }

                name2Function.put(name, builder.build());
            }
        }

        if (Catalog.getCurrentCatalogJournalVersion() >= FeMetaVersion.VERSION_81) {
            replicaQuotaSize = in.readLong();
        } else {
            replicaQuotaSize = FeConstants.default_db_replica_quota_size;
        }
    }

    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof Database)) {
            return false;
        }

        Database database = (Database) obj;

        if (idToTable != database.idToTable) {
            if (idToTable.size() != database.idToTable.size()) {
                return false;
            }
            for (Entry<Long, Table> entry : idToTable.entrySet()) {
                long key = entry.getKey();
                if (!database.idToTable.containsKey(key)) {
                    return false;
                }
                if (!entry.getValue().equals(database.idToTable.get(key))) {
                    return false;
                }
            }
        }

        return (id == database.id) && (fullQualifiedName.equals(database.fullQualifiedName)
                && dataQuotaBytes == database.dataQuotaBytes);
    }

    public String getClusterName() {
        return clusterName;
    }

    public void setClusterName(String clusterName) {
        this.clusterName = clusterName;
    }

    public DbState getDbState() {
        return dbState;
    }

    public void setDbState(DbState dbState) {
        if (dbState == null) {
            return;
        }
        this.dbState = dbState;
    }

    public void setAttachDb(String name) {
        this.attachDbName = name;
    }

    public String getAttachDb() {
        return this.attachDbName;
    }

    public void setName(String name) {
        this.fullQualifiedName = name;
    }

    public synchronized void addFunction(Function function) throws UserException {
        addFunctionImpl(function, false);
        Catalog.getCurrentCatalog().getEditLog().logAddFunction(function);
    }

    public synchronized void replayAddFunction(Function function) {
        try {
            addFunctionImpl(function, true);
        } catch (UserException e) {
            Preconditions.checkArgument(false);
        }
    }

    // return true if add success, false
    private void addFunctionImpl(Function function, boolean isReplay) throws UserException {
        String functionName = function.getFunctionName().getFunction();
        List<Function> existFuncs = name2Function.get(functionName);
        if (!isReplay) {
            if (existFuncs != null) {
                for (Function existFunc : existFuncs) {
                    if (function.compare(existFunc, Function.CompareMode.IS_IDENTICAL)) {
                        throw new UserException("function already exists");
                    }
                }
            }
            // Get function id for this UDF, use CatalogIdGenerator. Only get function id
            // when isReplay is false
            long functionId = Catalog.getCurrentCatalog().getNextId();
            // all user-defined functions id are negative to avoid conflicts with the builtin function
            function.setFunctionId(-functionId);
        }

        ImmutableList.Builder<Function> builder = ImmutableList.builder();
        if (existFuncs != null) {
            builder.addAll(existFuncs);
        }
        builder.add(function);
        name2Function.put(functionName, builder.build());
    }

    public synchronized void dropFunction(FunctionSearchDesc function) throws UserException {
        dropFunctionImpl(function);
        Catalog.getCurrentCatalog().getEditLog().logDropFunction(function);
    }

    public synchronized void replayDropFunction(FunctionSearchDesc functionSearchDesc) {
        try {
            dropFunctionImpl(functionSearchDesc);
        } catch (UserException e) {
            Preconditions.checkArgument(false);
        }
    }

    private void dropFunctionImpl(FunctionSearchDesc function) throws UserException {
        String functionName = function.getName().getFunction();
        List<Function> existFuncs = name2Function.get(functionName);
        if (existFuncs == null) {
            throw new UserException("Unknown function, function=" + function.toString());
        }
        boolean isFound = false;
        ImmutableList.Builder<Function> builder = ImmutableList.builder();
        for (Function existFunc : existFuncs) {
            if (function.isIdentical(existFunc)) {
                isFound = true;
            } else {
                builder.add(existFunc);
            }
        }
        if (!isFound) {
            throw new UserException("Unknown function, function=" + function.toString());
        }
        ImmutableList<Function> newFunctions = builder.build();
        if (newFunctions.isEmpty()) {
            name2Function.remove(functionName);
        } else {
            name2Function.put(functionName, newFunctions);
        }
    }

    public synchronized Function getFunction(Function desc, Function.CompareMode mode) {
        List<Function> fns = name2Function.get(desc.getFunctionName().getFunction());
        if (fns == null) {
            return null;
        }
        return Function.getFunction(fns, desc, mode);
    }

    public synchronized List<Function> getFunctions() {
        List<Function> functions = Lists.newArrayList();
        for (Map.Entry<String, ImmutableList<Function>> entry : name2Function.entrySet()) {
            functions.addAll(entry.getValue());
        }
        return functions;
    }

    public boolean isInfoSchemaDb() {
        return ClusterNamespace.getNameFromFullName(fullQualifiedName).equalsIgnoreCase(InfoSchemaDb.DATABASE_NAME);
    }
}
