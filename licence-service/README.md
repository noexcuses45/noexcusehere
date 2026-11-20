# Unlock codes for Ultimate Media Manager

The apps are free to copy. What is sold is a **code**. This folder is the small online service
that knows which codes exist and which devices each one has been used on.

## How it works

1. Someone opens the app for the first time and types their code.
2. The app sends the code to this service, with a fingerprint of the device.
3. If the code is real and still has a free place, the service answers with a signed note:
   "this code is in use on this device". The app keeps the note and from then on opens by
   itself, with or without the internet.

A code only unlocks a set number of devices (three unless you say otherwise), so handing a code
to friends does not work: once its places are taken, the next device is refused.

About once a week, when the internet happens to be there, an unlocked app asks whether its code
still stands. If the code has been switched off (after a refund, say), the app locks again.
Not being able to reach the service never locks anything.

## What is kept, and what is not

Kept: the codes, a note of your own against each one, and for each device a code is on: an
anonymous fingerprint, which kind of device it is (phone, TV stick, Windows...), the app's
version, and when it was first and last seen.

Not kept: names, e-mail addresses, internet addresses, anything about what people watch. The
fingerprint is made by the app from the device's own identifier in a way that cannot be turned
back, and is the same every time, so putting the app back on the same device does not use up
another place.

The notes are signed with a key that is made inside the database the first time the service
runs and never leaves it. The apps hold the matching public half, which can only check a
signature, not make one.

## Until the service exists

`LicenceConfig` in `mashwave/app/src/main/java/com/noexcusehere/mashwave/licence/Licence.kt`
holds the service's address and public key. While they are empty the apps do not ask for a
code at all.

## Setting it up (Supabase)

1. In the Supabase project, open **SQL Editor**, paste all of `schema.sql` and run it.
2. Deploy the function in `functions/licence/` under the name `licence`, with
   **Verify JWT switched off** (the apps are not signed in to anything; the code is the proof).
3. Open the function's address in a web browser. It shows `{"key":"..."}`: the public key.
4. Put the address and that key into `LicenceConfig` (`ENDPOINT` and `PUBLIC_KEY`), and the
   web address where codes are sold into `BUY_URL`. Build the apps. From that build on, they
   ask for a code.

## Day to day (SQL Editor)

Make five codes, with a note to yourself:

```sql
select * from licence_mint(5, 'November batch');
```

Make one code that unlocks a single device:

```sql
select * from licence_mint(1, 'for Sam', 1);
```

See every code and how many devices it is on:

```sql
select * from licence_overview;
```

Switch a code off (refund), or back on:

```sql
update licence_codes set switched_off = true where code = 'ABCDEFGHJKMNPQR';
```

Free a code's places, when a buyer has a new phone and the old one is gone:

```sql
delete from licence_devices where code_id = (select id from licence_codes where code = 'ABCDEFGHJKMNPQR');
```

(Codes are stored without the dashes.)

## Trying it on your own computer

`test/note.test.mjs` checks the signing. `test/local-server.mjs` runs the service's own code
against a local Postgres that has had `schema.sql` applied, so the apps can be pointed at it:

```
node --experimental-strip-types test/note.test.mjs
PGHOST=127.0.0.1 PGPORT=5432 PGUSER=postgres PGDATABASE=codes \
  node --experimental-strip-types test/local-server.mjs 8086 /somewhere/private/test-key.json
```

## What a code does and does not stop

It stops the ordinary thing: one person buying and everyone they know using it. It does not
stop someone with the skills to take the app apart and remove the check - nothing built into
an app can. Keeping the apps' source code private matters for the same reason.
