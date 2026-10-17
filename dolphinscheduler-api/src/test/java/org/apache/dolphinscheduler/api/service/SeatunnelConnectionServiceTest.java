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

package org.apache.dolphinscheduler.api.service;

import static org.apache.dolphinscheduler.api.AssertionsHelper.assertThrowsServiceException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.dolphinscheduler.ai.connection.ConnectionBrowser;
import org.apache.dolphinscheduler.ai.connection.ConnectionSelection;
import org.apache.dolphinscheduler.ai.connection.ConnectionType;
import org.apache.dolphinscheduler.ai.connection.DescribedField;
import org.apache.dolphinscheduler.ai.connection.ObjectDescription;
import org.apache.dolphinscheduler.api.dto.seatunnel.ConnectionContextRequest;
import org.apache.dolphinscheduler.api.dto.seatunnel.ConnectionSelectionRequest;
import org.apache.dolphinscheduler.api.dto.seatunnel.SeatunnelConnectionRequest;
import org.apache.dolphinscheduler.api.dto.seatunnel.SeatunnelConnectionVO;
import org.apache.dolphinscheduler.api.enums.Status;
import org.apache.dolphinscheduler.api.service.seatunnel.SeatunnelConnectionService;
import org.apache.dolphinscheduler.api.service.seatunnel.SeatunnelConnectionServiceImpl;
import org.apache.dolphinscheduler.common.enums.UserType;
import org.apache.dolphinscheduler.common.utils.JSONUtils;
import org.apache.dolphinscheduler.dao.entity.SeatunnelConnection;
import org.apache.dolphinscheduler.dao.entity.TaskDefinition;
import org.apache.dolphinscheduler.dao.entity.User;
import org.apache.dolphinscheduler.dao.mapper.TaskDefinitionMapper;
import org.apache.dolphinscheduler.dao.repository.SeatunnelConnectionDao;
import org.apache.dolphinscheduler.dao.repository.SeatunnelConnectionUserDao;
import org.apache.dolphinscheduler.dao.repository.UserDao;
import org.apache.dolphinscheduler.dao.utils.SeatunnelConnectionSecretCodec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

public class SeatunnelConnectionServiceTest {

    private final SeatunnelConnectionDao connectionDao = mock(SeatunnelConnectionDao.class);
    private final SeatunnelConnectionUserDao connectionUserDao = mock(SeatunnelConnectionUserDao.class);
    private final UserDao userDao = mock(UserDao.class);
    private final TaskDefinitionMapper taskDefinitionMapper = mock(TaskDefinitionMapper.class);
    private final ConnectionBrowser browser = mock(ConnectionBrowser.class);
    private final User admin = new User();
    private final User user = new User();
    private SeatunnelConnectionServiceImpl service;

    @BeforeEach
    public void setUp() {
        when(browser.supports(any())).thenReturn(true);
        service = new SeatunnelConnectionServiceImpl(connectionDao, connectionUserDao, userDao, taskDefinitionMapper,
                Collections.singletonList(browser));
        admin.setId(1);
        admin.setUserName("admin");
        admin.setUserType(UserType.ADMIN_USER);
        user.setId(2);
        user.setUserName("etl");
        user.setUserType(UserType.GENERAL_USER);
        when(connectionDao.queryAll()).thenReturn(new ArrayList<>());
    }

    private static SeatunnelConnectionRequest pgRequest(String password) {
        SeatunnelConnectionRequest request = new SeatunnelConnectionRequest();
        request.setConnKey("pg_dw");
        request.setName("PG dw");
        request.setType("POSTGRESQL");
        Map<String, String> params = new HashMap<>();
        params.put("host", "pg");
        params.put("port", "5432");
        params.put("database", "dw");
        params.put("username", "etl");
        // Unknown fields and secrets in the params are dropped
        params.put("password", "in-the-wrong-place");
        params.put("unknown", "x");
        request.setParams(params);
        request.setSecrets(password == null ? Collections.emptyMap() : Collections.singletonMap("password", password));
        return request;
    }

