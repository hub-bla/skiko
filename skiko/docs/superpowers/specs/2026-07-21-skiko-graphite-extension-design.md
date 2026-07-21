# Skiko Graphite Extension Design

## Goal

Extract the low-level Graphite Native Metal bindings from draft PR #1184 into a separately built and published `skiko-graphite` extension module, following the `skiko-skottie` module model. Keep Skiko core on Ganesh and defer all `SkiaLayer` rendering integration.

## Scope

The module exposes the experimental low-level Graphite objects from the draft: `GraphiteContext`, `Recorder`, `Recording`, Graphite `BackendTexture`, Metal context and texture creation, recording insertion and submission, and creation of a Skia `Surface` from a Graphite backend texture. Its native bridge also retains the recorder image provider used by the draft.

The module depends on Skiko core and links `skia_graphite_ext`. It has its own JVM native library loading and participates in the extension-symbol and publication infrastructure introduced for `skiko-skottie`.

The supported publications are Metal-capable Apple variants only:

- macOS AWT runtime artifacts
- macOS x64 and arm64
- iOS device and simulator variants
- tvOS device and simulator variants

No JS, Wasm, Kotlin/Native Linux, Android, Windows, or Linux AWT Graphite artifacts are published.

## Core Boundary

Graphite implementation files live under `skiko/skiko-graphite`; they do not become part of the core Skiko artifact. Skiko core exposes only the minimum `InternalSkikoApi` needed for an extension module to wrap a native `SkSurface` pointer. Existing extension build wiring is generalized so both Skottie and Graphite contribute required native symbols without coupling core to either extension's API.

## Explicit Exclusions

This refactor does not add a GPU-backend selector, `SkikoFlags`, Graphite context handlers, `SkiaLayer` integration, redrawer changes, automatic frame submission, samples, or end-to-end window rendering. It does not reorganize the existing Ganesh surface bridge, change Skia packaging scripts or versions, or add Dawn, Vulkan, Direct3D, or WebGPU bindings.

## Error and Lifecycle Behavior

Native factory failures return `null` where the Skia factory itself is nullable and otherwise fail at the Kotlin boundary instead of constructing wrappers around null pointers. Graphite wrappers own and release their native objects consistently with existing Skiko `Managed` and `RefCnt` types. Kotlin reachability barriers preserve native inputs across calls.

## Validation

Compile and link the Graphite module for macOS AWT and Kotlin/Native macOS on the local Apple host. Run focused tests for object creation/lifecycle and surface wrapping where a Metal device is available. Verify Gradle publication/task configuration and ensure excluded platform publications are absent. Run affected Skiko and Skottie build checks to catch regressions in the generalized extension wiring.
