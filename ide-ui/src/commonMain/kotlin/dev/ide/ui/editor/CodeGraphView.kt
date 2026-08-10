@file:OptIn(com.ronjunevaldoz.graphyn.core.GraphynExperimentalApi::class)

package dev.ide.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ronjunevaldoz.graphyn.core.model.ConnectionRef
import com.ronjunevaldoz.graphyn.core.model.NodeRef
import com.ronjunevaldoz.graphyn.core.model.NodeSpec
import com.ronjunevaldoz.graphyn.core.model.PortSpec
import com.ronjunevaldoz.graphyn.core.model.WorkflowDefinition
import com.ronjunevaldoz.graphyn.core.model.WorkflowNodePosition
import com.ronjunevaldoz.graphyn.core.model.WorkflowType
import com.ronjunevaldoz.graphyn.core.registry.DefaultNodeSpecRegistry
import com.ronjunevaldoz.graphyn.core.serialization.toJson
import com.ronjunevaldoz.graphyn.editor.canvas.GraphynCanvasSurface
import com.ronjunevaldoz.graphyn.editor.interaction.GraphynEditorIntent
import com.ronjunevaldoz.graphyn.editor.state.GraphynEditorState
import com.ronjunevaldoz.graphyn.editor.state.rememberGraphynEditorState

/**
 * Codex node-graph editor — Phase 1.
 *
 * Full-screen [GraphynCanvasSurface] (no shell rails/toolbar) with our own overlay UI drawn on top:
 * a compact control chip (Pan/Select toggle + Grupo/Caja/Expandir/Layout/JSON), a "+" button that
 * opens a category menu → node list with search + close, and a marquee multi-select box when the
 * toggle is in "Select" mode. Gestures come from Graphyn: 1-finger drag pans, 2-finger pinch zooms,
 * dragging a port draws a connection. There are no +/− zoom buttons (zoom is the pinch). Node add
 * lands at the viewport center (Graphyn's built-in behavior). Phase 2 will add connection magnets +
 * green/red port compatibility glow.
 */
@Composable
fun CodeGraphView(modifier: Modifier = Modifier) {
    val specs = remember { demoNodeSpecRegistry() }
    val state = rememberGraphynEditorState(initialWorkflow = demoWorkflow(), nodeSpecs = specs)

    var menu by remember { mutableStateOf<GraphMenu?>(null) }
    var search by remember { mutableStateOf("") }
    var showJson by remember { mutableStateOf(false) }
    var jsonText by remember { mutableStateOf("") }
    var selectMode by remember { mutableStateOf(false) }
    var renameFor by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }

    Box(modifier) {
        // Full-screen canvas. 1-finger drag pans, 2-finger pinch zooms, drag a port to wire nodes.
        GraphynCanvasSurface(state = state, nodeSpecs = specs)

        // Marquee multi-select overlay (only when the user toggled Pan → Select).
        if (selectMode) MarqueeSelectOverlay(state)

        // The graph UI lives on top of the canvas, inside the editor.
        ControlChip(
            selectMode = selectMode,
            onToggleSelect = { selectMode = !selectMode },
            onGroup = {
                val before = state.groups.map { it.id }
                if (state.effectiveSelectedNodeIds.size >= 2) state.dispatch(GraphynEditorIntent.CreateGroupFromSelection)
                val created = state.groups.firstOrNull { it.id !in before }
                if (created != null) { renameFor = created.id; renameText = created.label }
            },
            onCaja = {
                if (state.effectiveSelectedNodeIds.isNotEmpty()) {
                    state.dispatch(GraphynEditorIntent.CollapseSelectionToSubgraph)
                }
            },
            onExpand = {
                val sub = state.workflow?.nodes?.firstOrNull { it.type == "graphyn.subgraph" }
                if (sub != null) state.dispatch(GraphynEditorIntent.ExpandSubgraph(sub.id))
            },
            onLayout = { state.dispatch(GraphynEditorIntent.AutoLayout) },
            onJson = { jsonText = state.workflow?.toJson().orEmpty(); showJson = true },
            onAdd = { menu = GraphMenu.Categories },
        )

        if (showJson) {
            JsonPanel(jsonText = jsonText, onClose = { showJson = false })
        }

        when (val m = menu) {
            is GraphMenu.Categories -> CategoryMenu(
                onPick = { menu = GraphMenu.Nodes(it) },
                onClose = { menu = null },
            )
            is GraphMenu.Nodes -> NodeMenu(
                category = m.category,
                search = search,
                onSearch = { search = it },
                onClose = { menu = null },
                onPick = { spec ->
                    state.dispatch(GraphynEditorIntent.AddNode(spec))
                    menu = null
                    search = ""
                },
            )
            null -> {}
        }

        if (renameFor != null) {
            RenameDialog(
                initial = renameText,
                onDone = { label ->
                    if (renameFor != null) state.dispatch(GraphynEditorIntent.RenameGroup(renameFor!!, label))
                    renameFor = null
                },
                onClose = { renameFor = null },
            )
        }
    }
}

