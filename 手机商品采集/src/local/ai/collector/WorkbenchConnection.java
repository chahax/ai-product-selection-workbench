package local.ai.collector;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** Connection settings scoped to one paired workbench. */
final class WorkbenchConnection {
    final String address;
    final String fingerprint;
    final String token;
    private final SSLSocketFactory pinnedFactory;

    WorkbenchConnection(String address, String fingerprint, String token) throws GeneralSecurityException {
        this.address = validateAddress(address);
        this.fingerprint = normalizeFingerprint(fingerprint);
        this.token = token;
        if (!this.fingerprint.isEmpty() && !this.address.startsWith("https://")) {
            throw new IllegalArgumentException("证书指纹只用于 HTTPS 地址");
        }
        pinnedFactory = this.fingerprint.isEmpty() ? null : pinnedFactory(this.fingerprint);
    }

    private static String validateAddress(String input) {
        try {
            URI url = new URI(input.trim());
            boolean secure = "https".equals(url.getScheme());
            boolean local = "http".equals(url.getScheme()) && "127.0.0.1".equals(url.getHost()) && url.getPort() == 3000;
            String path = url.getRawPath();
            if ((secure || local) && url.getHost() != null && url.getRawUserInfo() == null
                    && url.getRawQuery() == null && url.getRawFragment() == null
                    && url.getPort() != 0 && url.getPort() <= 65535
                    && (path.isEmpty() || "/".equals(path))) {
                String normalized = url.toASCIIString();
                return path.isEmpty() ? normalized : normalized.substring(0, normalized.length() - 1);
            }
        } catch (URISyntaxException ignored) { }
        throw new IllegalArgumentException("请填写 HTTPS 工作台地址。USB 联调可用 http://127.0.0.1:3000");
    }

    private static String normalizeFingerprint(String value) {
        String hex = value.replaceAll("[:\\s]", "").toLowerCase(java.util.Locale.ROOT);
        if (!hex.isEmpty() && !hex.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("请填写 64 位 SHA-256 证书指纹");
        }
        return hex;
    }

    private static SSLSocketFactory pinnedFactory(String fingerprint) throws GeneralSecurityException {
        byte[] expected = new byte[32];
        for (int i = 0; i < expected.length; i++) {
            expected[i] = (byte) Integer.parseInt(fingerprint.substring(i * 2, i * 2 + 2), 16);
        }
        X509TrustManager trust = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                throw new CertificateException("仅验证工作台服务器证书");
            }

            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                if (chain == null || chain.length == 0) throw new CertificateException("工作台未提供服务器证书");
                chain[0].checkValidity();
                try {
                    byte[] actual = MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded());
                    if (!MessageDigest.isEqual(expected, actual)) throw new CertificateException("工作台证书指纹不匹配");
                } catch (GeneralSecurityException error) {
                    throw new CertificateException("工作台服务器证书无法通过验证", error);
                }
            }

            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{trust}, null);
        return context.getSocketFactory();
    }

    HttpURLConnection open(String path) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(address + path).toURL().openConnection();
        if (pinnedFactory != null && connection instanceof HttpsURLConnection) {
            ((HttpsURLConnection) connection).setSSLSocketFactory(pinnedFactory);
        }
        connection.setInstanceFollowRedirects(false);
        if (!token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);
        return connection;
    }

    JSONObject post(String path, JSONObject body) throws IOException, JSONException {
        HttpURLConnection connection = open(path);
        try {
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(7000);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            byte[] request = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(request.length);
            try (OutputStream output = connection.getOutputStream()) { output.write(request); }
            int status = connection.getResponseCode();
            try (InputStream input = status < 400 ? connection.getInputStream() : connection.getErrorStream()) {
                JSONObject response = readJson(input);
                if (status < 200 || status >= 300) {
                    String reason = response.optString("error", "");
                    throw new IOException(reason.isEmpty() ? "工作台返回 HTTP " + status : reason);
                }
                return response;
            }
        } finally {
            connection.disconnect();
        }
    }

    private static JSONObject readJson(InputStream input) throws IOException, JSONException {
        if (input == null) return new JSONObject();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int received;
        while ((received = input.read(buffer)) != -1) {
            if (output.size() + received > 65536) throw new IOException("工作台响应过大");
            output.write(buffer, 0, received);
        }
        return output.size() == 0 ? new JSONObject() : new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
    }
}
