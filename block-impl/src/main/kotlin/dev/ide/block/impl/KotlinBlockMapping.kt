package dev.ide.block.impl

import dev.ide.block.BlockMapping
import dev.ide.block.BlockNode
import dev.ide.block.BlockPart
import dev.ide.block.BlockTemplate
import dev.ide.block.ProjectionContext
import dev.ide.block.SlotCategory
import dev.ide.lang.LanguageId
import dev.ide.lang.dom.DomNode
import dev.ide.lang.dom.NodeKind
import dev.ide.lang.dom.TextRange

/**
 * The Kotlin [BlockMapping]. The Kotlin neutral-DOM reuses the language-neutral kinds for the shapes
 * that line up (file/class/function/block/call/name/…) and emits `kt.*` kinds for everything else, so
 * this mapping:
 *
 *  - turns the top-level containers into list slots (file → declarations, class body → members,
 *    a function body's block → statements) just like the Java mapping does;
 *  - decomposes the Kotlin-specific control flow (`kt.if`, `kt.for`, `kt.while`, `kt.do_while`,
 *    `kt.return`, `kt.throw`, `kt.try`) and expressions (`kt.lambda`, `kt.when`, `kt.binary`,
 *    `kt.string_template`, safe/qualified access) by generic gap-carving;
 *  - leaves anything else as an editable opaque text block (never a dead end).
 *
 * The engine picks a mapping per file *language* ([ProjectionContext] consumers gate on
 * [languages]), so sharing kind ids with the Java mapping is safe — each language engine sees only its
 * own mapping.
 */
object KotlinBlockMapping : BlockMapping {

    override val languages: Set<LanguageId> = setOf(LanguageId("kotlin"))

    override val handles: Set<NodeKind> = buildSet {
        // neutral kinds the Kotlin DOM reuses
        add(NodeKind.COMPILATION_UNIT); add(NodeKind.PACKAGE_DECL); add(NodeKind.IMPORT_DECL)
        add(NodeKind.CLASS_DECL); add(NodeKind.METHOD_DECL); add(NodeKind.PARAMETER)
        add(NodeKind.BLOCK); add(NodeKind.LOCAL_VAR)
        add(NodeKind.METHOD_CALL); add(NodeKind.MEMBER_ACCESS); add(NodeKind.NAME_REF)
        add(NodeKind.TYPE_REF); add(NodeKind.LITERAL)
        // kotlin-specific kinds
        add(KotlinKinds.PROPERTY); add(KotlinKinds.OBJECT_DECL); add(KotlinKinds.LAMBDA)
        add(KotlinKinds.WHEN); add(KotlinKinds.STRING_TEMPLATE); add(KotlinKinds.BINARY)
        add(KotlinKinds.CONSTRUCTOR)
        add(KotlinKinds.CLASS_BODY); add(KotlinKinds.IF); add(KotlinKinds.FOR)
        add(KotlinKinds.WHILE); add(KotlinKinds.DO_WHILE); add(KotlinKinds.RETURN)
        add(KotlinKinds.THROW); add(KotlinKinds.TRY); add(KotlinKinds.SUPER_EXPRESSION)
    }

    override fun project(node: DomNode, ctx: ProjectionContext): BlockNode = when (node.kind) {
        NodeKind.COMPILATION_UNIT -> listContainer(node, ctx, SlotCategory.DECLARATION)
        NodeKind.CLASS_DECL -> classDecl(node, ctx)
        KotlinKinds.CLASS_BODY -> listContainer(node, ctx, SlotCategory.DECLARATION)
        NodeKind.BLOCK -> listContainer(node, ctx, SlotCategory.STATEMENT)
        else -> ctx.carve(node)
    }

    override fun template(): BlockTemplate =
        BlockTemplate(label = "statement", category = SlotCategory.STATEMENT, defaultText = "println(${BlockTemplate.PLACEHOLDER})")

    /** The whole file / a class body / a function body: one foldable list slot, chrome preserved on the edges. */
    private fun listContainer(node: DomNode, ctx: ProjectionContext, category: SlotCategory): BlockNode {
        val children = node.children
        if (children.isEmpty()) {
            // Empty container: an empty slot whose "inside" is between the braces so an insert lands
            // BETWEEN them (a function body `fun f() {}` places the caret after the `{`), not after `}`.
            val inside = TextRange(
                (node.range.start + 1).coerceAtMost(node.range.end),
                (node.range.end - 1).coerceAtLeast(node.range.start + 1),
            )
            val empty = ctx.slot(category, emptyList(), multiple = true, range = inside)
            return ctx.block(node, node.kind, listOf(BlockPart.Slot(empty)), labelFor(node.kind))
        }
        val parts = ArrayList<BlockPart>()
        if (children.first().range.start > node.range.start) {
            parts += BlockPart.Field(ctx.chromeField(TextRange(node.range.start, children.first().range.start)))
        }
        parts += BlockPart.Slot(
            ctx.slot(
                category,
                children.map { ctx.child(it) },
                multiple = true,
                range = TextRange(children.first().range.start, children.last().range.end),
            ),
        )
        if (node.range.end > children.last().range.end) {
            parts += BlockPart.Field(ctx.chromeField(TextRange(children.last().range.end, node.range.end)))
        }
        return ctx.block(node, node.kind, parts, labelFor(node.kind))
    }

    /**
     * A class: header (modifiers, name, type params, primary constructor) as single slots, then its
     * members as ONE declaration list slot. The Kotlin DOM nests members inside a `kt.class_body`
     * wrapper, so that wrapper's CHILDREN become the class's members (unwrapped — no double nesting).
     */
    private fun classDecl(node: DomNode, ctx: ProjectionContext): BlockNode {
        val children = node.children
        val bodyChild = children.firstOrNull { it.kind == KotlinKinds.CLASS_BODY }
        val members = bodyChild?.children ?: emptyList()
        if (bodyChild == null) return ctx.carve(node)
        val parts = ArrayList<BlockPart>()
        var pos = node.range.start
        for (c in children) {
            if (c.range.start > pos) parts += BlockPart.Field(ctx.chromeField(TextRange(pos, c.range.start)))
            if (c === bodyChild) {
                if (members.isNotEmpty()) {
                    parts += BlockPart.Slot(
                        ctx.slot(
                            SlotCategory.DECLARATION,
                            members.map { ctx.child(it) },
                            multiple = true,
                            range = TextRange(members.first().range.start, members.last().range.end),
                        ),
                    )
                    pos = members.last().range.end
                }
            } else {
                parts += BlockPart.Slot(
                    ctx.slot(categoryFor(c.kind), listOf(ctx.child(c)), multiple = false, range = c.range),
                )
                pos = c.range.end
            }
        }
        if (node.range.end > pos) parts += BlockPart.Field(ctx.chromeField(TextRange(pos, node.range.end)))
        return ctx.block(node, node.kind, parts, labelFor(node.kind))
    }
}

/** Build a read-only chrome field over [range]'s source via the [ProjectionContext] factories. */
private fun ProjectionContext.chromeField(range: TextRange) =
    field(role = "syntax", text = textOf(range).toString(), editable = false, range = range)