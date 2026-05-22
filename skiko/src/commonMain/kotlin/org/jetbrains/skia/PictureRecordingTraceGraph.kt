package org.jetbrains.skia

import org.jetbrains.skia.impl.Native

data class PictureRecordingTraceGraph(
    val nodes: List<PictureRecordingTraceNode>,
    val rootNodeIndices: List<Int>,
    val totalOperationCount: Int,
    val totalDrawableCount: Int,
    val wrapperCount: Int,
    val payloadLeafCount: Int,
    val renderNodeCount: Int,
    val maxDrawableDepth: Int
)

data class PictureRecordingTraceNode(
    val drawableIndex: Int,
    val generationId: Int,
    val kind: PictureRecordingDrawableKind,
    val depth: Int,
    val parentDrawableIndex: Int?,
    val childDrawableIndices: List<Int>,
    val directOperationIndexRange: IntRange,
    val subtreeOperationIndexRange: IntRange,
    val directOpCount: Int,
    val subtreeOpCount: Int,
    val subtreeLeafCount: Int,
    val subtreeNodeCount: Int,
    val isWrapper: Boolean,
    val isPayloadLeaf: Boolean
)

data class PictureRecordingChunkCandidate(
    val orderedIndex: Int,
    val nodeIndices: List<Int>,
    val operationIndexRange: IntRange,
    val operationCount: Int,
    val stateSetupOperationKinds: List<PictureRecordingOperationKind>,
    val requiresStateSetup: Boolean,
    val hasUnsupportedPictureState: Boolean,
    val isSafeStandalonePicture: Boolean,
    val operationCounts: Map<PictureRecordingOperationKind, Int>,
    val dominantOperationKinds: List<PictureRecordingOperationKind>,
    val reason: PictureRecordingChunkCandidateReason
)

data class PictureRecordingChunkPicture(
    val candidate: PictureRecordingChunkCandidate,
    val picture: Picture?,
    val failureReason: String? = null
)

data class PictureRecordingChunkFootprint(
    val fingerprint: Long,
    val orderedIndex: Int,
    val reason: PictureRecordingChunkCandidateReason,
    val nodeKinds: List<PictureRecordingDrawableKind>,
    val wrapperPattern: List<Boolean>,
    val payloadLeafPattern: List<Boolean>,
    val relativeDepthProfile: List<Int>,
    val directOpCounts: List<Int>,
    val subtreeOpCounts: List<Int>,
    val subtreeLeafCounts: List<Int>,
    val subtreeNodeCounts: List<Int>,
    val operationKindSignature: List<PictureRecordingOperationKind>,
    val operationCounts: Map<PictureRecordingOperationKind, Int>,
    val dominantOperationKinds: List<PictureRecordingOperationKind>,
    val stateSetupOperationKinds: List<PictureRecordingOperationKind>,
    val requiresStateSetup: Boolean,
    val hasUnsupportedPictureState: Boolean
)

data class PictureRecordingChunkCacheEntry(
    val orderedIndex: Int,
    val fingerprint: Long,
    val picture: Picture
)

enum class PictureRecordingChunkCandidateReason {
    LEAF,
    SUBTREE,
    MERGED_NEIGHBORS
}

fun PictureRecordingTraceGraph.orderedNodes(): List<PictureRecordingTraceNode> =
    nodes.sortedBy { it.drawableIndex }

