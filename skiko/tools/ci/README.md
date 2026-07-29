# Binary size reports

The manual `Skiko Binary Sizes` GitHub Actions workflow builds direct Gradle outputs and renders Markdown tables for their binary payloads. It does not publish artifacts to Maven local or execute native tests.

```bash
./gradlew -p skiko reportArchiveBinarySizes :skiko-skottie:reportArchiveBinarySizes \
  -Pskiko.awt.enabled=true -Pskiko.arch=arm64
./gradlew -p skiko reportKexeBinarySizes :skiko-skottie:reportKexeBinarySizes \
  -Pskiko.native.ios.enabled=true
python3 skiko/tools/ci/aggregate_binary_size_reports.py downloaded-fragments sizes-all.md
```

Each module owns its reports under `build/reports/binary-sizes`: `archives.md` for runtime archives and `executables.md` for linked native tests. Invoke both the root `reportArchiveBinarySizes` or `reportKexeBinarySizes` task and its `:skiko-skottie` counterpart to report both modules.

Set `-PbinarySizeStageDir=<build-relative-directory>` to copy measured payloads below that directory in each module's `build` directory for optional workflow artifact upload. Enable the required platform (`skiko.awt.enabled`, `skiko.android.enabled`, `skiko.wasm.enabled`, or a specific `skiko.native.*.enabled` property); the report consumes exact task outputs and derives platform and architecture from configured targets. Archive reporting builds runtime JARs, while native reporting links `debugTest` executables but does not run them.

The linked iOS test executable reflects link-time dead-code stripping and is more representative than a raw `.klib`, but it is still only a proxy for an application: test framework code adds overhead, and App Store processing and shrinking are not represented.

Run `./gradlew -p skiko buildSrc:build` to check the reporting implementation. Measurement uses the JDK standard library; aggregation uses the Python 3 standard library.