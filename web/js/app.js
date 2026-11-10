// Scrubgnome-Face (web) - UI glue. Twig's voice lives here.
// Author: kultrshok. MIT License.
import { scan, scrub, score, categories, dateInName, extensionFor, mimeFor, UnsupportedFormatError } from './face.js';

const $ = id => document.getElementById(id);
const INTRO = "Twig here, photo edition! Every picture you take is a little tattletale: where you stood, when, which phone, sometimes even a tiny copy of the shot BEFORE you cropped it. Hand me some photos and I'll make them forget all that. Not one pixel changes, and nothing leaves your phone. This page isn't even ALLOWED to talk to the internet.";

// one entry per picked photo: { file, bytes, report, el, clean, cleanName, url }
let photos = [], busy = false, urls = [];

const twig = s => { $('twig').textContent = s; };
const show = (id, on = true) => $(id).classList.toggle('hidden', !on);
const geekOn = () => $('geek').checked;
const applyGeek = () => document.querySelectorAll('.geek').forEach(g => g.classList.toggle('hidden', !geekOn() || !g.textContent));
const progress = f => { $('prog').value = Math.round(f * 1000); };
const human = n => n < 1024 ? `${n} B` : n < 1048576 ? `${(n / 1024).toFixed(1)} KB` : `${(n / 1048576).toFixed(1)} MB`;
const color = s => s >= 50 ? 'var(--danger)' : s > 0 ? 'var(--warn)' : 'var(--accent)';

function setBusy(b, msg) {
  busy = b;
  for (const id of ['pick', 'scrub']) $(id).disabled = b;
  show('prog', b);
  $('prog').value = 0;
  $('status').classList.remove('err');
  $('status').textContent = msg || '';
}

function fail(msg) {
  setBusy(false);
  $('status').classList.add('err');
  $('status').textContent = msg;
  twig("Well, #@%&. That didn't go to plan. Read the red bit.");
}

function reset() {
  for (const u of urls) URL.revokeObjectURL(u);
  urls = []; photos = [];
  $('list').textContent = '';
  $('downloads').textContent = '';
  for (const id of ['scrub', 'savedcard', 'downloads']) show(id, false);
  $('status').textContent = '';
  $('file').value = '';
  twig(INTRO);
}

const randomHex = n => Array.from(crypto.getRandomValues(new Uint8Array(n)), x => x.toString(16).padStart(2, '0')).join('');

/** Plain-language summary: one line per kind of leak. */
function plainLines(findings, name) {
  const pick = (cat, keys) => findings.filter(f => f.cat === cat && (!keys || keys.some(k => f.key.includes(k))));
  const lines = [];
  const gps = findings.find(f => f.key === 'GPS position');
  const loc = pick('location');
  if (gps) lines.push(['📍', 'Where you were', gps.value]);
  else if (loc.length) lines.push(['📍', 'Where you were', loc.map(f => f.value).join(', ')]);
  const when = findings.find(f => f.key === 'DateTimeOriginal') || findings.find(f => f.cat === 'time');
  if (when) lines.push(['🕒', 'When', when.value]);
  const make = findings.find(f => f.key === 'Make'), model = findings.find(f => f.key === 'Model');
  const dev = pick('device');
  if (make || model) lines.push(['📱', 'What with', [make?.value, model?.value].filter(Boolean).join(' ')]);
  else if (dev.length) lines.push(['📱', 'What with', dev[0].value]);
  const who = pick('identity');
  if (who.length) lines.push(['🪪', 'Who', who.map(f => `${f.key}: ${f.value}`).slice(0, 3).join(' · ')]);
  const extras = [];
  if (findings.some(f => f.key === 'embedded thumbnail')) extras.push('a hidden preview thumbnail');
  if (findings.some(f => /motion photo/.test(f.value))) extras.push('a motion-photo video');
  if (findings.some(f => f.key === 'edit history')) extras.push('edit history');
  if (findings.some(f => f.key === 'comment')) extras.push('a comment');
  if (extras.length) lines.push(['🧩', 'Hidden extras', extras.join(', ')]);
  if (dateInName(name)) lines.push(['🏷️', 'The file name', `"${dateInName(name)}" is a timestamp, so the clean copy gets a random name`]);
  return lines;
}

function renderPhoto(p) {
  const el = document.createElement('div');
  el.className = 'card photo';
  const thumbUrl = URL.createObjectURL(p.file);
  urls.push(thumbUrl);
  const img = document.createElement('img');
  img.alt = '';
  img.src = thumbUrl;
  img.onerror = () => { const ph = document.createElement('div'); ph.className = 'ph'; ph.textContent = p.report?.format ?? '?'; img.replaceWith(ph); };
  const mid = document.createElement('div');
  const nm = document.createElement('div'); nm.className = 'name'; nm.textContent = p.file.name || 'photo';
  const sz = document.createElement('div'); sz.className = 'status'; sz.style.marginTop = '0';
  sz.textContent = `${human(p.file.size)} · ${p.report ? p.report.format.toUpperCase() : 'unreadable'}`;
  mid.append(nm, sz);
  const sc = document.createElement('div'); sc.className = 'score';
  el.append(img, mid, sc);
  if (p.error) {
    const e = document.createElement('div'); e.className = 'clean'; e.style.color = 'var(--danger)'; e.textContent = p.error;
    el.append(e);
  } else {
    const s = score(p.report.findings);
    sc.textContent = s; sc.style.color = color(s);
    const ul = document.createElement('ul'); ul.className = 'leaks';
    const lines = plainLines(p.report.findings, p.file.name);
    if (!lines.length && !p.report.findings.length) {
      const c = document.createElement('div'); c.className = 'clean'; c.textContent = 'Already squeaky clean. Suspicious… but clean.'; el.append(c);
    }
    for (const [icon, label, val] of lines) {
      const li = document.createElement('li');
      const b = document.createElement('b'); b.textContent = `${icon} ${label}: `;
      li.append(b, val);
      ul.append(li);
    }
    if (lines.length) el.append(ul);
    const g = document.createElement('div'); g.className = 'geek hidden';
    g.textContent = p.report.findings.map(f => `› [${f.cat}] ${f.where}: ${f.key} = ${f.value}`).join('\n');
    el.append(g);
  }
  p.el = el;
  $('list').append(el);
}