fun PictureRecordingTraceGraph.suggestChunkCandidates(
    operations: List<PictureRecordingOperation>,
    targetChunkCount: Int
): List<PictureRecordingChunkCandidate> {
    if (nodes.isEmpty()) {
        if (operations.isEmpty()) {
            return emptyList()
        }
        val operationCounts = operations.groupingBy { it.kind }.eachCount()
        return listOf(
            PictureRecordingChunkCandidate(
                orderedIndex = 0,
                nodeIndices = emptyList(),
                operationIndexRange = 0 until operations.size,
                operationCount = operations.size,
                stateSetupOperationKinds = emptyList(),
                requiresStateSetup = false,
                hasUnsupportedPictureState = false,
                isSafeStandalonePicture = true,
                operationCounts = operationCounts,
                dominantOperationKinds = operationCounts.entries
                    .sortedByDescending { it.value }
                    .take(3)
                    .map { it.key },
                reason = PictureRecordingChunkCandidateReason.SUBTREE
            )
        )
    }

    val normalizedTargetChunkCount = targetChunkCount.coerceAtLeast(1)
    val selectedRootIndices = rootNodeIndices
        .filter { nodes[it].subtreeOpCount > 0 }
        .toMutableList()

    if (selectedRootIndices.isEmpty()) {
        return listOf(
            PictureRecordingChunkCandidate(
                orderedIndex = 0,
                nodeIndices = emptyList(),
                operationIndexRange = if (operations.isEmpty()) IntRange.EMPTY else (0 until operations.size),
                operationCount = operations.size,
                stateSetupOperationKinds = emptyList(),
                requiresStateSetup = false,
                hasUnsupportedPictureState = false,
                isSafeStandalonePicture = true,
                operationCounts = operations.groupingBy { it.kind }.eachCount(),
                dominantOperationKinds = operations.groupingBy { it.kind }.eachCount().entries
                    .sortedByDescending { it.value }
                    .take(3)
                    .map { it.key },
                reason = PictureRecordingChunkCandidateReason.SUBTREE
            )
        )
    }

    while (selectedRootIndices.size < normalizedTargetChunkCount) {
        val splitIndex = selectedRootIndices.indices
            .filter { findMeaningfulSplitRoots(selectedRootIndices[it]).size > 1 }
            .maxByOrNull { nodes[selectedRootIndices[it]].subtreeOpCount }
            ?: break
        val splitRoots = findMeaningfulSplitRoots(selectedRootIndices[splitIndex])
        selectedRootIndices.removeAt(splitIndex)
        selectedRootIndices.addAll(splitIndex, splitRoots)
    }

    return selectedRootIndices
        .map { rootIndex ->
            buildChunkCandidate(
                graph = this,
                operations = operations,
                nodeIndices = collectSubtreeNodeIndices(rootIndex),
                orderedIndex = rootIndex,
                reason = PictureRecordingChunkCandidateReason.SUBTREE
            )
        }
        .mapIndexed { index, candidate -> candidate.copy(orderedIndex = index) }
        .let { normalizeChunkCandidatesForReplay(operations, it) }
}

private fun PictureRecordingTraceGraph.findMeaningfulSplitRoots(rootIndex: Int): List<Int> {
    var currentRootIndex = rootIndex
    while (true) {
        val meaningfulChildren = nodes[currentRootIndex].childDrawableIndices
            .filter { childIndex -> nodes[childIndex].subtreeOpCount > 0 }
        if (meaningfulChildren.size != 1 || nodes[currentRootIndex].directOpCount > 0) {
            return meaningfulChildren
        }
        currentRootIndex = meaningfulChildren.single()
    }
}

private fun PictureRecordingTraceGraph.normalizeChunkCandidatesForReplay(
    operations: List<PictureRecordingOperation>,
    candidates: List<PictureRecordingChunkCandidate>
): List<PictureRecordingChunkCandidate> {
    if (operations.isEmpty() || candidates.isEmpty()) {
        return candidates
    }

    val sortedCandidates = candidates.sortedBy { it.operationIndexRange.first }
    var previousAdjustedEnd = -1
    val normalizedCoverage = sortedCandidates
        .mapIndexed { index, candidate ->
            val nextCandidateStart = sortedCandidates
                .getOrNull(index + 1)
                ?.operationIndexRange
                ?.first
                ?: operations.size
            val adjustedStart = if (index == 0) 0 else previousAdjustedEnd + 1
            val adjustedEnd = if (index == candidates.lastIndex) {
                operations.lastIndex
            } else {
                maxOf(adjustedStart, nextCandidateStart - 1)
            }
            rebuildChunkCandidate(candidate, operations, adjustedStart..adjustedEnd)
                .also { previousAdjustedEnd = it.operationIndexRange.last }
        }
        .toMutableList()

    var index = 1
    while (index < normalizedCoverage.size) {
        val candidate = normalizedCoverage[index]
        if (!candidate.hasUnsupportedPictureState) {
            index += 1
            continue
        }

        val previousCandidate = normalizedCoverage[index - 1]
        normalizedCoverage[index - 1] = buildChunkCandidate(
            graph = this,
            operations = operations,
            nodeIndices = (previousCandidate.nodeIndices + candidate.nodeIndices).distinct().sorted(),
            orderedIndex = previousCandidate.orderedIndex,
            reason = PictureRecordingChunkCandidateReason.MERGED_NEIGHBORS,
            forcedOperationRange = previousCandidate.operationIndexRange.first..candidate.operationIndexRange.last
        )
        normalizedCoverage.removeAt(index)
    }

    return normalizedCoverage.mapIndexed { normalizedIndex, candidate ->
        candidate.copy(orderedIndex = normalizedIndex)
    }
}

