# Morphe patches + "Show channel names in comments"

This repository is the official [Morphe patches](https://github.com/MorpheApp/morphe-patches)
with one extra YouTube patch:

**Show channel names in comments** – comment authors are shown by their channel name
(for example "Lara Croft") instead of their @handle. Names are looked up once per channel
(channel RSS feed, YouTube browse as fallback) and cached on the device for 14 days.
The option is in YouTube → Settings → Morphe → General.

Files of the patch:

- `patches/src/main/kotlin/app/morphe/patches/youtube/layout/commentnames/CommentChannelNamesPatch.kt`
- `extensions/youtube/src/main/java/app/morphe/extension/youtube/patches/CommentChannelNamesPatch.java`
- One setting in `Settings.java` and two strings in `values/youtube/strings.xml`.

A daily GitHub Actions job (`.github/workflows/comment-names-release.yml`) merges the
official patches and publishes a new release when anything changed. Morphe Manager reads
`patches-bundle.json` from this repository.

## Add to Morphe Manager

Sources → add → paste `https://github.com/kmax76896-netizen/morphe-patches-comment-names`
