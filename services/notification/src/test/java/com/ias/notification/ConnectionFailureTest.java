package com.ias.notification;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionFailureTest {
    Connection connection(List<String> calls,Set<String> failures){return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(p,m,a)->{
        String name=m.getName();if(name.equals("setAutoCommit")){calls.add(name+":"+a[0]);assertEquals(false,a[0]);return null;}
        if(Set.of("commit","rollback","abort","close").contains(name)){calls.add(name);if(failures.contains(name))throw new SQLException("injected "+name);return null;}
        if(name.equals("toString"))return "PhysicalConnectionTest";if(name.equals("isClosed"))return false;throw new UnsupportedOperationException(name);
    });}
    @Test void rollbackAndAbortFailureQuarantinesWithoutPoolClose(){var calls=new ArrayList<String>();var c=connection(calls,Set.of("rollback","abort"));assertThrows(SQLException.class,()->JdbcDelivery.onPhysical(c,x->{throw new SQLException("business");}));assertEquals(List.of("setAutoCommit:false","rollback","abort"),calls);}
    @Test void failedCommitAndRollbackAbortBeforePhysicalClose(){var calls=new ArrayList<String>();var c=connection(calls,Set.of("commit","rollback"));assertThrows(SQLException.class,()->JdbcDelivery.onPhysical(c,x->1));assertEquals(List.of("setAutoCommit:false","commit","rollback","abort","close"),calls);}
    @Test void uncertainCommitWithSuccessfulRollbackStillClosesOwnedPhysicalConnection(){var calls=new ArrayList<String>();var c=connection(calls,Set.of("commit"));assertThrows(SQLException.class,()->JdbcDelivery.onPhysical(c,x->1));assertEquals(List.of("setAutoCommit:false","commit","rollback","close"),calls);}
    @Test void successfulTransactionCommitsAndClosesNeverRestoresAutoCommit()throws Exception{var calls=new ArrayList<String>();Integer value=JdbcDelivery.onPhysical(connection(calls,Set.of()),x->1);assertEquals(Integer.valueOf(1),value);assertEquals(List.of("setAutoCommit:false","commit","close"),calls);}
}

