package dev.ide.block.impl

import dev.ide.lang.dom.NodeKind

/**
 * The Kotlin-neutral-DOM's `kt.*` kinds, as plain strings (mirrors `lang-kotlin`'s `KotlinNodeKinds`).
 * Deliberately declared here with no dependency on the Kotlin module — same coupling-by-strings
 * philosophy as the Java mapping's JDT kind names, so `block-impl` stays backend-neutral.
 */
internal object KotlinKinds {
    val PROPERTY = NodeKind("kt.property")
    val OBJECT_DECL = NodeKind("kt.object")
    val LAMBDA = NodeKind("kt.lambda")
    val WHEN = NodeKind("kt.when")
    val STRING_TEMPLATE = NodeKind("kt.string_template")
    val BINARY = NodeKind("kt.binary")
    val CONSTRUCTOR = NodeKind("kt.constructor")
    val CLASS_BODY = NodeKind("kt.class_body")
    val IF = NodeKind("kt.if")
    val FOR = NodeKind("kt.for")
    val WHILE = NodeKind("kt.while")
    val DO_WHILE = NodeKind("kt.do_while")
    val RETURN = NodeKind("kt.return")
    val THROW = NodeKind("kt.throw")
    val TRY = NodeKind("kt.try")
    val SUPER_EXPRESSION = NodeKind("kt.super_expression")
}