# Source metadata

- Upstream repository: `Anezium/awesome-rokid`
- Upstream URL: https://github.com/Anezium/awesome-rokid
- Snapshot commit: `934394a95a345f679f1a487205d733a9c4063640`
- Snapshot date: 2026-08-04
- Local copy: `.docs/references/awesome-rokid/`

## What this reference contains

`awesome-rokid` is a community-curated index of Rokid apps, tools, SDKs,
documentation, bridges, experiments, and companion projects. It is primarily a
catalog of links and short descriptions rather than a vendored collection of
all the linked source repositories.

Use the linked projects as candidate references before designing new RokidTerm
features. Do not treat entries as verified compatibility claims; inspect the
upstream project and test against the exact glasses firmware before reusing an
approach.

## Refreshing the snapshot

The local copy is intentionally a plain documentation snapshot so the parent
RokidDev repository does not contain a nested Git repository. To refresh it:

```bash
tmp_dir=$(mktemp -d)
git clone --depth 1 https://github.com/Anezium/awesome-rokid.git "$tmp_dir/awesome-rokid"
cp "$tmp_dir/awesome-rokid/README.md" .docs/references/awesome-rokid/README.md
git -C "$tmp_dir/awesome-rokid" rev-parse HEAD
rm -rf "$tmp_dir"
```

After refreshing, update the commit and snapshot date in this file.