    private SeatunnelConnection saved(String key, String type, String params, Map<String, String> secrets) {
        SeatunnelConnection connection = SeatunnelConnection.builder().id(10).code(100L).connKey(key).name(key)
                .type(type).params(params).secrets(SeatunnelConnectionSecretCodec.encode(secrets)).build();
        when(connectionDao.queryByKey(key)).thenReturn(Optional.of(connection));
        when(connectionDao.queryAll()).thenReturn(new ArrayList<>(Collections.singletonList(connection)));
        return connection;
    }

    @Test
    public void testCreateEncryptsAndHidesSecrets() {
        when(connectionDao.queryByKey("pg_dw")).thenReturn(Optional.empty());
        SeatunnelConnectionVO vo = service.create(admin, pgRequest("S3cret!"));

        ArgumentCaptor<SeatunnelConnection> captor = ArgumentCaptor.forClass(SeatunnelConnection.class);
        verify(connectionDao).insert(captor.capture());
        SeatunnelConnection inserted = captor.getValue();
        Assertions.assertFalse(inserted.getSecrets().contains("S3cret!"));
        Assertions.assertEquals("S3cret!",
                SeatunnelConnectionSecretCodec.decode(inserted.getSecrets()).get("password"));
        Assertions.assertFalse(inserted.getParams().contains("in-the-wrong-place"), inserted.getParams());
        Assertions.assertFalse(inserted.getParams().contains("unknown"), inserted.getParams());
        // The VO never has the secret values
        Assertions.assertEquals(Collections.singletonList("password"), vo.getSecretsSet());
        Assertions.assertFalse(JSONUtils.toJsonString(vo).contains("S3cret!"));
    }

    @Test
    public void testCreateValidation() {
        assertThrowsServiceException(Status.USER_NO_OPERATION_PERM, () -> service.create(user, pgRequest("p")));
        SeatunnelConnectionRequest badKey = pgRequest("p");
        badKey.setConnKey("Bad-Key");
        assertThrowsServiceException(Status.SEATUNNEL_CONNECTION_KEY_INVALID, () -> service.create(admin, badKey));
        when(connectionDao.queryByKey("pg_dw")).thenReturn(Optional.empty());
        // The password is required
        assertThrowsServiceException(Status.SEATUNNEL_CONNECTION_PARAM_INVALID,
                () -> service.create(admin, pgRequest(null)));
        SeatunnelConnectionRequest kafka = new SeatunnelConnectionRequest();
        kafka.setConnKey("kafka_prod");
        kafka.setName("Kafka");
        kafka.setType("KAFKA");
        kafka.setParams(new HashMap<>(Collections.singletonMap("bootstrap_servers", "b:9092")));
        kafka.getParams().put("security_protocol", "SASL_SSL");
        kafka.getParams().put("sasl_username", "u");
        kafka.setSecrets(Collections.singletonMap("sasl_password", "has\"quote"));
        when(connectionDao.queryByKey("kafka_prod")).thenReturn(Optional.empty());
        assertThrowsServiceException(Status.SEATUNNEL_CONNECTION_PARAM_INVALID, () -> service.create(admin, kafka));
    }

    @Test
    public void testUpdateKeepsEmptySecrets() {
        SeatunnelConnection connection =
                saved("pg_dw", "POSTGRESQL", "{}", Collections.singletonMap("password", "old"));
        service.update(admin, 100L, pgRequest(""));
        Assertions.assertEquals("old", SeatunnelConnectionSecretCodec.decode(connection.getSecrets()).get("password"));
        service.update(admin, 100L, pgRequest("******"));
        Assertions.assertEquals("old", SeatunnelConnectionSecretCodec.decode(connection.getSecrets()).get("password"));
        service.update(admin, 100L, pgRequest("new"));
        Assertions.assertEquals("new", SeatunnelConnectionSecretCodec.decode(connection.getSecrets()).get("password"));
    }

