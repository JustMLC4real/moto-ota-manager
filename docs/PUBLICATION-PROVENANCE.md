# First-publication provenance

Before the first public GitHub push, local user-directory paths in historical build logs were replaced with `[USER_HOME]`. This was applied to the unpublished Git history, so original commit IDs changed. APK bytes and all 203 app/core/Gradle build inputs are unchanged.

| Historical record | Original local commit | Public equivalent |
|---|---|---|
| Source actually used for the clean release build | `198f66c41f49b2b6273d27c8e77485fdb768a42e` | `ea500d8de4a8906e64e8a6826d8535ed5c450940` |
| Completed local delivery and evidence | `c24b78d562ff7af8d982840f864bfb8ca32b4896` | `84c8db6590ab7d67fa8cf15e7a78b7a3dd8d4c69` |

The [complete mapping](PUBLICATION-PROVENANCE.json) is public. Build logs, old delivery records and the tested-input manifest deliberately retain the original IDs actually executed. The public equivalents contain the same compiled inputs after historical-log anonymization; this is not a claim that tests were rerun at a new commit ID.

The input digest remains `dd4896c700aae12517b16a6f8a0ce69bb78ade095dc39f37d5f377aaca2eba02`. The release APK SHA256 remains `e64664cf1ee4a1d218787e64474151298809f5e9961e08505a8f9c341e4a03e8`; debug APK SHA256 remains `323f1521d703bad2bea55e544970ce72f011526b4fce19799b402ccb9cb498d5`.

The final source ZIP includes this explanation and publication links, so its checksum differs from the earlier local archive. Use the current GitHub release's `SHA256SUMS.txt` and `ARTIFACTS.json`. Private pre-publication history backups, release signing material, user firmware and research scratch files are excluded from publication.
