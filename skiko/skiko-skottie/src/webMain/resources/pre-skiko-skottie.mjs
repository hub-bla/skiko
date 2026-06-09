import {
    loadedWasm,
    loadSkikoExtension as coreLoadSkikoExtension,
    registerSkikoWasmReadyHook,
    awaitSkiko,
} from "./skiko.mjs";

let skottieLoadPromise = null;
let skottieModuleLoaded = false;

const ensureSkottieLoaded = () => {
    if (skottieLoadPromise) return skottieLoadPromise;
    skottieLoadPromise = loadSkikoExtension("skiko-skottie.wasm").then((result) => {
        skottieModuleLoaded = true;
        return result;
    });
    return skottieLoadPromise;
};

export const loadSkikoExtension = (url) => coreLoadSkikoExtension(url);

registerSkikoWasmReadyHook(() => ensureSkottieLoaded());

export const isSideModuleLoaded = () => skottieModuleLoaded;

export { loadedWasm, awaitSkiko };