/** The overlay pill with graph controls, drawn on top of the canvas (empty screen areas pass touches through). */
@Composable
private fun ControlChip(
    selectMode: Boolean,
    onToggleSelect: () -> Unit,
    onGroup: () -> Unit,
    onCaja: () -> Unit,
    onExpand: () -> Unit,
    onLayout: () -> Unit,
    onJson: () -> Unit,
    onAdd: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 10.dp)
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .background(Color(0xE61B1B1B), RoundedCornerShape(20.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Chip(text = if (selectMode) "Seleccionar" else "Pan", active = selectMode, onClick = onToggleSelect)
            Chip(text = "Grupo", active = false, onClick = onGroup)
            Chip(text = "Caja", active = false, onClick = onCaja)
            Chip(text = "Expandir", active = false, onClick = onExpand)
            Chip(text = "Layout", active = false, onClick = onLayout)
            Chip(text = "JSON", active = false, onClick = onJson)
            Chip(text = "+", active = false, onClick = onAdd)
        }
    }
}

@Composable
private fun Chip(text: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (active) MaterialTheme.colorScheme.primary else Color(0xFF3A3A3A))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (active) Color(0xFF171717) else MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
    }
}

/** Floating "+" is part of the control chip for now; category/node menus are overlay cards. */

private sealed interface GraphMenu {
    data object Categories : GraphMenu
    data class Nodes(val category: GraphCategory) : GraphMenu
}

@Composable
private fun CategoryMenu(onPick: (GraphCategory) -> Unit, onClose: () -> Unit) {
    OverlayCard(title = "Añadir nodo", onClose = onClose) {
        catalog.forEach { cat ->
            MenuRow(text = cat.title) { onPick(cat) }
        }
    }
}

