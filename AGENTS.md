<!-- GSD:project-start source:PROJECT.md -->

## Project

**Redraft**

Redraft is an Android PDF editor that performs true content editing — changing existing text and images inside a PDF — rather than just annotation. It renders PDF pages through PDFium (via NDK/JNI) and edits page objects directly, regenerating the content stream on save.

**Core Value:** Editing existing PDF content must actually work. If text or images cannot be changed inside a PDF, nothing else matters.

### Constraints

- **Tech stack**: Kotlin + Jetpack Compose on top of PDFium (via NDK/JNI) — BSD/Apache licensed
- **Platform**: Android, minSdk 26, ABIs `arm64-v8a` plus `x86_64` for the emulator
- **Editing model**: edit PDFium page objects directly; regenerate content on save; no parallel document model

<!-- GSD:project-end -->

<!-- GSD:stack-start source:research/STACK.md -->

## Technology Stack

## Recommended Stack

### Core Technologies

| Technology | Version | Purpose | Why Recommended |
|------------|---------|---------|-----------------|
| Kotlin | 2.4.20 | Primary language | Latest stable; Compose-first, coroutines-first, K2 compiler. Strong Android support; AGP 9.x compatible. (Confidence: HIGH) |
| Jetpack Compose | BOM 2026.09.00 (UI/Material/Graphics etc. via BOM) | UI toolkit | Declarative UI, modern; aligns with 2026 Android recommendations. BOM locks compatible versions. (Confidence: HIGH) |
| Android Gradle Plugin (AGP) | 9.4.x (target) / 9.4.0 stable baseline | Build tool | Required for recent Compose/Kotlin; AGP 9.4 supports API 37 context; Gradle 9.6+ aligned. (Confidence: HIGH) |
| Gradle | 9.6.0+ | Build system | Matches AGP 9.4; Kotlin 2.4.20 compatible up to 9.7 (cf. Kotlin 2.4.20 release). (Confidence: HIGH) |
| Min SDK | 26 (Android 8.0) | Platform minimum | Matches project requirements and allows modern APIs; many PDFium bindings and libraries target API 24+ (some use API 26+). Matches Redraft constraints (minSdk 26). (Confidence: HIGH) |
| Target/Compile SDK | 35 (or 36) | Platform target | Current recommended; AGP 9.4 supports API 37, align with app’s target. (Confidence: HIGH) |
| NDK | 28.2.13676358 (default AGP) or r30 depending on PDFium build | Native PDFium | Needed if bundling custom PDFium; prebuilt AARs (e.g. PdfiumAndroidKt) include native libs (arm64-v8a, x86_64) matching constraints. (Confidence: HIGH) |

