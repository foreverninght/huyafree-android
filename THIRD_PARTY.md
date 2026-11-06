# Third-party software

Runtime dependencies are resolved from Google Maven and Maven Central; binary JAR files are not checked into this repository.

## AndroidX Media3

- Component: `androidx.media3:media3-exoplayer:1.4.1` and its transitive AndroidX dependencies
- Project: <https://github.com/androidx/media>
- License: Apache License 2.0

Dependency metadata and license files from the resolved artifacts are authoritative. Run Gradle's dependency report to inspect the complete resolved graph:

```powershell
./gradlew.bat app:dependencies
```