private fun PictureRecordingTraceGraph.rebuildChunkCandidate(
    candidate: PictureRecordingChunkCandidate,
    operations: List<PictureRecordingOperation>,
    operationRange: IntRange
): PictureRecordingChunkCandidate =
    buildChunkCandidate(
        graph = this,
        operations = operations,
        nodeIndices = candidate.nodeIndices,
        orderedIndex = candidate.orderedIndex,
        reason = candidate.reason,
        forcedOperationRange = operationRange
    )

fun buildPictureRecordingTraceGraph(
    operations: List<PictureRecordingOperation>,
    drawables: List<PictureRecordingDrawable>
): PictureRecordingTraceGraph {
    if (drawables.isEmpty()) {
        return PictureRecordingTraceGraph(
            nodes = emptyList(),
            rootNodeIndices = emptyList(),
            totalOperationCount = operations.size,
            totalDrawableCount = 0,
            wrapperCount = 0,
            payloadLeafCount = 0,
            renderNodeCount = 0,
            maxDrawableDepth = 0
        )
    }

    data class MutableNode(
        val drawableIndex: Int,
        val drawable: PictureRecordingDrawable,
        var parentDrawableIndex: Int? = null,
        val childDrawableIndices: MutableList<Int> = mutableListOf(),
        var directOpCount: Int = 0,
        var subtreeOperationStart: Int? = null,
        var subtreeOperationEndExclusive: Int? = null,
        var subtreeOpCount: Int = 0,
        var subtreeLeafCount: Int = 0,
        var subtreeNodeCount: Int = 1,
        var isPayloadLeaf: Boolean = false,
        var isWrapper: Boolean = false
    )

    val mutableNodes = drawables.mapIndexed { index, drawable ->
        MutableNode(
            drawableIndex = index,
            drawable = drawable,
            directOpCount = drawable.operationIndexRange.count()
        )
    }
    val rootNodeIndices = mutableListOf<Int>()
    val parentStack = ArrayDeque<Int>()

    mutableNodes.forEachIndexed { index, node ->
        while (parentStack.isNotEmpty() && mutableNodes[parentStack.last()].drawable.depth >= node.drawable.depth) {
            parentStack.removeLast()
        }

        val parentIndex = parentStack.lastOrNull()
        if (parentIndex == null) {
            rootNodeIndices += index
        } else {
            node.parentDrawableIndex = parentIndex
            mutableNodes[parentIndex].childDrawableIndices += index
        }

        parentStack.addLast(index)
    }

    for (index in mutableNodes.indices.reversed()) {
        val node = mutableNodes[index]
        val directRange = node.drawable.operationIndexRange
        val childIndices = node.childDrawableIndices
        node.isPayloadLeaf = !directRange.isEmpty() && childIndices.none { childIndex ->
            mutableNodes[childIndex].subtreeOpCount > 0
        }
        node.isWrapper = directRange.isEmpty()

        var subtreeStart: Int? = directRange.firstOrNull()
        var subtreeEndExclusive: Int? = directRange.lastOrNull()?.plus(1)
        var subtreeOpCount = node.directOpCount
        var subtreeLeafCount = if (node.isPayloadLeaf) 1 else 0
        var subtreeNodeCount = 1

        childIndices.forEach { childIndex ->
            val child = mutableNodes[childIndex]
            if (child.subtreeOperationStart != null && child.subtreeOperationEndExclusive != null) {
                subtreeStart = minOf(subtreeStart ?: child.subtreeOperationStart!!, child.subtreeOperationStart!!)
                subtreeEndExclusive = maxOf(subtreeEndExclusive ?: child.subtreeOperationEndExclusive!!, child.subtreeOperationEndExclusive!!)
            }
            subtreeOpCount += child.subtreeOpCount
            subtreeLeafCount += child.subtreeLeafCount
            subtreeNodeCount += child.subtreeNodeCount
        }

        node.subtreeOperationStart = subtreeStart
        node.subtreeOperationEndExclusive = subtreeEndExclusive
        node.subtreeOpCount = subtreeOpCount
        node.subtreeLeafCount = subtreeLeafCount
        node.subtreeNodeCount = subtreeNodeCount
    }

    val nodes = mutableNodes.map { node ->
        PictureRecordingTraceNode(
            drawableIndex = node.drawableIndex,
            generationId = node.drawable.generationId,
            kind = node.drawable.kind,
            depth = node.drawable.depth,
            parentDrawableIndex = node.parentDrawableIndex,
            childDrawableIndices = node.childDrawableIndices.toList(),
            directOperationIndexRange = node.drawable.operationIndexRange,
            subtreeOperationIndexRange =
                if (node.subtreeOperationStart == null || node.subtreeOperationEndExclusive == null) {
                    IntRange.EMPTY
                } else {
                    node.subtreeOperationStart!! until node.subtreeOperationEndExclusive!!
                },
            directOpCount = node.directOpCount,
            subtreeOpCount = node.subtreeOpCount,
            subtreeLeafCount = node.subtreeLeafCount,
            subtreeNodeCount = node.subtreeNodeCount,
            isWrapper = node.isWrapper,
            isPayloadLeaf = node.isPayloadLeaf
        )
    }

    return PictureRecordingTraceGraph(
        nodes = nodes,
        rootNodeIndices = rootNodeIndices,
        totalOperationCount = operations.size,
        totalDrawableCount = drawables.size,
        wrapperCount = nodes.count { it.isWrapper },
        payloadLeafCount = nodes.count { it.isPayloadLeaf },
        renderNodeCount = nodes.count { it.kind == PictureRecordingDrawableKind.RENDER_NODE },
        maxDrawableDepth = nodes.maxOfOrNull { it.depth } ?: 0
    )
}

