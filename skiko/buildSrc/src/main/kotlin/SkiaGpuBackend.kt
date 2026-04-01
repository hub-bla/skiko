enum class SkiaGpuBackend(val id: String) {
    GANESH("ganesh") {
        override fun preprocessorFlags(os: OS): List<String> = listOf("-DSK_GANESH")
        override fun staticLibraries(os: OS): List<String> = listOf("libskia_ganesh_ext.a")
        override fun hasMetal(): Boolean = true
    },
    // Renamed for internal clarity to distinguish from the user's broad "graphite" request
    GRAPHITE_NATIVE("graphite-native") {
        override fun preprocessorFlags(os: OS): List<String> = listOf("-DSK_GRAPHITE")
        override fun staticLibraries(os: OS): List<String> = listOf("libskia_graphite_ext.a")
        override fun hasMetal(): Boolean = true
    },
    GRAPHITE_DAWN("graphite-dawn") {
        override fun preprocessorFlags(os: OS): List<String> = listOf("-DSK_GRAPHITE", "-DSK_DAWN")
        override fun staticLibraries(os: OS): List<String> = buildList {
            add("libskia_graphite_dawn_ext.a")
            if (os != OS.Wasm) {
                add("libdawn_combined.a")
            }
        }
        override fun isDawn(): Boolean = true
    };

    abstract fun preprocessorFlags(os: OS): List<String>
    open fun staticLibraries(os: OS): List<String> = emptyList()
    open fun isDawn(): Boolean = false
    open fun hasMetal(): Boolean = false
}
