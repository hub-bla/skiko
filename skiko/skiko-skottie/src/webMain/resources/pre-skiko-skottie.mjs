import {
    loadedWasm,
    loadSkikoExtension,
    registerSkikoWasmReadyHook,
} from "./skiko.mjs";

let skottieLoadPromise = null;
const skottieWasm = new URL("./skiko-skottie.wasm", import.meta.url).href;

const ensureSkottieLoaded = () => {
    if (!skottieLoadPromise) {
        skottieLoadPromise = loadSkikoExtension(skottieWasm);
    }
    return skottieLoadPromise;
};

registerSkikoWasmReadyHook(() => ensureSkottieLoaded());

const isSideModuleLoaded = () => skottieLoadPromise !== null;

export { loadedWasm, isSideModuleLoaded };