### PDF Engine / Binding

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| [io.legere:pdfiumandroid](https://github.com/johngray1965/PdfiumAndroidKt) | 3.0.4-alpha01 | PDFium binding (Kotlin, coroutines, Arrow optional) | Recommended: idiomatic Kotlin, coroutine-friendly, active, Maven Central; supports rendering to bitmaps and low-level page access. Aligns with direct PDFium object access. (Confidence: HIGH) |
| [io.legere:pdfiumandroid-api](https://central.sonatype.com/artifact/io.legere/pdfiumandroid-api) | 3.0.4-alpha01 | API contract | Transitive; use with pdfiumandroid. (Confidence: HIGH) |
| [io.legere:pdfiumandroid-core](https://central.sonatype.com/artifact/io.legere/pdfiumandroid-core) | 3.0.4-alpha01 | Core runtime | Transitive. (Confidence: HIGH) |

### Supporting Libraries

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| androidx.lifecycle:lifecycle-viewmodel-ktx / lifecycle-viewmodel-compose | 2.11.0 | ViewModel + Compose integration + viewModelScope | For page viewer, selection state, undo/redo stack, I/O off UI thread. (Confidence: HIGH) |
| kotlinx.coroutines | 1.9.0+ (transitive via lifecycle/others; check) | Async I/O, page loading, save | Required for suspend APIs (PdfiumAndroidKt suspend API). Use Dispatchers.IO for native/bitmap ops. (Confidence: HIGH) |
| androidx.activity:activity-compose | match Compose BOM | Activity integration, SAF | For ACTION_OPEN_DOCUMENT, recent files. (Confidence: HIGH) |
| androidx.core:core-ktx | latest stable | Extensions | General utilities. (Confidence: HIGH) |
| androidx.graphics:graphics-shapes (optional) | 1.0.0-alpha05 | Canvas shapes/overlays | Only if custom selection/handle UI needs shapes; not required. (Confidence: MEDIUM) |

### Development Tools

| Tool | Purpose | Notes |
|------|---------|-------|
| Android Studio | IDE | Use stable with AGP 9.4+ support (Rabbit/Quail or later). |
| Compose Compiler (Kotlin Plugin) | Compose codegen | Use org.jetbrains.kotlin.plugin.compose aligned to Kotlin 2.4.20 (Compose compiler ships with Kotlin 2.0+). |
| CMake/NDK | Native builds | If using custom PDFium build; prebuilt AAR avoids this. |
| Git | VCS | Standard. |

## Installation

## Alternatives Considered

| Recommended | Alternative | When to Use Alternative |
|-------------|-------------|-------------------------|
| io.legere:pdfiumandroid (Kotlin/coroutines) | [HyntixHQ/KotlinPdfium](https://github.com/HyntixHQ/KotlinPdfium) | Also strong (396 JNI bindings, page editing APIs); check maven availability/maintenance; current latest 1.0.7. Use if API surface better fits specific editing ops. (Confidence: MEDIUM) |
| io.legere:pdfiumandroid | [ComposePdfReader](https://github.com/NucleusFramework/ComposePdfReader) (KMP) | KMP useful if targeting other platforms, but for pure Android Redraft, adds overhead; also depends on bblanchon/pdfium-binaries build pipeline. (Confidence: LOW-MEDIUM) |
| io.legere:pdfiumandroid | [PdfiumAndroid (barteksc)](https://github.com/barteksc/PdfiumAndroid) | Original Java binding; less idiomatic Kotlin/coroutines. Still works; maintenance varies. (Confidence: MEDIUM) |
| io.legere:pdfiumandroid | Android PdfRenderer (platform) | Only read/render, no text/image object editing or content stream regeneration. Cannot edit existing content. Incompatible with requirement. (Confidence: HIGH) |
| io.legere:pdfiumandroid | AndroidX PDF viewer | Viewer-only; no content editing API. Incompatible. (Confidence: HIGH) |

## What NOT to Use

| Avoid | Why | Use Instead |
|-------|-----|-------------|
| `androidx.pdf:pdf-viewer-fragment` / Android PdfRenderer | Designed for viewing only; no true content object editing, no direct content stream mutation API needed for regenerating on save. Cannot select/edit existing text/image objects as required. | PDFium (via binding) with direct page object access. |
| Pure HTML-based PDF “editors” embedded in WebView | WebView overhead, limited integration, harder to map taps to PDFium objects, no first-class Compose overlay. | Native Compose + PDFium. |
| Heavy PDFbox/OpenPDF on Android for large pages without native rasterization | Java-only parsing/rasterization can be slower; PDFbox not optimized for on-device real-time editing overlays. PDFium is native (Chromium). | PDFium binding. |
| Using deprecated PdfiumAndroid forks with no coroutines/NDK updates | Risky for modern AGP/NDK; may lack 16KB alignment, API 35 compatibility. | io.legere:pdfiumandroid 3.0.x or actively maintained fork. |

## Stack Patterns by Variant

- Use `io.legere:pdfiumandroid:3.0.4-alpha01` from Maven Central.
- Keep ABI filters to `arm64-v8a` + `x86_64` as specified.
- Use suspend API for all I/O (Dispatchers.IO).
- Build with NDK 28.2.13676358+, CMake; align with Chromium PDFium version used by binding.
- Ensure 16KB page size support if targeting Android 15+.
- Add native build to Gradle; slower iteration.

## Version Compatibility

| Package A | Compatible With | Notes |
|-----------|-----------------|-------|
| Kotlin 2.4.20 | AGP 9.3–9.4+, Gradle 9.5–9.7 | Matches Compose compiler; KGP/Compose plugin same version. |
| AGP 9.4.0 | Gradle 9.6.0+ | Per Android docs. |
| Compose BOM 2026.09.00 | Kotlin 2.4.20 | Requires KGP+Compose plugin; minSdk 21+ (we use 26). |
| lifecycle 2.11.0 | Compose BOM 2026.09.00, Kotlin 2.4.20 | Stable. |
| pdfiumandroid 3.0.4-alpha01 | Kotlin 2.4.10 in POM (transitive), minSdk varies | AAR supports API 24+ (description), project uses API 26; transitive Kotlin stdlib 2.4.10 is compatible with 2.4.20. |
| kotlinx-coroutines-android 1.9.0 | lifecycle 2.11.0 | Compatible. |

## Sources

- [io.legere/pdfiumandroidkt](https://github.com/johngray1965/PdfiumAndroidKt) — README, tags (3.0.4-alpha01, 2.0.3), Maven Central. Confidence: HIGH.
- Android Developers — [Jetpack Compose setup](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler), [BOM mapping](https://developer.android.com/develop/ui/compose/bom/bom-mapping), [Compose to Kotlin compatibility](https://developer.android.com/jetpack/androidx/releases/compose-kotlin), [Lifecycle 2.11.0 release notes](https://developer.android.com/jetpack/androidx/releases/lifecycle). Confidence: HIGH.
- Kotlin — [Kotlin 2.4.20 release](https://blog.jetbrains.com/kotlin/2026/09/kotlin-2-4-20-released/), [What’s new 2.4.0](https://kotlinlang.org/docs/whatsnew24.html), [KGP/AGP compatibility](https://github.com/jetbrains/kotlin-web-site/blob/master/docs/topics/gradle/gradle-configure-project.md). Confidence: HIGH.
- Android Studio/AGP — [AGP 9.4.0 release notes](https://developer.android.com/build/releases/agp-9-4-0-release-notes), [AGP compatibility table](https://developer.android.com/build/releases/about-agp). Confidence: HIGH.
- Context7 — pdfiumandroidkt, Jetpack Compose, Kotlin docs. Confidence: HIGH.
- PDFium — [core architecture](https://pdfium.googlesource.com/pdfium/+/HEAD/core/), [edit APIs](https://pdfium.googlesource.com/pdfium/+/HEAD/fpdfsdk/fpdf_edittext.cpp). Confidence: HIGH.

<!-- GSD:stack-end -->

<!-- GSD:conventions-start source:CONVENTIONS.md -->

## Conventions

Conventions not yet established. Will populate as patterns emerge during development.
<!-- GSD:conventions-end -->

<!-- GSD:architecture-start source:ARCHITECTURE.md -->

## Architecture

Architecture not yet mapped. Follow existing patterns found in the codebase.
<!-- GSD:architecture-end -->

<!-- GSD:skills-start source:skills/ -->

## Project Skills

No project skills found. Add skills to any of: `.claude/skills/`, `.agents/skills/`, `.cursor/skills/`, `.github/skills/`, or `.codex/skills/` with a `SKILL.md` index file.
<!-- GSD:skills-end -->

<!-- GSD:workflow-start source:GSD defaults -->

## GSD Workflow Enforcement

Before using Edit, Write, or other file-changing tools, start work through a GSD command so planning artifacts and execution context stay in sync.

Use these entry points:

- `/gsd-quick` for small fixes, doc updates, and ad-hoc tasks
- `/gsd-debug` for investigation and bug fixing
- `/gsd-execute-phase` for planned phase work

Do not make direct repo edits outside a GSD workflow unless the user explicitly asks to bypass it.
<!-- GSD:workflow-end -->

<!-- GSD:profile-start -->

## Developer Profile

> Profile not yet configured. Run `/gsd-profile-user` to generate your developer profile.
> This section is managed by `generate-claude-profile` -- do not edit manually.
<!-- GSD:profile-end -->
