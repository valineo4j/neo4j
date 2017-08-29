/*
 * Copyright (c) 2002-2017 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.builtinprocs;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;
import org.neo4j.kernel.api.proc.ProcedureSignature;
import org.neo4j.kernel.impl.proc.Procedures;
import org.neo4j.test.rule.DatabaseRule;
import org.neo4j.test.rule.EnterpriseDatabaseRule;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ProcedureResourcesIT
{
    @Rule
    public DatabaseRule db = new EnterpriseDatabaseRule();

    private final String indexDefinition = ":Label(prop)";
    private final String legacyIndexName = "legacyIndex";
    private final String relLegacyIndexName = "relLegacyIndex";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @After
    public void tearDown() throws InterruptedException
    {
        executor.shutdown();
        executor.awaitTermination( 5, TimeUnit.SECONDS );
    }

    @Test
    public void allProcedures() throws Exception
    {
        // when
        createLegacyIndex();
        createIndex();
        for(ProcedureSignature procedure : db.getDependencyResolver().resolveDependency( Procedures.class ).getAllProcedures())
        {
            // then
            initialData();
            verifyProcedureCloseAllAcquiredKernelStatements( procedureDataFor( procedure ) );
            clearDb();
        }
    }

    private void initialData()
    {
        Label unusedLabel = Label.label( "unusedLabel" );
        RelationshipType unusedRelType = RelationshipType.withName( "unusedRelType" );
        String unusedPropKey = "unusedPropKey";
        try ( Transaction tx = db.beginTx() )
        {
            Node node1 = db.createNode( unusedLabel );
            node1.setProperty( unusedPropKey, "value" );
            Node node2 = db.createNode( unusedLabel );
            node2.setProperty( unusedPropKey, 1 );
            node1.createRelationshipTo( node2, unusedRelType );
            tx.success();
        }
    }

    private void verifyProcedureCloseAllAcquiredKernelStatements( ProcedureData proc ) throws ExecutionException, InterruptedException
    {
        String failureMessage = "Failed on procedure " + proc.name;
        try ( Transaction outer = db.beginTx() )
        {
            String procedureQuery = proc.buildProcedureQuery();
            exhaust( db.execute( procedureQuery ) ).close();
            exhaust( db.execute( "MATCH (mo:Label) WHERE mo.prop = 'n/a' RETURN mo" ) ).close();
            executeInOtherThread( "CREATE(mo:Label) SET mo.prop = 'val' RETURN mo" );
            Result result = db.execute( "MATCH (mo:Label) WHERE mo.prop = 'val' RETURN mo" );
            assertTrue( failureMessage, result.hasNext() );
            Map<String,Object> next = result.next();
            assertNotNull( failureMessage, next.get( "mo" ) );
            exhaust( result );
            result.close();
            outer.success();
        }
    }

    private Result exhaust( Result execute )
    {
        while ( execute.hasNext() )
        {
            execute.next();
        }
        return execute;
    }

    private void createIndex()
    {
        try ( Transaction tx = db.beginTx() )
        {
            db.execute( "CREATE INDEX ON " + indexDefinition );
            tx.success();
        }
        try ( Transaction tx = db.beginTx() )
        {
            db.schema().awaitIndexesOnline( 5, TimeUnit.SECONDS );
            tx.success();
        }
    }

    private void createLegacyIndex()
    {
        try ( Transaction tx = db.beginTx() )
        {
            db.index().forNodes( legacyIndexName );
            db.index().forRelationships( relLegacyIndexName );
            tx.success();
        }
    }

    private void clearDb()
    {
        try ( Transaction tx = db.beginTx() )
        {
            db.execute( "MATCH (n) DETACH DELETE n" ).close();
            tx.success();
        }
    }

    private static class ProcedureData
    {
        private final String name;
        private final List<Object> params = new ArrayList<>();
        private String setupQuery = null;
        private String postQuery = null;

        private ProcedureData( ProcedureSignature procedure )
        {
            this.name = procedure.name().toString();
        }

        private void withParam( Object param )
        {
            this.params.add( param );
        }

        private void withSetup( String setupQuery, String postQuery )
        {
            this.setupQuery = setupQuery;
            this.postQuery = postQuery;
        }

        private String buildProcedureQuery()
        {
            StringJoiner stringJoiner = new StringJoiner( ",", "CALL " + name + "(", ")" );
            for ( Object parameter : params )
            {
                stringJoiner.add( parameter.toString() );
            }
            if ( setupQuery != null && postQuery != null )
            {
                return setupQuery + " " + stringJoiner.toString() + " " + postQuery;
            }
            else
            {
                return stringJoiner.toString();
            }
        }
    }

    private ProcedureData procedureDataFor( ProcedureSignature procedure )
    {
        ProcedureData proc = new ProcedureData( procedure );
        switch ( proc.name )
        {
        case "db.createProperty":
            proc.withParam( "'propKey'" );
            break;
        case "db.resampleIndex":
            proc.withParam( "'" + indexDefinition + "'" );
            break;
        case "db.createRelationshipType":
            proc.withParam( "'RelType'" );
            break;
        case "dbms.queryJmx":
            proc.withParam( "'*:*'" );
            break;
        case "db.awaitIndex":
            proc.withParam( "'" + indexDefinition + "'" );
            proc.withParam( 100 );
            break;
        case "db.createLabel":
            proc.withParam( "'OtherLabel'" );
            break;
        case "dbms.killQuery":
            proc.withParam( "'query-1234'" );
            break;
        case "dbms.killQueries":
            proc.withParam( "['query-1234']" );
            break;
        case "dbms.setTXMetaData":
            proc.withParam( "{realUser:'MyMan'}" );
            break;
        case "dbms.listActiveLocks":
            proc.withParam( "'query-1234'" );
            break;
        case "db.nodeManualIndexSeek":
            proc.withParam( "'" + legacyIndexName + "'" );
            proc.withParam( "'noKey'" );
            proc.withParam( "'noValue'" );
            break;
        case "db.nodeManualIndexSearch":
            proc.withParam( "'" + legacyIndexName + "'" );
            proc.withParam( "'noKey:foo*'" );
            break;
        case "db.relationshipManualIndexSearch":
            proc.withParam( "'" + relLegacyIndexName + "'" );
            proc.withParam( "'noKey:foo*'" );
            break;
        case "db.relationshipManualIndexSeek":
            proc.withParam( "'" + relLegacyIndexName + "'" );
            proc.withParam( "'noKey'" );
            proc.withParam( "'noValue'" );
            break;
        case "db.nodeAutoIndexSeek":
            proc.withParam( "'noKey'" );
            proc.withParam( "'noValue'" );
            break;
        case "db.nodeAutoIndexSearch":
            proc.withParam( "'noKey:foo*'" );
            break;
        case "db.relationshipAutoIndexSearch":
            proc.withParam( "'noKey:foo*'" );
            break;
        case "db.relationshipAutoIndexSeek":
            proc.withParam( "'noKey'" );
            proc.withParam( "'noValue'" );
            break;
        case "db.nodeManualIndexExists":
            proc.withParam( "'" + legacyIndexName + "'" );
            break;
        case "db.relationshipManualIndexExists":
            proc.withParam( "'" + legacyIndexName + "'" );
            break;
        case "db.nodeManualIndex":
            proc.withParam( "'" + legacyIndexName + "'" );
            break;
        case "db.relationshipManualIndex":
            proc.withParam( "'" + legacyIndexName + "'" );
            break;
        case "db.nodeManualIndexAdd":
            proc.withParam( "'" + legacyIndexName + "'" );
            proc.withParam( "n" );
            proc.withParam( "'prop'" );
            proc.withParam( "'value'");
            proc.withSetup( "OPTIONAL MATCH (n) WITH n LIMIT 1", "YIELD success RETURN success" );
            break;
        case "db.relationshipManualIndexAdd":
            proc.withParam( "'" + legacyIndexName + "'" );
            proc.withParam( "r" );
            proc.withParam( "'prop'" );
            proc.withParam( "'value'");
            proc.withSetup( "OPTIONAL MATCH ()-[r]->() WITH r LIMIT 1", "YIELD success RETURN success" );
            break;
        case "db.nodeManualIndexRemove":
            proc.withParam( "'" + legacyIndexName + "'" );
            proc.withParam( "n" );
            proc.withParam( "'prop'" );
            proc.withSetup( "OPTIONAL MATCH (n) WITH n LIMIT 1", "YIELD success RETURN success" );
            break;
        case "db.relationshipManualIndexRemove":
            proc.withParam( "'" + legacyIndexName + "'" );
            proc.withParam( "r" );
            proc.withParam( "'prop'" );
            proc.withSetup( "OPTIONAL MATCH ()-[r]->() WITH r LIMIT 1", "YIELD success RETURN success" );
            break;
        case "db.manualIndexDrop":
            proc.withParam( "'" + legacyIndexName + "'" );
            break;
        case "dbms.setConfigValue":
            proc.withParam( "'dbms.logs.query.enabled'" );
            proc.withParam( "'false'" );
            break;
        default:
        }
        return proc;
    }

    private void executeInOtherThread( String query ) throws ExecutionException, InterruptedException
    {
        Future<?> future = executor.submit( () ->
        {
            try ( Transaction tx = db.beginTx() )
            {
                exhaust( db.execute( query ) );
                tx.success();
            }
        } );
        future.get();
    }

}
