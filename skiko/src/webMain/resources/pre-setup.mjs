// This file is merged with skiko.mjs by emcc

export const loadedWasm = {
    _: {}
}

let skikoGl = null;
let readyCoreModule = null;
const wasmReadyHooks = [];

export const registerSkikoWasmReadyHook = (hook) => {
    wasmReadyHooks.push(hook);
};

const extensionLoadPromises = new Map();

export const loadSkikoExtension = (filename) => {
    if (extensionLoadPromises.has(filename)) return extensionLoadPromises.get(filename);
    const loadPromise = awaitSkikoCore.then(async (module) => {
        const absoluteUrl = typeof globalThis.location !== "undefined"
            ? new URL(filename, globalThis.location.href).toString()
            : filename;

        const originalLocateFile = module.locateFile;
        module.locateFile = (path, prefix) => {
            if (path === filename || path.endsWith("/" + filename)) return absoluteUrl;
            return originalLocateFile ? originalLocateFile(path, prefix) : prefix + path;
        };

        const sideModuleExports = {};

        await module.loadDynamicLibrary(filename, {
            loadAsync: true,
            global: false,
            nodelete: true,
            nodeJS: false
        }, sideModuleExports);

        Object.assign(loadedWasm._, sideModuleExports);

        await module.loadDynamicLibrary(filename, {
            loadAsync: true,
            global: true,
            nodelete: true,
            nodeJS: false
        });

        module.locateFile = originalLocateFile;

    }).catch((error) => {
        extensionLoadPromises.delete(filename);
        throw error;
    });

    extensionLoadPromises.set(filename, loadPromise);
    return loadPromise;
};

const awaitSkikoCore = loadSkikoWASM().then((module) => {
    loadedWasm._ = module.wasmExports;
    skikoGl = module.GL;
    readyCoreModule = module;
    return module;
});

// `awaitSkiko` is the public-facing readiness signal. It waits for the core
// module AND for all registered wasm-ready hooks to complete.
export const awaitSkiko = awaitSkikoCore.then(async (module) => {
    for (const hook of wasmReadyHooks) {
        await hook(module);
    }

    return module
});

export const GL = new Proxy({}, {
    get(object, propName) {
        return skikoGl[propName];
    }
})