fun Picture.makeChunkPictures(candidates: List<PictureRecordingChunkCandidate>): List<PictureRecordingChunkPicture> =
    candidates.map { candidate ->
        if (!candidate.canMakeStandalonePicture()) {
            PictureRecordingChunkPicture(
                candidate = candidate,
                picture = null,
                failureReason = chunkFailureReason(candidate)
            )
        } else {
            val picturePtr = _nMakeChunkPicture(_ptr, candidate.operationIndexRange.first, candidate.operationIndexRange.last + 1)
            PictureRecordingChunkPicture(
                candidate = candidate,
                picture = if (picturePtr == Native.NullPointer) null else Picture(picturePtr),
                failureReason = if (picturePtr == Native.NullPointer) "unable to extract chunk picture from recorded picture" else null
            )
        }
    }

fun PictureRecorder.makeChunkPictures(
    picture: Picture,
    targetChunkCount: Int
): List<PictureRecordingChunkPicture> =
    suggestChunkCandidates(targetChunkCount).let(picture::makeChunkPictures)

fun PictureRecordingChunkCandidate.canMakeStandalonePicture(): Boolean =
    isSafeStandalonePicture && !hasOnlyDrawableWrapperOperations()

fun PictureRecordingTraceGraph.buildChunkFootprints(
    candidates: List<PictureRecordingChunkCandidate>,
    operations: List<PictureRecordingOperation>
): List<PictureRecordingChunkFootprint> =
    candidates.map { candidate ->
        buildChunkFootprint(candidate, operations)
    }

fun PictureRecorder.buildChunkFootprints(targetChunkCount: Int): List<PictureRecordingChunkFootprint> {
    val operations = recordedOperations
    val graph = traceGraph
    return graph.buildChunkFootprints(
        candidates = graph.suggestChunkCandidates(operations, targetChunkCount),
        operations = operations
    )
}

private fun PictureRecordingTraceGraph.collectSubtreeNodeIndices(rootIndex: Int): List<Int> {
    val collected = mutableListOf<Int>()

    fun walk(index: Int) {
        collected += index
        nodes[index].childDrawableIndices.forEach(::walk)
    }

    walk(rootIndex)
    return collected
}