async function load(fileList) {
  if (busy || !fileList?.length) return;
  const files = [...fileList];          // copy first: reset() empties the live FileList
  reset();
  setBusy(true, 'Sniffing for snitches…');
  let leaky = 0, unreadable = 0;
  for (let i = 0; i < files.length; i++) {
    const file = files[i];
    const p = { file };
    try {
      p.bytes = new Uint8Array(await file.arrayBuffer());
      p.report = scan(p.bytes);
      if (p.report.findings.length) leaky++;
    } catch (e) {
      unreadable++;
      p.error = e instanceof UnsupportedFormatError
        ? `#@%&! ${e.message} I do JPEG, PNG, WebP, GIF, HEIC and AVIF.`
        : `Gah! Couldn't read this one: ${e.message}`;
    }
    photos.push(p);
    renderPhoto(p);
    progress((i + 1) / files.length);
  }
  setBusy(false);
  applyGeek();
  const ok = photos.filter(p => !p.error).length;
  show('scrub', ok > 0);
  $('scrub').textContent = ok === 1 ? '🧹  Scrub & make a clean copy' : `🧹  Scrub all ${ok} & make clean copies`;
  if (!ok) twig("Nothing here I can work on. Photos only, please: JPEG, PNG, WebP, GIF, HEIC or AVIF.");
  else if (!leaky) twig("Huh. Not a single snitch in the bunch. Either you're a pro or someone beat me to it. I'm mildly offended. You can still make fresh copies with random names.");
  else twig(`Great gears! ${leaky === 1 ? 'This photo is' : `${leaky} of these are`} blabbing like tourists. Hit Scrub and I'll make ${leaky === 1 ? 'it' : 'them'} forget everything.${unreadable ? ' (I skipped the ones I couldn\'t read.)' : ''}`);
}

async function doScrub() {
  if (busy) return;
  setBusy(true, 'Scrubbing! Zap… wipe… forget…');
  const todo = photos.filter(p => !p.error);
  const out = [];
  let removed = 0;
  try {
    for (let i = 0; i < todo.length; i++) {
      const p = todo[i];
      const { clean, before } = scrub(p.bytes);
      const verify = scan(clean);
      if (verify.findings.length) throw new Error(`something survived in ${p.file.name}: ${categories(verify.findings).join(', ')}`);
      removed += before.findings.length;
      const name = `photo_${randomHex(3)}${extensionFor(before.format)}`;
      p.clean = new File([clean], name, { type: mimeFor(before.format), lastModified: 0 });
      p.bytes = null;
      out.push(p.clean);
      const sc = p.el.querySelector('.score');
      sc.textContent = `${score(before.findings)} → 0`; sc.style.color = 'var(--accent)';
      const c = document.createElement('div'); c.className = 'clean'; c.textContent = `✔ Clean copy: ${name} (${human(clean.length)})`;
      p.el.append(c);
      progress((i + 1) / todo.length);
      await new Promise(r => setTimeout(r));   // let the page breathe between photos
    }
  } catch (e) {
    fail(`#@%&! ${e.message}. Don't post that one. Tell kultrshok.`);
    return;
  }
  setBusy(false);
  show('scrub', false);
  show('savedcard');
  $('saved').textContent = `Double-checked: zero metadata left in ${out.length} clean cop${out.length === 1 ? 'y' : 'ies'}. Removed ${removed} snitching bits. The pictures themselves are untouched, not one pixel re-encoded.`;
  const canShare = navigator.canShare && navigator.canShare({ files: out });
  show('share', !!canShare);
  const dl = $('downloads');
  dl.textContent = '';
  for (const f of out) {
    const u = URL.createObjectURL(f); urls.push(u);
    const a = document.createElement('a'); a.className = 'dl'; a.href = u; a.download = f.name; a.textContent = `⬇️ ${f.name}`;
    dl.append(a);
  }
  show('downloads', !canShare);
  twig('Ha HA! Squeaky clean! Tap Save / share and pick "Save Image" to put the clean copies in Photos, or send them straight to wherever you were posting.');
}

async function doShare() {
  const files = photos.filter(p => p.clean).map(p => p.clean);
  if (!files.length) return;
  try {
    await navigator.share({ files });
  } catch (e) {
    if (e.name !== 'AbortError') { show('downloads'); $('status').textContent = 'Sharing hiccuped. Use the download links instead.'; }
  }
}

$('pick').addEventListener('click', () => !busy && $('file').click());
$('file').addEventListener('change', e => load(e.target.files));
$('scrub').addEventListener('click', doScrub);
$('share').addEventListener('click', doShare);
$('again').addEventListener('click', reset);
$('geek').addEventListener('change', applyGeek);

// desktop drag-and-drop
const main = $('main');
main.addEventListener('dragover', e => { e.preventDefault(); main.classList.add('drop'); });
main.addEventListener('dragleave', () => main.classList.remove('drop'));
main.addEventListener('drop', e => { e.preventDefault(); main.classList.remove('drop'); load(e.dataTransfer.files); });

reset();

// offline support (works after the first visit, even in airplane mode)
if ('serviceWorker' in navigator && location.protocol === 'https:') {
  navigator.serviceWorker.register('sw.js').catch(() => {});
}
