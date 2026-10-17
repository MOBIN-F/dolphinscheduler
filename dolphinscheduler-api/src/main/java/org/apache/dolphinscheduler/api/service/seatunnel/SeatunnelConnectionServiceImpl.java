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
import org.apache.dolphinscheduler.ai.connection.ConnectionField;
import org.apache.dolphinscheduler.ai.connection.ConnectionSelection;
import org.apache.dolphinscheduler.ai.connection.ConnectionToolBackend;
import org.apache.dolphinscheduler.ai.connection.ConnectionType;
import org.apache.dolphinscheduler.ai.connection.DescribedField;
import org.apache.dolphinscheduler.ai.connection.KafkaConsumerGroups;
import org.apache.dolphinscheduler.ai.connection.ObjectDescription;
import org.apache.dolphinscheduler.api.dto.seatunnel.ConnectionContextRequest;
import org.apache.dolphinscheduler.api.dto.seatunnel.ConnectionSelectionRequest;
import org.apache.dolphinscheduler.api.dto.seatunnel.DescribedObject;
import org.apache.dolphinscheduler.api.dto.seatunnel.SeatunnelConnectionRequest;
import org.apache.dolphinscheduler.api.dto.seatunnel.SeatunnelConnectionVO;
import org.apache.dolphinscheduler.api.enums.Status;
import org.apache.dolphinscheduler.api.exceptions.ServiceException;
import org.apache.dolphinscheduler.api.service.impl.BaseServiceImpl;
import org.apache.dolphinscheduler.common.constants.Constants;
import org.apache.dolphinscheduler.common.utils.CodeGenerateUtils;
import org.apache.dolphinscheduler.common.utils.JSONUtils;
import org.apache.dolphinscheduler.dao.entity.SeatunnelConnection;
import org.apache.dolphinscheduler.dao.entity.SeatunnelConnectionUser;
import org.apache.dolphinscheduler.dao.entity.TaskDefinition;
import org.apache.dolphinscheduler.dao.entity.User;
import org.apache.dolphinscheduler.dao.mapper.TaskDefinitionMapper;
import org.apache.dolphinscheduler.dao.repository.SeatunnelConnectionDao;
import org.apache.dolphinscheduler.dao.repository.SeatunnelConnectionUserDao;
import org.apache.dolphinscheduler.dao.repository.UserDao;
import org.apache.dolphinscheduler.dao.utils.SeatunnelConnectionSecretCodec;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;

@Slf4j
@Service
public class SeatunnelConnectionServiceImpl extends BaseServiceImpl implements SeatunnelConnectionService {

    static final Pattern KEY = Pattern.compile("[a-z][a-z0-9_]{1,63}");

    /**
     * {@code ${stconn.<key>.<field>}} in a script.
     */
    public static final Pattern REFERENCE = Pattern.compile("\\$\\{stconn\\.([a-z][a-z0-9_]{1,63})\\.([a-z_]+)}");

    private static final String SEATUNNEL = "SEATUNNEL";
    private static final String MASK = "******";
    private static final int MAX_ERROR_LENGTH = 300;

    private final SeatunnelConnectionDao connectionDao;
    private final SeatunnelConnectionUserDao connectionUserDao;
    private final UserDao userDao;
    private final TaskDefinitionMapper taskDefinitionMapper;
    private final List<ConnectionBrowser> browsers;

    public SeatunnelConnectionServiceImpl(SeatunnelConnectionDao connectionDao,
                                          SeatunnelConnectionUserDao connectionUserDao, UserDao userDao,
                                          TaskDefinitionMapper taskDefinitionMapper, List<ConnectionBrowser> browsers) {
        this.connectionDao = connectionDao;
        this.connectionUserDao = connectionUserDao;
        this.userDao = userDao;
        this.taskDefinitionMapper = taskDefinitionMapper;
        this.browsers = browsers;
    }

    @Override
    public List<Map<String, Object>> types() {
        List<Map<String, Object>> types = new ArrayList<>();
        for (ConnectionType type : ConnectionType.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", type.name());
            item.put("label", type.label());
            item.put("roles", type.roles());
            item.put("objectKind", type.objectKind());
            item.put("fields", type.fields());
            types.add(item);
        }
        return types;
    }