private fun buildChunkCandidate(
    graph: PictureRecordingTraceGraph,
    operations: List<PictureRecordingOperation>,
    nodeIndices: List<Int>,
    orderedIndex: Int,
    reason: PictureRecordingChunkCandidateReason,
    forcedOperationRange: IntRange? = null
): PictureRecordingChunkCandidate {
    val ranges = nodeIndices.mapNotNull { index ->
        val range = graph.nodes[index].subtreeOperationIndexRange
        if (range.isEmpty()) null else range
    }
    val operationRange = forcedOperationRange ?: when {
        ranges.isEmpty() -> IntRange.EMPTY
        else -> ranges.minOf { it.first } until (ranges.maxOf { it.last } + 1)
    }
    val chunkOperations = if (operationRange.isEmpty()) emptyList() else operations.slice(operationRange)
    val stateSummary = buildChunkStateSummary(operations, operationRange)
    val operationCounts = chunkOperations.groupingBy { it.kind }.eachCount()
    return PictureRecordingChunkCandidate(
        orderedIndex = orderedIndex,
        nodeIndices = nodeIndices,
        operationIndexRange = operationRange,
        operationCount = chunkOperations.size,
        stateSetupOperationKinds = stateSummary.stateSetupOperationKinds,
        requiresStateSetup = stateSummary.requiresStateSetup,
        hasUnsupportedPictureState = stateSummary.hasUnsupportedPictureState,
        isSafeStandalonePicture = !stateSummary.hasUnsupportedPictureState,
        operationCounts = operationCounts,
        dominantOperationKinds = operationCounts.entries
            .sortedByDescending { it.value }
            .take(3)
            .map { it.key },
        reason = reason
    )
}

private fun chunkFailureReason(
    candidate: PictureRecordingChunkCandidate
): String = when {
    candidate.hasOnlyDrawableWrapperOperations() ->
        "unsupported drawDrawable content"

    else -> "unsupported inherited saveLayer state"
}

private fun PictureRecordingChunkCandidate.hasOnlyDrawableWrapperOperations(): Boolean =
    operationCounts.isNotEmpty() &&
        operationCounts.size == 1 &&
        operationCounts.containsKey(PictureRecordingOperationKind.DRAW_DRAWABLE)

private fun PictureRecordingTraceGraph.buildChunkFootprint(
    candidate: PictureRecordingChunkCandidate,
    operations: List<PictureRecordingOperation>
): PictureRecordingChunkFootprint {
    val orderedNodes = candidate.nodeIndices
        .distinct()
        .sortedBy { index -> nodes[index].drawableIndex }
        .map(nodes::get)
    val minDepth = orderedNodes.minOfOrNull(PictureRecordingTraceNode::depth) ?: 0
    val operationKindSignature = if (candidate.operationIndexRange.isEmpty()) {
        emptyList()
    } else {
        operations.slice(candidate.operationIndexRange).map(PictureRecordingOperation::kind)
    }
    val fingerprint = ChunkFingerprintBuilder()
        .add(candidate.orderedIndex)
        .add(candidate.reason.ordinal)
        .addAll(
            orderedNodes
                .filter { it.kind == PictureRecordingDrawableKind.RENDER_NODE }
                .map(PictureRecordingTraceNode::generationId)
        )
        .addAll(orderedNodes.map { it.kind.ordinal })
        .addAll(orderedNodes.map { if (it.isWrapper) 1 else 0 })
        .addAll(orderedNodes.map { if (it.isPayloadLeaf) 1 else 0 })
        .addAll(orderedNodes.map { it.depth - minDepth })
        .addAll(orderedNodes.map(PictureRecordingTraceNode::directOpCount))
        .addAll(orderedNodes.map(PictureRecordingTraceNode::subtreeOpCount))
        .addAll(orderedNodes.map(PictureRecordingTraceNode::subtreeLeafCount))
        .addAll(orderedNodes.map(PictureRecordingTraceNode::subtreeNodeCount))
        .addAll(operationKindSignature.map(PictureRecordingOperationKind::ordinal))
        .addMap(candidate.operationCounts.mapKeys { it.key.ordinal })
        .addAll(candidate.dominantOperationKinds.map(PictureRecordingOperationKind::ordinal))
        .addAll(candidate.stateSetupOperationKinds.map(PictureRecordingOperationKind::ordinal))
        .add(if (candidate.requiresStateSetup) 1 else 0)
        .add(if (candidate.hasUnsupportedPictureState) 1 else 0)
        .build()
    return PictureRecordingChunkFootprint(
        fingerprint = fingerprint,
        orderedIndex = candidate.orderedIndex,
        reason = candidate.reason,
        nodeKinds = orderedNodes.map(PictureRecordingTraceNode::kind),
        wrapperPattern = orderedNodes.map(PictureRecordingTraceNode::isWrapper),
        payloadLeafPattern = orderedNodes.map(PictureRecordingTraceNode::isPayloadLeaf),
        relativeDepthProfile = orderedNodes.map { node -> node.depth - minDepth },
        directOpCounts = orderedNodes.map(PictureRecordingTraceNode::directOpCount),
        subtreeOpCounts = orderedNodes.map(PictureRecordingTraceNode::subtreeOpCount),
        subtreeLeafCounts = orderedNodes.map(PictureRecordingTraceNode::subtreeLeafCount),
        subtreeNodeCounts = orderedNodes.map(PictureRecordingTraceNode::subtreeNodeCount),
        operationKindSignature = operationKindSignature,
        operationCounts = candidate.operationCounts,
        dominantOperationKinds = candidate.dominantOperationKinds,
        stateSetupOperationKinds = candidate.stateSetupOperationKinds,
        requiresStateSetup = candidate.requiresStateSetup,
        hasUnsupportedPictureState = candidate.hasUnsupportedPictureState
    )
}

