package com.github.xfalcon.vhosts.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class HttpUtils {
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 10000;
    private static final int MAX_REDIRECTS = 5;
    // 下载体上限，防御被劫持/恶意的订阅返回超大 body 撑爆内存（OOM/DoS）。
    private static final int MAX_BODY_BYTES = 16 * 1024 * 1024;

    static public String get(String url) throws IOException {
        return get(url, null);
    }

    static public String get(String url, Map<String, String> headers) throws IOException {
        return fetch("GET", url, null, headers);
    }

    static public String fetch(String method, String url, String body,
                               Map<String, String> headers) throws IOException {
        String current = url;
        // 自己迭代处理重定向：限制最大跳数（防重定向环路导致 StackOverflow），
        // 且每一跳都重新校验协议（防跳转到 file:// 等非 http 协议）。
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            URL u = new URL(current);
            validateUrl(u);
            HttpURLConnection conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(false);
            if (method != null) {
                conn.setRequestMethod(method);
            }
            if (headers != null) {
                for (String key : headers.keySet()) {
                    conn.addRequestProperty(key, headers.get(key));
                }
            }
            if (body != null) {
                conn.setDoOutput(true);
                OutputStream os = conn.getOutputStream();
                try {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                } finally {
                    os.close();
                }
            }

            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_MOVED_TEMP
                    || code == HttpURLConnection.HTTP_SEE_OTHER || code == 307 || code == 308) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location == null) throw new IOException("Redirect without Location header");
                current = new URL(u, location).toString();  // 支持相对重定向
                continue;
            }

            InputStream is = conn.getInputStream();
            try {
                return streamToString(is);
            } finally {
                is.close();
                conn.disconnect();
            }
        }
        throw new IOException("Too many redirects (> " + MAX_REDIRECTS + ")");
    }

    // 协议白名单：仅允许 http/https，拒绝 file://、ftp:// 等（含 SSRF/本地文件读取的协议面）。
    private static void validateUrl(URL u) throws IOException {
        String protocol = u.getProtocol();
        if (!"http".equalsIgnoreCase(protocol) && !"https".equalsIgnoreCase(protocol)) {
            throw new IOException("Unsupported protocol: " + protocol);
        }
        if (u.getHost() == null || u.getHost().isEmpty()) {
            throw new IOException("Empty host in URL");
        }
    }

    /**
     * Read an input stream into a string. UTF-8 解码在全部字节读齐后统一进行，
     * 避免按 4096 字节分块 new String 把多字节 UTF-8 字符从中间截断损坏。
     */
    static public String streamToString(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(b)) != -1) {
            total += n;
            if (total > MAX_BODY_BYTES) {
                throw new IOException("Response too large (> " + MAX_BODY_BYTES + " bytes)");
            }
            buf.write(b, 0, n);
        }
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }
}
