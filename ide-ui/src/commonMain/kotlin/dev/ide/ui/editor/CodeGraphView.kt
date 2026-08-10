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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
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
import com.ronjunevaldoz.graphyn.core.registry.NodeSpecRegistry
import com.ronjunevaldoz.graphyn.core.serialization.toJson
import com.ronjunevaldoz.graphyn.editor.canvas.GraphynCanvasSurface
import com.ronjunevaldoz.graphyn.editor.canvas.components.PortCompatibility
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

        // Phase 2: drag-from-port connections with magnet snap + green/red compatibility rings.
        ConnectOverlay(state = state, nodeSpecs = specs)

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

// ------------------------------------------------------------------------------------
// Phase 2 — Drag-to-connect with magnet + green/red rings.
//
// Graphyn's own connect UX is tap-start → tap-target (clickable port dots), which feels off on a
// phone. This overlay reimplements the Godot-style drag: press an output port, drag a cable, and
// compatible input ports glow green (red when rejected) while the nearest compatible one inside its
// row hotzone becomes the magnet snap target. Released on the snap → connection; on another input →
// nearest port; on empty → cable cancelled (and the output's previous wire is cut, like our engine).
// Port geometry mirrors the default FieldCardFactory (CARD_W=240, HEADER=28, ROW=22, dp) so rings
// sit exactly on Graphyn's rendered dots. Everything else (rendering, undo, collapse, serialization)
// stays in Graphyn.
// ------------------------------------------------------------------------------------

private const val DragSlopPx = 10f
private val SnapGreen = Color(0xFF4ADE80)
private val SnapRed = Color(0xFFE2583C)

private data class WorldPort(
    val nodeId: String,
    val portName: String,
    val spec: PortSpec,
    val isInput: Boolean,
    val world: Offset,
)

private data class RingPort(
    val screen: Offset,
    val compatible: Boolean,
    val isSnap: Boolean,
)

@Composable
private fun ConnectOverlay(state: GraphynEditorState, nodeSpecs: NodeSpecRegistry) {
    var rings by remember { mutableStateOf<List<RingPort>>(emptyList()) }
    var dragOut by remember { mutableStateOf<Offset?>(null) }
    val density = LocalDensity.current

    // Clear the rings when the draft disappears (e.g. a patient-port tap-then-tap completes).
    LaunchedEffect(state.connectionDraft) {
        if (state.connectionDraft == null) {
            rings = emptyList()
            dragOut = null
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(state, nodeSpecs) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val touch = down.position
                    val out = hitOutputPort(state, nodeSpecs, screenToWorld(state, touch), density)
                    if (out != null) {
                        down.consume()
                        state.dispatch(GraphynEditorIntent.BeginConnection(out.nodeId, out.portName))
                        dragOut = worldToScreen(state, out.world)
                        var began = false
                        var lastScreen = touch
                        var snapped: WorldPort? = null
                        var exited = false

                        while (!exited) {
                            val ev = awaitPointerEvent()
                            val pressed = ev.changes.filter { it.pressed }
                            if (pressed.isEmpty() || pressed.none { it.id == down.id }) {
                                ev.changes.firstOrNull { it.id == down.id }?.consume()
                                break
                            }
                            if (pressed.size >= 2) {
                                // Second finger = pinch zoom: cancel the cable and hand the gesture back.
                                state.dispatch(GraphynEditorIntent.CancelConnection)
                                exited = true
                                break
                            }
                            val c = pressed.first { it.id == down.id }
                            val screen = c.position
                            if (!began && (screen - touch).getDistance() < DragSlopPx) {
                                c.consume()
                                continue
                            }
                            began = true
                            snapped = findSnapTarget(state, nodeSpecs, out, screenToWorld(state, screen), density)
                            val target = snapped?.world ?: screenToWorld(state, screen)
                            state.dispatch(GraphynEditorIntent.UpdateConnectionDraftPosition(target))
                            rings = inputRingPorts(state, nodeSpecs, out.spec, snapped, density)
                            dragOut = worldToScreen(state, out.world)
                            c.consume()
                            lastScreen = screen
                        }

                        rings = emptyList()
                        dragOut = null
                        if (exited || !began) {
                            // Pinch took over, or a plain tap on a port: keep the draft alive so a second
                            // tap on a target port completes it via Graphyn's own flow.
                            return@awaitEachGesture
                        }
                        if (snapped != null) {
                            state.dispatch(GraphynEditorIntent.CompleteConnection(snapped!!.nodeId, snapped!!.portName))
                        } else {
                            val drop = screenToWorld(state, lastScreen)
                            val nearest = nearestInputPort(state, nodeSpecs, drop, density)
                            if (nearest != null && nearest.nodeId != out.nodeId) {
                                state.dispatch(GraphynEditorIntent.CompleteConnection(nearest.nodeId, nearest.portName))
                            } else {
                                // Dropped on empty: cancel; if the output was already wired, cut that wire.
                                val wire = state.workflow?.connections?.firstOrNull {
                                    it.fromNodeId == out.nodeId && it.fromPort == out.portName
                                }
                                state.dispatch(GraphynEditorIntent.CancelConnection)
                                if (wire != null) {
                                    state.selectedConnection = wire
                                    state.dispatch(GraphynEditorIntent.DeleteSelectedConnection)
                                }
                            }
                        }
                    }
                    // Non-port touches fall through to pan / node drag / Graphyn's own flows.
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val ripple = 23f * state.viewport.scale
            dragOut?.let { drawCircle(Color.White, radius = ripple * 0.8f, center = it) }
            rings.forEach { rp ->
                val color = if (rp.compatible) SnapGreen else SnapRed
                if (rp.isSnap) {
                    drawCircle(color.copy(alpha = 0.9f), radius = ripple + 3f, center = rp.screen)
                    drawCircle(Color.White, radius = ripple - 5f, center = rp.screen)
                } else {
                    drawCircle(color.copy(alpha = 0.85f), radius = ripple, center = rp.screen)
                    drawCircle(color, radius = ripple, center = rp.screen, style = Stroke(width = 3f))
                }
            }
        }
    }
}

