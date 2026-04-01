import org.gradle.api.GradleException

enum class RequestedSkiaGpuBackend(val id: String) {
    GANESH("ganesh"),
    GRAPHITE("graphite");

    companion object {
        fun parse(propertyValue: String?): List<RequestedSkiaGpuBackend> {
            if (propertyValue.isNullOrBlank()) {
                throw GradleException("At least one skia.gpu.backend should be enabled. Supported backends are: 'ganesh', 'graphite'. Use -Pskia.gpu.backend=ganesh,graphite")
            }

            return propertyValue.split(",").map { name ->
                val trimmedName = name.trim()
                values().find { it.id == trimmedName }
                    ?: throw GradleException("Unknown skia.gpu.backend: '$trimmedName'. Supported backends are: 'ganesh', 'graphite'.")
            }
        }
    }
}

fun List<RequestedSkiaGpuBackend>.resolveForTarget(os: OS, isNative: Boolean = false): List<SkiaGpuBackend> {
    return this.map { requested ->
        when (requested) {
            RequestedSkiaGpuBackend.GANESH -> SkiaGpuBackend.GANESH
            RequestedSkiaGpuBackend.GRAPHITE -> {
                when {
                    os == OS.Wasm -> SkiaGpuBackend.GRAPHITE_DAWN
                    !isNative || os == OS.Linux -> SkiaGpuBackend.GRAPHITE_DAWN
                    else -> SkiaGpuBackend.GRAPHITE_NATIVE // Apple native
                }
            }
        }
    }
}
