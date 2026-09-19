// Run after: npm install --prefix target/compliance-browser playwright
const { chromium } = require("../target/compliance-browser/node_modules/playwright");
const fs = require("node:fs");
const assert = require("node:assert/strict");

(async () => {
  const browser = await chromium.launch({ channel: "msedge", headless: true });
  try {
    for (const viewport of [{ width: 1440, height: 1000 }, { width: 390, height: 844 }]) {
      for (const path of ["/", "/index.html", "/tenders/123"]) {
        const page = await browser.newPage({ viewport });
        const errors = [];
        page.on("pageerror", error => errors.push(error.message));
        let job = 0, active = false, failPoll = false, posted = false, emptyResult = false;
        const products = [
          { productName: "Ice-lined Refrigerator Large", scheduleNumber: "1", clauseCount: 10,
            pdfDownloadUrl: "/documents/123/1.pdf", docxDownloadUrl: "/documents/123/1.docx" },
          { productName: "Deep Freezer Small", scheduleNumber: "2", clauseCount: 8,
            pdfDownloadUrl: "/documents/123/2.pdf", docxDownloadUrl: "/documents/123/2.docx" }
        ];
        const metrics = {model: "gpt-5-nano", apiAttempts: 4, successfulModelResponses: 4,
          tokens: {input: 1000, output: 500, total: 1500},
          retries: {validation: 0, ocr: 1, split: 0, rateLimit: 0},
          durationsMs: {api: 1200, total: 1800},
          document: {pages: 11, batches: 3, products: 2, clauses: 18},
          estimatedCostInr: 3.3081, warnings: []};
        await page.route("http://compliance.test/**", async route => {
          const url = new URL(route.request().url());
          if (url.pathname === "/compliance-progress.js") return route.fulfill({
            contentType: "text/javascript", body: fs.readFileSync("src/main/resources/static/compliance-progress.js", "utf8")
          });
          if (url.pathname.endsWith("tech-spec-progress")) {
            if (failPoll) return route.fulfill({ status: 503, body: "unavailable" });
            return route.fulfill({ json: { jobId: `job-${job}`, status: active ? "EXTRACTING" : "COMPLETED",
              percent: active ? 30 : 100, message: active ? "Sending PDF batches" : "Previous result",
              totalBatches: 3, completedBatches: active ? 1 : 3,
              products: active ? [] : products, metrics,
              events: [{ stage: "AI", message: "Batch processing" }] } });
          }
          if (url.pathname.endsWith("upload-tech-spec")) {
            posted = true;
            await new Promise(resolve => setTimeout(resolve, 1500)); // Old completed snapshot remains visible briefly.
            job++; active = true;
            await new Promise(resolve => setTimeout(resolve, 2000));
            failPoll = true;
            await new Promise(resolve => setTimeout(resolve, 1300));
            failPoll = false;
            await new Promise(resolve => setTimeout(resolve, 1300));
            active = false;
            return route.fulfill({ json: emptyResult
              ? { success: true, generated: false, products: [], metrics,
                  message: "No products found with technical specifications." }
              : { success: true, generated: true, products, metrics, message: "Sheets ready" } });
          }
          return route.fulfill({ contentType: "text/html", body: `<!doctype html><html><head></head>
            <body><input id="techSpecUpload_123" type="file"><script src="/compliance-progress.js"></script>
            <script>document.querySelector('input').onchange=async e=>{
              const body=new FormData();body.append('file',e.target.files[0]);
              window.result=await (await fetch('/api/tenders/123/upload-tech-spec',{method:'POST',body})).json();
            };</script></body></html>` });
        });
        await page.goto("http://compliance.test" + path);
        for (let upload = 0; upload < 2; upload++) {
          posted = false;
          await page.locator("input").setInputFiles({ name: `specification-${upload}.pdf`,
            mimeType: "application/pdf", buffer: Buffer.alloc(150000, "x") });
          await page.waitForFunction(() => document.querySelector(".cp-upload-value")?.textContent.includes("%"));
          await page.waitForFunction(() => document.querySelector(".cp-message")?.textContent === "Sending PDF batches");
          assert(posted);
          await page.waitForFunction(() => document.querySelector(".cp-warning")?.textContent.includes("503"));
          await page.waitForFunction(() => document.querySelector(".cp-message")?.textContent === "Sheets ready");
          await page.waitForFunction(() => document.querySelector(".cp-upload-value")?.textContent.includes("100%"));
          assert.equal(await page.locator(".cp-links a").count(), 4);
          assert.match(await page.locator(".cp-metrics").innerText(), /₹3\.3081/);
          assert.match(await page.locator(".cp-metrics").innerText(), /Total tokens[\s\S]*1,500/);
          assert.equal(await page.locator("#compliance-progress-panel").count(), 1);
          const bounds = await page.locator("#compliance-progress-panel").boundingBox();
          assert(bounds.x >= 0 && bounds.y >= 0 && bounds.x + bounds.width <= viewport.width);
        }
        emptyResult = true;
        await page.locator("input").setInputFiles({ name: "administrative.pdf",
          mimeType: "application/pdf", buffer: Buffer.alloc(10000, "x") });
        await page.waitForFunction(() => document.querySelector(".cp-message")?.textContent ===
          "No products found with technical specifications.");
        assert.equal(await page.locator(".cp-links a").count(), 0);
        assert.equal(await page.locator(".cp-message.cp-neutral").count(), 1);
        assert.deepEqual(errors, []);
        await page.waitForTimeout(400);
        fs.mkdirSync("target/compliance-browser/screenshots", { recursive: true });
        await page.screenshot({ path: `target/compliance-browser/screenshots/${viewport.width}-${path.replaceAll("/", "_")}.png` });
        await page.close();
        console.log(`PASS ${viewport.width}px ${path}: repeat upload, stale job, poll error, 4 download links`);
      }
    }
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exit(1); });
