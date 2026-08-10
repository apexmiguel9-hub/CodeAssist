package dev.ide.lang.kotlin

import dev.ide.block.BlockNode
import dev.ide.block.BlockPart
import dev.ide.block.BlockTemplate
import dev.ide.block.InsertTemplate
import dev.ide.block.SlotCategory
import dev.ide.block.SlotRef
import dev.ide.block.impl.BlockProjectionEngine
import dev.ide.block.impl.KotlinBlockMapping
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The Kotlin [KotlinBlockMapping] end-to-end: project real Kotlin (through the real Kotlin PSI → neutral
 * DOM) into a block tree, and compile a palette insert back into a surgical document edit. Proves the
 * "blocks work on .kt" wiring headless before the APK is ever built.
 */
class KotlinBlockProjectionTest {

    private fun blocksOf(kotlin: String): List<BlockNode> {
        val engine = BlockProjectionEngine(listOf(KotlinBlockMapping))
        val root = engine.project(parse(kotlin)).root
        val out = ArrayList<BlockNode>()
        fun walk(b: BlockNode) {
            out.add(b)
            b.parts.forEach { p -> if (p is BlockPart.Slot) p.slot.children.forEach(::walk) }
        }
        walk(root)
        return out
    }

    @Test
    fun decomposesFunctionIntoBlocks() {
        val src = """
            fun f(a: Int): Int {
                val x = 1
                if (x > 0) {
                    return x
                }
                return a
            }
        """.trimIndent()
        val blocks = blocksOf(src)
        val labels = blocks.map { it.label }
        assertTrue("method" in labels, "function decomposes to a method block: $labels")
        assertTrue("if" in labels, "kotlin if decomposes to a control block: $labels")
        assertTrue("return" in labels, "kotlin return decomposes: $labels")
        assertTrue("val" in labels || "var" in labels, "local property decomposes: $labels")
    }

    @Test
    fun classHasMemberListAndFunctionBodyIsAStatementSlot() {
        val src = """
            class Greeter(val who: String) {
                val greeting: String = "Hi"

                fun greet(name: String) {
                    println(name)
                }
            }
        """.trimIndent()
        val blocks = blocksOf(src)
        val cls = blocks.firstOrNull { it.label == "class" }
        assertTrue(cls != null, "class block present: ${blocks.map { it.label }}")
        val body = blocks.firstOrNull { it.kind.id == "kt.class_body" }
        assertTrue(body != null, "class body container present: ${blocks.map { it.kind.id }}")
        val memberSlot = body?.slots?.singleOrNull { it.multiple }
        assertTrue(memberSlot != null, "class body is one multiple declaration slot")
    }

    @Test
    fun paletteInsertIntoEmptyFunctionBodyIsASurgicalEdit() {
        val src = "fun f() {\n    val x = 1\n}"
        val engine = BlockProjectionEngine(listOf(KotlinBlockMapping))
        val tree = engine.project(parse(src))
        // The function's body is a "block"-labeled STATEMENT container with a multiple slot.
        val body = blocksOfWalk(tree.root).firstOrNull { it.label == "block" }
        assertTrue(body != null, "function body block present")
        val slotIndex = body.slots.indexOfFirst { it.multiple }
        assertTrue(slotIndex >= 0, "body has a multiple statement slot")
        val insert = InsertTemplate(
            at = SlotRef(body.id, slotIndex, 0),
            template = BlockTemplate("println", SlotCategory.STATEMENT, "println(█)"),
        )
        val edits = engine.computeEdit(tree, src, insert)
        assertTrue(edits.isNotEmpty(), "an insert compiles to at least one document edit")
        assertTrue(edits.first().newText.contains("println"), "insert carries the template text")
    }

    private fun blocksOfWalk(b: BlockNode): List<BlockNode> = buildList {
        fun walk(n: BlockNode) {
            add(n)
            n.parts.forEach { p -> if (p is BlockPart.Slot) p.slot.children.forEach(::walk) }
        }
        walk(b)
    }
}