(() => {
  if (window.__complianceProgressLoaded) return;
  window.__complianceProgressLoaded = true;
  const originalFetch = window.fetch.bind(window);
  let timer = null;
  let activeTender = null;
  let uploadState = null;
  let generation = 0;
  let previousJob = null;
  let pollingHeaders = {};
  let pollInFlight = false;
  let latestData = null;
  let baselineKnown = false;

  const style = document.createElement("style");
  style.textContent = `
    #compliance-progress-panel{position:fixed;right:16px;bottom:16px;width:min(460px,calc(100vw - 32px));max-height:85vh;background:#101827;color:#eef2ff;border:1px solid #334155;border-radius:8px;box-shadow:0 8px 24px rgba(0,0,0,.3);z-index:2147483647;font-family:Inter,system-ui,sans-serif;overflow:auto}
    #compliance-progress-panel.cp-inline{position:relative;right:auto;bottom:auto;width:100%;max-height:none;margin-top:10px;box-shadow:none;z-index:auto}
    #compliance-progress-panel *{box-sizing:border-box}#compliance-progress-panel .cp-head{display:flex;justify-content:space-between;align-items:center;padding:15px 16px;border-bottom:1px solid #334155}
    #compliance-progress-panel .cp-title{font-size:14px;font-weight:750}#compliance-progress-panel .cp-close{border:0;background:transparent;color:#94a3b8;font-size:20px;cursor:pointer}
    #compliance-progress-panel .cp-body{padding:14px 16px}#compliance-progress-panel .cp-message{font-size:13px;line-height:1.45;margin-bottom:10px}
    #compliance-progress-panel .cp-track{height:8px;background:#263449;border-radius:8px;overflow:hidden}#compliance-progress-panel .cp-bar{height:100%;width:0;background:linear-gradient(90deg,#2563eb,#22c55e);transition:width .35s ease}
    #compliance-progress-panel .cp-upload{margin-bottom:12px}#compliance-progress-panel .cp-upload-label{display:flex;justify-content:space-between;margin-bottom:6px;color:#cbd5e1;font-size:11px}#compliance-progress-panel .cp-upload-bar{height:100%;width:0;background:#60a5fa;transition:width .15s linear}
    #compliance-progress-panel .cp-meta{display:flex;justify-content:space-between;margin-top:8px;color:#94a3b8;font-size:11px}
    #compliance-progress-panel .cp-events{margin-top:12px;max-height:270px;overflow:auto;border-top:1px solid #263449;padding-top:8px}
    #compliance-progress-panel .cp-event{font-size:11px;line-height:1.4;color:#cbd5e1;padding:5px 0;border-bottom:1px solid rgba(51,65,85,.45)}
    #compliance-progress-panel .cp-stage{color:#60a5fa;font-weight:700;margin-right:5px}#compliance-progress-panel .cp-failed{color:#fca5a5}#compliance-progress-panel .cp-done{color:#86efac}#compliance-progress-panel .cp-neutral{color:#cbd5e1}
    #compliance-progress-panel .cp-links{display:flex;flex-direction:column;gap:8px;margin-top:12px}#compliance-progress-panel .cp-links a{display:inline-block;margin:4px 8px 0 0;padding:6px;border-radius:4px;background:#2563eb;color:white;text-decoration:none;font-size:12px;font-weight:700}
    #compliance-progress-panel .cp-product{font-size:12px;border-top:1px solid #334155;padding-top:6px}
    #compliance-progress-panel .cp-warning{font-size:12px;color:#fbbf24;margin-top:8px}
    #compliance-progress-panel .cp-metrics{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:6px 12px;margin-top:12px;padding:10px;border:1px solid #334155;border-radius:6px;background:#0b1220;font-size:11px;color:#cbd5e1}
    #compliance-progress-panel .cp-metric-title{grid-column:1/-1;color:#f8fafc;font-weight:750;font-size:12px}
    #compliance-progress-panel .cp-metric strong{display:block;color:#f8fafc;font-size:12px;margin-top:2px}
    #compliance-progress-panel .cp-metric-warnings{grid-column:1/-1;color:#fbbf24;line-height:1.35}
  `;
  document.head.appendChild(style);
  document.addEventListener("click", event => {
    const input = event.target;
    if (input instanceof HTMLInputElement && input.type === "file"
        && input.id.startsWith("techSpecUpload_")) input.value = "";
  }, true);

  function panel() {
    let root = document.getElementById("compliance-progress-panel");
    if (root) return root;
    root = document.createElement("section");
    root.id = "compliance-progress-panel";
    root.innerHTML = `<div class="cp-head"><div class="cp-title">Compliance sheet progress</div><button class="cp-close" title="Hide">&times;</button></div><div class="cp-body"><div class="cp-upload" hidden><div class="cp-upload-label"><span>Uploading document</span><span class="cp-upload-value">0%</span></div><div class="cp-track"><div class="cp-upload-bar"></div></div></div><div class="cp-message">Starting upload...</div><div class="cp-track"><div class="cp-bar"></div></div><div class="cp-meta"><span class="cp-percent">0%</span><span class="cp-counts"></span></div><div class="cp-metrics" hidden></div><div class="cp-events"></div><div class="cp-links"></div></div>`;
    root.querySelector(".cp-close").addEventListener("click", () => root.remove());
    // Keep the progress panel outside React-owned subtrees so rerenders cannot remove it.
    document.body.appendChild(root);
    const warning = document.createElement("div");
    warning.className = "cp-warning";
    warning.setAttribute("role", "status");
    root.querySelector(".cp-body").appendChild(warning);
    return root;
  }

  function formatBytes(bytes) {
    if (!Number.isFinite(bytes) || bytes <= 0) return "0 MB";
    return `${(bytes / 1048576).toFixed(bytes < 10485760 ? 1 : 0)} MB`;
  }

  function renderUpload(root) {
    const section = root.querySelector(".cp-upload");
    if (!uploadState) {
      section.hidden = true;
      return;
    }
    section.hidden = false;
    const percent = Math.max(0, Math.min(100, uploadState.percent || 0));
    root.querySelector(".cp-upload-bar").style.width = `${percent}%`;
    root.querySelector(".cp-upload-value").textContent = uploadState.total
      ? `${formatBytes(uploadState.loaded)} / ${formatBytes(uploadState.total)} (${percent}%)`
      : `${formatBytes(uploadState.loaded)} uploaded`;
  }

  function formatDuration(milliseconds) {
    const value = Number(milliseconds || 0);
    if (value < 1000) return `${Math.round(value)} ms`;
    if (value < 60000) return `${(value / 1000).toFixed(1)} s`;
    return `${Math.floor(value / 60000)}m ${Math.round((value % 60000) / 1000)}s`;
  }

  function renderMetrics(root, metrics) {
    const section = root.querySelector(".cp-metrics");
    if (!metrics) {
      section.hidden = true;
      section.replaceChildren();
      return;
    }
    section.hidden = false;
    section.replaceChildren();
    const tokens = metrics.tokens || {};
    const retries = metrics.retries || {};
    const durations = metrics.durationsMs || {};
    const documentCounts = metrics.document || {};
    const values = [
      ["Total tokens", Number(tokens.total || 0).toLocaleString("en-IN")],
      ["Input tokens", `${Number(tokens.input || 0).toLocaleString("en-IN")} (${Number(tokens.cachedInput || 0).toLocaleString("en-IN")} cached)`],
      ["Output tokens", `${Number(tokens.output || 0).toLocaleString("en-IN")} (${Number(tokens.reasoning || 0).toLocaleString("en-IN")} reasoning)`],
      ["API calls", `${metrics.successfulModelResponses || 0}/${metrics.apiAttempts || 0} successful`],
      ["Retries", `${retries.validation || 0} validation, ${retries.ocr || 0} OCR, ${retries.split || 0} split, ${retries.rateLimit || 0} rate limit`],
      ["Document", `${documentCounts.pages || 0} pages, ${documentCounts.batches || 0} batches, ${documentCounts.products || 0} products, ${documentCounts.clauses || 0} clauses`],
      ["AI time", formatDuration(durations.api)],
      ["Extraction / rendering", `${formatDuration(durations.extraction)} / ${formatDuration(durations.rendering)}`],
      ["Total time", formatDuration(durations.total)],
      ["Estimated cost", metrics.estimatedCostInr == null ? "Unavailable" : new Intl.NumberFormat("en-IN", {style: "currency", currency: "INR", minimumFractionDigits: 2, maximumFractionDigits: 4}).format(metrics.estimatedCostInr)],
      ["Model", metrics.model || "gpt-5-nano"]
    ];
    const title = document.createElement("div");
    title.className = "cp-metric-title";
    title.textContent = "AI usage and timing";
    section.appendChild(title);
    values.forEach(([label, value]) => {
      const item = document.createElement("div");
      item.className = "cp-metric";
      item.append(document.createTextNode(label));
      const strong = document.createElement("strong");
      strong.textContent = value;
      item.appendChild(strong);
      section.appendChild(item);
    });
    if (metrics.warnings && metrics.warnings.length) {
      const warning = document.createElement("div");
      warning.className = "cp-metric-warnings";
      warning.textContent = metrics.warnings.join(" ");
      section.appendChild(warning);
    }
  }

  function render(data) {
    latestData = data;
    const root = panel();
    const terminal = data.status === "COMPLETED" || data.status === "FAILED";
    renderUpload(root);
    renderMetrics(root, data.metrics);
    root.querySelector(".cp-message").textContent = data.message || "Processing...";
    root.querySelector(".cp-message").className = "cp-message " +
      (data.status === "FAILED" ? "cp-failed" : data.status === "COMPLETED"
        ? (data.generated === false ? "cp-neutral" : "cp-done") : "");
    const percent = Number.isFinite(data.percent) ? data.percent : 0;
    root.querySelector(".cp-bar").style.width = `${Math.max(0, Math.min(100, percent))}%`;
    root.querySelector(".cp-percent").textContent = `Workflow ${percent}%`;
    const batchText = data.totalBatches ? `Batches ${data.completedBatches || 0}/${data.totalBatches}` : "";
    const clauseText = data.clauses ? `${data.clauses} requirements` : "";
    root.querySelector(".cp-counts").textContent = [batchText, clauseText].filter(Boolean).join(" | ");

    const events = root.querySelector(".cp-events");
    events.replaceChildren();
    (data.events || []).slice(-30).forEach(event => {
      const row = document.createElement("div");
      row.className = "cp-event";
      const stage = document.createElement("span");
      stage.className = "cp-stage";
      stage.textContent = `[${String(event.stage || "WORKING").replaceAll("_", " ")}]`;
      row.append(stage, document.createTextNode(` ${event.message || ""}`));
      events.appendChild(row);
    });
    events.scrollTop = events.scrollHeight;

    const links = root.querySelector(".cp-links");
    links.replaceChildren();
    if (data.products && data.products.length) {
      data.products.forEach(product => {
        const group = document.createElement("div");
        group.className = "cp-product";
        const name = document.createElement("div");
        name.textContent = `Schedule ${product.scheduleNumber}: ${product.productName}`;
        group.appendChild(name);
        group.appendChild(downloadLink(product.pdfDownloadUrl, "PDF"));
        group.appendChild(downloadLink(product.docxDownloadUrl, "Word DOCX"));
        links.appendChild(group);
      });
    } else {
      if (data.pdfDownloadUrl) links.appendChild(downloadLink(data.pdfDownloadUrl, "Download PDF"));
      if (data.docxDownloadUrl) links.appendChild(downloadLink(data.docxDownloadUrl, "Download DOCX"));
    }
    return terminal;
  }

  function downloadLink(href, label) {
    const link = document.createElement("a");
    link.href = href;
    link.textContent = label;
    link.setAttribute("download", "");
    return link;
  }

  async function poll() {
    if (!activeTender || pollInFlight) return;
    const current = generation;
    pollInFlight = true;
    try {
      const response = await originalFetch(`/api/tenders/${encodeURIComponent(activeTender)}/tech-spec-progress`,
        {cache: "no-store", headers: pollingHeaders});
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const data = await response.json();
      if (current !== generation) return;
      panel().querySelector(".cp-warning").textContent = "";
      if (data.status !== "NOT_STARTED" && (!previousJob || data.jobId !== previousJob)) {
        if (!baselineKnown && (data.status === "COMPLETED" || data.status === "FAILED")) return;
        const done = render(data);
        if (done) {
          clearInterval(timer);
          timer = null;
        }
      }
    } catch (error) {
      if (current === generation) panel().querySelector(".cp-warning").textContent =
        `Progress temporarily unavailable (${error.message}). Retrying...`;
    } finally { pollInFlight = false; }
  }

  function xhrUpload(url, init, onProgress) {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open(init.method || "POST", url, true);
      xhr.responseType = "blob";
      const headers = new Headers(init.headers || {});
      headers.forEach((value, name) => xhr.setRequestHeader(name, value));
      xhr.upload.addEventListener("progress", event => {
        const total = event.lengthComputable ? event.total : (uploadState && uploadState.total) || 0;
        const percent = total ? Math.round((event.loaded / total) * 100) : 0;
        onProgress(event.loaded, total, percent);
      });
      xhr.upload.addEventListener("load", () => {
        const total = (uploadState && uploadState.total) || 0;
        onProgress(total, total, 100);
      });
      xhr.onerror = () => reject(new TypeError("Network request failed"));
      xhr.onabort = () => reject(new DOMException("The operation was aborted.", "AbortError"));
      xhr.onload = () => {
        const total = (uploadState && uploadState.total) || 0;
        onProgress(total, total, 100);
        const responseHeaders = new Headers();
        xhr.getAllResponseHeaders().trim().split(/[\r\n]+/).forEach(line => {
          const separator = line.indexOf(":");
          if (separator > 0) responseHeaders.append(line.slice(0, separator).trim(), line.slice(separator + 1).trim());
        });
        resolve(new Response(xhr.response, {status: xhr.status, statusText: xhr.statusText, headers: responseHeaders}));
      };
      if (init.signal) {
        if (init.signal.aborted) return reject(new DOMException("The operation was aborted.", "AbortError"));
        init.signal.addEventListener("abort", () => xhr.abort(), {once: true});
      }
      xhr.send(init.body);
    });
  }

  window.fetch = async (input, init = {}) => {
    const url = typeof input === "string" ? input : input && input.url;
    const match = url && url.match(/\/api\/tenders\/([^/]+)\/upload-tech-spec(?:\?|$)/);
    const method = String(init.method || (input instanceof Request ? input.method : "GET")).toUpperCase();
    if (!match || method !== "POST") return originalFetch(input, init);

    activeTender = decodeURIComponent(match[1]);
    const current = ++generation;
    pollingHeaders = new Headers(init.headers || {});
    pollingHeaders.delete("Content-Type");
    try {
      const token = localStorage.getItem("token");
      if (token && !pollingHeaders.has("Authorization")) pollingHeaders.set("Authorization", `Bearer ${token}`);
    } catch (_) {}
    const file = init.body instanceof FormData ? init.body.get("file") : null;
    uploadState = {loaded: 0, total: file instanceof Blob ? file.size : 0, percent: 0};
    render({status: "UPLOADING", message: "Uploading tender document...", percent: 0, events: []});
    clearInterval(timer);
    try {
      // Capture the previous job before POST so its completed status cannot terminate this upload.
      previousJob = null;
      baselineKnown = false;
      try {
        const baseline = await originalFetch(`/api/tenders/${encodeURIComponent(activeTender)}/tech-spec-progress`,
          {cache: "no-store", headers: pollingHeaders, signal: AbortSignal.timeout(3000)});
        if (baseline.ok) {
          previousJob = (await baseline.json()).jobId || null;
          baselineKnown = true;
        }
      } catch (_) {}
      if (current !== generation) return originalFetch(input, init);
      timer = setInterval(poll, 1000);
      const response = init.body instanceof FormData
        ? await xhrUpload(url, init, (loaded, total, percent) => {
            if (current !== generation) return;
            uploadState = {loaded, total, percent};
            if (latestData && latestData.status !== "UPLOADING") renderUpload(panel());
            else render({status: "UPLOADING", message: percent === 100
              ? "Upload complete. Waiting for extraction..." : "Uploading tender document...", percent: 0, events: []});
          })
        : await originalFetch(input, init);
      if (current !== generation) return response;
      const result = await response.clone().json().catch(() => ({}));
      if (!response.ok || result.success === false) {
        clearInterval(timer); timer = null;
        render({status: "FAILED", message: result.error || `Conversion failed (HTTP ${response.status}).`,
          percent: latestData?.percent || 0, events: latestData?.events || []});
      } else if (result.success) {
        clearInterval(timer); timer = null;
        render({...result, status: "COMPLETED", percent: 100,
          message: result.message, events: latestData?.events || []});
      } else setTimeout(poll, 100);
      return response;
    } catch (error) {
      if (current !== generation) throw error;
      clearInterval(timer);
      timer = null;
      render({status: "FAILED", message: `Upload failed: ${error.message}`, percent: 0, events: []});
      throw error;
    }
  };
})();
