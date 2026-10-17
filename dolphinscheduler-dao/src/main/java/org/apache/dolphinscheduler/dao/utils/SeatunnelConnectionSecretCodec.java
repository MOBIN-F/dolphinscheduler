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

package org.apache.dolphinscheduler.dao.utils;

import org.apache.dolphinscheduler.common.utils.JSONUtils;
import org.apache.dolphinscheduler.common.utils.PropertyUtils;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import lombok.extern.slf4j.Slf4j;

import com.fasterxml.jackson.core.type.TypeReference;

/**
 * Encrypts the secrets of the SeaTunnel connections with AES-GCM. The api server encrypts them when they are saved,
 * the master decrypts them to inject them into the tasks, so both use the same key: the property
 * {@code seatunnel.connection.encryption.key} of common.properties.
 */
@Slf4j
public final class SeatunnelConnectionSecretCodec {

    public static final String KEY_PROPERTY = "seatunnel.connection.encryption.key";

    /**
     * Used when the key is not configured, then the secrets are only obfuscated.
     */
    private static final String DEFAULT_KEY = "dolphinscheduler-seatunnel-connection";
    private static final String PREFIX = "v1:";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private static volatile boolean warned;

    private SeatunnelConnectionSecretCodec() {
    }

    public static String encode(Map<String, String> secrets) {
        if (secrets == null || secrets.isEmpty()) {
            return "";
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(JSONUtils.toJsonString(secrets).getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to encrypt the SeaTunnel connection secrets", e);
        }
    }

    public static Map<String, String> decode(String stored) {
        if (stored == null || stored.isEmpty()) {
            return Collections.emptyMap();
        }
        if (!stored.startsWith(PREFIX)) {
            throw new IllegalStateException("Unknown format of the SeaTunnel connection secrets");
        }
        try {
            byte[] data = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, data, 0, IV_LENGTH));
            String json = new String(cipher.doFinal(data, IV_LENGTH, data.length - IV_LENGTH),
                    StandardCharsets.UTF_8);
            Map<String, String> secrets =
                    JSONUtils.parseObject(json, new TypeReference<LinkedHashMap<String, String>>() {
                    });
            return secrets == null ? Collections.emptyMap() : secrets;
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Failed to decrypt the SeaTunnel connection secrets, check that "
                    + KEY_PROPERTY + " is the same on the api server and the master", e);
        }
    }

    private static SecretKeySpec key() throws GeneralSecurityException {
        String configured = PropertyUtils.getString(KEY_PROPERTY, "");
        if (configured.isEmpty()) {
            if (!warned) {
                warned = true;
                log.warn("{} is not configured in common.properties, the SeaTunnel connection secrets are only "
                        + "obfuscated with the built-in key", KEY_PROPERTY);
            }
            configured = DEFAULT_KEY;
        }
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(configured.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(digest, "AES");
    }
}