@Composable
private fun NodeMenu(
    category: GraphCategory,
    search: String,
    onSearch: (String) -> Unit,
    onClose: () -> Unit,
    onPick: (NodeSpec) -> Unit,
) {
    OverlayCard(title = category.title, onClose = onClose) {
        OutlinedTextField(
            value = search,
            onValueChange = onSearch,
            placeholder = { Text("Buscar nodos…", fontSize = 13.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        )
        val filtered = if (search.isBlank()) category.specs else category.specs.filter { it.label.contains(search, ignoreCase = true) }
        filtered.forEach { spec ->
            MenuRow(text = spec.label) { onPick(spec) }
        }
        if (filtered.isEmpty()) Text("Sin resultados", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun OverlayCard(title: String, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(320.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Text(title, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Box(
                    Modifier.clip(CircleShape).background(Color(0xFF3A3A3A)).clickable(onClick = onClose).padding(6.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("✕", fontSize = 12.sp) }
            }
            content()
        }
    }
}

@Composable
private fun MenuRow(text: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().padding(vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF2A2A2A))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) { Text(text, fontSize = 14.sp) }
}

@Composable
private fun RenameDialog(initial: String, onDone: (String) -> Unit, onClose: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
        Column(Modifier.width(320.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp)).padding(16.dp)) {
            Text("Nombre del grupo", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(bottom = 8.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { onDone(text) }, modifier = Modifier.weight(1f)) { Text("Renombrar") }
                Button(onClick = onClose, modifier = Modifier.weight(1f)) { Text("Cancelar") }
            }
        }
    }
}

@Composable
private fun JsonPanel(jsonText: String, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Box(
            Modifier.fillMaxWidth().height(240.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .padding(12.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text("JSON del grafo", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Box(Modifier.clip(CircleShape).background(Color(0xFF3A3A3A)).clickable(onClick = onClose).padding(4.dp)) { Text("✕", fontSize = 11.sp) }
            }
            Text(
                jsonText,
                fontSize = 9.sp,
                modifier = Modifier
                    .padding(top = 26.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState()),
            )
        }
    }
}

/** Marquee multi-select: drags starting on empty canvas draw a box; on release the nodes inside it are selected. */
@Composable
private fun MarqueeSelectOverlay(state: GraphynEditorState) {
    var start by remember { mutableStateOf<Offset?>(null) }
    var current by remember { mutableStateOf<Offset?>(null) }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(state) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (down.changedToUp()) return@awaitEachGesture
                    // Touches near a node fall through so node drag/selection works as usual.
                    if (isNearNode(state, down.position)) {
                        while (true) {
                            val ev = awaitPointerEvent()
                            if (ev.changes.all { it.changedToUp() }) break
                        }
                        return@awaitEachGesture
                    }
                    down.consume()
                    start = down.position
                    current = down.position
                    var out = false
                    while (!out) {
                        val ev = awaitPointerEvent()
                        if (ev.changes.size > 1) { out = true; continue }
                        val c = ev.changes.firstOrNull { it.id == down.id } ?: continue
                        if (!c.pressed) { out = true; break }
                        c.consume()
                        current = c.position
                    }
                    finalizeMarquee(state, start ?: Offset.Zero, current ?: Offset.Zero)
                    start = null
                    current = null
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val s = start
            val c = current
            if (s != null && c != null) {
                val rect = Rect(minOf(s.x, c.x), minOf(s.y, c.y), maxOf(s.x, c.x), maxOf(s.y, c.y))
                val topLeft = Offset(rect.left, rect.top)
                val size = Size(rect.width, rect.height)
                drawRect(Color(0x3333B5E5), topLeft = topLeft, size = size, style = Stroke(width = 2f))
                drawRect(Color(0x2233B5E5), topLeft = topLeft, size = size)
            }
        }
    }
}

private fun isNearNode(state: GraphynEditorState, screenPoint: Offset): Boolean {
    val vp = state.viewport
    val scale = vp.scale
    val off = vp.offset
    val pos = state.nodePositionsByNodeId
    val nodes = state.workflow?.nodes.orEmpty()
    for (n in nodes) {
        val p = pos[n.id]
        if (p == null) continue
        val world = Offset((screenPoint.x - off.x) / scale, (screenPoint.y - off.y) / scale)
        val fx = p.x.toFloat()
        val fy = p.y.toFloat()
        if (world.x >= fx && world.x <= fx + NodeHitWidth && world.y >= fy && world.y <= fy + NodeHitHeight) return true
    }
    return false
}

