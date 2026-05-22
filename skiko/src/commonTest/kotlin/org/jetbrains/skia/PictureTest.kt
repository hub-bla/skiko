package org.jetbrains.skia

import org.jetbrains.skiko.node.RenderNode
import org.jetbrains.skiko.node.RenderNodeContext
import org.jetbrains.skia.tests.assertCloseEnough
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PictureTest {
    private fun renderPictureBytes(picture: Picture, width: Int, height: Int): ByteArray =
        Surface.makeRasterN32Premul(width, height).use { surface ->
            picture.playback(surface.canvas)
            Bitmap.makeFromImage(surface.makeImageSnapshot()).readPixels()!!
        }

    @Test
    fun canMakeShader() {
        val pic = Picture.makePlaceholder(0.0f, 0.0f, 32.0f, 32.0f)
        val localMatrix = Matrix33(1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f)
        val tile = Rect(0.0f, 0.0f, 16.0f, 16.0f)
        pic.makeShader(FilterTileMode.MIRROR, FilterTileMode.MIRROR, FilterMode.LINEAR)
        pic.makeShader(FilterTileMode.MIRROR, FilterTileMode.MIRROR, FilterMode.LINEAR, localMatrix)
        pic.makeShader(FilterTileMode.MIRROR, FilterTileMode.MIRROR, FilterMode.LINEAR, localMatrix, tile)
    }

    @Test
    fun canGetCullRect() {
        val size = Rect(0.0f, 0.0f, 32.0f, 32.0f)
        val pic = Picture.makePlaceholder(size)
        val cullRect = pic.cullRect
        assertCloseEnough(size, cullRect)
    }


    @Test
    fun canReplay() {
        val size = Rect(0.0f, 0.0f, 32.0f, 32.0f)
        val recorder = PictureRecorder()
        val canvas = recorder.beginRecording(size)
        canvas.drawRect(Rect(10.0f, 10.0f, 20.0f, 20.0f), Paint().apply { color = Color.RED })
        val pic = recorder.finishRecordingAsPicture()
        assertTrue(pic.approximateBytesUsed > 0)
        assertTrue(pic.approximateOpCount > 0)

        val surface = Surface.makeRasterN32Premul(32, 32)
        pic.playback(surface.canvas)
        assertEquals(Color.RED, Bitmap.makeFromImage(surface.makeImageSnapshot()).getColor(15, 15))
    }


    @Test
    fun canReplayWithCallback() {
        val size = Rect(0.0f, 0.0f, 32.0f, 32.0f)
        val recorder = PictureRecorder()
        val canvas = recorder.beginRecording(size)
        canvas.drawRect(Rect(10.0f, 10.0f, 20.0f, 20.0f), Paint().apply { color = Color.RED })
        canvas.drawRect(Rect(12.0f, 12.0f, 18.0f, 18.0f), Paint().apply { color = Color.WHITE })
        val pic = recorder.finishRecordingAsPicture()

        val surface = Surface.makeRasterN32Premul(32, 32)
        var drawCount = 0
        pic.playback(surface.canvas) { drawCount += 1; drawCount > 1 } // Draw only once
        assertEquals(2, drawCount)
        assertEquals(Color.RED, Bitmap.makeFromImage(surface.makeImageSnapshot()).getColor(15, 15))
    }

    @Test
    fun canTraceRecordedOperations() {
        val size = Rect(0.0f, 0.0f, 32.0f, 32.0f)
        val nestedRecorder = PictureRecorder()
        nestedRecorder.beginRecording(size).drawRect(
            Rect(2.0f, 2.0f, 6.0f, 6.0f),
            Paint().apply { color = Color.BLUE }
        )
        val nestedPicture = nestedRecorder.finishRecordingAsPicture()

        val recorder = PictureRecorder()
        val canvas = recorder.beginRecordingWithOperationTrace(size)
        canvas.save()
        canvas.drawRect(Rect(10.0f, 10.0f, 20.0f, 20.0f), Paint().apply { color = Color.RED })
        canvas.drawPicture(nestedPicture)
        canvas.restore()
        recorder.finishRecordingAsPicture()

        assertEquals(
            listOf(
                PictureRecordingOperation(PictureRecordingOperationKind.DRAW_RECT, 0),
                PictureRecordingOperation(PictureRecordingOperationKind.DRAW_RECT, 0)
            ),
            recorder.recordedOperations
        )
        assertEquals(
            listOf(PictureRecordingPicture(nestedPicture.uniqueId, 0)),
            recorder.recordedPictures
        )
    }

    @Test
    fun canReconstructTraceGraphAndSuggestChunks() {
        val operations = listOf(
            PictureRecordingOperation(PictureRecordingOperationKind.DRAW_DRAWABLE, 0),
            PictureRecordingOperation(PictureRecordingOperationKind.DRAW_DRAWABLE, 1),
            PictureRecordingOperation(PictureRecordingOperationKind.SAVE, 2),
            PictureRecordingOperation(PictureRecordingOperationKind.DRAW_RECT, 2),
            PictureRecordingOperation(PictureRecordingOperationKind.RESTORE, 1),
            PictureRecordingOperation(PictureRecordingOperationKind.DRAW_DRAWABLE, 1),
            PictureRecordingOperation(PictureRecordingOperationKind.DRAW_PATH, 1)
        )
        val drawables = listOf(
            PictureRecordingDrawable(PictureRecordingDrawableKind.RENDER_NODE, 1, 0, IntRange.EMPTY),
            PictureRecordingDrawable(PictureRecordingDrawableKind.UNKNOWN, 2, 1, IntRange.EMPTY),
            PictureRecordingDrawable(PictureRecordingDrawableKind.UNKNOWN, 3, 2, 2..4),
            PictureRecordingDrawable(PictureRecordingDrawableKind.UNKNOWN, 4, 1, 5..6)
        )

        val graph = buildPictureRecordingTraceGraph(operations, drawables)

        assertEquals(listOf(0), graph.rootNodeIndices)
        assertEquals(1, graph.renderNodeCount)
        assertEquals(2, graph.payloadLeafCount)
        assertEquals(2, graph.nodes[0].childDrawableIndices.size)
        assertEquals(0, graph.nodes[1].parentDrawableIndex)
        assertEquals(1, graph.nodes[2].parentDrawableIndex)
        assertTrue(graph.nodes[2].isPayloadLeaf)
        assertEquals(3, graph.nodes[2].subtreeOpCount)
        assertEquals(4, graph.nodes[1].subtreeOperationIndexRange.last)
        assertEquals(2, graph.nodes[0].subtreeLeafCount)

        val chunks = graph.suggestChunkCandidates(operations, targetChunkCount = 1)

        assertEquals(1, chunks.size)
        assertEquals(listOf(0, 1, 2, 3), chunks.single().nodeIndices)
        assertEquals(0..6, chunks.single().operationIndexRange)
        assertEquals(7, chunks.single().operationCount)
        assertTrue(!chunks.single().requiresStateSetup)
        assertTrue(chunks.single().isSafeStandalonePicture)
        assertEquals(PictureRecordingChunkCandidateReason.SUBTREE, chunks.single().reason)
        assertTrue(chunks.single().dominantOperationKinds.contains(PictureRecordingOperationKind.DRAW_DRAWABLE))
    }

    @Test
    fun recorderExposesTraceGraphHelpers() {
        val size = Rect(0.0f, 0.0f, 32.0f, 32.0f)
        val recorder = PictureRecorder()
        val canvas = recorder.beginRecordingWithOperationTrace(size)
        canvas.drawRect(Rect(1.0f, 1.0f, 10.0f, 10.0f), Paint().apply { color = Color.RED })
        recorder.finishRecordingAsPicture()

        val graph = recorder.traceGraph
        val chunks = recorder.suggestChunkCandidates(targetChunkCount = 1)

        assertTrue(graph.totalOperationCount >= 1)
        assertEquals(recorder.recordedDrawables.size, graph.totalDrawableCount)
        assertEquals(1, chunks.size)
        assertEquals(recorder.recordedOperations.size, chunks.single().operationCount)
    }

    @Test
    fun chunkFootprintsRequireExactStructuralAndOperationMatch() {
        val baselineOperations = listOf(
            PictureRecordingOperation(PictureRecordingOperationKind.DRAW_DRAWABLE, 0),
            PictureRecordingOperation(PictureRecordingOperationKind.SAVE, 1),
            PictureRecordingOperation(PictureRecordingOperationKind.DRAW_RECT, 1),
            PictureRecordingOperation(PictureRecordingOperationKind.RESTORE, 0)
        )
        val changedOperations = listOf(
            PictureRecordingOperation(PictureRecordingOperationKind.DRAW_DRAWABLE, 0),
            PictureRecordingOperation(PictureRecordingOperationKind.SAVE, 1),
            PictureRecordingOperation(PictureRecordingOperationKind.DRAW_PATH, 1),
            PictureRecordingOperation(PictureRecordingOperationKind.RESTORE, 0)
        )
        val drawables = listOf(
            PictureRecordingDrawable(PictureRecordingDrawableKind.RENDER_NODE, 1, 0, IntRange.EMPTY),
            PictureRecordingDrawable(PictureRecordingDrawableKind.UNKNOWN, 2, 1, 1..3)
        )
        val changedDrawables = listOf(
            PictureRecordingDrawable(PictureRecordingDrawableKind.RENDER_NODE, 1, 0, IntRange.EMPTY),
            PictureRecordingDrawable(PictureRecordingDrawableKind.RENDER_NODE, 2, 1, 1..3)
        )

        val baselineGraph = buildPictureRecordingTraceGraph(baselineOperations, drawables)
        val baselineChunk = baselineGraph.suggestChunkCandidates(baselineOperations, targetChunkCount = 1).single()
        val baselineFootprint = baselineGraph.buildChunkFootprints(listOf(baselineChunk), baselineOperations).single()
        val sameFootprint = baselineGraph.buildChunkFootprints(listOf(baselineChunk), baselineOperations).single()

        val changedOperationGraph = buildPictureRecordingTraceGraph(changedOperations, drawables)
        val changedOperationChunk = changedOperationGraph.suggestChunkCandidates(changedOperations, targetChunkCount = 1).single()
        val changedOperationFootprint = changedOperationGraph
            .buildChunkFootprints(listOf(changedOperationChunk), changedOperations)
            .single()

        val changedShapeGraph = buildPictureRecordingTraceGraph(baselineOperations, changedDrawables)
        val changedShapeChunk = changedShapeGraph.suggestChunkCandidates(baselineOperations, targetChunkCount = 1).single()
        val changedShapeFootprint = changedShapeGraph
            .buildChunkFootprints(listOf(changedShapeChunk), baselineOperations)
            .single()

        assertEquals(baselineFootprint, sameFootprint)
        assertNotEquals(baselineFootprint, changedOperationFootprint)
        assertNotEquals(baselineFootprint, changedShapeFootprint)
        assertEquals(
            listOf(
                PictureRecordingOperationKind.DRAW_DRAWABLE,
                PictureRecordingOperationKind.SAVE,
                PictureRecordingOperationKind.DRAW_RECT,
                PictureRecordingOperationKind.RESTORE
            ),
            baselineFootprint.operationKindSignature
        )
    }

    @Test
    fun chunkFootprintsChangeWhenRenderNodeGenerationIdsChange() {
        val operations = listOf(
            PictureRecordingOperation(PictureRecordingOperationKind.DRAW_DRAWABLE, 0),
            PictureRecordingOperation(PictureRecordingOperationKind.DRAW_RECT, 1)
        )
        val baselineDrawables = listOf(
            PictureRecordingDrawable(PictureRecordingDrawableKind.RENDER_NODE, 1, 0, IntRange.EMPTY),
            PictureRecordingDrawable(PictureRecordingDrawableKind.UNKNOWN, 2, 1, 1..1)
        )
        val changedGenerationDrawables = listOf(
            PictureRecordingDrawable(PictureRecordingDrawableKind.RENDER_NODE, 101, 0, IntRange.EMPTY),
            PictureRecordingDrawable(PictureRecordingDrawableKind.UNKNOWN, 202, 1, 1..1)
        )

        val baselineGraph = buildPictureRecordingTraceGraph(operations, baselineDrawables)
        val baselineChunk = baselineGraph.suggestChunkCandidates(operations, targetChunkCount = 1).single()
        val baselineFootprint = baselineGraph.buildChunkFootprints(listOf(baselineChunk), operations).single()

        val changedGenerationGraph = buildPictureRecordingTraceGraph(operations, changedGenerationDrawables)
        val changedGenerationChunk = changedGenerationGraph.suggestChunkCandidates(operations, targetChunkCount = 1).single()
        val changedGenerationFootprint = changedGenerationGraph
            .buildChunkFootprints(listOf(changedGenerationChunk), operations)
            .single()

        assertNotEquals(baselineFootprint.fingerprint, changedGenerationFootprint.fingerprint)
        assertNotEquals(baselineFootprint, changedGenerationFootprint)
    }

    @Test
    fun chunkFootprintsIgnoreUnknownDrawableGenerationIds() {
        val operations = listOf(
            PictureRecordingOperation(PictureRecordingOperationKind.DRAW_DRAWABLE, 0),
            PictureRecordingOperation(PictureRecordingOperationKind.DRAW_RECT, 1)
        )
        val baselineDrawables = listOf(
            PictureRecordingDrawable(PictureRecordingDrawableKind.RENDER_NODE, 1, 0, IntRange.EMPTY),
            PictureRecordingDrawable(PictureRecordingDrawableKind.UNKNOWN, 2, 1, 1..1)
        )
        val changedUnknownGenerationDrawables = listOf(
            PictureRecordingDrawable(PictureRecordingDrawableKind.RENDER_NODE, 1, 0, IntRange.EMPTY),
            PictureRecordingDrawable(PictureRecordingDrawableKind.UNKNOWN, 202, 1, 1..1)
        )

        val baselineGraph = buildPictureRecordingTraceGraph(operations, baselineDrawables)
        val baselineChunk = baselineGraph.suggestChunkCandidates(operations, targetChunkCount = 1).single()
        val baselineFootprint = baselineGraph.buildChunkFootprints(listOf(baselineChunk), operations).single()

        val changedUnknownGenerationGraph = buildPictureRecordingTraceGraph(operations, changedUnknownGenerationDrawables)
        val changedUnknownGenerationChunk = changedUnknownGenerationGraph
            .suggestChunkCandidates(operations, targetChunkCount = 1)
            .single()
        val changedUnknownGenerationFootprint = changedUnknownGenerationGraph
            .buildChunkFootprints(listOf(changedUnknownGenerationChunk), operations)
            .single()

        assertEquals(baselineFootprint.fingerprint, changedUnknownGenerationFootprint.fingerprint)
        assertEquals(baselineFootprint, changedUnknownGenerationFootprint)
    }

    @Test
    fun chunkSuggestionsCoverPrefixStateAndMergeAcrossInheritedSaveLayer() {
        val operations = listOf(
            PictureRecordingOperation(PictureRecordingOperationKind.SAVE_LAYER, 0),
            PictureRecordingOperation(PictureRecordingOperationKind.DRAW_DRAWABLE, 1),
            PictureRecordingOperation(PictureRecordingOperationKind.DRAW_RECT, 1),
            PictureRecordingOperation(PictureRecordingOperationKind.DRAW_DRAWABLE, 1),
            PictureRecordingOperation(PictureRecordingOperationKind.DRAW_RECT, 1),
            PictureRecordingOperation(PictureRecordingOperationKind.RESTORE, 0)
        )
        val drawables = listOf(
            PictureRecordingDrawable(PictureRecordingDrawableKind.RENDER_NODE, 1, 0, IntRange.EMPTY),
            PictureRecordingDrawable(PictureRecordingDrawableKind.UNKNOWN, 2, 1, 1..2),
            PictureRecordingDrawable(PictureRecordingDrawableKind.UNKNOWN, 3, 1, 3..4)
        )

        val graph = buildPictureRecordingTraceGraph(operations, drawables)
        val chunks = graph.suggestChunkCandidates(operations, targetChunkCount = 2)

        assertEquals(1, chunks.size)
        assertEquals(0..5, chunks.single().operationIndexRange)
        assertEquals(operations.size, chunks.single().operationCount)
        assertTrue(chunks.single().isSafeStandalonePicture)
        assertFalse(chunks.single().hasUnsupportedPictureState)
    }

    @Test
    fun chunkSuggestionsDescendIntoLargestRootWhenTopLevelRootsAreTooCoarse() {
        val operations = listOf(
            PictureRecordingOperation(PictureRecordingOperationKind.DRAW_DRAWABLE, 0),
            PictureRecordingOperation(PictureRecordingOperationKind.DRAW_DRAWABLE, 1),
            PictureRecordingOperation(PictureRecordingOperationKind.DRAW_RECT, 2),
            PictureRecordingOperation(PictureRecordingOperationKind.DRAW_DRAWABLE, 1),
            PictureRecordingOperation(PictureRecordingOperationKind.DRAW_PATH, 2),
            PictureRecordingOperation(PictureRecordingOperationKind.DRAW_DRAWABLE, 0),
            PictureRecordingOperation(PictureRecordingOperationKind.DRAW_RRECT, 1)
        )
        val drawables = listOf(
            PictureRecordingDrawable(PictureRecordingDrawableKind.RENDER_NODE, 1, 0, IntRange.EMPTY),
            PictureRecordingDrawable(PictureRecordingDrawableKind.UNKNOWN, 2, 1, IntRange.EMPTY),
            PictureRecordingDrawable(PictureRecordingDrawableKind.UNKNOWN, 3, 2, 2..2),
            PictureRecordingDrawable(PictureRecordingDrawableKind.UNKNOWN, 4, 1, IntRange.EMPTY),
            PictureRecordingDrawable(PictureRecordingDrawableKind.UNKNOWN, 5, 2, 4..4),
            PictureRecordingDrawable(PictureRecordingDrawableKind.UNKNOWN, 6, 0, 6..6)
        )

        val graph = buildPictureRecordingTraceGraph(operations, drawables)
        val chunks = graph.suggestChunkCandidates(operations, targetChunkCount = 3)

        assertEquals(3, chunks.size)
        assertEquals(listOf(0..3, 4..5, 6..6), chunks.map(PictureRecordingChunkCandidate::operationIndexRange))
        assertEquals(
            listOf(listOf(1, 2), listOf(3, 4), listOf(5)),
            chunks.map(PictureRecordingChunkCandidate::nodeIndices)
        )
        assertTrue(chunks.all { it.reason == PictureRecordingChunkCandidateReason.SUBTREE })
    }

    @Test
    fun normalizedChunkSuggestionsDoNotOverlapAfterSaveLayerMerge() {
        val operations = listOf(
            PictureRecordingOperation(PictureRecordingOperationKind.DRAW_DRAWABLE, 0),
            PictureRecordingOperation(PictureRecordingOperationKind.SAVE_LAYER, 0),
            PictureRecordingOperation(PictureRecordingOperationKind.DRAW_DRAWABLE, 1),
            PictureRecordingOperation(PictureRecordingOperationKind.DRAW_RECT, 1),
            PictureRecordingOperation(PictureRecordingOperationKind.RESTORE, 0),
            PictureRecordingOperation(PictureRecordingOperationKind.DRAW_DRAWABLE, 0),
            PictureRecordingOperation(PictureRecordingOperationKind.DRAW_RECT, 0)
        )
        val drawables = listOf(
            PictureRecordingDrawable(PictureRecordingDrawableKind.RENDER_NODE, 1, 0, IntRange.EMPTY),
            PictureRecordingDrawable(PictureRecordingDrawableKind.UNKNOWN, 2, 1, 2..3),
            PictureRecordingDrawable(PictureRecordingDrawableKind.UNKNOWN, 3, 0, 5..6)
        )

        val graph = buildPictureRecordingTraceGraph(operations, drawables)
        val chunks = graph.suggestChunkCandidates(operations, targetChunkCount = 2)

        assertEquals(listOf(0..4, 5..6), chunks.map(PictureRecordingChunkCandidate::operationIndexRange))
        assertTrue(chunks.zipWithNext().all { (left, right) -> left.operationIndexRange.last < right.operationIndexRange.first })
    }

    @Test
    fun canMakeStandaloneChunkPicturesFromOrderedOperationRanges() {
        val size = Rect(0.0f, 0.0f, 32.0f, 32.0f)
        val recorder = PictureRecorder()
        val canvas = recorder.beginRecording(size)
        canvas.drawRect(Rect(0.0f, 0.0f, 16.0f, 16.0f), Paint().apply { color = Color.RED })
        canvas.drawRect(Rect(16.0f, 16.0f, 32.0f, 32.0f), Paint().apply { color = Color.BLUE })
        val picture = recorder.finishRecordingAsPicture()

        val chunkPictures = picture.makeChunkPictures(
            listOf(
                PictureRecordingChunkCandidate(
                    orderedIndex = 0,
                    nodeIndices = emptyList(),
                    operationIndexRange = 0..0,
                    operationCount = 1,
                    stateSetupOperationKinds = emptyList(),
                    requiresStateSetup = false,
                    hasUnsupportedPictureState = false,
                    isSafeStandalonePicture = true,
                    operationCounts = mapOf(PictureRecordingOperationKind.DRAW_RECT to 1),
                    dominantOperationKinds = listOf(PictureRecordingOperationKind.DRAW_RECT),
                    reason = PictureRecordingChunkCandidateReason.LEAF
                ),
                PictureRecordingChunkCandidate(
                    orderedIndex = 1,
                    nodeIndices = emptyList(),
                    operationIndexRange = 1..1,
                    operationCount = 1,
                    stateSetupOperationKinds = emptyList(),
                    requiresStateSetup = false,
                    hasUnsupportedPictureState = false,
                    isSafeStandalonePicture = true,
                    operationCounts = mapOf(PictureRecordingOperationKind.DRAW_RECT to 1),
                    dominantOperationKinds = listOf(PictureRecordingOperationKind.DRAW_RECT),
                    reason = PictureRecordingChunkCandidateReason.LEAF
                )
            )
        )

        assertEquals(2, chunkPictures.size)
        assertNotNull(chunkPictures[0].picture)
        assertNotNull(chunkPictures[1].picture)

        val surface = Surface.makeRasterN32Premul(32, 32)
        chunkPictures.forEach { chunk -> chunk.picture!!.playback(surface.canvas) }

        val bitmap = Bitmap.makeFromImage(surface.makeImageSnapshot())
        assertEquals(Color.RED, bitmap.getColor(8, 8))
        assertEquals(Color.BLUE, bitmap.getColor(24, 24))
    }

    @Test
    fun refusesStandaloneChunkPicturesForUnsupportedInheritedSaveLayerState() {
        val size = Rect(0.0f, 0.0f, 32.0f, 32.0f)
        val recorder = PictureRecorder()
        val canvas = recorder.beginRecording(size)
        canvas.saveLayer(null, Paint())
        canvas.drawRect(Rect(0.0f, 0.0f, 16.0f, 16.0f), Paint().apply { color = Color.RED })
        canvas.restore()
        val picture = recorder.finishRecordingAsPicture()

        val candidate = PictureRecordingChunkCandidate(
            orderedIndex = 0,
            nodeIndices = emptyList(),
            operationIndexRange = 1..1,
            operationCount = 1,
            stateSetupOperationKinds = listOf(PictureRecordingOperationKind.SAVE_LAYER),
            requiresStateSetup = true,
            hasUnsupportedPictureState = true,
            isSafeStandalonePicture = false,
            operationCounts = mapOf(PictureRecordingOperationKind.DRAW_RECT to 1),
            dominantOperationKinds = listOf(PictureRecordingOperationKind.DRAW_RECT),
            reason = PictureRecordingChunkCandidateReason.LEAF
        )

        val chunkPicture = picture.makeChunkPictures(listOf(candidate)).single()

        assertNull(chunkPicture.picture)
        assertEquals("unsupported inherited saveLayer state", chunkPicture.failureReason)
    }

    @Test
    fun allowsStandaloneChunkPicturesForDrawableBackedContentWithPayload() {
        val operations = listOf(
            PictureRecordingOperation(PictureRecordingOperationKind.DRAW_DRAWABLE, 0),
            PictureRecordingOperation(PictureRecordingOperationKind.DRAW_RECT, 1)
        )
        val drawables = listOf(
            PictureRecordingDrawable(PictureRecordingDrawableKind.RENDER_NODE, 1, 0, IntRange.EMPTY),
            PictureRecordingDrawable(PictureRecordingDrawableKind.UNKNOWN, 2, 1, 1..1)
        )

        val graph = buildPictureRecordingTraceGraph(operations, drawables)
        val candidate = graph.suggestChunkCandidates(operations, targetChunkCount = 1).single()

        assertTrue(candidate.operationCounts.containsKey(PictureRecordingOperationKind.DRAW_DRAWABLE))
        assertTrue(candidate.isSafeStandalonePicture)
        assertFalse(candidate.hasUnsupportedPictureState)
        assertTrue(candidate.canMakeStandalonePicture())
    }

    @Test
    fun fullRangeChunkPictureRemainsReplayableAfterSourceObjectsClose() {
        val context = RenderNodeContext()
        val node = RenderNode(context)
        node.bounds = Rect(0f, 0f, 32f, 32f)
        node.beginRecording().drawRect(
            Rect(4f, 4f, 28f, 28f),
            Paint().apply { color = Color.RED }
        )
        node.endRecording()

        val recorder = PictureRecorder()
        val canvas = recorder.beginRecordingWithOperationTrace(Rect(0f, 0f, 32f, 32f))
        node.drawInto(canvas)
        val picture = recorder.finishRecordingAsPicture()

        val chunkPicture = picture.makeChunkPictures(
            listOf(
                PictureRecordingChunkCandidate(
                    orderedIndex = 0,
                    nodeIndices = recorder.traceGraph.rootNodeIndices,
                    operationIndexRange = 0 until recorder.recordedOperations.size,
                    operationCount = recorder.recordedOperations.size,
                    stateSetupOperationKinds = emptyList(),
                    requiresStateSetup = false,
                    hasUnsupportedPictureState = false,
                    isSafeStandalonePicture = true,
                    operationCounts = recorder.recordedOperations.groupingBy { it.kind }.eachCount(),
                    dominantOperationKinds = recorder.recordedOperations.groupingBy { it.kind }.eachCount().keys.toList(),
                    reason = PictureRecordingChunkCandidateReason.SUBTREE
                )
            )
        ).single()

        assertNotNull(chunkPicture.picture)

        picture.close()
        node.close()
        context.close()

        val surface = Surface.makeRasterN32Premul(32, 32)
        chunkPicture.picture!!.playback(surface.canvas)
        val bitmap = Bitmap.makeFromImage(surface.makeImageSnapshot())
        assertEquals(Color.RED, bitmap.getColor(8, 8))

        chunkPicture.picture?.close()
        surface.close()
    }
}
