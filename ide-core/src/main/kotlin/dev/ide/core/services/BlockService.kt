package dev.ide.core.services

import dev.ide.block.BLOCK_MAPPING_EP
import dev.ide.block.BlockEdit
import dev.ide.block.BlockMapping
import dev.ide.block.BlockTree
import dev.ide.block.impl.BlockProjectionEngine
import dev.ide.core.EngineContext
import dev.ide.lang.LanguageId
import dev.ide.lang.incremental.DocumentEdit
import java.nio.file.Path

/**
 * WORKSPACE-scoped engine service: the projectional (block) editor. Projects a buffer into a [BlockTree] and
 * compiles a [BlockEdit] back to surgical document edits. Carved out of [dev.ide.core.IdeServices].
 *
 * A block tree is a projection of the SAME tolerant DOM the editor/analyzer use, so this service depends only
 * on [EngineContext.parse] (the shared parse primitive) plus the [BlockProjectionEngine] (built from the
 * `platform.blockMapping` EP, so a plugin can contribute its own decomposition). The engine is per-language:
 * a file is projected with the mappings whose `languages` contain the file's editor language, so the Java and
 * Kotlin mappings (which share node kinds) never collide. Projection is deterministic for identical text, so
 * the ids it assigns round-trip a block edit (which re-projects the same text to resolve them) without
 * holding any session state.
 */
internal class BlockService(private val ctx: EngineContext) {

    private val allMappings = ctx.platform.extensions.extensions(BLOCK_MAPPING_EP)
    private val engines = LinkedHashMap<LanguageId, BlockProjectionEngine>()

    /** The engine for [file]: its editor language's mappings (falling back to every mapping when none match). */
    private fun engineFor(file: Path): BlockProjectionEngine {
        val language = ctx.languageFor(file)
        return engines.getOrPut(language) {
            val picked = allMappings.filter { language in it.languages }
            if (picked.isEmpty()) BlockProjectionEngine(allMappings) else BlockProjectionEngine(picked)
        }
    }

    /**
     * Whether the block editor is available — i.e. at least one block-mapping is registered on
     * [BLOCK_MAPPING_EP]. Disabling the `blocks` built-in plugin drops the only mappings, so this goes false
     * and the UI hides the Blocks view-mode segment. With no mapping the projection is an inert opaque tree.
     */
    val enabled: Boolean get() = allMappings.isNotEmpty()

    /** Project [file]'s live buffer [text] into a [BlockTree], or null if [file] is outside the project. */
    fun projectBlocks(file: Path, text: String): BlockTree? =
        ctx.parse(file, text)?.let { engineFor(file).project(it) }

    /** Compile a [BlockEdit] against [file]'s buffer [text] into surgical document edits (empty if N/A). */
    fun computeBlockEdit(file: Path, text: String, edit: BlockEdit): List<DocumentEdit> {
        val tree = projectBlocks(file, text) ?: return emptyList()
        return engineFor(file).computeEdit(tree, text, edit)
    }
}