private fun finalizeMarquee(state: GraphynEditorState, startScreen: Offset, endScreen: Offset) {
    val vp = state.viewport
    val scale = vp.scale
    val off = vp.offset
    fun toWorld(p: Offset) = Offset((p.x - off.x) / scale, (p.y - off.y) / scale)
    val a = toWorld(startScreen)
    val b = toWorld(endScreen)
    val worldRect = Rect(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y))
    val pos = state.nodePositionsByNodeId
    val nodes = state.workflow?.nodes.orEmpty()
    val selected = nodes.mapNotNull { n ->
        val p = pos[n.id] ?: return@mapNotNull null
        val nodeRect = Rect(p.x.toFloat(), p.y.toFloat(), p.x + NodeHitWidth, p.y + NodeHitHeight)
        if (worldRect.overlaps(nodeRect) || nodeRect.contains(worldRect.topLeft) || nodeRect.contains(worldRect.bottomRight)) n.id else null
    }.toSet()
    if (selected.isNotEmpty()) {
        state.selectedNodeIds = selected
        state.selectedNodeId = selected.first()
    } else {
        state.selectedNodeIds = emptySet()
    }
}

private const val NodeHitWidth = 160f
private const val NodeHitHeight = 70f

/** A catalog category with its node specs. */
data class GraphCategory(
    val id: String,
    val title: String,
    val specs: List<NodeSpec>,
)

private fun ns(type: String, label: String, inputs: List<String> = listOf("cond"), outputs: List<String> = listOf("next")) = NodeSpec(
    type = type,
    label = label,
    inputs = inputs.map { PortSpec(it, WorkflowType.OpaqueType, required = false) },
    outputs = outputs.map { PortSpec(it, WorkflowType.OpaqueType, required = false) },
    defaultValues = emptyMap(),
    category = null,
    description = null,
)

/** The node catalog surfaced by the "+" menu: categories → nodes, searchable. */
private val catalog = listOf(
    GraphCategory("programacion", "Nodos de programación", listOf(
        ns("codex.log", "Escribir log"),
        ns("codex.var", "Crear variable"),
        ns("codex.call", "Llamar función"),
        ns("codex.repeat", "Repetir"),
    )),
    GraphCategory("logica", "Nodos de lógica", listOf(
        ns("codex.if", "Si / Si no"),
        ns("codex.compare", "Comparar"),
        ns("codex.and", "Y"),
        ns("codex.or", "O"),
        ns("codex.not", "No"),
    )),
    GraphCategory("flujo", "Nodos de flujo", listOf(
        ns("codex.start", "Inicio", inputs = emptyList()),
        ns("codex.retornar", "Retornar", outputs = emptyList()),
    )),
    GraphCategory("io", "Entrada / Salida", listOf(
        ns("codex.input", "Leer"),
        ns("codex.output", "Escribir"),
    )),
)

/** Registry: every catalog spec + the demo spec used by the initial workflow. */
private fun demoNodeSpecRegistry(): DefaultNodeSpecRegistry = DefaultNodeSpecRegistry().apply {
    catalog.forEach { cat -> cat.specs.forEach { register(it) } }
    register(
        ns("demo.stmt", "Fila"),
    )
}

/** Demo workflow: a linear "ruta" of 5 nodes chained by `next`, laid out diagonally. */
private fun demoWorkflow() = WorkflowDefinition(
    id = "demo-workflow",
    name = "Demo",
    nodes = listOf(
        NodeRef("n1", "demo.stmt"),
        NodeRef("n2", "demo.stmt"),
        NodeRef("n3", "demo.stmt"),
        NodeRef("n4", "demo.stmt"),
        NodeRef("n5", "demo.stmt"),
    ),
    connections = listOf(
        ConnectionRef("n1", "next", "n2", "cond"),
        ConnectionRef("n2", "next", "n3", "cond"),
        ConnectionRef("n3", "next", "n4", "cond"),
        ConnectionRef("n4", "next", "n5", "cond"),
    ),
    nodePositions = mapOf(
        "n1" to WorkflowNodePosition(x = 40, y = 120),
        "n2" to WorkflowNodePosition(x = 300, y = 200),
        "n3" to WorkflowNodePosition(x = 560, y = 280),
        "n4" to WorkflowNodePosition(x = 820, y = 360),
        "n5" to WorkflowNodePosition(x = 1080, y = 440),
    ),
)