    @Override
    public List<SeatunnelConnectionVO> list(User loginUser) {
        List<SeatunnelConnection> connections = isAdmin(loginUser) ? connectionDao.queryAll()
                : connectionDao.queryAuthorized(loginUser.getId());
        return toVOs(connections);
    }

    @Override
    @Transactional
    public SeatunnelConnectionVO create(User loginUser, SeatunnelConnectionRequest request) {
        checkAdmin(loginUser);
        String key = request.getConnKey() == null ? "" : request.getConnKey().trim();
        if (!KEY.matcher(key).matches()) {
            throw new ServiceException(Status.SEATUNNEL_CONNECTION_KEY_INVALID, key);
        }
        ConnectionType type = ConnectionType.of(request.getType())
                .orElseThrow(() -> invalid("unknown type " + request.getType()));
        if (connectionDao.queryByKey(key).isPresent()) {
            throw new ServiceException(Status.SEATUNNEL_CONNECTION_EXISTS, key);
        }
        String name = checkName(request.getName(), null);
        Map<String, String> params = params(type, request.getParams());
        Map<String, String> secrets = secrets(type, request.getSecrets(), Collections.emptyMap());
        checkRequired(type, params, secrets);
        Date now = new Date();
        SeatunnelConnection connection = SeatunnelConnection.builder()
                .code(genCode())
                .connKey(key)
                .name(name)
                .type(type.name())
                .description(request.getDescription())
                .params(JSONUtils.toJsonString(params))
                .secrets(SeatunnelConnectionSecretCodec.encode(secrets))
                .createUser(loginUser.getId())
                .updateUser(loginUser.getId())
                .createTime(now)
                .updateTime(now)
                .build();
        try {
            connectionDao.insert(connection);
        } catch (DuplicateKeyException e) {
            throw new ServiceException(Status.SEATUNNEL_CONNECTION_EXISTS, key + " / " + name);
        }
        log.info("SeaTunnel connection {} created by {}", key, loginUser.getUserName());
        return toVO(connection);
    }

    @Override
    @Transactional
    public SeatunnelConnectionVO update(User loginUser, long code, SeatunnelConnectionRequest request) {
        checkAdmin(loginUser);
        SeatunnelConnection connection = queryByCode(code);
        // The key and the type are immutable, the scripts reference the key
        ConnectionType type = type(connection);
        connection.setName(checkName(request.getName(), connection.getId()));
        connection.setDescription(request.getDescription());
        Map<String, String> params = params(type, request.getParams());
        Map<String, String> secrets = secrets(type, request.getSecrets(),
                SeatunnelConnectionSecretCodec.decode(connection.getSecrets()));
        checkRequired(type, params, secrets);
        connection.setParams(JSONUtils.toJsonString(params));
        connection.setSecrets(SeatunnelConnectionSecretCodec.encode(secrets));
        connection.setUpdateUser(loginUser.getId());
        connection.setUpdateTime(new Date());
        connectionDao.updateById(connection);
        log.info("SeaTunnel connection {} updated by {}", connection.getConnKey(), loginUser.getUserName());
        return toVO(connection);
    }

    @Override
    @Transactional
    public void delete(User loginUser, long code) {
        checkAdmin(loginUser);
        SeatunnelConnection connection = queryByCode(code);
        List<String> tasks = taskDefinitionMapper.selectList(new QueryWrapper<TaskDefinition>().lambda()
                .eq(TaskDefinition::getTaskType, SEATUNNEL)
                .like(TaskDefinition::getTaskParams, "${stconn." + connection.getConnKey() + "."))
                .stream().map(TaskDefinition::getName).limit(10).collect(Collectors.toList());
        if (!tasks.isEmpty()) {
            throw new ServiceException(Status.SEATUNNEL_CONNECTION_IN_USE, connection.getConnKey(),
                    String.join(", ", tasks));
        }
        connectionUserDao.deleteByConnectionId(connection.getId());
        connectionDao.deleteById(connection.getId());
        log.info("SeaTunnel connection {} deleted by {}", connection.getConnKey(), loginUser.getUserName());
    }

