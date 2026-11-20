// Run with:  node --experimental-strip-types test/note.test.mjs
import assert from "node:assert/strict";
import { fromBase64Url, importPrivate, importPublic, makeKeys, readNote, signNote, toBase64Url } from "../functions/licence/note.ts";

const made = await makeKeys();
const signer = await importPrivate(made.privateJwk);
const checker = await importPublic(made.publicSpki);
const device = "0123456789abcdef0123456789abcdef";

const note = await signNote(signer, "umm", "aa79bd01-e3c8-483c-ab36-95b21b21cd94", "FTMQ5", device);
const body = await readNote(checker, note);
assert.equal(body.d, device);
assert.equal(body.h, "FTMQ5");
assert.equal(body.p, "umm");

// Altered, cut short, or signed by somebody else: not ours.
const [signed, signature] = note.split(".");
const other = JSON.parse(new TextDecoder().decode(fromBase64Url(signed)));
other.d = "ffffffffffffffffffffffffffffffff";
const forged = toBase64Url(new TextEncoder().encode(JSON.stringify(other))) + "." + signature;
assert.equal(await readNote(checker, forged), null);
assert.equal(await readNote(checker, signed), null);
assert.equal(await readNote(checker, "nonsense"), null);
const stranger = await makeKeys();
assert.equal(await readNote(await importPublic(stranger.publicSpki), note), null);

// For the apps' own test: a real note and the public key it was signed under.
if (process.argv.includes("--print")) console.log(JSON.stringify({ key: made.publicSpki, note, device }));
console.log("note tests passed");
