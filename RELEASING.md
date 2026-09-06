# Releasing

## One-time setup

### 1. Claim the namespace

Sign in to [central.sonatype.com](https://central.sonatype.com), then register the
namespace `com.rrmadon`.

Ownership of `rrmadon.com` is proved with a DNS TXT record. The Portal issues a
verification key; add it as a TXT record on the apex domain, then press verify.
DNS for this domain is on Vercel, so the record goes in the Vercel dashboard under
the domain's DNS settings.

```
Type   TXT
Name   @
Value  <verification key from the Portal>
```

Verification is automated and usually completes within minutes of the record
propagating. The TXT record can be removed afterwards.

Verifying `com.rrmadon` also grants every namespace beneath it, so a future
`com.rrmadon.something` needs no further verification.

### 2. Create a signing key

Central requires a GPG signature beside every published file.

```bash
gpg --full-generate-key          # RSA 4096, no expiry, your real email
gpg --list-secret-keys --keyid-format=long
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
```

Publishing the public key to a keyserver is not optional — Central verifies
signatures against it.

Keep the passphrase in a password manager. Losing it means generating a new key and
re-signing; leaking it means someone else can publish under your name.

### 3. Store credentials

Generate a user token in the Portal under *Account → Generate User Token*, then add
both it and the GPG passphrase to `~/.m2/settings.xml`:

```xml
<settings>
  <servers>
    <server>
      <id>central</id>
      <username>TOKEN_USERNAME</username>
      <password>TOKEN_PASSWORD</password>
    </server>
  </servers>
  <profiles>
    <profile>
      <id>gpg</id>
      <properties>
        <gpg.passphrase>YOUR_PASSPHRASE</gpg.passphrase>
      </properties>
    </profile>
  </profiles>
  <activeProfiles>
    <activeProfile>gpg</activeProfile>
  </activeProfiles>
</settings>
```

This file holds live credentials. It belongs outside the repository, and
`~/.m2/settings.xml` should be readable only by you (`chmod 600`).

## Cutting a release

```bash
./mvnw versions:set -DnewVersion=X.Y.Z -DgenerateBackupPoms=false
./mvnw clean verify                      # full suite, needs a Redis
git commit -am "release: vX.Y.Z" && git tag -a vX.Y.Z -m "Palang vX.Y.Z"
git push origin main && git push origin vX.Y.Z
./mvnw -Prelease clean deploy            # uploads the bundle
```

Then open the Portal, review the deployment, and publish it.

`autoPublish` is deliberately off. **A release on Central is permanent**: it cannot
be deleted, replaced, or re-pointed at a different commit. Confirm the version
number and that the artifacts carry sources, javadoc and signatures before pressing
publish.

## After publishing

Bump the version in `README.md` and cut a GitHub release with notes.

Central propagates to `repo1.maven.org` within about 30 minutes and to search
indexes within a few hours.

## Why JitPack is still in the README

JitPack builds straight from a git tag and needs no signing or account, which makes
it a useful fallback and a way to consume unreleased commits. It is not a substitute
for Central: JitPack builds lazily on first request and caches failures per version
string, so a bad tag stays bad forever.