    @Test
    public void testDeleteInUse() {
        saved("pg_dw", "POSTGRESQL", "{}", Collections.singletonMap("password", "p"));
        TaskDefinition task = new TaskDefinition();
        task.setName("sync_orders");
        when(taskDefinitionMapper.selectList(any())).thenReturn(Collections.singletonList(task));
        assertThrowsServiceException(Status.SEATUNNEL_CONNECTION_IN_USE, () -> service.delete(admin, 100L));
        verify(connectionDao, never()).deleteById(any());

        when(taskDefinitionMapper.selectList(any())).thenReturn(Collections.emptyList());
        service.delete(admin, 100L);
        verify(connectionUserDao).deleteByConnectionId(10);
        verify(connectionDao).deleteById(10);
    }

    @Test
    public void testCheckReferences() {
        saved("pg_dw", "POSTGRESQL", "{}", Collections.singletonMap("password", "p"));
        TaskDefinition task = new TaskDefinition();
        task.setCode(5L);
        task.setTaskType("SEATUNNEL");
        task.setTaskParams("{\"rawScript\":\"password = \\\"${stconn.pg_dw.password}\\\"\"}");
        when(taskDefinitionMapper.queryByCodeList(any())).thenReturn(Collections.emptyList());
        when(connectionUserDao.queryConnectionIdsByUserId(2)).thenReturn(Collections.emptyList());
        // A new reference to a connection the user may not use
        assertThrowsServiceException(Status.SEATUNNEL_CONNECTION_NOT_AUTHORIZED,
                () -> service.checkReferences(user, Collections.singletonList(task)));
        service.checkReferences(admin, Collections.singletonList(task));

        // The reference was already saved, e.g. another user edits the workflow
        TaskDefinition saved = new TaskDefinition();
        saved.setCode(5L);
        saved.setTaskParams(task.getTaskParams());
        when(taskDefinitionMapper.queryByCodeList(any())).thenReturn(Collections.singletonList(saved));
        service.checkReferences(user, Collections.singletonList(task));

        // A missing connection
        task.setTaskParams("{\"rawScript\":\"${stconn.gone.password}\"}");
        when(connectionDao.queryByKey("gone")).thenReturn(Optional.empty());
        assertThrowsServiceException(Status.SEATUNNEL_CONNECTION_NOT_FOUND,
                () -> service.checkReferences(admin, Collections.singletonList(task)));
    }

    @Test
    public void testResolveMasksSecretsAndDescribes() throws Exception {
        saved("api", "HTTP", "{\"base_url\":\"https://api.example.com\",\"auth_type\":\"BEARER\"}",
                Collections.singletonMap("token", "tk-123"));
        when(connectionUserDao.queryConnectionIdsByUserId(2)).thenReturn(Collections.singletonList(10));
        when(browser.describe(any(), eq("/orders"))).thenReturn(new ObjectDescription("/orders", "json",
                Collections.singletonList(new DescribedField("id", "bigint", false, false, "1", "")), "$.data", "",
                Collections.singletonList("{\"raw\":true}")));
        ConnectionSelectionRequest source = new ConnectionSelectionRequest();
        source.setConnectionKey("api");
        source.setObjects(Collections.singletonList("/orders"));
        ConnectionContextRequest context = new ConnectionContextRequest();
        context.setSource(source);

        SeatunnelConnectionService.ResolvedContext resolved = service.resolve(user, context, null);
        ConnectionSelection selection = resolved.getSelections().get(0);
        Assertions.assertEquals(ConnectionType.Role.SOURCE, selection.role());
        Assertions.assertEquals("******", selection.connection().secret("token"));
        String prompt = selection.toPrompt();
        Assertions.assertFalse(prompt.contains("tk-123"), prompt);
        Assertions.assertTrue(prompt.contains("Bearer ${stconn.api.token}"), prompt);
        Assertions.assertTrue(prompt.contains("- id: bigint"), prompt);
        // Only the HTTP tool gets the real secret
        Assertions.assertEquals("tk-123", resolved.getReferences().get("${stconn.api.token}"));

        // S3 can not be a source, and another user may not use the connection
        saved("lake", "S3", "{}", Collections.emptyMap());
        source.setConnectionKey("lake");
        assertThrowsServiceException(Status.SEATUNNEL_CONNECTION_PARAM_INVALID,
                () -> service.resolve(admin, context, null));
        when(connectionUserDao.queryConnectionIdsByUserId(2)).thenReturn(Arrays.asList(1, 2));
        assertThrowsServiceException(Status.SEATUNNEL_CONNECTION_NOT_AUTHORIZED,
                () -> service.resolve(user, context, null));
    }

