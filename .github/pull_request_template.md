Describe the concrete behavior change and its purpose. Nederlands of Engels is welkom.

Validation performed:

- JVM / emulator / release checks, with explicit skips and failures.
- Distinguish real file operations from synthetic device/HTTP/engine observations.
- State whether any physical device was used; never infer permission for a write.

Review before submitting:

- No firmware, private keystore, credentials, raw device logs or signed URLs included.
- Unknown identity, source proof, snapshot or signing state still fails closed.
- Dynamic app_process/JNI/WorkManager contracts remain valid in the release build.
