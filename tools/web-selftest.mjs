// Web self-test: the JS scrubber must clean every sample, and must match the Java
// core byte-for-byte (scrubbed file) and line-for-line (findings).
// Usage: node tools/web-selftest.mjs SAMPLES_DIR JAVA_OUT_DIR WEB_OUT_DIR
import { readFileSync, writeFileSync, readdirSync, mkdirSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import { inflateSync } from 'node:zlib';
import { scan, scrub, score, dateInName, setInflate } from '../web/js/face.js';

setInflate(b => inflateSync(b));
const [inDir, javaDir, outDir] = process.argv.slice(2);
mkdirSync(outDir, { recursive: true });
let n = 0, bad = 0;
for (const name of readdirSync(inDir).sort()) {
  const d = new Uint8Array(readFileSync(join(inDir, name)));
  const { clean, before } = scrub(d);
  const after = scan(clean);
  writeFileSync(join(outDir, name), clean);
  const problems = [];
  if (!before.findings.length) problems.push('found no leaks');
  if (after.findings.length) problems.push('LEFT: ' + after.findings.map(f => `${f.cat}:${f.key}`).join(', '));
  const jFile = join(javaDir, name);
  if (existsSync(jFile)) {
    const j = new Uint8Array(readFileSync(jFile));
    if (j.length !== clean.length || j.some((x, i) => x !== clean[i])) problems.push('output differs from Java');
    const jf = readFileSync(jFile + '.findings.txt', 'utf8');
    const wf = before.findings.map(f => `${f.cat}|${f.where}|${f.key}|${f.value}\n`).join('');
    if (jf !== wf) problems.push('findings differ from Java:\n--- java\n' + jf + '--- web\n' + wf);
  } else problems.push('no Java output to compare');
  console.log(`${name.padEnd(40)} ${before.format.padEnd(5)} ${String(score(before.findings)).padStart(3)} -> ${score(after.findings)}  ${problems.length ? 'FAIL ' + problems.join('; ') : 'OK (== Java)'}`);
  if (problems.length) bad++;
  n++;
}
if (dateInName('PXL_20261107_183522.jpg') !== '20261107_183522' || dateInName('holiday.jpg') !== null) { console.log('FAIL date-in-name'); bad++; }
console.log(bad ? `WEB SELF-TEST FAILED (${bad})` : `web core: ${n} photos scrubbed clean, identical to Java`);
process.exit(bad ? 1 : 0);
