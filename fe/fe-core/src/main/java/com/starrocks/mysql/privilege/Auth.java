// This file is made available under Elastic License 2.0.
// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/main/java/org/apache/doris/mysql/privilege/Auth.java

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

package com.starrocks.mysql.privilege;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.starrocks.StarRocksFE;
import com.starrocks.analysis.AlterUserStmt;
import com.starrocks.analysis.CreateRoleStmt;
import com.starrocks.analysis.CreateUserStmt;
import com.starrocks.analysis.DropRoleStmt;
import com.starrocks.analysis.DropUserStmt;
import com.starrocks.analysis.GrantStmt;
import com.starrocks.analysis.ResourcePattern;
import com.starrocks.analysis.RevokeStmt;
import com.starrocks.analysis.SetPassVar;
import com.starrocks.analysis.SetUserPropertyStmt;
import com.starrocks.analysis.TablePattern;
import com.starrocks.analysis.UserIdentity;
import com.starrocks.catalog.AuthorizationInfo;
import com.starrocks.catalog.Catalog;
import com.starrocks.catalog.InfoSchemaDb;
import com.starrocks.cluster.ClusterNamespace;
import com.starrocks.common.AnalysisException;
import com.starrocks.common.Config;
import com.starrocks.common.DdlException;
import com.starrocks.common.FeConstants;
import com.starrocks.common.FeMetaVersion;
import com.starrocks.common.Pair;
import com.starrocks.common.io.Writable;
import com.starrocks.persist.PrivInfo;
import com.starrocks.qe.ConnectContext;
import com.starrocks.system.SystemInfoService;
import com.starrocks.thrift.TFetchResourceResult;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class Auth implements Writable {
    private static final Logger LOG = LogManager.getLogger(Auth.class);

    // root user's role is operator.
    // each starrocks system has only one root user.
    public static final String ROOT_USER = "root";
    public static final String ADMIN_USER = "admin";

    public static final String KRB5_AUTH_CLASS_NAME = "com.starrocks.plugins.auth.KerberosAuthentication";
    public static final String KRB5_AUTH_JAR_PATH = StarRocksFE.STARROCKS_HOME_DIR + "/lib/starrocks-kerberos.jar";

    private UserPrivTable userPrivTable = new UserPrivTable();
    private DbPrivTable dbPrivTable = new DbPrivTable();
    private TablePrivTable tablePrivTable = new TablePrivTable();
    private ResourcePrivTable resourcePrivTable = new ResourcePrivTable();

    private RoleManager roleManager = new RoleManager();
    private UserPropertyMgr propertyMgr = new UserPropertyMgr();

    private ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private Class<?> authClazz = null;

    private void readLock() {
        lock.readLock().lock();
    }

    private void readUnlock() {
        lock.readLock().unlock();
    }

    private void writeLock() {
        lock.writeLock().lock();
    }

    private void writeUnlock() {
        lock.writeLock().unlock();
    }

    public enum PrivLevel {
        GLOBAL, DATABASE, TABLE, RESOURCE
    }

    public Auth() {
        initUser();
    }

    public UserPrivTable getUserPrivTable() {
        return userPrivTable;
    }

    public DbPrivTable getDbPrivTable() {
        return dbPrivTable;
    }

    public TablePrivTable getTablePrivTable() {
        return tablePrivTable;
    }

    private GlobalPrivEntry grantGlobalPrivs(UserIdentity userIdentity, boolean errOnExist, boolean errOnNonExist,
                                             PrivBitSet privs) throws DdlException {
        if (errOnExist && errOnNonExist) {
            throw new DdlException("Can only specified errOnExist or errOnNonExist");
        }
        GlobalPrivEntry entry;
        try {
            // password set here will not overwrite the password of existing entry, no need to worry.
            entry = GlobalPrivEntry.create(userIdentity.getHost(), userIdentity.getQualifiedUser(),
                    userIdentity.isDomain(), new Password(new byte[0]) /* no use */, privs);
            entry.setSetByDomainResolver(false);
        } catch (AnalysisException e) {
            throw new DdlException(e.getMessage());
        }
        userPrivTable.addEntry(entry, errOnExist, errOnNonExist);
        return entry;
    }

    private void revokeGlobalPrivs(UserIdentity userIdentity, PrivBitSet privs, boolean errOnNonExist)
            throws DdlException {
        GlobalPrivEntry entry;
        try {
            entry = GlobalPrivEntry.create(userIdentity.getHost(), userIdentity.getQualifiedUser(),
                    userIdentity.isDomain(), new Password(new byte[0]) /* no use */, privs);
            entry.setSetByDomainResolver(false);
        } catch (AnalysisException e) {
            throw new DdlException(e.getMessage());
        }

        userPrivTable.revoke(entry, errOnNonExist,
                false /* not delete entry if priv is empty, because global priv entry has password */);
    }

    private void grantDbPrivs(UserIdentity userIdentity, String db, boolean errOnExist, boolean errOnNonExist,
                              PrivBitSet privs) throws DdlException {
        DbPrivEntry entry;
        try {
            entry = DbPrivEntry.create(userIdentity.getHost(), db, userIdentity.getQualifiedUser(),
                    userIdentity.isDomain(), privs);
            entry.setSetByDomainResolver(false);
        } catch (AnalysisException e) {
            throw new DdlException(e.getMessage());
        }
        dbPrivTable.addEntry(entry, errOnExist, errOnNonExist);
    }

    private void revokeDbPrivs(UserIdentity userIdentity, String db, PrivBitSet privs, boolean errOnNonExist)
            throws DdlException {
        DbPrivEntry entry;
        try {
            entry = DbPrivEntry.create(userIdentity.getHost(), db, userIdentity.getQualifiedUser(),
                    userIdentity.isDomain(), privs);
            entry.setSetByDomainResolver(false);
        } catch (AnalysisException e) {
            throw new DdlException(e.getMessage());
        }

        dbPrivTable.revoke(entry, errOnNonExist, true /* delete entry when empty */);
    }

    public String getRoleName(UserIdentity userIdentity) {
        return roleManager.getRoleName(userIdentity);
    }

    private void grantTblPrivs(UserIdentity userIdentity, String db, String tbl, boolean errOnExist,
                               boolean errOnNonExist, PrivBitSet privs) throws DdlException {
        TablePrivEntry entry;
        try {
            entry = TablePrivEntry.create(userIdentity.getHost(), db, userIdentity.getQualifiedUser(), tbl,
                    userIdentity.isDomain(), privs);
            entry.setSetByDomainResolver(false);
        } catch (AnalysisException e) {
            throw new DdlException(e.getMessage());
        }
        tablePrivTable.addEntry(entry, errOnExist, errOnNonExist);
    }

    private void revokeTblPrivs(UserIdentity userIdentity, String db, String tbl, PrivBitSet privs,
                                boolean errOnNonExist) throws DdlException {
        TablePrivEntry entry;
        try {
            entry = TablePrivEntry.create(userIdentity.getHost(), db, userIdentity.getQualifiedUser(), tbl,
                    userIdentity.isDomain(), privs);
            entry.setSetByDomainResolver(false);
        } catch (AnalysisException e) {
            throw new DdlException(e.getMessage());
        }
        tablePrivTable.revoke(entry, errOnNonExist, true /* delete entry when empty */);
    }

    private void grantResourcePrivs(UserIdentity userIdentity, String resourceName, boolean errOnExist,
                                    boolean errOnNonExist, PrivBitSet privs) throws DdlException {
        ResourcePrivEntry entry;
        try {
            entry = ResourcePrivEntry.create(userIdentity.getHost(), resourceName, userIdentity.getQualifiedUser(),
                    userIdentity.isDomain(), privs);
            entry.setSetByDomainResolver(false);
        } catch (AnalysisException e) {
            throw new DdlException(e.getMessage());
        }
        resourcePrivTable.addEntry(entry, errOnExist, errOnNonExist);
    }

    private void revokeResourcePrivs(UserIdentity userIdentity, String resourceName, PrivBitSet privs,
                                     boolean errOnNonExist) throws DdlException {
        ResourcePrivEntry entry;
        try {
            entry = ResourcePrivEntry.create(userIdentity.getHost(), resourceName, userIdentity.getQualifiedUser(),
                    userIdentity.isDomain(), privs);
            entry.setSetByDomainResolver(false);
        } catch (AnalysisException e) {
            throw new DdlException(e.getMessage());
        }

        resourcePrivTable.revoke(entry, errOnNonExist, true /* delete entry when empty */);
    }

    /*
     * check password, if matched, save the userIdentity in matched entry.
     * the following auth checking should use userIdentity saved in currentUser.
     */
    public boolean checkPassword(String remoteUser, String remoteHost, byte[] remotePasswd, byte[] randomString,
                                 List<UserIdentity> currentUser) {
        if (!Config.enable_auth_check) {
            return true;
        }
        // TODO: Got no better ways to handle the case that user forgot the password, but to remove this backdoor temporarily.
        // if ((remoteUser.equals(ROOT_USER) || remoteUser.equals(ADMIN_USER)) && remoteHost.equals("127.0.0.1")) {
        //     // root and admin user is allowed to login from 127.0.0.1, in case user forget password.
        //     if (remoteUser.equals(ROOT_USER)) {
        //         currentUser.add(UserIdentity.ROOT);
        //     } else {
        //         currentUser.add(UserIdentity.ADMIN);
        //     }
        //     return true;
        // }
        readLock();
        try {
            return userPrivTable.checkPassword(remoteUser, remoteHost, remotePasswd, randomString, currentUser);
        } finally {
            readUnlock();
        }
    }

    public boolean checkPlainPassword(String remoteUser, String remoteHost, String remotePasswd,
                                      List<UserIdentity> currentUser) {
        if (!Config.enable_auth_check) {
            return true;
        }
        readLock();
        try {
            return userPrivTable.checkPlainPassword(remoteUser, remoteHost, remotePasswd, currentUser);
        } finally {
            readUnlock();
        }
    }

    public boolean checkGlobalPriv(ConnectContext ctx, PrivPredicate wanted) {
        return checkGlobalPriv(ctx.getCurrentUserIdentity(), wanted);
    }

    public boolean checkGlobalPriv(UserIdentity currentUser, PrivPredicate wanted) {
        if (!Config.enable_auth_check) {
            return true;
        }
        PrivBitSet savedPrivs = PrivBitSet.of();
        if (checkGlobalInternal(currentUser, wanted, savedPrivs)) {
            return true;
        }

        LOG.debug("failed to get wanted privs: {}, ganted: {}", wanted, savedPrivs);
        return false;
    }

    public boolean checkDbPriv(ConnectContext ctx, String qualifiedDb, PrivPredicate wanted) {
        return checkDbPriv(ctx.getCurrentUserIdentity(), qualifiedDb, wanted);
    }

    /*
     * Check if 'user'@'host' on 'db' has 'wanted' priv.
     * If the given db is null, which means it will no check if database name is matched.
     */
    public boolean checkDbPriv(UserIdentity currentUser, String db, PrivPredicate wanted) {
        if (!Config.enable_auth_check) {
            return true;
        }
        if (wanted.getPrivs().containsNodePriv()) {
            LOG.debug("should not check NODE priv in Database level. user: {}, db: {}",
                    currentUser, db);
            return false;
        }

        PrivBitSet savedPrivs = PrivBitSet.of();
        if (checkGlobalInternal(currentUser, wanted, savedPrivs)
                || checkDbInternal(currentUser, db, wanted, savedPrivs)) {
            return true;
        }

        // if user has any privs of table in this db, and the wanted priv is SHOW, return true
        if (db != null && wanted == PrivPredicate.SHOW && checkTblWithDb(currentUser, db)) {
            return true;
        }

        LOG.debug("failed to get wanted privs: {}, ganted: {}", wanted, savedPrivs);
        return false;
    }

    /*
     * User may not have privs on a database, but have privs of tables in this database.
     * So we have to check if user has any privs of tables in this database.
     * if so, the database should be visible to this user.
     */
    private boolean checkTblWithDb(UserIdentity currentUser, String db) {
        readLock();
        try {
            return tablePrivTable.hasPrivsOfDb(currentUser, db);
        } finally {
            readUnlock();
        }
    }

    public boolean checkTblPriv(ConnectContext ctx, String qualifiedDb, String tbl, PrivPredicate wanted) {
        return checkTblPriv(ctx.getCurrentUserIdentity(), qualifiedDb, tbl, wanted);
    }

    public boolean checkTblPriv(UserIdentity currentUser, String db, String tbl, PrivPredicate wanted) {
        if (!Config.enable_auth_check) {
            return true;
        }
        if (wanted.getPrivs().containsNodePriv()) {
            LOG.debug("should check NODE priv in GLOBAL level. user: {}, db: {}, tbl: {}", currentUser, db, tbl);
            return false;
        }

        PrivBitSet savedPrivs = PrivBitSet.of();
        if (checkGlobalInternal(currentUser, wanted, savedPrivs)
                || checkDbInternal(currentUser, db, wanted, savedPrivs)
                || checkTblInternal(currentUser, db, tbl, wanted, savedPrivs)) {
            return true;
        }

        LOG.debug("failed to get wanted privs: {}, ganted: {}", wanted, savedPrivs);
        return false;
    }

    public boolean checkResourcePriv(ConnectContext ctx, String resourceName, PrivPredicate wanted) {
        return checkResourcePriv(ctx.getCurrentUserIdentity(), resourceName, wanted);
    }

    public boolean checkResourcePriv(UserIdentity currentUser, String resourceName, PrivPredicate wanted) {
        if (!Config.enable_auth_check) {
            return true;
        }

        PrivBitSet savedPrivs = PrivBitSet.of();
        if (checkGlobalInternal(currentUser, wanted, savedPrivs)
                || checkResourceInternal(currentUser, resourceName, wanted, savedPrivs)) {
            return true;
        }

        LOG.debug("failed to get wanted privs: {}, granted: {}", wanted, savedPrivs);
        return false;
    }

    public boolean checkPrivByAuthInfo(ConnectContext ctx, AuthorizationInfo authInfo, PrivPredicate wanted) {
        if (authInfo == null) {
            return false;
        }
        if (authInfo.getDbName() == null) {
            return false;
        }
        if (authInfo.getTableNameList() == null || authInfo.getTableNameList().isEmpty()) {
            return checkDbPriv(ctx, authInfo.getDbName(), wanted);
        }
        for (String tblName : authInfo.getTableNameList()) {
            if (!Catalog.getCurrentCatalog().getAuth().checkTblPriv(ConnectContext.get(), authInfo.getDbName(),
                    tblName, wanted)) {
                return false;
            }
        }
        return true;
    }

    /*
     * Check if current user has certain privilege.
     * This method will check the given privilege levels
     */
    public boolean checkHasPriv(ConnectContext ctx, PrivPredicate priv, PrivLevel... levels) {
        return checkHasPrivInternal(ctx.getRemoteIP(), ctx.getQualifiedUser(), priv, levels);
    }

    private boolean checkHasPrivInternal(String host, String user, PrivPredicate priv, PrivLevel... levels) {
        for (PrivLevel privLevel : levels) {
            switch (privLevel) {
                case GLOBAL:
                    if (userPrivTable.hasPriv(host, user, priv)) {
                        return true;
                    }
                    break;
                case DATABASE:
                    if (dbPrivTable.hasPriv(host, user, priv)) {
                        return true;
                    }
                    break;
                case TABLE:
                    if (tablePrivTable.hasPriv(host, user, priv)) {
                        return true;
                    }
                    break;
                default:
                    break;
            }
        }
        return false;
    }

    private boolean checkGlobalInternal(UserIdentity currentUser, PrivPredicate wanted, PrivBitSet savedPrivs) {
        readLock();
        try {
            userPrivTable.getPrivs(currentUser, savedPrivs);
            if (Privilege.satisfy(savedPrivs, wanted)) {
                return true;
            }
            return false;
        } finally {
            readUnlock();
        }
    }

    private boolean checkDbInternal(UserIdentity currentUser, String db, PrivPredicate wanted,
                                    PrivBitSet savedPrivs) {
        readLock();
        try {
            dbPrivTable.getPrivs(currentUser, db, savedPrivs);
            if (Privilege.satisfy(savedPrivs, wanted)) {
                return true;
            }
        } finally {
            readUnlock();
        }
        return false;
    }

    private boolean checkTblInternal(UserIdentity currentUser, String db, String tbl,
                                     PrivPredicate wanted, PrivBitSet savedPrivs) {
        readLock();
        try {
            tablePrivTable.getPrivs(currentUser, db, tbl, savedPrivs);
            if (Privilege.satisfy(savedPrivs, wanted)) {
                return true;
            }
            return false;
        } finally {
            readUnlock();
        }
    }

    private boolean checkResourceInternal(UserIdentity currentUser, String resourceName,
                                          PrivPredicate wanted, PrivBitSet savedPrivs) {
        readLock();
        try {
            resourcePrivTable.getPrivs(currentUser, resourceName, savedPrivs);
            if (Privilege.satisfy(savedPrivs, wanted)) {
                return true;
            }
            return false;
        } finally {
            readUnlock();
        }
    }

    // for test only
    public void clear() {
        userPrivTable.clear();
        dbPrivTable.clear();
        tablePrivTable.clear();
        resourcePrivTable.clear();
    }

    // create user
    public void createUser(CreateUserStmt stmt) throws DdlException {
        AuthPlugin authPlugin = null;
        if (!Strings.isNullOrEmpty(stmt.getAuthPlugin())) {
            authPlugin = AuthPlugin.valueOf(stmt.getAuthPlugin());
        }
        createUserInternal(stmt.getUserIdent(), stmt.getQualifiedRole(),
                new Password(stmt.getPassword(), authPlugin, stmt.getUserForAuthPlugin()), false);
    }

    // alter user
    public void alterUser(AlterUserStmt stmt) throws DdlException {
        AuthPlugin authPlugin = null;
        if (!Strings.isNullOrEmpty(stmt.getAuthPlugin())) {
            authPlugin = AuthPlugin.valueOf(stmt.getAuthPlugin());
        }
        // alter user only support change password till now
        setPasswordInternal(stmt.getUserIdent(),
                new Password(stmt.getPassword(), authPlugin, stmt.getUserForAuthPlugin()), null, true, false, false);
    }

    public void replayCreateUser(PrivInfo privInfo) {
        try {
            createUserInternal(privInfo.getUserIdent(), privInfo.getRole(), privInfo.getPasswd(), true);
        } catch (DdlException e) {
            LOG.error("should not happen", e);
        }
    }

    /*
     * Do following steps:
     * 1. Check does specified role exist. If not, throw exception.
     * 2. Check does user already exist. If yes, throw exception.
     * 3. set password for specified user.
     * 4. grant privs of role to user, if role is specified.
     */
    private void createUserInternal(UserIdentity userIdent, String roleName, Password password,
                                    boolean isReplay) throws DdlException {
        writeLock();
        try {
            // 1. check if role exist
            Role role = null;
            if (roleName != null) {
                role = roleManager.getRole(roleName);
                if (role == null) {
                    throw new DdlException("Role: " + roleName + " does not exist");
                }
            }

            // 2. check if user already exist
            if (userPrivTable.doesUserExist(userIdent)) {
                throw new DdlException("User " + userIdent + " already exist");
            }

            // 3. set password
            setPasswordInternal(userIdent, password, null, false /* err on non exist */,
                    false /* set by resolver */, true /* is replay */);

            // 4. grant privs of role to user
            if (role != null) {
                for (Map.Entry<TablePattern, PrivBitSet> entry : role.getTblPatternToPrivs().entrySet()) {
                    // use PrivBitSet copy to avoid same object being changed synchronously
                    grantInternal(userIdent, null /* role */, entry.getKey(), entry.getValue().copy(),
                            false /* err on non exist */, true /* is replay */);
                }
                for (Map.Entry<ResourcePattern, PrivBitSet> entry : role.getResourcePatternToPrivs().entrySet()) {
                    // use PrivBitSet copy to avoid same object being changed synchronously
                    grantInternal(userIdent, null /* role */, entry.getKey(), entry.getValue().copy(),
                            false /* err on non exist */, true /* is replay */);
                }
            }

            if (role != null) {
                // add user to this role
                role.addUser(userIdent);
            }

            // other user properties
            propertyMgr.addUserResource(userIdent.getQualifiedUser(), false /* not system user */);

            if (!userIdent.getQualifiedUser().equals(ROOT_USER) && !userIdent.getQualifiedUser().equals(ADMIN_USER)) {
                // grant read privs to database information_schema
                TablePattern tblPattern = new TablePattern(InfoSchemaDb.DATABASE_NAME, "*");
                try {
                    tblPattern.analyze(ClusterNamespace.getClusterNameFromFullName(userIdent.getQualifiedUser()));
                } catch (AnalysisException e) {
                    LOG.warn("should not happen", e);
                }
                grantInternal(userIdent, null /* role */, tblPattern, PrivBitSet.of(Privilege.SELECT_PRIV),
                        false /* err on non exist */, true /* is replay */);
            }

            if (!isReplay) {
                PrivInfo privInfo = new PrivInfo(userIdent, null, password, roleName);
                Catalog.getCurrentCatalog().getEditLog().logCreateUser(privInfo);
            }
            LOG.debug("finished to create user: {}, is replay: {}", userIdent, isReplay);
        } finally {
            writeUnlock();
        }
    }

    // drop user
    public void dropUser(DropUserStmt stmt) throws DdlException {
        String user = stmt.getUserIdentity().getQualifiedUser();
        String host = stmt.getUserIdentity().getHost();
        if (SystemInfoService.DEFAULT_CLUSTER.equals(stmt.getClusterName())
                && (ROOT_USER.equals(user) || ADMIN_USER.equals(user))
                && "%".equals(host)) {
            // Dropping `root@%` and `admin@%` is not allowed for `default_cluster`.
            throw new DdlException(String.format("User `%s`@`%s` is not allowed to be dropped.", user, host));
        }
        if (!SystemInfoService.DEFAULT_CLUSTER.equals(stmt.getClusterName())
                && "%".equals(host)) {
            // Allow dropping `superuser@%` when doing `DROP CLUSTER`, but not for `DROP USER`.
            throw new DdlException(String.format("User `%s`@`%s` is not allowed to be dropped.", user, host));
        }

        writeLock(); 
        try {
            if (!doesUserExist(stmt.getUserIdentity())) {
                throw new DdlException(String.format("User `%s`@`%s` does not exist.", user, host));
            }
            dropUserInternal(stmt.getUserIdentity(), false);
        } finally {
            writeUnlock();
        }
    }

    public void replayDropUser(UserIdentity userIdent) {
        dropUserInternal(userIdent, true);
    }

    private void dropUserInternal(UserIdentity userIdent, boolean isReplay) {
        writeLock();
        try {
            // we don't check if user exists
            userPrivTable.dropUser(userIdent);
            dbPrivTable.dropUser(userIdent);
            tablePrivTable.dropUser(userIdent);
            resourcePrivTable.dropUser(userIdent);
            // drop user in roles if exist
            roleManager.dropUser(userIdent);

            if (!userPrivTable.doesUsernameExist(userIdent.getQualifiedUser())) {
                // if user name does not exist in userPrivTable, which means all userIdent with this name
                // has been remove, then we can drop this user from property manager
                propertyMgr.dropUser(userIdent);
            } else if (userIdent.isDomain()) {
                // if there still has entry with this user name, we can not drop user from property map,
                // but we need to remove the specified domain from this user.
                propertyMgr.removeDomainFromUser(userIdent);
            }

            if (!isReplay) {
                Catalog.getCurrentCatalog().getEditLog().logNewDropUser(userIdent);
            }
            LOG.info("finished to drop user: {}, is replay: {}", userIdent.getQualifiedUser(), isReplay);
        } finally {
            writeUnlock();
        }
    }

    // grant
    public void grant(GrantStmt stmt) throws DdlException {
        PrivBitSet privs = PrivBitSet.of(stmt.getPrivileges());
        if (stmt.getTblPattern() != null) {
            grantInternal(stmt.getUserIdent(), stmt.getQualifiedRole(), stmt.getTblPattern(), privs,
                    true /* err on non exist */, false /* not replay */);
        } else {
            grantInternal(stmt.getUserIdent(), stmt.getQualifiedRole(), stmt.getResourcePattern(), privs,
                    true /* err on non exist */, false /* not replay */);
        }
    }

    public void replayGrant(PrivInfo privInfo) {
        try {
            if (privInfo.getTblPattern() != null) {
                grantInternal(privInfo.getUserIdent(), privInfo.getRole(),
                        privInfo.getTblPattern(), privInfo.getPrivs(),
                        true /* err on non exist */, true /* is replay */);
            } else {
                grantInternal(privInfo.getUserIdent(), privInfo.getRole(),
                        privInfo.getResourcePattern(), privInfo.getPrivs(),
                        true /* err on non exist */, true /* is replay */);
            }
        } catch (DdlException e) {
            LOG.error("should not happen", e);
        }
    }

    private void grantInternal(UserIdentity userIdent, String role, TablePattern tblPattern,
                               PrivBitSet privs, boolean errOnNonExist, boolean isReplay)
            throws DdlException {
        writeLock();
        try {
            if (role != null) {
                // grant privs to role, role must exist
                Role newRole = new Role(role, tblPattern, privs);
                Role existingRole = roleManager.addRole(newRole, false /* err on exist */);

                // update users' privs of this role
                for (UserIdentity user : existingRole.getUsers()) {
                    for (Map.Entry<TablePattern, PrivBitSet> entry : existingRole.getTblPatternToPrivs().entrySet()) {
                        // copy the PrivBitSet
                        grantPrivs(user, entry.getKey(), entry.getValue().copy(), errOnNonExist);
                    }
                }
            } else {
                grantPrivs(userIdent, tblPattern, privs, errOnNonExist);
            }

            if (!isReplay) {
                PrivInfo info = new PrivInfo(userIdent, tblPattern, privs, null, role);
                Catalog.getCurrentCatalog().getEditLog().logGrantPriv(info);
            }
            LOG.debug("finished to grant privilege. is replay: {}", isReplay);
        } finally {
            writeUnlock();
        }
    }

    private void grantInternal(UserIdentity userIdent, String role, ResourcePattern resourcePattern, PrivBitSet privs,
                               boolean errOnNonExist, boolean isReplay) throws DdlException {
        writeLock();
        try {
            if (role != null) {
                // grant privs to role, role must exist
                Role newRole = new Role(role, resourcePattern, privs);
                Role existingRole = roleManager.addRole(newRole, false /* err on exist */);

                // update users' privs of this role
                for (UserIdentity user : existingRole.getUsers()) {
                    for (Map.Entry<ResourcePattern, PrivBitSet> entry : existingRole.getResourcePatternToPrivs()
                            .entrySet()) {
                        // copy the PrivBitSet
                        grantPrivs(user, entry.getKey(), entry.getValue().copy(), errOnNonExist);
                    }
                }
            } else {
                grantPrivs(userIdent, resourcePattern, privs, errOnNonExist);
            }

            if (!isReplay) {
                PrivInfo info = new PrivInfo(userIdent, resourcePattern, privs, null, role);
                Catalog.getCurrentCatalog().getEditLog().logGrantPriv(info);
            }
            LOG.debug("finished to grant resource privilege. is replay: {}", isReplay);
        } finally {
            writeUnlock();
        }
    }

    public void grantPrivs(UserIdentity userIdent, TablePattern tblPattern, PrivBitSet privs,
                           boolean errOnNonExist) throws DdlException {
        LOG.debug("grant {} on {} to {}, err on non exist: {}", privs, tblPattern, userIdent, errOnNonExist);

        writeLock();
        try {
            // check if user identity already exist
            if (errOnNonExist && !doesUserExist(userIdent)) {
                throw new DdlException("user " + userIdent + " does not exist");
            }

            // grant privs to user
            switch (tblPattern.getPrivLevel()) {
                case GLOBAL:
                    grantGlobalPrivs(userIdent,
                            false /* err on exist */,
                            errOnNonExist,
                            privs);
                    break;
                case DATABASE:
                    grantDbPrivs(userIdent, tblPattern.getQuolifiedDb(),
                            false /* err on exist */,
                            false /* err on non exist */,
                            privs);
                    break;
                case TABLE:
                    grantTblPrivs(userIdent, tblPattern.getQuolifiedDb(),
                            tblPattern.getTbl(),
                            false /* err on exist */,
                            false /* err on non exist */,
                            privs);
                    break;
                default:
                    Preconditions.checkNotNull(null, tblPattern.getPrivLevel());
            }
        } finally {
            writeUnlock();
        }
    }

    public void grantPrivs(UserIdentity userIdent, ResourcePattern resourcePattern, PrivBitSet privs,
                           boolean errOnNonExist) throws DdlException {
        LOG.debug("grant {} on resource {} to {}, err on non exist: {}", privs, resourcePattern, userIdent,
                errOnNonExist);

        writeLock();
        try {
            // check if user identity already exist
            if (errOnNonExist && !doesUserExist(userIdent)) {
                throw new DdlException("user " + userIdent + " does not exist");
            }

            // grant privs to user
            switch (resourcePattern.getPrivLevel()) {
                case GLOBAL:
                    grantGlobalPrivs(userIdent, false, errOnNonExist, privs);
                    break;
                case RESOURCE:
                    grantResourcePrivs(userIdent, resourcePattern.getResourceName(), false, false, privs);
                    break;
                default:
                    Preconditions.checkNotNull(null, resourcePattern.getPrivLevel());
            }
        } finally {
            writeUnlock();
        }
    }

    // return true if user ident exist
    private boolean doesUserExist(UserIdentity userIdent) {
        if (userIdent.isDomain()) {
            return propertyMgr.doesUserExist(userIdent);
        } else {
            return userPrivTable.doesUserExist(userIdent);
        }
    }

    // revoke
    public void revoke(RevokeStmt stmt) throws DdlException {
        PrivBitSet privs = PrivBitSet.of(stmt.getPrivileges());
        if (stmt.getTblPattern() != null) {
            revokeInternal(stmt.getUserIdent(), stmt.getQualifiedRole(), stmt.getTblPattern(), privs,
                    true /* err on non exist */, false /* is replay */);
        } else {
            revokeInternal(stmt.getUserIdent(), stmt.getQualifiedRole(), stmt.getResourcePattern(), privs,
                    true /* err on non exist */, false /* is replay */);
        }
    }

    public void replayRevoke(PrivInfo info) {
        try {
            if (info.getTblPattern() != null) {
                revokeInternal(info.getUserIdent(), info.getRole(), info.getTblPattern(), info.getPrivs(),
                        true /* err on non exist */, true /* is replay */);
            } else {
                revokeInternal(info.getUserIdent(), info.getRole(), info.getResourcePattern(), info.getPrivs(),
                        true /* err on non exist */, true /* is replay */);
            }
        } catch (DdlException e) {
            LOG.error("should not happend", e);
        }
    }

    private void revokeInternal(UserIdentity userIdent, String role, TablePattern tblPattern,
                                PrivBitSet privs, boolean errOnNonExist, boolean isReplay) throws DdlException {
        writeLock();
        try {
            if (role != null) {
                // revoke privs from role
                Role existingRole = roleManager.revokePrivs(role, tblPattern, privs, errOnNonExist);
                if (existingRole != null) {
                    // revoke privs from users of this role
                    for (UserIdentity user : existingRole.getUsers()) {
                        revokePrivs(user, tblPattern, privs, false /* err on non exist */);
                    }
                }
            } else {
                revokePrivs(userIdent, tblPattern, privs, errOnNonExist);
            }

            if (!isReplay) {
                PrivInfo info = new PrivInfo(userIdent, tblPattern, privs, null, role);
                Catalog.getCurrentCatalog().getEditLog().logRevokePriv(info);
            }
            LOG.info("finished to revoke privilege. is replay: {}", isReplay);
        } finally {
            writeUnlock();
        }
    }

    private void revokeInternal(UserIdentity userIdent, String role, ResourcePattern resourcePattern,
                                PrivBitSet privs, boolean errOnNonExist, boolean isReplay) throws DdlException {
        writeLock();
        try {
            if (role != null) {
                // revoke privs from role
                Role existingRole = roleManager.revokePrivs(role, resourcePattern, privs, errOnNonExist);
                if (existingRole != null) {
                    // revoke privs from users of this role
                    for (UserIdentity user : existingRole.getUsers()) {
                        revokePrivs(user, resourcePattern, privs, false /* err on non exist */);
                    }
                }
            } else {
                revokePrivs(userIdent, resourcePattern, privs, errOnNonExist);
            }

            if (!isReplay) {
                PrivInfo info = new PrivInfo(userIdent, resourcePattern, privs, null, role);
                Catalog.getCurrentCatalog().getEditLog().logRevokePriv(info);
            }
            LOG.info("finished to revoke privilege. is replay: {}", isReplay);
        } finally {
            writeUnlock();
        }
    }

    public void revokePrivs(UserIdentity userIdent, TablePattern tblPattern, PrivBitSet privs,
                            boolean errOnNonExist) throws DdlException {
        writeLock();
        try {
            switch (tblPattern.getPrivLevel()) {
                case GLOBAL:
                    revokeGlobalPrivs(userIdent, privs, errOnNonExist);
                    break;
                case DATABASE:
                    revokeDbPrivs(userIdent, tblPattern.getQuolifiedDb(), privs, errOnNonExist);
                    break;
                case TABLE:
                    revokeTblPrivs(userIdent, tblPattern.getQuolifiedDb(), tblPattern.getTbl(), privs,
                            errOnNonExist);
                    break;
                default:
                    Preconditions.checkNotNull(null, tblPattern.getPrivLevel());
            }
        } finally {
            writeUnlock();
        }
    }

    public void revokePrivs(UserIdentity userIdent, ResourcePattern resourcePattern, PrivBitSet privs,
                            boolean errOnNonExist) throws DdlException {
        writeLock();
        try {
            switch (resourcePattern.getPrivLevel()) {
                case GLOBAL:
                    revokeGlobalPrivs(userIdent, privs, errOnNonExist);
                    break;
                case RESOURCE:
                    revokeResourcePrivs(userIdent, resourcePattern.getResourceName(), privs, errOnNonExist);
                    break;
            }
        } finally {
            writeUnlock();
        }
    }

    // set password
    public void setPassword(SetPassVar stmt) throws DdlException {
        Password passwordToSet = new Password(stmt.getPassword());
        Password currentPassword = userPrivTable.getPassword(stmt.getUserIdent());
        if (currentPassword != null) {
            passwordToSet.setAuthPlugin(currentPassword.getAuthPlugin());
            passwordToSet.setUserForAuthPlugin(currentPassword.getUserForAuthPlugin());
        }
        setPasswordInternal(stmt.getUserIdent(), passwordToSet, null, true /* err on non exist */,
                false /* set by resolver */, false);
    }

    public void replaySetPassword(PrivInfo info) {
        try {
            setPasswordInternal(info.getUserIdent(), info.getPasswd(), null, true /* err on non exist */,
                    false /* set by resolver */, true);
        } catch (DdlException e) {
            LOG.error("should not happend", e);
        }
    }

    public void setPasswordInternal(UserIdentity userIdent, Password password, UserIdentity domainUserIdent,
                                    boolean errOnNonExist, boolean setByResolver, boolean isReplay)
            throws DdlException {
        Preconditions.checkArgument(!setByResolver || domainUserIdent != null, setByResolver + ", " + domainUserIdent);
        writeLock();
        try {
            if (userIdent.isDomain()) {
                // throw exception if this user already contains this domain
                propertyMgr.setPasswordForDomain(userIdent, password.getPassword(), true /* err on exist */,
                        errOnNonExist /* err on non exist */);
            } else {
                GlobalPrivEntry passwdEntry;
                try {
                    passwdEntry = GlobalPrivEntry.create(userIdent.getHost(), userIdent.getQualifiedUser(),
                            userIdent.isDomain(), password, PrivBitSet.of());
                    passwdEntry.setSetByDomainResolver(setByResolver);
                    if (setByResolver) {
                        Preconditions.checkNotNull(domainUserIdent);
                        passwdEntry.setDomainUserIdent(domainUserIdent);
                    }
                } catch (AnalysisException e) {
                    throw new DdlException(e.getMessage());
                }
                userPrivTable.setPassword(passwdEntry, errOnNonExist);
            }

            if (!isReplay) {
                PrivInfo info = new PrivInfo(userIdent, null, password, null);
                Catalog.getCurrentCatalog().getEditLog().logSetPassword(info);
            }
        } finally {
            writeUnlock();
        }
        LOG.debug("finished to set password for {}. is replay: {}", userIdent, isReplay);
    }

    // create role
    public void createRole(CreateRoleStmt stmt) throws DdlException {
        createRoleInternal(stmt.getQualifiedRole(), false);
    }

    public void replayCreateRole(PrivInfo info) {
        try {
            createRoleInternal(info.getRole(), true);
        } catch (DdlException e) {
            LOG.error("should not happend", e);
        }
    }

    private void createRoleInternal(String role, boolean isReplay) throws DdlException {
        Role emptyPrivsRole = new Role(role);
        writeLock();
        try {
            roleManager.addRole(emptyPrivsRole, true /* err on exist */);

            if (!isReplay) {
                PrivInfo info = new PrivInfo(null, null, null, role);
                Catalog.getCurrentCatalog().getEditLog().logCreateRole(info);
            }
        } finally {
            writeUnlock();
        }
        LOG.info("finished to create role: {}, is replay: {}", role, isReplay);
    }

    // drop role
    public void dropRole(DropRoleStmt stmt) throws DdlException {
        dropRoleInternal(stmt.getQualifiedRole(), false);
    }

    public void replayDropRole(PrivInfo info) {
        try {
            dropRoleInternal(info.getRole(), true);
        } catch (DdlException e) {
            LOG.error("should not happend", e);
        }
    }

    private void dropRoleInternal(String role, boolean isReplay) throws DdlException {
        writeLock();
        try {
            roleManager.dropRole(role, true /* err on non exist */);

            if (!isReplay) {
                PrivInfo info = new PrivInfo(null, null, null, role);
                Catalog.getCurrentCatalog().getEditLog().logDropRole(info);
            }
        } finally {
            writeUnlock();
        }
        LOG.info("finished to drop role: {}, is replay: {}", role, isReplay);
    }

    // update user property
    public void updateUserProperty(SetUserPropertyStmt stmt) throws DdlException {
        List<Pair<String, String>> properties = stmt.getPropertyPairList();
        updateUserPropertyInternal(stmt.getUser(), properties, false /* is replay */);
    }

    public void replayUpdateUserProperty(UserPropertyInfo propInfo) throws DdlException {
        updateUserPropertyInternal(propInfo.getUser(), propInfo.getProperties(), true /* is replay */);
    }

    public void updateUserPropertyInternal(String user, List<Pair<String, String>> properties, boolean isReplay)
            throws DdlException {
        writeLock();
        try {
            propertyMgr.updateUserProperty(user, properties);
            if (!isReplay) {
                UserPropertyInfo propertyInfo = new UserPropertyInfo(user, properties);
                Catalog.getCurrentCatalog().getEditLog().logUpdateUserProperty(propertyInfo);
            }
            LOG.info("finished to set properties for user: {}", user);
        } finally {
            writeUnlock();
        }
    }

    public long getMaxConn(String qualifiedUser) {
        readLock();
        try {
            return propertyMgr.getMaxConn(qualifiedUser);
        } finally {
            readUnlock();
        }
    }

    public void getAllDomains(Set<String> allDomains) {
        readLock();
        try {
            propertyMgr.getAllDomains(allDomains);
        } finally {
            readUnlock();
        }
    }

    public List<DbPrivEntry> getDBPrivEntries(UserIdentity userIdent) {
        List<DbPrivEntry> dbPrivs = Lists.newArrayList();
        readLock();
        try {
            for (PrivEntry entry : dbPrivTable.entries) {
                if (!entry.match(userIdent, true /* exact match */)) {
                    continue;
                }
                dbPrivs.add((DbPrivEntry) entry);
            }
        } finally {
            readUnlock();
        }
        return dbPrivs;
    }

    public List<TablePrivEntry> getTablePrivEntries(UserIdentity userIdent) {
        List<TablePrivEntry> tablePrivs = Lists.newArrayList();
        readLock();
        try {
            for (PrivEntry entry : tablePrivTable.entries) {
                if (!entry.match(userIdent, true /* exact match */)) {
                    continue;
                }
                tablePrivs.add((TablePrivEntry) entry);
            }
        } finally {
            readUnlock();
        }
        return tablePrivs;
    }

    // refresh all priv entries set by domain resolver.
    // 1. delete all priv entries in user priv table which are set by domain resolver previously.
    // 2. add priv entries by new resolving IPs
    public void refreshUserPrivEntriesByResovledIPs(Map<String, Set<String>> resolvedIPsMap) {
        writeLock();
        try {
            // 1. delete all previously set entries
            userPrivTable.clearEntriesSetByResolver();
            // 2. add new entries
            propertyMgr.addUserPrivEntriesByResovledIPs(resolvedIPsMap);
        } finally {
            writeUnlock();
        }
    }

    // return the auth info of specified user, or infos of all users, if user is not specified.
    // the returned columns are defined in AuthProcDir
    // the specified user identity should be the identity created by CREATE USER, same as result of
    // SELECT CURRENT_USER();
    public List<List<String>> getAuthInfo(UserIdentity specifiedUserIdent) {
        List<List<String>> userAuthInfos = Lists.newArrayList();
        readLock();
        try {
            if (specifiedUserIdent == null) {
                // get all users' auth info
                Set<UserIdentity> userIdents = getAllUserIdents(false /* include entry set by resolver */);
                for (UserIdentity userIdent : userIdents) {
                    getUserAuthInfo(userAuthInfos, userIdent);
                }
            } else {
                getUserAuthInfo(userAuthInfos, specifiedUserIdent);
            }
        } finally {
            readUnlock();
        }
        return userAuthInfos;
    }

    private void getUserAuthInfo(List<List<String>> userAuthInfos, UserIdentity userIdent) {
        List<String> userAuthInfo = Lists.newArrayList();

        // global
        for (PrivEntry entry : userPrivTable.entries) {
            if (!entry.match(userIdent, true /* exact match */)) {
                continue;
            }
            GlobalPrivEntry gEntry = (GlobalPrivEntry) entry;
            userAuthInfo.add(userIdent.toString());
            Password password = gEntry.getPassword();
            //Password
            if (userIdent.isDomain()) {
                // for domain user ident, password is saved in property manager
                userAuthInfo.add(propertyMgr.doesUserHasPassword(userIdent) ? "No" : "Yes");
            } else {
                userAuthInfo.add((password == null || password.getPassword().length == 0) ? "No" : "Yes");
            }
            //AuthPlugin and UserForAuthPlugin
            if (password == null) {
                userAuthInfo.add(FeConstants.null_string);
                userAuthInfo.add(FeConstants.null_string);
            } else {
                if (password.getAuthPlugin() == null) {
                    userAuthInfo.add(FeConstants.null_string);
                } else {
                    userAuthInfo.add(password.getAuthPlugin().name());
                }

                if (Strings.isNullOrEmpty(password.getUserForAuthPlugin())) {
                    userAuthInfo.add(FeConstants.null_string);
                } else {
                    userAuthInfo.add(password.getUserForAuthPlugin());
                }
            }
            //GlobalPrivs
            userAuthInfo.add(gEntry.getPrivSet().toString() + " (" + gEntry.isSetByDomainResolver() + ")");
            break;
        }

        if (userAuthInfo.isEmpty()) {
            if (!userIdent.isDomain()) {
                // If this is not a domain user identity, it must have global priv entry.
                // TODO(cmy): I don't know why previous comment said:
                // This may happen when we grant non global privs to a non exist user via GRANT stmt.
                LOG.warn("user identity does not have global priv entry: {}", userIdent);
                userAuthInfo.add(userIdent.toString());
                userAuthInfo.add(FeConstants.null_string);
                userAuthInfo.add(FeConstants.null_string);
                userAuthInfo.add(FeConstants.null_string);
                userAuthInfo.add(FeConstants.null_string);
            } else {
                // this is a domain user identity and fall in here, which means this user identity does not
                // have global priv, we need to check user property to see if it has password.
                userAuthInfo.add(userIdent.toString());
                userAuthInfo.add(propertyMgr.doesUserHasPassword(userIdent) ? "No" : "Yes");
                userAuthInfo.add(FeConstants.null_string);
                userAuthInfo.add(FeConstants.null_string);
                userAuthInfo.add(FeConstants.null_string);
            }
        }

        // db
        List<String> dbPrivs = Lists.newArrayList();
        for (PrivEntry entry : dbPrivTable.entries) {
            if (!entry.match(userIdent, true /* exact match */)) {
                continue;
            }
            DbPrivEntry dEntry = (DbPrivEntry) entry;
            dbPrivs.add(dEntry.getOrigDb() + ": " + dEntry.getPrivSet().toString()
                    + " (" + entry.isSetByDomainResolver() + ")");
        }
        if (dbPrivs.isEmpty()) {
            userAuthInfo.add(FeConstants.null_string);
        } else {
            userAuthInfo.add(Joiner.on("; ").join(dbPrivs));
        }

        // tbl
        List<String> tblPrivs = Lists.newArrayList();
        for (PrivEntry entry : tablePrivTable.entries) {
            if (!entry.match(userIdent, true /* exact match */)) {
                continue;
            }
            TablePrivEntry tEntry = (TablePrivEntry) entry;
            tblPrivs.add(tEntry.getOrigDb() + "." + tEntry.getOrigTbl() + ": "
                    + tEntry.getPrivSet().toString()
                    + " (" + entry.isSetByDomainResolver() + ")");
        }
        if (tblPrivs.isEmpty()) {
            userAuthInfo.add(FeConstants.null_string);
        } else {
            userAuthInfo.add(Joiner.on("; ").join(tblPrivs));
        }

        // resource
        List<String> resourcePrivs = Lists.newArrayList();
        for (PrivEntry entry : resourcePrivTable.entries) {
            if (!entry.match(userIdent, true /* exact match */)) {
                continue;
            }
            ResourcePrivEntry rEntry = (ResourcePrivEntry) entry;
            resourcePrivs.add(rEntry.getOrigResource() + ": " + rEntry.getPrivSet().toString()
                    + " (" + entry.isSetByDomainResolver() + ")");
        }
        if (resourcePrivs.isEmpty()) {
            userAuthInfo.add(FeConstants.null_string);
        } else {
            userAuthInfo.add(Joiner.on("; ").join(resourcePrivs));
        }

        userAuthInfos.add(userAuthInfo);
    }

    private Set<UserIdentity> getAllUserIdents(boolean includeEntrySetByResolver) {
        Set<UserIdentity> userIdents = Sets.newHashSet();
        for (PrivEntry entry : userPrivTable.entries) {
            if (!includeEntrySetByResolver && entry.isSetByDomainResolver()) {
                continue;
            }
            userIdents.add(entry.getUserIdent());
        }
        for (PrivEntry entry : dbPrivTable.entries) {
            if (!includeEntrySetByResolver && entry.isSetByDomainResolver()) {
                continue;
            }
            userIdents.add(entry.getUserIdent());
        }
        for (PrivEntry entry : tablePrivTable.entries) {
            if (!includeEntrySetByResolver && entry.isSetByDomainResolver()) {
                continue;
            }
            userIdents.add(entry.getUserIdent());
        }
        for (PrivEntry entry : resourcePrivTable.entries) {
            if (!includeEntrySetByResolver && entry.isSetByDomainResolver()) {
                continue;
            }
            userIdents.add(entry.getUserIdent());
        }
        return userIdents;
    }

    public List<List<String>> getUserProperties(String qualifiedUser) {
        readLock();
        try {
            return propertyMgr.fetchUserProperty(qualifiedUser);
        } catch (AnalysisException e) {
            return Lists.newArrayList();
        } finally {
            readUnlock();
        }
    }

    public void dropUserOfCluster(String clusterName, boolean isReplay) {
        writeLock();
        try {
            Set<UserIdentity> allUserIdents = getAllUserIdents(true);
            for (UserIdentity userIdent : allUserIdents) {
                if (userIdent.getQualifiedUser().startsWith(clusterName)) {
                    dropUserInternal(userIdent, isReplay);
                }
            }
        } finally {
            writeUnlock();
        }
    }

    // user can enter a cluster, if it has any privs of database or table in this cluster.
    public boolean checkCanEnterCluster(ConnectContext ctx, String clusterName) {
        readLock();
        try {
            if (checkGlobalPriv(ctx, PrivPredicate.ALL)) {
                return true;
            }

            if (dbPrivTable.hasClusterPriv(ctx, clusterName)) {
                return true;
            }

            if (tablePrivTable.hasClusterPriv(ctx, clusterName)) {
                return true;
            }

            return false;
        } finally {
            readUnlock();
        }
    }

    private void initUser() {
        try {
            UserIdentity rootUser = new UserIdentity(ROOT_USER, "%");
            rootUser.setIsAnalyzed();
            createUserInternal(rootUser, Role.OPERATOR_ROLE, new Password(new byte[0]), true /* is replay */);
        } catch (DdlException e) {
            LOG.error("should not happend", e);
        }
    }

    public TFetchResourceResult toResourceThrift() {
        readLock();
        try {
            return propertyMgr.toResourceThrift();
        } finally {
            readUnlock();
        }
    }

    public List<List<String>> getRoleInfo() {
        readLock();
        try {
            List<List<String>> results = Lists.newArrayList();
            roleManager.getRoleInfo(results);
            return results;
        } finally {
            readUnlock();
        }
    }

    public boolean isSupportKerberosAuth() {
        if (!Config.enable_authentication_kerberos) {
            LOG.error("enable_authentication_kerberos need to be set to true");
            return false;
        }

        if (Config.authentication_kerberos_service_principal.isEmpty()) {
            LOG.error("authentication_kerberos_service_principal must be set in config");
            return false;
        }

        if (Config.authentication_kerberos_service_key_tab.isEmpty()) {
            LOG.error("authentication_kerberos_service_key_tab must be set in config");
            return false;
        }

        if (authClazz == null) {
            try {
                File jarFile = new File(KRB5_AUTH_JAR_PATH);
                if (!jarFile.exists()) {
                    LOG.error("Can not found jar file at {}", KRB5_AUTH_JAR_PATH);
                    return false;
                } else {
                    ClassLoader loader = URLClassLoader.newInstance(
                            new URL[] { jarFile.toURL() },
                            getClass().getClassLoader()
                    );
                    authClazz = Class.forName(Auth.KRB5_AUTH_CLASS_NAME, true, loader);
                }
            } catch (Exception e) {
                LOG.error("Failed to load {}", Auth.KRB5_AUTH_CLASS_NAME, e);
                return false;
            }
        }

        return true;
    }

    public Class<?> getAuthClazz() {
        return authClazz;
    }

    public static Auth read(DataInput in) throws IOException {
        Auth auth = new Auth();
        auth.readFields(in);
        return auth;
    }

    @Override
    public void write(DataOutput out) throws IOException {
        // role manager must be first, because role should be exist before any user
        roleManager.write(out);
        userPrivTable.write(out);
        dbPrivTable.write(out);
        tablePrivTable.write(out);
        resourcePrivTable.write(out);
        propertyMgr.write(out);
    }

    public void readFields(DataInput in) throws IOException {
        roleManager = RoleManager.read(in);
        userPrivTable = (UserPrivTable) PrivTable.read(in);
        dbPrivTable = (DbPrivTable) PrivTable.read(in);
        tablePrivTable = (TablePrivTable) PrivTable.read(in);
        if (Catalog.getCurrentCatalogJournalVersion() >= FeMetaVersion.VERSION_87) {
            resourcePrivTable = (ResourcePrivTable) PrivTable.read(in);
        }
        propertyMgr = UserPropertyMgr.read(in);

        if (userPrivTable.isEmpty()) {
            // init root and admin user
            initUser();
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(userPrivTable).append("\n");
        sb.append(dbPrivTable).append("\n");
        sb.append(tablePrivTable).append("\n");
        sb.append(resourcePrivTable).append("\n");
        sb.append(roleManager).append("\n");
        sb.append(propertyMgr).append("\n");
        return sb.toString();
    }
}

