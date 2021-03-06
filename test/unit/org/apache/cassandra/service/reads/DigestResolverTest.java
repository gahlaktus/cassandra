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

package org.apache.cassandra.service.reads;

import org.junit.Assert;
import org.junit.Test;

import org.apache.cassandra.db.ConsistencyLevel;
import org.apache.cassandra.db.SimpleBuilders;
import org.apache.cassandra.db.SinglePartitionReadCommand;
import org.apache.cassandra.db.partitions.PartitionUpdate;
import org.apache.cassandra.db.rows.Row;
import org.apache.cassandra.locator.EndpointsForToken;
import org.apache.cassandra.locator.ReplicaLayout;
import org.apache.cassandra.schema.TableMetadata;
import org.apache.cassandra.service.reads.repair.NoopReadRepair;
import org.apache.cassandra.service.reads.repair.TestableReadRepair;

import static org.apache.cassandra.locator.ReplicaUtils.full;
import static org.apache.cassandra.locator.ReplicaUtils.trans;

public class DigestResolverTest extends AbstractReadResponseTest
{
    private static PartitionUpdate.Builder update(TableMetadata metadata, String key, Row... rows)
    {
        PartitionUpdate.Builder builder = new PartitionUpdate.Builder(metadata, dk(key), metadata.regularAndStaticColumns(), rows.length, false);
        for (Row row: rows)
        {
            builder.add(row);
        }
        return builder;
    }

    private static PartitionUpdate.Builder update(Row... rows)
    {
        return update(cfm, "key1", rows);
    }

    private static Row row(long timestamp, int clustering, int value)
    {
        SimpleBuilders.RowBuilder builder = new SimpleBuilders.RowBuilder(cfm, Integer.toString(clustering));
        builder.timestamp(timestamp).add("c1", Integer.toString(value));
        return builder.build();
    }

    @Test
    public void noRepairNeeded()
    {
        SinglePartitionReadCommand command = SinglePartitionReadCommand.fullPartitionRead(cfm, nowInSec, dk);
        EndpointsForToken targetReplicas = EndpointsForToken.of(dk.getToken(), full(EP1), full(EP2));
        TestableReadRepair readRepair = new TestableReadRepair(command, ConsistencyLevel.QUORUM);
        DigestResolver resolver = new DigestResolver(command, plan(ConsistencyLevel.QUORUM, targetReplicas), readRepair, 0);

        PartitionUpdate response = update(row(1000, 4, 4), row(1000, 5, 5)).build();

        Assert.assertFalse(resolver.isDataPresent());
        resolver.preprocess(response(command, EP2, iter(response), true));
        resolver.preprocess(response(command, EP1, iter(response), false));
        Assert.assertTrue(resolver.isDataPresent());
        Assert.assertTrue(resolver.responsesMatch());

        assertPartitionsEqual(filter(iter(response)), resolver.getData());
    }

    @Test
    public void digestMismatch()
    {
        SinglePartitionReadCommand command = SinglePartitionReadCommand.fullPartitionRead(cfm, nowInSec, dk);
        EndpointsForToken targetReplicas = EndpointsForToken.of(dk.getToken(), full(EP1), full(EP2));
        DigestResolver resolver = new DigestResolver(command, plan(ConsistencyLevel.QUORUM, targetReplicas), NoopReadRepair.instance,0);

        PartitionUpdate response1 = update(row(1000, 4, 4), row(1000, 5, 5)).build();
        PartitionUpdate response2 = update(row(2000, 4, 5)).build();

        Assert.assertFalse(resolver.isDataPresent());
        resolver.preprocess(response(command, EP2, iter(response1), true));
        resolver.preprocess(response(command, EP1, iter(response2), false));
        Assert.assertTrue(resolver.isDataPresent());
        Assert.assertFalse(resolver.responsesMatch());
        Assert.assertFalse(resolver.hasTransientResponse());
    }

    /**
     * A full response and a transient response, with the transient response being a subset of the full one
     */
    @Test
    public void agreeingTransient()
    {
        SinglePartitionReadCommand command = SinglePartitionReadCommand.fullPartitionRead(cfm, nowInSec, dk);
        EndpointsForToken targetReplicas = EndpointsForToken.of(dk.getToken(), full(EP1), trans(EP2));
        TestableReadRepair readRepair = new TestableReadRepair(command, ConsistencyLevel.QUORUM);
        DigestResolver resolver = new DigestResolver(command, plan(ConsistencyLevel.QUORUM, targetReplicas), readRepair, 0);

        PartitionUpdate response1 = update(row(1000, 4, 4), row(1000, 5, 5)).build();
        PartitionUpdate response2 = update(row(1000, 5, 5)).build();

        Assert.assertFalse(resolver.isDataPresent());
        resolver.preprocess(response(command, EP1, iter(response1), false));
        resolver.preprocess(response(command, EP2, iter(response2), false));
        Assert.assertTrue(resolver.isDataPresent());
        Assert.assertTrue(resolver.responsesMatch());
        Assert.assertTrue(resolver.hasTransientResponse());
        Assert.assertTrue(readRepair.sent.isEmpty());
    }

    /**
     * Transient responses shouldn't be classified as the single dataResponse
     */
    @Test
    public void transientResponse()
    {
        SinglePartitionReadCommand command = SinglePartitionReadCommand.fullPartitionRead(cfm, nowInSec, dk);
        EndpointsForToken targetReplicas = EndpointsForToken.of(dk.getToken(), full(EP1), trans(EP2));
        DigestResolver resolver = new DigestResolver(command, plan(ConsistencyLevel.QUORUM, targetReplicas), NoopReadRepair.instance, 0);

        PartitionUpdate response2 = update(row(1000, 5, 5)).build();
        Assert.assertFalse(resolver.isDataPresent());
        Assert.assertFalse(resolver.hasTransientResponse());
        resolver.preprocess(response(command, EP2, iter(response2), false));
        Assert.assertFalse(resolver.isDataPresent());
        Assert.assertTrue(resolver.hasTransientResponse());
    }

    private ReplicaLayout.ForToken plan(ConsistencyLevel consistencyLevel, EndpointsForToken replicas)
    {
        return new ReplicaLayout.ForToken(ks, consistencyLevel, replicas.token(), replicas, null, replicas);
    }
}
