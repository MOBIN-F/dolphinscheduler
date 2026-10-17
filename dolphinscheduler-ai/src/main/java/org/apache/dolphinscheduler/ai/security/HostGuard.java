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

package org.apache.dolphinscheduler.ai.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides which addresses the api server may connect to on behalf of the LLM, e.g. a Kafka cluster or a database
 * the user wrote in the conversation. The api server is usually in the internal network: the loopback, link-local
 * (e.g. the cloud metadata service), any-local and multicast addresses are always denied, the private addresses
 * are allowed because the data sources are usually internal.
 */
public class HostGuard {

    /**
     * The IPv6 address of the EC2 metadata service, a unique local address.
     */
    private static final List<String> BUILT_IN_DENIED_HOSTS = List.of("fd00:ec2::254");

    private static final Pattern URL_HOST = Pattern.compile("(?i)\\b[a-z][a-z0-9+.-]*://(?:[^/\\s@]*@)?"
            + "(\\[[0-9a-f:.]+]|[a-z0-9.-]+)");
    private static final Pattern HOST_PORT = Pattern.compile("(?i)(?<![\\w.-])([a-z0-9](?:[a-z0-9.-]*[a-z0-9])?)"
            + ":\\d{2,5}(?![\\w.])");
    private static final Pattern DOMAIN = Pattern.compile("(?i)(?<![\\w.@-])((?:[a-z0-9-]+\\.)+[a-z][a-z0-9-]*)"
            + "(?![\\w.-])");
    private static final Pattern IPV4 = Pattern.compile("(?<![\\d.])(\\d{1,3}(?:\\.\\d{1,3}){3})(?![\\d.])");

    private final List<String> deniedHosts;
    private final boolean allowLoopback;

    /**
     * @param deniedHosts   extra host names (also matching the sub domains), IPs or CIDRs
     * @param allowLoopback allow the loopback addresses, for tests only
     */
    public HostGuard(List<String> deniedHosts, boolean allowLoopback) {
        this.deniedHosts = new ArrayList<>(BUILT_IN_DENIED_HOSTS);
        if (deniedHosts != null) {
            this.deniedHosts.addAll(deniedHosts);
        }
        this.allowLoopback = allowLoopback;
    }

    /**
     * @return the host names the user wrote in the texts, lower case: in URLs (including jdbc:...), host:port, IP
     *         addresses and domain names
     */
    public static Set<String> hostNames(String... texts) {
        Set<String> hosts = new LinkedHashSet<>();
        for (String text : texts) {
            if (text == null) {
                continue;
            }
            for (Pattern pattern : List.of(URL_HOST, HOST_PORT, DOMAIN, IPV4)) {
                Matcher matcher = pattern.matcher(text);
                while (matcher.find()) {
                    hosts.add(matcher.group(1).replaceAll("^\\[|]$", "").toLowerCase(Locale.ROOT));
                }
            }
        }
        return hosts;
    }

    /**
     * @param allowedHosts the host names the user gave
     * @return why the host must not be connected to, or null if allowed
     */
    public String check(String host, Set<String> allowedHosts) {
        String name = host == null ? "" : host.trim().replaceAll("^\\[|]$", "").toLowerCase(Locale.ROOT);
        if (name.isEmpty()) {
            return "the host is empty";
        }
        if (!allowedHosts.contains(name)) {
            return "the host " + name + " is not given by the user, only the addresses the user wrote in the "
                    + "conversation or in the script can be connected to";
        }
        return checkAddress(name);
    }

    /**
     * @return why the addresses of the host must not be connected to, or null if allowed
     */
    public String checkAddress(String host) {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            return "unknown host " + host + " (the api-server can not resolve it)";
        }
        for (InetAddress address : addresses) {
            if ((!allowLoopback && (address.isLoopbackAddress() || address.isAnyLocalAddress()))
                    || address.isLinkLocalAddress() || address.isMulticastAddress()
                    || deniedHosts.stream().anyMatch(entry -> isDenied(entry, host, address))) {
                return "the address of " + host + " is not allowed to be connected to";
            }
        }
        return null;
    }

    /**
     * @param entry a host name (also matches the sub domains), an IP or a CIDR
     */
    public static boolean isDenied(String entry, String host, InetAddress address) {
        String value = entry.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            return false;
        }
        boolean literal = value.matches("[0-9.]+(/\\d+)?") || value.contains(":");
        if (!literal) {
            String name = host.toLowerCase(Locale.ROOT);
            return name.equals(value) || name.endsWith("." + value);
        }
        try {
            String[] parts = value.split("/");
            byte[] network = InetAddress.getByName(parts[0]).getAddress();
            byte[] target = address.getAddress();
            if (network.length != target.length) {
                return false;
            }
            int bits = parts.length > 1 ? Integer.parseInt(parts[1]) : network.length * 8;
            for (int i = 0; i < network.length && bits > 0; i++, bits -= 8) {
                int mask = bits >= 8 ? 0xFF : (0xFF << (8 - bits)) & 0xFF;
                if ((network[i] & mask) != (target[i] & mask)) {
                    return false;
                }
            }
            return true;
        } catch (UnknownHostException | NumberFormatException e) {
            return false;
        }
    }
}
