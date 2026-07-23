/*
 **Copyright (C) 2017  xfalcon
 **
 **This program is free software: you can redistribute it and/or modify
 **it under the terms of the GNU General Public License as published by
 **the Free Software Foundation, either version 3 of the License, or
 **(at your option) any later version.
 **
 **This program is distributed in the hope that it will be useful,
 **but WITHOUT ANY WARRANTY; without even the implied warranty of
 **MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 **GNU General Public License for more details.
 **
 **You should have received a copy of the GNU General Public License
 **along with this program.  If not, see <http://www.gnu.org/licenses/>.
 **
 */

package com.github.xfalcon.vhosts.vservice;

import com.github.xfalcon.vhosts.util.LogUtils;
import org.xbill.DNS.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DnsChange {

    static String TAG = DnsChange.class.getSimpleName();
    static volatile ConcurrentHashMap<String, String> DOMAINS_IP_MAPS4 = null;
    static volatile ConcurrentHashMap<String, String> DOMAINS_IP_MAPS6 = null;


    public static ByteBuffer handle_dns_packet(Packet packet) {
        if (DOMAINS_IP_MAPS4 == null) {
            LogUtils.d(TAG, "DOMAINS_IP_MAPS IS　NULL　HOST FILE ERROR");
            return null;
        }
        try {
            ByteBuffer packet_buffer = packet.backingBuffer;
            packet_buffer.mark();
            byte[] tmp_bytes = new byte[packet_buffer.remaining()];
            packet_buffer.get(tmp_bytes);
            packet_buffer.reset();
            Message message = new Message(tmp_bytes);
            org.xbill.DNS.Record question = message.getQuestion();
            ConcurrentHashMap<String, String> DOMAINS_IP_MAPS;
            int type = question.getType();
            if (type == Type.A)
                DOMAINS_IP_MAPS = DOMAINS_IP_MAPS4;
            else if (type == Type.AAAA)
                DOMAINS_IP_MAPS = DOMAINS_IP_MAPS6;
            else return null;
            Name query_domain = message.getQuestion().getName();
            String query_string = query_domain.toString();
            LogUtils.d(TAG, "query: " + question.getType() + " :" + query_string);
            if (!DOMAINS_IP_MAPS.containsKey(query_string)) {
                query_string = "." + query_string;
                int j = 0;
                while (true) {
                    int i = query_string.indexOf(".", j);
                    if (i == -1) {
                        return null;
                    }
                    String str = query_string.substring(i);

                    if (".".equals(str) || "".equals(str)) {
                        return null;
                    }
                    if (DOMAINS_IP_MAPS.containsKey(str)) {
                        query_string = str;
                        break;
                    }
                    j = i + 1;
                }
            }
            InetAddress address = Address.getByAddress(DOMAINS_IP_MAPS.get(query_string));
            org.xbill.DNS.Record record;
            if (type == Type.A) record = new ARecord(query_domain, 1, 86400, address);
            else record = new AAAARecord(query_domain, 1, 86400, address);
            message.addRecord(record, 1);
            message.getHeader().setFlag(Flags.QR);
            packet_buffer.limit(packet_buffer.capacity());
            packet_buffer.put(message.toWire());
            packet_buffer.limit(packet_buffer.position());
            packet_buffer.reset();
            packet.swapSourceAndDestination();
            packet.updateUDPBuffer(packet_buffer, packet_buffer.remaining());
            packet_buffer.position(packet_buffer.limit());
            LogUtils.d(TAG, "hit: " + question.getType() + " :" + query_domain.toString() + " :" + address.getHostName());
            return packet_buffer;
        } catch (Exception e) {
            LogUtils.d(TAG, "dns hook error", e);
            return null;
        }

    }

    public static int handle_hosts(InputStream inputStream) {
        String STR_COMMENT = "#";
        String HOST_PATTERN_STR = "^\\s*(" + STR_COMMENT + "?)\\s*(\\S*)\\s*([^" + STR_COMMENT + "]*)" + STR_COMMENT + "?(.*)$";
        Pattern HOST_PATTERN = Pattern.compile(HOST_PATTERN_STR);
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
            String line;
            DOMAINS_IP_MAPS4 = new ConcurrentHashMap<>();
            DOMAINS_IP_MAPS6 = new ConcurrentHashMap<>();
            while (!Thread.interrupted() && (line = reader.readLine()) != null) {
                if (line.length() > 1000 || line.startsWith(STR_COMMENT)) continue;
                Matcher matcher = HOST_PATTERN.matcher(line);
                if (matcher.find()) {
                    String ip = matcher.group(2).trim();
                    try {
                        Address.getByAddress(ip);
                    } catch (Exception e) {
                        continue;
                    }
                    if (ip.contains(":")) {
                        DOMAINS_IP_MAPS6.put(matcher.group(3).trim() + ".", ip);
                    } else {
                        DOMAINS_IP_MAPS4.put(matcher.group(3).trim() + ".", ip);
                    }
                }
            }
            reader.close();
            inputStream.close();
            LogUtils.d(TAG, DOMAINS_IP_MAPS4.toString());
            LogUtils.d(TAG, DOMAINS_IP_MAPS6.toString());
            return DOMAINS_IP_MAPS4.size() + DOMAINS_IP_MAPS6.size();
        } catch (IOException e) {
            LogUtils.d(TAG, "Hook dns error", e);
            return 0;
        }
    }

    public static void loadProfiles(List<String> contentsInOrder) {
        ConcurrentHashMap<String, String> map4 = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, String> map6 = new ConcurrentHashMap<>();

        // 按传入顺序（靠前优先）解析每个方案
        for (String content : contentsInOrder) {
            try {
                parseAndMerge(content, map4, map6);
            } catch (Exception e) {
                LogUtils.e(TAG, "Error parsing profile", e);
            }
        }

        // 原子替换（字段为 volatile，VPN 线程读到的始终是完整旧表或完整新表）
        DOMAINS_IP_MAPS4 = map4;
        DOMAINS_IP_MAPS6 = map6;
        LogUtils.d(TAG, "Loaded profiles: " + map4.size() + " IPv4, " + map6.size() + " IPv6");
    }

    private static void parseAndMerge(String content, ConcurrentHashMap<String, String> map4, ConcurrentHashMap<String, String> map6) throws Exception {
        String STR_COMMENT = "#";
        String HOST_PATTERN_STR = "^\\s*(" + STR_COMMENT + "?)\\s*(\\S*)\\s*([^" + STR_COMMENT + "]*)" + STR_COMMENT + "?(.*)$";
        java.util.regex.Pattern HOST_PATTERN = java.util.regex.Pattern.compile(HOST_PATTERN_STR);

        java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.StringReader(content));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.length() > 1000 || line.startsWith(STR_COMMENT)) continue;
            java.util.regex.Matcher matcher = HOST_PATTERN.matcher(line);
            if (matcher.find()) {
                String ip = matcher.group(2).trim();
                try {
                    Address.getByAddress(ip);
                } catch (Exception e) {
                    continue;
                }
                String domain = matcher.group(3).trim() + ".";
                if (ip.contains(":")) {
                    map6.putIfAbsent(domain, ip);  // 靠前优先
                } else {
                    map4.putIfAbsent(domain, ip);  // 靠前优先
                }
            }
        }
        reader.close();
    }

    // 当前生效的合并映射，格式化为「IP 域名」文本（供 UI 展示生效 hosts）。
    public static String getActiveHostsText() {
        StringBuilder sb = new StringBuilder();
        appendMap(sb, DOMAINS_IP_MAPS4);
        appendMap(sb, DOMAINS_IP_MAPS6);
        return sb.toString();
    }

    private static void appendMap(StringBuilder sb, ConcurrentHashMap<String, String> map) {
        if (map == null) return;
        for (java.util.Map.Entry<String, String> e : map.entrySet()) {
            String domain = e.getKey();
            if (domain.endsWith(".")) domain = domain.substring(0, domain.length() - 1);  // 去存储用的末尾点
            sb.append(e.getValue()).append(" ").append(domain).append("\n");
        }
    }

}