    @Override
    public void test(User loginUser, SeatunnelConnectionRequest request, Long code) {
        checkAdmin(loginUser);
        ConnectionType type = ConnectionType.of(request.getType())
                .orElseThrow(() -> invalid("unknown type " + request.getType()));
        Map<String, String> saved = code == null ? Collections.emptyMap()
                : SeatunnelConnectionSecretCodec.decode(queryByCode(code).getSecrets());
        Map<String, String> params = params(type, request.getParams());
        Map<String, String> secrets = secrets(type, request.getSecrets(), saved);
        checkRequired(type, params, secrets);
        org.apache.dolphinscheduler.ai.connection.SeatunnelConnection connection =
                new org.apache.dolphinscheduler.ai.connection.SeatunnelConnection(
                        request.getConnKey() == null ? "test" : request.getConnKey(), request.getName(), type,
                        params, secrets);
        browse(connection, browser -> {
            browser.test(connection);
            return null;
        });
    }

    @Override
    public List<SeatunnelConnectionVO> authorized(User loginUser, int userId) {
        checkAdmin(loginUser);
        return toVOs(connectionDao.queryAuthorized(userId));
    }

    @Override
    public List<SeatunnelConnectionVO> unauthorized(User loginUser, int userId) {
        checkAdmin(loginUser);
        Set<Integer> authorized = new HashSet<>(connectionUserDao.queryConnectionIdsByUserId(userId));
        return toVOs(connectionDao.queryAll().stream()
                .filter(connection -> !authorized.contains(connection.getId()))
                .collect(Collectors.toList()));
    }

    @Override
    @Transactional
    public void grant(User loginUser, int userId, String connectionIds) {
        checkAdmin(loginUser);
        if (userDao.queryById(userId) == null) {
            throw new ServiceException(Status.USER_NOT_EXIST, userId);
        }
        connectionUserDao.deleteByUserId(userId);
        if (connectionIds == null || connectionIds.trim().isEmpty()) {
            return;
        }
        Date now = new Date();
        for (String id : new LinkedHashSet<>(java.util.Arrays.asList(connectionIds.split(",")))) {
            SeatunnelConnectionUser relation = new SeatunnelConnectionUser();
            relation.setUserId(userId);
            relation.setConnectionId(Integer.parseInt(id.trim()));
            relation.setPerm(Constants.AUTHORIZE_WRITABLE_PERM);
            relation.setCreateTime(now);
            relation.setUpdateTime(now);
            connectionUserDao.insert(relation);
        }
        log.info("SeaTunnel connections {} granted to user {} by {}", connectionIds, userId,
                loginUser.getUserName());
    }

    @Override
    public List<BrowseNode> objects(User loginUser, String connKey, String parent) {
        org.apache.dolphinscheduler.ai.connection.SeatunnelConnection connection =
                toConnection(usable(loginUser, connKey));
        return browse(connection, browser -> browser.list(connection, parent == null ? "" : parent));
    }

    @Override
    public ObjectDescription describe(User loginUser, String connKey, String object) {
        org.apache.dolphinscheduler.ai.connection.SeatunnelConnection connection =
                toConnection(usable(loginUser, connKey));
        return browse(connection, browser -> browser.describe(connection, object));
    }

    @Override
    public ResolvedContext resolve(User loginUser, ConnectionContextRequest context, Long taskCode) {
        List<ConnectionSelection> selections = new ArrayList<>();
        Map<String, String> references = new LinkedHashMap<>();
        if (context != null) {
            resolve(loginUser, ConnectionType.Role.SOURCE, context.getSource(), taskCode, selections, references);
            resolve(loginUser, ConnectionType.Role.SINK, context.getSink(), taskCode, selections, references);
        }
        return new ResolvedContext(selections, references);
    }

