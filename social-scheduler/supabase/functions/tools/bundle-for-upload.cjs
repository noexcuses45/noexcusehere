// Packs the `scheduler` function into ONE file for deploying through a tool
// that takes file contents as text (for example the Supabase MCP connector).
// The normal `supabase functions deploy scheduler` command does not need this:
// it uploads the sources in scheduler/ as they are.
//
// Usage (from this folder):
//   npm install --no-save esbuild terser acorn acorn-walk
//   node tools/bundle-for-upload.cjs [output-dir]
//
// Output:
//   index.js     the single-file function (deploy with entrypoint index.js,
//                JWT verification off)
//   payload.txt  the same file JSON-escaped, one line per source line
//
// The output is the same code as the sources: bundled and minified by
// esbuild, re-printed with single-quoted strings (so it needs almost no
// escaping inside JSON), and wrapped only after complete statements.
const fs = require('fs');
const path = require('path');
const esbuild = require('esbuild');
const { minify } = require('terser');
const acorn = require('acorn');
const walk = require('acorn-walk');

const outDir = path.resolve(process.argv[2] || path.join(__dirname, 'dist'));
const parse = (code) => acorn.parse(code, { ecmaVersion: 'latest', sourceType: 'module' });

// A raw line break inside a template literal means exactly "\n"; write it
// that way so no line break in the file carries meaning.
function foldTemplateNewlines(code) {
  const spans = [];
  walk.full(parse(code), (n) => { if (n.type === 'TemplateElement') spans.push([n.start, n.end]); });
  spans.sort((a, b) => b[0] - a[0]);
  for (const [s, e] of spans) {
    const piece = code.slice(s, e);
    if (piece.includes('\n')) code = code.slice(0, s) + piece.replace(/\r?\n/g, '\\n') + code.slice(e);
  }
  return code.replace(/\n/g, '');
}

(async () => {
  const built = await esbuild.build({
    entryPoints: [path.join(__dirname, '..', 'scheduler', 'index.ts')],
    bundle: true, format: 'esm', platform: 'neutral', target: 'esnext',
    alias: {
      '@supabase/supabase-js': 'npm:@supabase/supabase-js@2',
      '@anthropic-ai/sdk': 'npm:@anthropic-ai/sdk@1',
    },
    external: ['npm:*'], legalComments: 'none', minify: true, charset: 'ascii',
    write: false, logLevel: 'error',
  });
  let code = foldTemplateNewlines(built.outputFiles[0].text);
  const reprinted = await minify(code, {
    module: true, compress: false, mangle: false,
    format: { quote_style: 1, ascii_only: true, comments: false, semicolons: true },
  });
  code = foldTemplateNewlines(reprinted.code);

  const cuts = new Set();
  walk.full(parse(code), (n) => {
    if (n.type === 'Program' || n.type === 'BlockStatement') for (const s of n.body) cuts.add(s.end);
  });
  const lines = [];
  let lineStart = 0, lastCut = -1;
  for (const c of [...cuts].sort((a, b) => a - b)) {
    if (c - lineStart > 800 && lastCut > lineStart) { lines.push(code.slice(lineStart, lastCut)); lineStart = lastCut; }
    lastCut = c;
  }
  lines.push(code.slice(lineStart));

  const file = lines.join('\n') + '\n';
  const payload = lines.map((l) => JSON.stringify(l + '\n').slice(1, -1)).join('\n') + '\n';
  if (JSON.parse('"' + payload.replace(/\n/g, '') + '"') !== file) throw new Error('payload does not round-trip');
  fs.mkdirSync(outDir, { recursive: true });
  fs.writeFileSync(path.join(outDir, 'index.js'), file);
  fs.writeFileSync(path.join(outDir, 'payload.txt'), payload);
  const sha = require('crypto').createHash('sha256').update(file).digest('hex');
  console.log(`${file.length} bytes, ${lines.length} lines, sha256 ${sha}`);
})();