private fun screenToWorld(state: GraphynEditorState, p: Offset): Offset {
    val vp = state.viewport
    return Offset((p.x - vp.offset.x) / vp.scale, (p.y - vp.offset.y) / vp.scale)
}

private fun worldToScreen(state: GraphynEditorState, w: Offset): Offset {
    val vp = state.viewport
    return Offset(w.x * vp.scale + vp.offset.x, w.y * vp.scale + vp.offset.y)
}

private fun nodeOrigin(state: GraphynEditorState, node: NodeRef): Offset {
    val p = state.nodePositionsByNodeId[node.id]
        ?: state.workflow?.nodePositions?.get(node.id)?.let { IntOffset(it.x, it.y) }
        ?: return Offset.Zero
    return Offset(p.x.toFloat(), p.y.toFloat())
}

/** All input/output ports in world space, positioned exactly where Graphyn renders their dots. */
private fun worldPorts(state: GraphynEditorState, nodeSpecs: NodeSpecRegistry, density: Density): List<WorldPort> {
    val wf = state.workflow ?: return emptyList()
    val cardW = 240f * density.density
    return buildList {
        for (node in wf.nodes) {
            val spec = nodeSpecs.resolve(node.type) ?: continue
            val o = nodeOrigin(state, node)
            spec.inputs.forEachIndexed { i, p ->
                val y = 28f + i * 22f + 11f
                add(WorldPort(node.id, p.name, p, true, Offset(o.x, o.y + y * density.density)))
            }
            spec.outputs.forEachIndexed { i, p ->
                val y = 28f + spec.inputs.size * 22f + 1f + i * 22f + 11f
                add(WorldPort(node.id, p.name, p, false, Offset(o.x + cardW, o.y + y * density.density)))
            }
        }
    }
}

/** Godot-style row hotzone: height = ROW, and it sticks out of the card edge so it's easy to grab. */
private fun portHotRect(wp: WorldPort, density: Density): Rect {
    val inner = 20f * density.density
    val outer = 26f * density.density
    val row = 22f * density.density
    val left = if (wp.isInput) wp.world.x - outer else wp.world.x - inner
    return Rect(left, wp.world.y - row / 2f, left + inner + outer, wp.world.y + row / 2f)
}

private fun hitOutputPort(
    state: GraphynEditorState,
    nodeSpecs: NodeSpecRegistry,
    world: Offset,
    density: Density,
): WorldPort? =
    worldPorts(state, nodeSpecs, density)
        .asReversed()
        .firstOrNull { !it.isInput && portHotRect(it, density).contains(world) }

private fun inputRingPorts(
    state: GraphynEditorState,
    nodeSpecs: NodeSpecRegistry,
    srcPort: PortSpec,
    snap: WorldPort?,
    density: Density,
): List<RingPort> =
    worldPorts(state, nodeSpecs, density)
        .filter { it.isInput }
        .mapNotNull { wp ->
            val compatible = PortCompatibility.isCompatible(wp.spec, srcPort)
            RingPort(
                screen = worldToScreen(state, wp.world),
                compatible = compatible,
                isSnap = snap != null && snap.nodeId == wp.nodeId && snap.portName == wp.portName,
            )
        }

/** Magnet: nearest compatible input whose row-hotzone contains the pointer. */
private fun findSnapTarget(
    state: GraphynEditorState,
    nodeSpecs: NodeSpecRegistry,
    out: WorldPort,
    world: Offset,
    density: Density,
): WorldPort? =
    worldPorts(state, nodeSpecs, density)
        .filter { it.isInput && it.nodeId != out.nodeId && PortCompatibility.isCompatible(it.spec, out.spec) }
        .filter { portHotRect(it, density).contains(world) }
        .minByOrNull { (world - it.world).getDistance() }

/** Nearest input port whose hotzone contains the point (used as the release fallback). */
private fun nearestInputPort(
    state: GraphynEditorState,
    nodeSpecs: NodeSpecRegistry,
    world: Offset,
    density: Density,
): WorldPort? =
    worldPorts(state, nodeSpecs, density)
        .filter { it.isInput && portHotRect(it, density).contains(world) }
        .minByOrNull { (world - it.world).getDistance() }