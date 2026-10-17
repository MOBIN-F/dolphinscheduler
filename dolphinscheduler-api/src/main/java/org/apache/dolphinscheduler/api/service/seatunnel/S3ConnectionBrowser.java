/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.dolphinscheduler.api.service.seatunnel;

import org.apache.dolphinscheduler.ai.connection.BrowseNode;
import org.apache.dolphinscheduler.ai.connection.ConnectionBrowser;
import org.apache.dolphinscheduler.ai.connection.ConnectionType;
import org.apache.dolphinscheduler.ai.connection.ObjectDescription;
import org.apache.dolphinscheduler.ai.connection.SeatunnelConnection;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.ListObjectsV2Request;
import com.amazonaws.services.s3.model.ListObjectsV2Result;

/**
 * Browses the directories of the bucket of an S3 connection, the target path of an S3 sink is selected from them.
 * Uses the AWS SDK v1 which the api server already has.
 */
@Component
public class S3ConnectionBrowser implements ConnectionBrowser {

    static final int MAX_KEYS = 1000;
    private static final int TIMEOUT_MILLIS = 10_000;

    @Override
    public boolean supports(ConnectionType type) {
        return type == ConnectionType.S3;
    }

    @Override
    public void test(SeatunnelConnection connection) {
        AmazonS3 s3 = client(connection);
        try {
            s3.listObjectsV2(new ListObjectsV2Request().withBucketName(connection.param("bucket")).withMaxKeys(1));
        } finally {
            s3.shutdown();
        }
    }

    /**
     * @param parent a directory such as {@code warehouse/orders/}, empty for the root of the bucket
     * @return the sub directories, they can be selected and browsed further
     */
    @Override
    public List<BrowseNode> list(SeatunnelConnection connection, String parent) {
        String prefix = parent == null || parent.isEmpty() || parent.equals("/") ? ""
                : parent.replaceAll("^/+", "").replaceAll("/*$", "/");
        AmazonS3 s3 = client(connection);
        try {
            ListObjectsV2Result result = s3.listObjectsV2(new ListObjectsV2Request()
                    .withBucketName(connection.param("bucket")).withPrefix(prefix).withDelimiter("/")
                    .withMaxKeys(MAX_KEYS));
            List<BrowseNode> nodes = new ArrayList<>();
            for (String directory : result.getCommonPrefixes()) {
                String name = directory.substring(prefix.length()).replaceAll("/$", "");
                nodes.add(new BrowseNode(name, "/" + directory.replaceAll("/$", ""), false));
            }
            return nodes;
        } finally {
            s3.shutdown();
        }
    }

    @Override
    public ObjectDescription describe(SeatunnelConnection connection, String path) {
        return new ObjectDescription(path, "path", List.of(), null, "target directory of the sink", List.of());
    }

    private static AmazonS3 client(SeatunnelConnection connection) {
        AmazonS3ClientBuilder builder = AmazonS3ClientBuilder.standard()
                .withClientConfiguration(new ClientConfiguration().withConnectionTimeout(TIMEOUT_MILLIS)
                        .withSocketTimeout(TIMEOUT_MILLIS).withMaxErrorRetry(1));
        String region = connection.param("region");
        String endpoint = connection.param("endpoint");
        if (endpoint.isEmpty()) {
            builder.withRegion(region);
        } else {
            builder.withEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration(endpoint, region))
                    .withPathStyleAccessEnabled(true);
        }
        if (connection.param("auth_type").equals("ACCESS_KEY")) {
            builder.withCredentials(new AWSStaticCredentialsProvider(
                    new BasicAWSCredentials(connection.param("access_key"), connection.secret("secret_key"))));
        } else {
            builder.withCredentials(DefaultAWSCredentialsProviderChain.getInstance());
        }
        return builder.build();
    }
}
