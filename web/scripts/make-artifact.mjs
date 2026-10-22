// Turns dist/index.html into a body-only page (dist/artifact.html) for hosting the build as a
// static preview page, and lists the asset files that must be published next to it.
import { readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const dist = new URL('../dist/', import.meta.url).pathname;
const html = readFileSync(join(dist, 'index.html'), 'utf8');
const css = [...html.matchAll(/<link rel="stylesheet"[^>]*href="\.\/(assets\/[^"]+\.css)"/g)].map((m) => m[1]);
const js = [...html.matchAll(/<script type="module"[^>]*src="\.\/(assets\/[^"]+\.js)"/g)].map((m) => m[1]);
const fonts = html.match(/<link\s+rel="stylesheet"\s+href="(https:\/\/fonts\.googleapis\.com[^"]+)"/s)?.[1];

const page = [
  '<title>ReconHub</title>',
  '<meta name="claude:viewport-fit-sides" content="cover">',
  fonts ? `<link rel="stylesheet" href="${fonts}">` : '',
  ...css.map((c) => `<link rel="stylesheet" href="${c}">`),
  '<div id="root"></div>',
  ...js.map((s) => `<script type="module" src="${s}"></script>`),
  '',
].filter(Boolean).join('\n');
writeFileSync(join(dist, 'artifact.html'), page);

// Old-IE (.eot) and SVG font formats are not needed by any current browser.
const assets = readdirSync(join(dist, 'assets')).filter((f) => !/\.(eot|svg)$/.test(f));
writeFileSync(join(dist, 'artifact-files.json'), JSON.stringify(Object.fromEntries(assets.map((f) => [`assets/${f}`, join(dist, 'assets', f)])), null, 2));
console.log(page);
console.log(`${assets.length} asset files`);
