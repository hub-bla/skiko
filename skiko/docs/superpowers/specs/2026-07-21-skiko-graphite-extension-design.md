# Skiko Graphite Extension Design

## Goal

Extract the low-level Graphite bindings from draft PR #1184 into a separately built and published `skiko-graphite` extension module, following the `skiko-skottie` module model. Keep Skiko core on Ganesh and defer all `SkiaLayer` rendering integration.

## Scope

The module exposes the experimental low-level Graphite objects from the draft: `GraphiteContext`, `Recorder`, `Recording`, Graphite `BackendTexture`, Metal context and texture creation, recording insertion and submission, and creation of a Skia `Surface` from a Graphite backend texture. Its native bridge also retains the recorder image provider used by the draft.

The module depends on Skiko core and links `skia_graphite_ext`. It has its own JVM native library loading and participates in the extension-symbol and publication infrastructure introduced for `skiko-skottie`.

The supported publications are:

- AWT API and runtime artifacts for macOS, Linux, and Windows, x64 and arm64
- macOS x64 and arm64
- iOS device and simulator variants
- tvOS device and simulator variants

The desktop JVM bindings compile on every AWT runtime. Metal context and texture entry points remain in the shared AWT API but fail as unsupported on non-Apple runtimes until Direct3D or Vulkan texture bindings are added. No JS, Wasm, Kotlin/Native Linux, or Android Graphite targets are declared or published.

## Core Boundary

Graphite implementation files live under `skiko/skiko-graphite`; they do not become part of the core Skiko artifact. The module is always present in the Gradle project and documentation graph, like `skiko-skottie`, while its Kotlin target declarations remain limited to AWT and Apple native targets. Skiko core exposes only the minimum `InternalSkikoApi` needed for an extension module to wrap a native `SkSurface` pointer. Existing extension build wiring is generalized so both Skottie and Graphite contribute required native symbols without coupling core to either extension's API.

## Explicit Exclusions

This refactor does not add a GPU-backend selector, `SkikoFlags`, Graphite context handlers, `SkiaLayer` integration, redrawer changes, automatic frame submission, samples, or end-to-end window rendering. It does not reorganize the existing Ganesh surface bridge, change Skia packaging scripts or versions, or add Dawn, Vulkan, Direct3D, or WebGPU bindings.

## Error and Lifecycle Behavior

Native factory failures return `null` where the Skia factory itself is nullable and otherwise fail at the Kotlin boundary instead of constructing wrappers around null pointers. On non-Apple JVM runtimes, Metal-only factories report that the backend is unsupported rather than attempting to create Metal objects. Graphite wrappers own and release their native objects consistently with existing Skiko `Managed` and `RefCnt` types. Kotlin reachability barriers preserve native inputs across calls.

## Validation

Compile and link the Graphite module for macOS AWT and Kotlin/Native macOS on the local Apple host. Run focused tests for object creation/lifecycle and surface wrapping where a Metal device is available. Verify that AWT publication metadata contains the standard macOS, Linux, and Windows runtime variants, and that no web or Kotlin/Native Linux target is declared. Run affected Skiko and Skottie build checks to catch regressions in the generalized extension wiring.