    private void resolve(User loginUser, ConnectionType.Role role, ConnectionSelectionRequest request,
                         Long taskCode, List<ConnectionSelection> selections, Map<String, String> references) {
        if (request == null || request.getConnectionKey() == null || request.getConnectionKey().isEmpty()) {
            return;
        }
        org.apache.dolphinscheduler.ai.connection.SeatunnelConnection connection =
                toConnection(usable(loginUser, request.getConnectionKey()));
        if (!connection.type().roles().contains(role)) {
            throw invalid(connection.type().label() + " can not be the " + role.name().toLowerCase());
        }
        List<String> objects = request.getObjects() == null ? Collections.emptyList() : request.getObjects();
        List<ObjectDescription> descriptions = new ArrayList<>();
        if (request.getDescriptions() != null && !request.getDescriptions().isEmpty()) {
            request.getDescriptions().forEach(description -> descriptions.add(toDescription(description)));
        } else if (connection.type() != ConnectionType.S3) {
            // E.g. called by another system without the descriptions
            for (String object : objects) {
                descriptions.add(browse(connection, browser -> browser.describe(connection, object)));
            }
        }
        if (connection.type() == ConnectionType.HTTP) {
            for (ConnectionField field : connection.type().fields()) {
                if (field.secret() && connection.hasSecret(field.key())) {
                    references.put(connection.placeholder(field.key()), connection.secret(field.key()));
                }
            }
            references.put(connection.placeholder("basic_auth"), Base64.getEncoder().encodeToString(
                    (connection.param("basic_username") + ":" + connection.secret("basic_password"))
                            .getBytes(StandardCharsets.UTF_8)));
        }
        // The job of the task consumes with its own group: the authorized prefix and the task code
        String prefix = KafkaConsumerGroups.prefix(connection);
        String consumerGroup = role == ConnectionType.Role.SOURCE && connection.type() == ConnectionType.KAFKA
                && !prefix.isEmpty() && taskCode != null && taskCode > 0
                        ? KafkaConsumerGroups.ofTask(prefix, taskCode)
                        : null;
        selections.add(new ConnectionSelection(role, masked(connection), objects, request.getNewTable(),
                request.getPath(), request.getFileFormat(), descriptions, consumerGroup));
    }

    @Override
    public ConnectionToolBackend toolBackend(User loginUser) {
        return new ConnectionToolBackend() {

            @Override
            public List<org.apache.dolphinscheduler.ai.connection.SeatunnelConnection> list() {
                List<SeatunnelConnection> connections = isAdmin(loginUser) ? connectionDao.queryAll()
                        : connectionDao.queryAuthorized(loginUser.getId());
                return connections.stream().map(connection -> masked(toConnection(connection)))
                        .collect(Collectors.toList());
            }

            @Override
            public Optional<org.apache.dolphinscheduler.ai.connection.SeatunnelConnection> registered(String key) {
                return Optional.of(masked(toConnection(usable(loginUser, key))));
            }

            @Override
            public List<BrowseNode> browse(String key, String parent) {
                return objects(loginUser, key, parent);
            }

            @Override
            public ObjectDescription describe(String key, String object) {
                return SeatunnelConnectionServiceImpl.this.describe(loginUser, key, object);
            }

            @Override
            public List<BrowseNode> browse(org.apache.dolphinscheduler.ai.connection.SeatunnelConnection connection,
                                           String parent) {
                return SeatunnelConnectionServiceImpl.this.browse(connection,
                        browser -> browser.list(connection, parent == null ? "" : parent));
            }

            @Override
            public ObjectDescription describe(org.apache.dolphinscheduler.ai.connection.SeatunnelConnection connection,
                                              String object) {
                return SeatunnelConnectionServiceImpl.this.browse(connection,
                        browser -> browser.describe(connection, object));
            }
        };
    }

    @Override
    public void checkReferences(User loginUser, List<? extends TaskDefinition> taskDefinitions) {
        if (taskDefinitions == null || taskDefinitions.isEmpty()) {
            return;
        }
        List<Long> codes = taskDefinitions.stream()
                .filter(task -> SEATUNNEL.equals(task.getTaskType()))
                .map(TaskDefinition::getCode).collect(Collectors.toList());
        if (codes.isEmpty()) {
            return;
        }
        Map<Long, Set<String>> savedKeys = new HashMap<>();
        taskDefinitionMapper.queryByCodeList(codes)
                .forEach(task -> savedKeys.put(task.getCode(), referencedKeys(task.getTaskParams())));
        Set<String> newKeys = new LinkedHashSet<>();
        for (TaskDefinition task : taskDefinitions) {
            if (!SEATUNNEL.equals(task.getTaskType())) {
                continue;
            }
            Set<String> keys = referencedKeys(task.getTaskParams());
            keys.removeAll(savedKeys.getOrDefault(task.getCode(), Collections.emptySet()));
            newKeys.addAll(keys);
        }
        for (String key : newKeys) {
            usable(loginUser, key);
        }
    }

    /**
     * @return the keys of the connections referenced by the text, e.g. the task params of a SeaTunnel task
     */
    public static Set<String> referencedKeys(String text) {
        Set<String> keys = new LinkedHashSet<>();
        if (text == null) {
            return keys;
        }
        Matcher matcher = REFERENCE.matcher(text);
        while (matcher.find()) {
            keys.add(matcher.group(1));
        }
        return keys;
    }

