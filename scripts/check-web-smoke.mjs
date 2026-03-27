import { readFileSync } from "node:fs";
import { resolve } from "node:path";

const rootDir = resolve(process.cwd());
const htmlPath = resolve(rootDir, "index.html");
const jsPath = resolve(rootDir, "app.js");

const html = readFileSync(htmlPath, "utf8");
const js = readFileSync(jsPath, "utf8");

if (!/<script\s+src="app\.js(?:\?[^"]*)?"><\/script>/i.test(html)) {
  console.error("Smoke test failed: index.html does not load app.js.");
  process.exit(1);
}

const htmlIds = new Set(
  [...html.matchAll(/\sid="([^"]+)"/g)].map((match) => match[1]),
);

const jsDomIds = new Set(
  [...js.matchAll(/getElementById\("([^"]+)"\)/g)].map((match) => match[1]),
);

const missingIds = [...jsDomIds].filter((id) => !htmlIds.has(id));

if (missingIds.length > 0) {
  console.error(
    `Smoke test failed: these DOM ids are used in app.js but missing in index.html: ${missingIds.join(", ")}`,
  );
  process.exit(1);
}

const scriptIndex = html.search(/<script\s+src="app\.js(?:\?[^"]*)?"><\/script>/i);
const bodyEndIndex = html.search(/<\/body>/i);

if (scriptIndex === -1 || bodyEndIndex === -1 || scriptIndex > bodyEndIndex) {
  console.error("Smoke test failed: app.js script tag is not placed correctly in index.html.");
  process.exit(1);
}

console.log("Smoke test passed: index.html and app.js are wired correctly.");
