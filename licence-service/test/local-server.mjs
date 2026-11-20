// The code service on this computer, for trying the apps against it before anything is online.
// It runs the service's own code (service.ts, note.ts) against a local Postgres that has had
// schema.sql applied, reached through the psql program.
//
//   PGHOST=127.0.0.1 PGPORT=54329 PGUSER=postgres PGDATABASE=codes \
//   node --experimental-strip-types test/local-server.mjs 8086 /somewhere/private/test-key.json
//
// The key file is made on first use. It is a test key: never the real service's.

import { execFile } from "node:child_process";
import { existsSync, readFileSync, writeFileSync } from "node:fs";
import http from "node:http";
import { promisify } from "node:util";
import { importPrivate, importPublic, makeKeys } from "../functions/licence/note.ts";
import { handle } from "../functions/licence/service.ts";

const port = Number(process.argv[2] || 8086);
const keyFile = process.argv[3];
if (!keyFile) throw new Error("say where to keep the test key");
const run = promisify(execFile);

async function sql(text, values) {
  const args = ["-q", "-At", "-v", "ON_ERROR_STOP=1"];
  for (const [name, value] of Object.entries(values)) args.push("-v", `${name}=${value}`);
  // The question goes in on standard input: psql only fills in :'name' values there, not with -c.
  const child = run("psql", args);
  child.child.stdin.end(text + "\n");
  return (await child).stdout.trim();
}

const store = {
  async unlock(code, device, platform, version) {
    return JSON.parse(await sql("select licence_unlock(:'code', :'device', :'platform', :'version');", { code, device, platform, version }));
  },
  async check(codeId, device) {
    return await sql("select licence_check(:'id'::uuid, :'device');", { id: codeId, device });
  },
};

if (!existsSync(keyFile)) writeFileSync(keyFile, JSON.stringify(await makeKeys()));
const kept = JSON.parse(readFileSync(keyFile, "utf8"));
const keys = { signer: await importPrivate(kept.privateJwk), checker: await importPublic(kept.publicSpki), publicSpki: kept.publicSpki };

http
  .createServer(async (request, response) => {
    let text = "";
    for await (const piece of request) text += piece;
    const reply = await handle(request.method, text, store, async () => keys);
    console.log(new Date().toISOString().slice(11, 19), request.method, text.replace(/"note":"[^"]{12}[^"]*"/, '"note":"..."').slice(0, 160), "->", JSON.stringify(reply.body).slice(0, 60));
    response.writeHead(reply.status, { "Content-Type": "application/json", "Cache-Control": "no-store" });
    response.end(JSON.stringify(reply.body));
  })
  .listen(port, "0.0.0.0", () => console.log(`code service (test) on port ${port}; public key: ${kept.publicSpki}`));