    private SeatunnelConnection usable(User loginUser, String connKey) {
        SeatunnelConnection connection = connectionDao.queryByKey(connKey)
                .orElseThrow(() -> new ServiceException(Status.SEATUNNEL_CONNECTION_NOT_FOUND, connKey));
        if (!isAdmin(loginUser)
                && !connectionUserDao.queryConnectionIdsByUserId(loginUser.getId()).contains(connection.getId())) {
            throw new ServiceException(Status.SEATUNNEL_CONNECTION_NOT_AUTHORIZED, connKey);
        }
        return connection;
    }

    private <T> T browse(org.apache.dolphinscheduler.ai.connection.SeatunnelConnection connection,
                         BrowseFunction<T> function) {
        ConnectionBrowser browser = browsers.stream().filter(item -> item.supports(connection.type())).findFirst()
                .orElseThrow(() -> new ServiceException(Status.SEATUNNEL_CONNECTION_FAILED,
                        connection.type().label() + " needs the AI assistant to be enabled"));
        try {
            return function.apply(browser);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Failed to access the SeaTunnel connection {}", connection.key(), e);
            throw new ServiceException(Status.SEATUNNEL_CONNECTION_FAILED, errorMessage(e));
        }
    }

    @FunctionalInterface
    private interface BrowseFunction<T> {

        T apply(ConnectionBrowser browser) throws Exception;
    }

