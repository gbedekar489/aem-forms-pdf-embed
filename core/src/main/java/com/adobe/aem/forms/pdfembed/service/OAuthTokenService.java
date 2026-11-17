
package com.adobe.aem.forms.pdfembed.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.osgi.service.metatype.annotations.AttributeDefinition;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

@Component(service = OAuthTokenService.class)
@Designate(ocd = OAuthTokenService.Config.class)
public class OAuthTokenService {

    private static final Logger LOG =
        LoggerFactory.getLogger(OAuthTokenService.class);

    private static final ObjectMapper MAPPER =
        new ObjectMapper();

    private static final long REFRESH_MARGIN_SECONDS = 120;

    @ObjectClassDefinition(
        name = "Banking Application OAuth Token Service"
    )
    public @interface Config {

        @AttributeDefinition(name = "IMS Token URL")
        String tokenUrl() default
            "https://ims-na1.adobelogin.com/ims/token/v3";

        @AttributeDefinition(name = "OAuth Client ID")
        String clientId() default "";

        @AttributeDefinition(name = "OAuth Client Secret")
        String clientSecret() default "";

        @AttributeDefinition(name = "OAuth Scopes")
        String scopes() default "";
    }

    private volatile Config config;

    private String cachedToken;
    private long expiresAtEpochSeconds;

    @Activate
    @Modified
    protected synchronized void activate(Config config) {
        this.config = config;
        invalidateToken();
        LOG.info("[OAuth] Token service configured");
    }

    public synchronized String getAccessToken() throws IOException {
        long now = Instant.now().getEpochSecond();

        if (cachedToken != null &&
            now < expiresAtEpochSeconds) {
            return cachedToken;
        }

        return requestNewToken();
    }

    public synchronized void invalidateToken() {
        cachedToken = null;
        expiresAtEpochSeconds = 0;
    }

    private String requestNewToken() throws IOException {
        Config current = config;

        if (current == null ||
            isBlank(current.clientId()) ||
            isBlank(current.clientSecret()) ||
            isBlank(current.scopes())) {

            throw new IOException(
                "OAuth credentials are not configured"
            );
        }

        LOG.info("[OAuth] Requesting access token from Adobe IMS");

        String requestBody =
            "grant_type=client_credentials" +
            "&client_id=" + encode(current.clientId()) +
            "&client_secret=" + encode(current.clientSecret()) +
            "&scope=" + encode(current.scopes());

        HttpURLConnection connection = null;

        try {
            URL url = new URL(current.tokenUrl());

            if (!"https".equalsIgnoreCase(url.getProtocol()) ||
                !"ims-na1.adobelogin.com".equalsIgnoreCase(
                    url.getHost())) {
                throw new IOException("Invalid IMS token endpoint");
            }

            connection =
                (HttpURLConnection) url.openConnection();

            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(20000);
            connection.setInstanceFollowRedirects(false);

            connection.setRequestProperty(
                "Content-Type",
                "application/x-www-form-urlencoded"
            );

            connection.setRequestProperty(
                "Accept",
                "application/json"
            );

            byte[] bytes =
                requestBody.getBytes(StandardCharsets.UTF_8);

            try (OutputStream out = connection.getOutputStream()) {
                out.write(bytes);
            }

            int status = connection.getResponseCode();

            if (status != 200) {
                LOG.error(
                    "[OAuth] IMS token request failed: HTTP {}",
                    status
                );

                throw new IOException(
                    "IMS token request failed: HTTP " + status
                );
            }

            byte[] responseBytes;

            try (InputStream in = connection.getInputStream()) {
                responseBytes = readLimited(in, 65536);
            }

            JsonNode json = MAPPER.readTree(responseBytes);

            String accessToken =
                json.path("access_token").asText("");

            long expiresIn =
                json.path("expires_in").asLong(0);

            if (accessToken.isEmpty() || expiresIn <= 0) {
                throw new IOException(
                    "Invalid IMS token response"
                );
            }

            cachedToken = accessToken;

            long refreshMargin =
                Math.min(REFRESH_MARGIN_SECONDS,
                         Math.max(1, expiresIn / 10));

            expiresAtEpochSeconds =
                Instant.now().getEpochSecond()
                + Math.max(1, expiresIn - refreshMargin);

            LOG.info(
                "[OAuth] Access token acquired; expires in {} seconds",
                expiresIn
            );

            return cachedToken;

        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String encode(String value)
            throws IOException {
        return URLEncoder.encode(value, "UTF-8");
    }

    private static byte[] readLimited(
            InputStream in, int limit) throws IOException {

        ByteArrayOutputStream out =
            new ByteArrayOutputStream();

        byte[] buffer = new byte[4096];
        int count;

        while ((count = in.read(buffer)) != -1) {
            if (out.size() + count > limit) {
                throw new IOException("IMS response too large");
            }
            out.write(buffer, 0, count);
        }

        return out.toByteArray();
    }
}