    @Test
    public void testKafkaConsumerGroupOfTheTask() {
        saved("kafka_dev", "KAFKA", "{\"bootstrap_servers\":\"b:9093\",\"security_protocol\":\"PLAINTEXT\","
                + "\"consumer_group_prefix\":\"team-a\"}", Collections.emptyMap());
        ConnectionSelectionRequest source = new ConnectionSelectionRequest();
        source.setConnectionKey("kafka_dev");
        source.setObjects(Collections.singletonList("orders"));
        source.setDescriptions(
                Collections.singletonList(new org.apache.dolphinscheduler.api.dto.seatunnel.DescribedObject()));
        ConnectionContextRequest context = new ConnectionContextRequest();
        context.setSource(source);
        Assertions.assertEquals("team-a-ds-42",
                service.resolve(admin, context, 42L).getSelections().get(0).consumerGroup());
        Assertions.assertNull(service.resolve(admin, context, null).getSelections().get(0).consumerGroup());
        // The sink does not consume
        ConnectionContextRequest sink = new ConnectionContextRequest();
        sink.setSink(source);
        Assertions.assertNull(service.resolve(admin, sink, 42L).getSelections().get(0).consumerGroup());
    }

    @Test
    public void testToolBackend() throws Exception {
        saved("pg_dw", "POSTGRESQL", "{\"host\":\"pg\"}", Collections.singletonMap("password", "S3cret!"));
        when(connectionUserDao.queryConnectionIdsByUserId(2)).thenReturn(Collections.emptyList());
        when(connectionDao.queryAuthorized(2)).thenReturn(Collections.emptyList());
        org.apache.dolphinscheduler.ai.connection.ConnectionToolBackend adminBackend = service.toolBackend(admin);
        // The secrets are masked for the tools
        Assertions.assertEquals("******", adminBackend.list().get(0).secret("password"));
        Assertions.assertEquals("******", adminBackend.registered("pg_dw").get().secret("password"));
        // Another user may not use it
        org.apache.dolphinscheduler.ai.connection.ConnectionToolBackend userBackend = service.toolBackend(user);
        Assertions.assertTrue(userBackend.list().isEmpty());
        assertThrowsServiceException(Status.SEATUNNEL_CONNECTION_NOT_AUTHORIZED, () -> userBackend.registered("pg_dw"));
        assertThrowsServiceException(Status.SEATUNNEL_CONNECTION_NOT_AUTHORIZED,
                () -> userBackend.describe("pg_dw", "public.t"));
        // An inline connection goes to the browser of its type
        org.apache.dolphinscheduler.ai.connection.SeatunnelConnection inline =
                new org.apache.dolphinscheduler.ai.connection.SeatunnelConnection("inline", "", ConnectionType.MYSQL,
                        Collections.singletonMap("host", "10.0.0.5"), Collections.singletonMap("password", "p"));
        when(browser.describe(eq(inline), eq("shop.orders"))).thenReturn(new ObjectDescription("shop.orders",
                "table", Collections.emptyList(), null, "", Collections.emptyList()));
        Assertions.assertEquals("shop.orders", userBackend.describe(inline, "shop.orders").object());
    }

    @Test
    public void testBrowseErrorsAreWrapped() throws Exception {
        saved("pg_dw", "POSTGRESQL", "{}", Collections.singletonMap("password", "p"));
        when(browser.list(any(), any())).thenThrow(new IllegalStateException("Connection refused"));
        assertThrowsServiceException(Status.SEATUNNEL_CONNECTION_FAILED, () -> service.objects(admin, "pg_dw", ""));
        List<String> keys = new ArrayList<>(SeatunnelConnectionServiceImpl.referencedKeys(
                "a ${stconn.pg_dw.password} b ${stconn.api.token} c ${stconn.pg_dw.password} ${not.a.ref}"));
        Assertions.assertEquals(Arrays.asList("pg_dw", "api"), keys);
    }
}