    private static String errorMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        return message.length() > MAX_ERROR_LENGTH ? message.substring(0, MAX_ERROR_LENGTH) + "..." : message;
    }

    private Map<String, String> params(ConnectionType type, Map<String, String> values) {
        Map<String, String> params = new LinkedHashMap<>();
        for (ConnectionField field : type.fields()) {
            if (!field.secret() && values != null && values.get(field.key()) != null) {
                params.put(field.key(), values.get(field.key()).trim());
            }
        }
        return params;
    }

    /**
     * @param saved the saved secrets, kept when the new value is empty
     */
    private Map<String, String> secrets(ConnectionType type, Map<String, String> values, Map<String, String> saved) {
        Map<String, String> secrets = new LinkedHashMap<>();
        for (ConnectionField field : type.fields()) {
            if (!field.secret()) {
                continue;
            }
            String value = values == null ? null : values.get(field.key());
            if (value == null || value.isEmpty() || value.equals(MASK)) {
                value = saved.get(field.key());
            }
            if (value != null && !value.isEmpty()) {
                secrets.put(field.key(), value);
            }
        }
        return secrets;
    }

    private void checkRequired(ConnectionType type, Map<String, String> params, Map<String, String> secrets) {
        Map<String, String> values = new HashMap<>(params);
        for (ConnectionField field : type.fields()) {
            values.putIfAbsent(field.key(), field.defaultValue());
        }
        for (ConnectionField field : type.fields()) {
            if (!field.required() || !field.isActive(values)) {
                continue;
            }
            String value = field.secret() ? secrets.get(field.key()) : values.get(field.key());
            if (value == null || value.trim().isEmpty()) {
                throw invalid(field.label() + " is required");
            }
            if (field.input().equals("number") && !value.trim().matches("\\d{1,5}")) {
                throw invalid(field.label() + " must be a number");
            }
            if (!field.options().isEmpty() && !field.options().contains(value)) {
                throw invalid(field.label() + " must be one of " + field.options());
            }
        }
        String saslPassword = secrets.getOrDefault("sasl_password", "");
        if (saslPassword.contains("\"") || saslPassword.contains("\\")) {
            throw invalid("the SASL password must not contain \" or \\");
        }
        String baseUrl = values.getOrDefault("base_url", "");
        if (type == ConnectionType.HTTP && !baseUrl.matches("(?i)https?://[^/\\s]+.*")) {
            throw invalid("the base URL must start with http:// or https://");
        }
    }

    private String checkName(String name, Integer id) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.length() > 128) {
            throw invalid("the name is required, at most 128 characters");
        }
        boolean exists = connectionDao.queryAll().stream()
                .anyMatch(connection -> connection.getName().equals(trimmed) && !connection.getId().equals(id));
        if (exists) {
            throw new ServiceException(Status.SEATUNNEL_CONNECTION_EXISTS, trimmed);
        }
        return trimmed;
    }

    private SeatunnelConnection queryByCode(long code) {
        return connectionDao.queryAll().stream().filter(connection -> connection.getCode() == code).findFirst()
                .orElseThrow(() -> new ServiceException(Status.SEATUNNEL_CONNECTION_NOT_FOUND, code));
    }

    private void checkAdmin(User loginUser) {
        if (!isAdmin(loginUser)) {
            throw new ServiceException(Status.USER_NO_OPERATION_PERM);
        }
    }

    private static ServiceException invalid(String message) {
        return new ServiceException(Status.SEATUNNEL_CONNECTION_PARAM_INVALID, message);
    }

    private static long genCode() {
        try {
            return CodeGenerateUtils.genCode();
        } catch (CodeGenerateUtils.CodeGenerateException e) {
            throw new ServiceException(Status.INTERNAL_SERVER_ERROR_ARGS, e.getMessage());
        }
    }

    private static ConnectionType type(SeatunnelConnection connection) {
        return ConnectionType.of(connection.getType())
                .orElseThrow(() -> invalid("unknown type " + connection.getType()));
    }

    /**
     * @return the connection with the decrypted secrets, used on the server only
     */
    private static org.apache.dolphinscheduler.ai.connection.SeatunnelConnection toConnection(
                                                                                              SeatunnelConnection connection) {
        Map<String, String> params = JSONUtils.parseObject(connection.getParams(),
                new TypeReference<LinkedHashMap<String, String>>() {
                });
        return new org.apache.dolphinscheduler.ai.connection.SeatunnelConnection(connection.getConnKey(),
                connection.getName(), type(connection), params == null ? Collections.emptyMap() : params,
                SeatunnelConnectionSecretCodec.decode(connection.getSecrets()));
    }

    /**
     * @return the connection for the prompt: the secrets are masked, the prompt only has their references
     */
    private static org.apache.dolphinscheduler.ai.connection.SeatunnelConnection masked(
                                                                                        org.apache.dolphinscheduler.ai.connection.SeatunnelConnection connection) {
        Map<String, String> secrets = new LinkedHashMap<>();
        connection.secrets().keySet().forEach(key -> secrets.put(key, MASK));
        return new org.apache.dolphinscheduler.ai.connection.SeatunnelConnection(connection.key(), connection.name(),
                connection.type(), connection.params(), secrets);
    }

    private static ObjectDescription toDescription(DescribedObject described) {
        List<DescribedField> fields = new ArrayList<>();
        if (described.getFields() != null) {
            described.getFields().forEach(field -> fields.add(new DescribedField(field.getName(), field.getType(),
                    field.isNullable(), field.isPrimaryKey(), field.getSample(), field.getNote())));
        }
        // The raw samples stay in the browser
        return new ObjectDescription(described.getObject(), described.getFormat(), fields,
                described.getRecordPath(), described.getNote(), Collections.emptyList());
    }

    private List<SeatunnelConnectionVO> toVOs(List<SeatunnelConnection> connections) {
        Map<Integer, String> userNames = new HashMap<>();
        return connections.stream()
                .sorted((left, right) -> left.getName().compareTo(right.getName()))
                .map(connection -> {
                    SeatunnelConnectionVO vo = toVO(connection);
                    Integer user = connection.getUpdateUser();
                    if (user != null) {
                        vo.setUpdateUserName(userNames.computeIfAbsent(user,
                                id -> Optional.ofNullable(userDao.queryById(id)).map(User::getUserName)
                                        .orElse("")));
                    }
                    return vo;
                })
                .collect(Collectors.toList());
    }

    private static SeatunnelConnectionVO toVO(SeatunnelConnection connection) {
        SeatunnelConnectionVO vo = new SeatunnelConnectionVO();
        vo.setId(connection.getId());
        vo.setCode(connection.getCode());
        vo.setConnKey(connection.getConnKey());
        vo.setName(connection.getName());
        vo.setType(connection.getType());
        vo.setDescription(connection.getDescription());
        Map<String, String> params = JSONUtils.parseObject(connection.getParams(),
                new TypeReference<LinkedHashMap<String, String>>() {
                });
        vo.setParams(params == null ? Collections.emptyMap() : params);
        vo.setSecretsSet(new ArrayList<>(SeatunnelConnectionSecretCodec.decode(connection.getSecrets()).keySet()));
        vo.setUpdateTime(connection.getUpdateTime());
        return vo;
    }
}
