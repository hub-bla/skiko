import {
    loadedWasm,
    loadSkikoExtension,
    registerSkikoWasmReadyHook,
} from "./skiko.mjs";

let skottieLoadPromise = null;
const skottieWasm = "skiko-skottie.wasm";

const ensureSkottieLoaded = () => {
    if (!skottieLoadPromise) {
        skottieLoadPromise = loadSkikoExtension(skottieWasm);
    }
    return skottieLoadPromise;
};

registerSkikoWasmReadyHook(() => ensureSkottieLoaded());

const isSideModuleLoaded = () => skottieLoadPromise !== null;

export { loadedWasm, isSideModuleLoaded };
