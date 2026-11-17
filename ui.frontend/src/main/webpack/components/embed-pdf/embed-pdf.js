
import "./embed-pdf.scss";

const ADOBE_SDK_SRC =
  "https://acrobatservices.adobe.com/view-sdk/viewer.js";

const DATA_MERGE_ENDPOINT =
  "/bin/aem-forms-pdf-embed/merge-pdf";
  
let adobeSdkPromise = null;

// --------------------------------------------------
// Loading indicator
// --------------------------------------------------

function showPdfLoading(component) {
  const viewer = component.querySelector(".embed-pdf__viewer");
  let loading = component.querySelector(".embed-pdf__loading");

  if (!loading) {
    loading = document.createElement("div");
    loading.className = "embed-pdf__loading";
    loading.setAttribute("role", "status");
    loading.setAttribute("aria-live", "polite");
    component.appendChild(loading);
  }

  loading.innerHTML = `
    <div class="embed-pdf__spinner" aria-hidden="true"></div>
    <div class="embed-pdf__loading-title">
      Generating your PDF...
    </div>
    <div class="embed-pdf__loading-message">
      Please wait while we prepare your document.
    </div>
  `;

  loading.setAttribute("role", "status");
  loading.hidden = false;

  if (viewer) {
    viewer.setAttribute("aria-busy", "true");
  }
}

function hidePdfLoading(component) {
  const loading = component.querySelector(".embed-pdf__loading");
  const viewer = component.querySelector(".embed-pdf__viewer");

  if (loading) {
    loading.hidden = true;
  }

  if (viewer) {
    viewer.setAttribute("aria-busy", "false");
  }
}

function showPdfError(component, message) {
  hidePdfLoading(component);

  const loading = component.querySelector(".embed-pdf__loading");

  if (loading) {
    loading.textContent = message;
    loading.setAttribute("role", "alert");
    loading.hidden = false;
  }
}

// --------------------------------------------------
// Adobe PDF Embed SDK
// --------------------------------------------------

function loadAdobeSdk() {
  if (window.AdobeDC) {
    return Promise.resolve();
  }

  if (adobeSdkPromise) {
    return adobeSdkPromise;
  }

  adobeSdkPromise = new Promise((resolve, reject) => {
    let timeout;
    let script;

    function cleanup() {
      clearTimeout(timeout);

      document.removeEventListener(
        "adobe_dc_view_sdk.ready",
        onReady
      );

      if (script) {
        script.removeEventListener("error", onError);
      }
    }

    function onReady() {
      if (window.AdobeDC) {
        cleanup();
        resolve();
      }
    }

    function onError() {
      cleanup();
      reject(new Error("Failed to load Adobe PDF Embed SDK"));
    }

    document.addEventListener(
      "adobe_dc_view_sdk.ready",
      onReady
    );

    timeout = setTimeout(() => {
      cleanup();
      reject(
        new Error("Adobe PDF Embed SDK initialization timed out")
      );
    }, 15000);

    script = document.querySelector(
      `script[src="${ADOBE_SDK_SRC}"]`
    );

    if (!script) {
      script = document.createElement("script");
      script.src = ADOBE_SDK_SRC;
      script.async = true;

      script.addEventListener("error", onError, { once: true });
      document.head.appendChild(script);
    } else {
      script.addEventListener("error", onError, { once: true });
    }

    onReady();
  }).catch((error) => {
    adobeSdkPromise = null;
    throw error;
  });

  return adobeSdkPromise;
}

// --------------------------------------------------
// Component lookup
// --------------------------------------------------

function getPdfViewerComponent() {
  return document.querySelector(
    ".embed-pdf[data-api-key]"
  );
}

// --------------------------------------------------
// Configure viewer height
// --------------------------------------------------

function configureViewerHeight(component) {
  const viewer = component.querySelector(".embed-pdf__viewer");

  if (!viewer) {
    throw new Error("PDF viewer element not found");
  }

  const configuredHeight = Number(
    viewer.dataset.viewerHeight ||
    component.dataset.viewerHeight ||
    800
  );

  const viewerHeight =
    Number.isFinite(configuredHeight) &&
    configuredHeight >= 200
      ? configuredHeight
      : 800;

  viewer.style.height = `${viewerHeight}px`;

  return viewer;
}

// --------------------------------------------------
// Build request payload
// --------------------------------------------------

function buildRequestPayload(component, payload = {}) {
  const generationType =
    component.dataset.pdfGenerationType || "IC";

  const formData = payload.formData;

  if (
    !formData ||
    typeof formData !== "object" ||
    Array.isArray(formData)
  ) {
    throw new Error("Adaptive Form JSON data is required");
  }

  if (generationType === "IC") {
    const documentId = component.dataset.documentId;

    if (!documentId) {
      throw new Error(
        "Document ID is required for Interactive Communication"
      );
    }

    return {
      documentId,
      formData
    };
  }

  if (generationType === "DATA_MERGE") {
    const templateName = component.dataset.templateName;
    const rootElement =
      component.dataset.rootElement || "formData";

    if (!templateName) {
      throw new Error(
        "XDP Template is required for PDF Data Merge"
      );
    }

    return {
      templateName,
      rootElement,
      formData
    };
  }

  throw new Error(
    "Unsupported PDF generation type: " + generationType
  );
}

