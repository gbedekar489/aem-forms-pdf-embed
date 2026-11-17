package com.adobe.aem.forms.pdfembed.servlets;

import com.adobe.aemfd.docmanager.Document;
import com.adobe.fd.output.api.AcrobatVersion;
import com.adobe.fd.output.api.OutputService;
import com.adobe.fd.output.api.PDFOutputOptions;
import com.day.cq.dam.api.Asset;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.w3c.dom.Element;

import javax.servlet.Servlet;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Pattern;

@Component(
    service = Servlet.class,
    property = {
        "sling.servlet.paths=/bin/aem-forms-pdf-embed/merge-pdf",
        "sling.servlet.methods=POST"
    }
)
public class PdfDataMergeServlet extends SlingAllMethodsServlet {

    private static final long serialVersionUID = 1L;

    private static final Logger LOG =
        LoggerFactory.getLogger(PdfDataMergeServlet.class);

    private static final String TEMPLATE_ROOT =
        "/content/dam/formsanddocuments/";

    private static final int MAX_REQUEST_BYTES = 1024 * 1024;
    private static final int MAX_XML_DEPTH = 20;

    private static final Pattern XML_NAME =
        Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]*");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Reference
    private OutputService outputService;

    @Override
    protected void doPost(
            SlingHttpServletRequest request,
            SlingHttpServletResponse response) throws IOException {

        response.setHeader("Cache-Control", "no-store");

        try {
            // 1. Read request JSON with a size limit
            byte[] requestBytes =
                readLimited(request.getInputStream(), MAX_REQUEST_BYTES);

            JsonNode body = objectMapper.readTree(requestBytes);

            if (body == null || !body.isObject()) {
                sendError(response, 400, "Invalid JSON request");
                return;
            }

            String templateName =
                body.path("templateName").asText("");

            String rootElement =
                body.path("rootElement").asText("formData");

            JsonNode formData = body.path("formData");

            // 2. Validate required properties
            if (!isAllowedTemplatePath(templateName)) {
                sendError(response, 400, "Invalid XDP template path");
                return;
            }

            if (!isValidXmlPath(rootElement)) {
                sendError(response, 400, "Invalid XML root path");
                return;
            }

            if (!formData.isObject()) {
                sendError(response, 400, "formData must be a JSON object");
                return;
            }

            // 3. Resolve XDP from DAM
            Resource templateResource =
                request.getResourceResolver().getResource(templateName);

            if (templateResource == null ||
                templateResource.adaptTo(Asset.class) == null) {

                sendError(response, 404, "XDP template not found");
                return;
            }

            LOG.info("[PDF Merge] Starting PDF generation");

            // 4. Convert JSON form data into XML
            byte[] xmlBytes = convertJsonToXml(formData, rootElement);

            LOG.debug("[PDF Merge] XML generated: {} bytes",
                xmlBytes.length);

            // 5. Load XDP and XML as Forms Documents
            try (
                Document xdpDocument = new Document(
                    templateName,
                    request.getResourceResolver()
                );
                Document xmlDocument = new Document(xmlBytes)
            ) {
                // 6. Configure Output Service
                PDFOutputOptions options = new PDFOutputOptions();

                options.setAcrobatVersion(
                    AcrobatVersion.Acrobat_10
                );

                // 7. Merge XDP with XML
                try (Document generatedPdf =
                    outputService.generatePDFOutput(
                        xdpDocument,
                        xmlDocument,
                        options
                    )) {

                    if (generatedPdf == null) {
                        throw new IOException(
                            "Output Service returned no PDF"
                        );
                    }

                    // 8. Return generated PDF
                    response.setContentType("application/pdf");
                    response.setHeader(
                        "Content-Disposition",
                        "inline; filename=\"generated.pdf\""
                    );

                    try (InputStream pdfStream =
                             generatedPdf.getInputStream()) {

                        byte[] buffer = new byte[8192];
                        int bytesRead;

                        while ((bytesRead = pdfStream.read(buffer)) != -1) {
                            response.getOutputStream().write(
                                buffer, 0, bytesRead
                            );
                        }
                    }

                    response.getOutputStream().flush();

                    LOG.info("[PDF Merge] PDF generated successfully");
                }
            }

        } catch (IllegalArgumentException e) {
            LOG.warn("[PDF Merge] Invalid request: {}", e.getMessage());
            sendError(response, 400, "Invalid form data");

        } catch (Exception e) {
            LOG.error("[PDF Merge] PDF generation failed", e);

            if (!response.isCommitted()) {
                sendError(response, 500, "PDF generation failed");
            }
        }
    }

    private static boolean isAllowedTemplatePath(String path) {
        return path != null
            && path.startsWith(TEMPLATE_ROOT)
            && path.toLowerCase(java.util.Locale.ROOT).endsWith(".xdp")
            && !path.contains("..")
            && !path.contains("//")
            && !path.contains("\\");
    }

    private static boolean isValidXmlName(String name) {
        return name != null
            && XML_NAME.matcher(name).matches()
            && !name.toLowerCase(java.util.Locale.ROOT)
                .startsWith("xml");
    }

    /**
     * Accepts a single root name (form1) or a slash-delimited XML path
     * (form1/registrationform). Empty segments and invalid XML names are rejected.
     */
    private static boolean isValidXmlPath(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }

        String[] segments = path.split("/", -1);
        if (segments.length > MAX_XML_DEPTH) {
            return false;
        }

        for (String segment : segments) {
            if (!isValidXmlName(segment)) {
                return false;
            }
        }
        return true;
    }

    private byte[] convertJsonToXml(
            JsonNode formData,
            String rootPath) throws Exception {

        DocumentBuilderFactory factory =
            DocumentBuilderFactory.newInstance();

        factory.setFeature(
            XMLConstants.FEATURE_SECURE_PROCESSING, true
        );

        org.w3c.dom.Document xmlDoc =
            factory.newDocumentBuilder().newDocument();

        // Build the XML hierarchy specified in the component's Root Element
        // property. For example: form1/registrationform.
        if (!isValidXmlPath(rootPath)) {
            throw new IllegalArgumentException("Invalid XML root path");
        }

        String[] segments = rootPath.split("/", -1);
        Element current = null;

        for (String segment : segments) {
            Element next = xmlDoc.createElement(segment);
            if (current == null) {
                xmlDoc.appendChild(next);
            } else {
                current.appendChild(next);
            }
            current = next;
        }

        // Add form fields below the last element in the configured path.
        appendJsonFields(xmlDoc, current, formData, segments.length);

        TransformerFactory transformerFactory =
            TransformerFactory.newInstance();

        transformerFactory.setFeature(
            XMLConstants.FEATURE_SECURE_PROCESSING, true
        );

        Transformer transformer =
            transformerFactory.newTransformer();

        transformer.setOutputProperty(
            OutputKeys.ENCODING, "UTF-8"
        );

        transformer.setOutputProperty(
            OutputKeys.INDENT, "yes"
        );

        try (ByteArrayOutputStream output =
                 new ByteArrayOutputStream()) {

            transformer.transform(
                new DOMSource(xmlDoc),
                new StreamResult(output)
            );

            return output.toByteArray();
        }
    }

    private void appendJsonFields(
            org.w3c.dom.Document xmlDoc,
            Element parent,
            JsonNode data,
            int depth) {

        if (depth > MAX_XML_DEPTH) {
            throw new IllegalArgumentException(
                "Form data nesting is too deep"
            );
        }

        Iterator<Map.Entry<String, JsonNode>> fields =
            data.fields();

        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();

            String fieldName = field.getKey();
            JsonNode value = field.getValue();

            if (!isValidXmlName(fieldName)) {
                throw new IllegalArgumentException(
                    "Invalid XML field name"
                );
            }

            appendValue(
                xmlDoc, parent, fieldName, value, depth + 1
            );
        }
    }

    private void appendValue(
            org.w3c.dom.Document xmlDoc,
            Element parent,
            String name,
            JsonNode value,
            int depth) {

        if (depth > MAX_XML_DEPTH) {
            throw new IllegalArgumentException(
                "Form data nesting is too deep"
            );
        }

        if (value.isArray()) {
            for (JsonNode item : value) {
                appendValue(xmlDoc, parent, name, item, depth + 1);
            }
            return;
        }

        Element element = xmlDoc.createElement(name);
        parent.appendChild(element);

        if (value.isObject()) {
            appendJsonFields(xmlDoc, element, value, depth + 1);

        } else if (!value.isNull()) {
            element.setTextContent(value.asText());
        }
    }

    private static byte[] readLimited(
            InputStream input,
            int maxBytes) throws IOException {

        try (ByteArrayOutputStream output =
                 new ByteArrayOutputStream()) {

            byte[] buffer = new byte[8192];
            int count;

            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > maxBytes) {
                    throw new IllegalArgumentException(
                        "Request body exceeds size limit"
                    );
                }
                output.write(buffer, 0, count);
            }

            return output.toByteArray();
        }
    }

    private static void sendError(
            SlingHttpServletResponse response,
            int status,
            String message) throws IOException {

        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        response.getWriter().write(
            "{\"error\":\"" + message + "\"}"
        );
    }
}
