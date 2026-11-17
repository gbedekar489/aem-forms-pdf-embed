
/**
 * Adaptive Forms custom function to generate and display
 * a PDF using the AEM Embed PDF component.
 *
 * Invoke this function from the Generate PDF button.
 *
 * Requires:
 * - AEM Adaptive Forms with GuideBridge available
 * - The Embed PDF component on the same page
 * - A configured PDF-generation endpoint
 */
function generateEmbeddedPdf() {
  if (!window.guideBridge) {
    console.error(
      "[Embed PDF] Adaptive Forms GuideBridge is unavailable"
    );
    return;
  }

  if (typeof window.renderEmbeddedPdf !== "function") {
    console.error(
      "[Embed PDF] Embed PDF component is not initialized"
    );
    return;
  }

  window.guideBridge.getFormDataObject({
    success: function(resultObj) {
      try {
        const formData = JSON.parse(resultObj.data.data);

        console.log(
          "[Embed PDF] Adaptive Form data retrieved"
        );

        window.renderEmbeddedPdf({
          formData: formData
        }).catch(function(error) {
          console.error(
            "[Embed PDF] PDF generation failed",
            error
          );
        });

      } catch (error) {
        console.error(
          "[Embed PDF] Unable to parse Adaptive Form data",
          error
        );
      }
    },

    error: function(error) {
      console.error(
        "[Embed PDF] Unable to retrieve Adaptive Form data",
        error
      );
    }
  });
}

window.generateEmbeddedPdf = generateEmbeddedPdf;
