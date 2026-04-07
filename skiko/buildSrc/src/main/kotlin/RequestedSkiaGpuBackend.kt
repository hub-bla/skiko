import org.gradle.api.GradleException

enum class RequestedSkiaGpuBackend(val id: String) {
    GANESH("ganesh"),
    GRAPHITE("graphite"),
    ALL("all");

    companion object {
        fun parse(propertyValue: String?): List<RequestedSkiaGpuBackend> {
            if (propertyValue.isNullOrBlank()) {
                throw GradleException(
                    "At least one skia.gpu.backend should be enabled. " +
                            "Supported backends are: 'ganesh', 'graphite', 'all'. " +
                            "Use -Pskia.gpu.backend=ganesh,graphite or -Pskia.gpu.backend=all"
                )
            }

            return propertyValue.split(",").map { name ->
                val trimmedName = name.trim()
                values().find { it.id == trimmedName }
                    ?: throw GradleException(
                        "Unknown skia.gpu.backend: '$trimmedName'. " +
                                "Supported backends are: 'ganesh', 'graphite', 'all'."
                    )
            }.distinct()
        }
    }

    fun resolveForTarget(os: OS, isNative: Boolean = false): List<SkiaGpuBackend> {
        fun graphiteFor(os: OS, isNative: Boolean): SkiaGpuBackend = when {
            os == OS.Wasm -> SkiaGpuBackend.GRAPHITE_DAWN
            !isNative || os == OS.Linux -> SkiaGpuBackend.GRAPHITE_DAWN
            else -> SkiaGpuBackend.GRAPHITE_NATIVE
        }
        return when (this) {
            GANESH -> listOf(SkiaGpuBackend.GANESH)
            GRAPHITE -> listOf(graphiteFor(os, isNative))
            ALL -> listOf(SkiaGpuBackend.GANESH, graphiteFor(os, isNative))
        }
    }
}

fun List<RequestedSkiaGpuBackend>.resolveForTarget(os: OS, isNative: Boolean = false): List<SkiaGpuBackend> {
    return this
        .flatMap { requested ->
            requested.resolveForTarget(os, isNative)
        }
}