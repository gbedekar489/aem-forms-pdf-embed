
package com.adobe.aem.forms.pdfembed.servlets;

import com.adobe.aem.forms.pdfembed.service.OAuthTokenService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.osgi.service.metatype.annotations.AttributeDefinition;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.Servlet;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import java.util.UUID;

@Component(
    service = Servlet.class,
    property = {
        "sling.servlet.paths=/bin/aem-forms-pdf-embed/generate-ic-pdf",
        "sling.servlet.methods=POST"
    }
)
@Designate(ocd = IcPdfGenerationServlet.Config.class)
public class IcPdfGenerationServlet
        extends SlingAllMethodsServlet {

    private static final long serialVersionUID = 1L;

    private static final Logger LOG =
        LoggerFactory.getLogger(IcPdfGenerationServlet.class);

    private static final ObjectMapper MAPPER =
        new ObjectMapper();

    // Development-only restrictions
    //private static final String TEST_DOCUMENT_ID =
        //"f35ea2d9-6164-4791-8943-c56a6085eb91";

    //private static final String TEST_USER_ID = "1";

    private static final String IC_SERVICE_NAME = "IC_FDM";

    private static final int MAX_REQUEST_SIZE = 65536;
    private static final int MAX_PDF_SIZE = 20 * 1024 * 1024;

    @ObjectClassDefinition(
        name = "Banking Application IC PDF Generation"
    )
    public @interface Config {

        @AttributeDefinition(name = "AEM API Base URL")
        String apiBaseUrl() default "";
        
    }

    @Reference
    private OAuthTokenService oauthTokenService;

    private volatile Config config;

    @Activate
    @Modified
    protected void activate(Config config) {
        this.config = config;
        LOG.info("[IC PDF] Servlet configured");
    }

    @Override
    protected void doPost(
            SlingHttpServletRequest request,
            SlingHttpServletResponse response)
            throws IOException {

        response.setHeader("Cache-Control", "no-store");

        try {
            
            // 2. Read incoming JSON
            byte[] requestBytes =
                readLimited(
                    request.getInputStream(),
                    MAX_REQUEST_SIZE
                );

            JsonNode body = MAPPER.readTree(requestBytes);

            if (body == null || !body.isObject()) {
                response.sendError(400, "Invalid JSON request");
                return;
            }

            String documentId =
                body.path("documentId").asText("");

            JsonNode formData = body.path("formData");

            if (!formData.isObject()) {
                response.sendError(
                    400, "formData must be a JSON object"
                );
                return;
            }

            /* JsonNode userIdNode = formData.path("userId");

            if (!userIdNode.isTextual() &&
                !userIdNode.isNumber()) {
                response.sendError(400, "Invalid userId");
                return;
            }

            String userId = userIdNode.asText("");
 */            // Read Document ID from the request
            if (documentId == null ||
                    !documentId.matches(
                    "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
                )) 
                    {
                        response.sendError(400, "Invalid IC Document ID");
                        return;
                    }

// Validate userId from Adaptive Form

            // 3. Restrict to synthetic development data
            /* if (!TEST_DOCUMENT_ID.equals(documentId) ||
                !TEST_USER_ID.equals(userId)) {

                response.sendError(
                    403, "Document or test user not permitted"
                );
                return;
            }
 */
            LOG.info(
                "[IC PDF] Generating approved test document"
            );

            // 4. Get OAuth token automatically
            String accessToken =
                oauthTokenService.getAccessToken();

            // 5. Prepare IC prefill options
            /* JsonNode options = MAPPER.createObjectNode()
                .set(
                    "prefill",
                    MAPPER.createObjectNode()
                        .put("serviceName", IC_SERVICE_NAME)
                        .set(
                            "serviceParams",
                            MAPPER.createObjectNode()
                                .put("userId", userId)
                        )
                );
 */
            JsonNode options = MAPPER.createObjectNode()
                .set(
                        "prefill",
                        MAPPER.createObjectNode()
                        .put("serviceName", IC_SERVICE_NAME)
                        .set(
                            "serviceParams", formData.deepCopy()
            )
                    );
            String optionsJson =
                MAPPER.writeValueAsString(options);

            // 6. Call AEM IC API
            byte[] pdfBytes = callIcApi(
                documentId,
                optionsJson,
                accessToken
            );

            // 7. Return PDF
            response.setContentType("application/pdf");

            response.setHeader(
                "Content-Disposition",
                "inline; filename=\"IC-Document.pdf\""
            );

            response.setContentLength(pdfBytes.length);

            response.getOutputStream().write(pdfBytes);
            response.getOutputStream().flush();

            LOG.info(
                "[IC PDF] PDF generated successfully, bytes={}",
                pdfBytes.length
            );

        } catch (IllegalArgumentException e) {
            LOG.warn("[IC PDF] Invalid request", e);

            if (!response.isCommitted()) {
                response.sendError(400, "Invalid request");
            }

        } catch (Exception e) {
            LOG.error("[IC PDF] Generation failed", e);

            if (!response.isCommitted()) {
                response.sendError(
                    502, "IC PDF generation failed"
                );
            }
        }
    }

    private byte[] callIcApi(
            String documentId,
            String optionsJson,
            String accessToken) throws IOException {

        String baseUrl = config.apiBaseUrl();

if (baseUrl == null || baseUrl.trim().isEmpty()) {
    throw new IOException("AEM API Base URL is not configured");
}

baseUrl = baseUrl.trim().replaceAll("/+$", "");

URI baseUri;

try {
    baseUri = URI.create(baseUrl);
} catch (IllegalArgumentException e) {
    throw new IOException("Invalid AEM API Base URL", e);
}

if (!"https".equalsIgnoreCase(baseUri.getScheme())
        || baseUri.getHost() == null
        || baseUri.getUserInfo() != null
        || baseUri.getPort() != -1
        || (baseUri.getRawPath() != null
            && !baseUri.getRawPath().isEmpty())
        || baseUri.getRawQuery() != null
        || baseUri.getRawFragment() != null
        || !baseUri.getHost().matches(
            "(?:author|publish)-p[0-9]+-e[0-9]+\\.adobeaemcloud\\.com"
        )) {
    throw new IOException("Invalid AEM API Base URL");
}
     String apiUrl =
            baseUrl.replaceAll("/+$", "")
            + "/adobe/communications/"
            + documentId
            + "/pdf";

        String boundary =
            "----ICBoundary" + UUID.randomUUID();

        HttpURLConnection connection = null;

        try {
                connection =
                (HttpURLConnection) URI.create(apiUrl)
                .toURL()
                .openConnection();
            
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(60000);
            connection.setInstanceFollowRedirects(false);

            connection.setRequestProperty(
                "Authorization",
                "Bearer " + accessToken
            );

            connection.setRequestProperty(
                "Accept", "application/pdf"
            );

            connection.setRequestProperty(
                "Content-Type",
                "multipart/form-data; boundary=" + boundary
            );

            String multipartBody =
                "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; "
                + "name=\"options\"\r\n"
                + "Content-Type: application/json\r\n\r\n"
                + optionsJson + "\r\n"
                + "--" + boundary + "--\r\n";

            byte[] multipartBytes =
                multipartBody.getBytes(StandardCharsets.UTF_8);

            connection.setFixedLengthStreamingMode(
                multipartBytes.length
            );

            try (OutputStream out =
                     connection.getOutputStream()) {
                out.write(multipartBytes);
            }

            int status = connection.getResponseCode();

            LOG.info(
                "[IC PDF] Communications API status: {}",
                status
            );

            if (status != 200) {
                throw new IOException(
                    "Communications API returned HTTP " + status
                );
            }

            String contentType = connection.getContentType();

            if (contentType == null ||
                !contentType.toLowerCase()
                    .contains("application/pdf")) {

                throw new IOException(
                    "Communications API did not return a PDF"
                );
            }

            try (InputStream in =
                     connection.getInputStream()) {

                return readLimited(in, MAX_PDF_SIZE);
            }

        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static byte[] readLimited(
            InputStream input,
            int maxBytes) throws IOException {

        ByteArrayOutputStream output =
            new ByteArrayOutputStream();

        byte[] buffer = new byte[8192];
        int count;

        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > maxBytes) {
                throw new IllegalArgumentException(
                    "Request or response exceeds size limit"
                );
            }

            output.write(buffer, 0, count);
        }

        return output.toByteArray();
    }
}