private class ChunkFingerprintBuilder {
    private var hash = -3750763034362895579L
    private val prime = 1099511628211L

    fun add(value: Int): ChunkFingerprintBuilder {
        hash = (hash xor value.toLong()) * prime
        return this
    }

    fun addAll(values: List<Int>): ChunkFingerprintBuilder {
        add(values.size)
        values.forEach(::add)
        return this
    }

    fun addMap(values: Map<Int, Int>): ChunkFingerprintBuilder {
        add(values.size)
        values.entries.sortedBy { it.key }.forEach { (key, value) ->
            add(key)
            add(value)
        }
        return this
    }

    fun build(): Long = hash
}

private data class ChunkStateSummary(
    val stateSetupOperationKinds: List<PictureRecordingOperationKind>,
    val requiresStateSetup: Boolean,
    val hasUnsupportedPictureState: Boolean
)

private fun buildChunkStateSummary(
    operations: List<PictureRecordingOperation>,
    operationRange: IntRange
): ChunkStateSummary {
    if (operationRange.isEmpty()) {
        return ChunkStateSummary(
            stateSetupOperationKinds = emptyList(),
            requiresStateSetup = false,
            hasUnsupportedPictureState = false
        )
    }

    val prefixStateKinds = mutableListOf<PictureRecordingOperationKind>()
    var activeSaveLayerDepth = 0
    for (index in 0 until operationRange.first) {
        when (val kind = operations[index].kind) {
            PictureRecordingOperationKind.SAVE -> prefixStateKinds += kind
            PictureRecordingOperationKind.SAVE_LAYER -> {
                prefixStateKinds += kind
                activeSaveLayerDepth += 1
            }
            PictureRecordingOperationKind.RESTORE -> {
                if (activeSaveLayerDepth > 0) {
                    activeSaveLayerDepth -= 1
                }
                prefixStateKinds += kind
            }
            PictureRecordingOperationKind.CONCAT,
            PictureRecordingOperationKind.SET_MATRIX,
            PictureRecordingOperationKind.CLIP_RECT,
            PictureRecordingOperationKind.CLIP_RRECT,
            PictureRecordingOperationKind.CLIP_PATH -> prefixStateKinds += kind
            else -> Unit
        }
    }

    return ChunkStateSummary(
        stateSetupOperationKinds = prefixStateKinds,
        requiresStateSetup = prefixStateKinds.isNotEmpty(),
        hasUnsupportedPictureState = activeSaveLayerDepth > 0
    )
}