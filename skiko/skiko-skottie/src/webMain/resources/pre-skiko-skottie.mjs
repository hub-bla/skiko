import {
    loadedWasm,
    loadSkikoExtension as coreLoadSkikoExtension,
    registerSkikoWasmReadyHook,
    awaitSkiko,
} from "./skiko.mjs";

let skottieLoadPromise = null;
const ensureSkottieLoaded = () => {
    if (skottieLoadPromise) return skottieLoadPromise;
    skottieLoadPromise = loadSkikoExtension("/skiko-skottie.wasm");
    return skottieLoadPromise;
};

export const loadSkikoExtension = (url) => coreLoadSkikoExtension(url);

registerSkikoWasmReadyHook(() => ensureSkottieLoaded());

export const skottieSetupRegistered = true;

export { loadedWasm, awaitSkiko };