// --------------------------------------------------
// Validate PDF response
// --------------------------------------------------

async function readPdfResponse(response) {
  if (!response.ok) {
    const errorText = await response.text();

    throw new Error(
      `PDF generation failed: HTTP ${response.status} ` +
      errorText.slice(0, 300)
    );
  }

  const contentType =
    response.headers.get("content-type") || "";

  if (!contentType.toLowerCase().includes("application/pdf")) {
    throw new Error(
      "PDF endpoint returned an unexpected content type: " +
      contentType
    );
  }

  const buffer = await response.arrayBuffer();

  if (buffer.byteLength === 0) {
    throw new Error("Generated PDF is empty");
  }

  console.log(
    "[Embed PDF] PDF received:",
    buffer.byteLength,
    "bytes"
  );

  return buffer;
}

// --------------------------------------------------
// Generate Interactive Communication PDF
// Directly from AEM Communications API
// --------------------------------------------------

async function generateIcPdf(requestPayload, endpoint) {

  if (!endpoint) {
    throw new Error("IC PDF endpoint is not configured");
  }

  console.log("[Embed PDF] Calling IC servlet:", endpoint);

  console.log("[Embed PDF] IC request keys:",
    Object.keys(requestPayload)
  );

  const response = await fetch(endpoint, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "Accept": "application/pdf"
    },
    credentials: "same-origin",
    body: JSON.stringify(requestPayload)
  });

  return readPdfResponse(response);
}
// --------------------------------------------------
// Generate PDF Data Merge
// Using AEM Sling Servlet
// --------------------------------------------------

async function generateDataMergePdf(
  requestPayload,
  endpoint
) {
  const mergeEndpoint = endpoint || DATA_MERGE_ENDPOINT;

  console.log(
    "[Embed PDF] Calling Data Merge endpoint:",
    mergeEndpoint
  );

  console.log("[Embed PDF] Data Merge configuration:", {
    templateName: requestPayload.templateName,
    rootElement: requestPayload.rootElement,
    formDataKeys: Object.keys(requestPayload.formData)
  });

  const response = await fetch(mergeEndpoint, {
    method: "POST",
    headers: {
      "Content-Type": "application/json"
    },
    credentials: "same-origin",
    body: JSON.stringify(requestPayload)
  });

  return readPdfResponse(response);
}

// --------------------------------------------------
// Generate and display PDF
// --------------------------------------------------

async function renderPdf(component, payload) {
  if (!component) {
    throw new Error("Embed PDF component not found");
  }

  const apiKey = component.dataset.apiKey;
  const endpoint = component.dataset.endpoint;

  const pdfFileName =
    component.dataset.pdfFileName || "generated.pdf";

  const embedMode =
    component.dataset.embedMode || "SIZED_CONTAINER";

  const generationType =
    component.dataset.pdfGenerationType || "IC";

  if (!apiKey) {
    throw new Error("Adobe PDF Embed API key is missing");
  }

  const viewer = configureViewerHeight(component);

  if (!viewer.id) {
    throw new Error("PDF viewer ID is missing");
  }

  const requestPayload =
    buildRequestPayload(component, payload);

  console.log(
    "[Embed PDF] Generation type:",
    generationType
  );

  console.log(
    "[Embed PDF] Request keys:",
    Object.keys(requestPayload)
  );

  let buffer;

  // Select generation service
  if (generationType === "IC") {
  buffer = await generateIcPdf(
    requestPayload,
    endpoint
  );

} else if (generationType === "DATA_MERGE") {
  buffer = await generateDataMergePdf(
    requestPayload,
    endpoint
  );
}

  // Display generated PDF
  const view = new window.AdobeDC.View({
    clientId: apiKey,
    divId: viewer.id
  });

  await view.previewFile(
    {
      content: {
        promise: Promise.resolve(buffer)
      },
      metaData: {
        fileName: pdfFileName
      }
    },
    {
      embedMode
    }
  );

  console.log("[Embed PDF] PDF preview initialized");
}

// --------------------------------------------------
// Public function called by Adaptive Form
// --------------------------------------------------

window.renderEmbeddedPdf = function (payload) {
  const component = getPdfViewerComponent();

  if (!component) {
    console.error("[Embed PDF] Component not found");

    return Promise.reject(
      new Error("Embed PDF component not found")
    );
  }

  // Prevent duplicate generation requests
  if (component.dataset.isRendering === "true") {
    console.log(
      "[Embed PDF] Generation already in progress"
    );

    return Promise.resolve();
  }

  component.dataset.isRendering = "true";

  // Show spinner immediately
  showPdfLoading(component);

  return loadAdobeSdk()
    .then(() => renderPdf(component, payload))
    .then(() => {
      hidePdfLoading(component);
    })
    .catch((error) => {
      console.error(
        "[Embed PDF] Generation failed:",
        error
      );

      showPdfError(
        component,
        "Unable to generate the PDF. Please try again."
      );

      throw error;
    })
    .finally(() => {
      component.dataset.isRendering = "false";
    });
};

console.log("embed-pdf.js loaded");
