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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ronjunevaldoz.graphyn.core.execution.NodeExecutionStatus
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
import com.ronjunevaldoz.graphyn.editor.canvas.GraphynCanvasBounds
import com.ronjunevaldoz.graphyn.editor.canvas.NodeCanvasContext
import com.ronjunevaldoz.graphyn.editor.canvas.components.GraphynConnectionLayer
import com.ronjunevaldoz.graphyn.editor.canvas.components.PortCompatibility
import com.ronjunevaldoz.graphyn.editor.design.GraphynDs
import com.ronjunevaldoz.graphyn.editor.interaction.GraphynEditorIntent
import com.ronjunevaldoz.graphyn.editor.state.GraphynEditorState
import com.ronjunevaldoz.graphyn.editor.state.rememberGraphynEditorState
import com.ronjunevaldoz.graphyn.editor.state.fitToContent
import com.ronjunevaldoz.graphyn.editor.state.updateCanvasSize
import com.ronjunevaldoz.graphyn.ui.cards.FieldCardFactory
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Codex node-graph editor — Phase 3 (own gestures).
 *
 * Graphyn's built-in gestures are desktop-first (mouse-wheel zoom, click-click connect, hover) and are
 * unusable on a phone, so we do NOT compose `GraphynCanvasSurface`. Instead we render the graph ourselves
 * from the public state API — [GraphynCanvasBackdrop] + [GraphynConnectionLayer] + real [FieldCardFactory]
 * cards with the viewport transform applied via `graphicsLayer` — with NO internal gesture handlers, and
 * put a single full-screen [GraphGestures] overlay on top that owns every interaction Godot-style (like the
 * resize of NodeXStudio): 1-finger pan on empty canvas, pinch-to-zoom on the two-finger midpoint, grab a
 * card body to move it, and drag out of an output port to wire (magnet + green/red rings). Consuming every
 * pointer event here also guarantees the cards' internal tap/drag gestures (which would fight our layer)
 * never fire.
 */
@Composable
fun CodeGraphView(modifier: Modifier = Modifier) {
    val specs = remember { demoNodeSpecRegistry() }
    val state = rememberGraphynEditorState(
        initialWorkflow = demoWorkflow(),
        nodeSpecs = specs,
        // Effectively unlimited world: node placement clamps to [0, 60000] and panning is bound
        // by this rect, so there is always room to keep placing nodes ("se queda corto el viewport").
        canvasBounds = GraphynCanvasBounds(width = 60000, height = 60000),
    )

    var menu by remember { mutableStateOf<GraphMenu?>(null) }
    var search by remember { mutableStateOf("") }
    var showJson by remember { mutableStateOf(false) }
    var jsonText by remember { mutableStateOf("") }
    var selectMode by remember { mutableStateOf(false) }
    var renameFor by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }

    Box(modifier) {
        // Canvas: renders nodes/connections ourselves, NO library gesture handlers.
        GraphCanvas(state = state, nodeSpecs = specs, modifier = Modifier.fillMaxSize())

        // Gestures: pan / pinch-zoom / node-grab / connect. Consumes everything.
        GraphGestures(state = state, nodeSpecs = specs, selectMode = selectMode, modifier = Modifier.fillMaxSize())

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

/**
 * Renders the graph with Graphyn's own visuals but ZERO library gesture handlers, so our [GraphGestures]
 * layer is the only thing driving the interaction. Viewport transform is a plain `graphicsLayer` like the
 * library's canvas, scaled around top-left origin.
 */
@Composable
private fun GraphCanvas(state: GraphynEditorState, nodeSpecs: NodeSpecRegistry, modifier: Modifier = Modifier) {
    var initialFit by remember { mutableStateOf(true) }
    Box(
        modifier
            .background(GraphynDs.colors.canvasBackground)
            .onSizeChanged {
                state.updateCanvasSize(it)
                // First real size -> fit the whole graph once so the demo chain is visible on open;
                // afterwards the user's pans/zooms take over (AutoLayout also calls fitToContent).
                if (initialFit && it.width > 0 && it.height > 0) {
                    initialFit = false
                    state.fitToContent()
                }
            },
    ) {
        GraphBackdrop(state = state, modifier = Modifier.fillMaxSize())

        val workflow = state.workflow
        if (workflow != null && workflow.nodes.isNotEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(0f, 0f)
                        translationX = state.viewport.offset.x
                        translationY = state.viewport.offset.y
                        scaleX = state.viewport.scale
                        scaleY = state.viewport.scale
                    },
            ) {
                GraphGroupLayer(state)
                GraphynConnectionLayer(
                    workflow = workflow, state = state, nodeSpecs = nodeSpecs,
                    canvasCards = null,
                    draft = state.connectionDraft, draftPointer = state.connectionDraftPosition,
                    modifier = Modifier.fillMaxSize(),
                    color = GraphynDs.colors.connectionLine.copy(alpha = 0.6f),
                )
                GraphCardLayer(state, workflow, nodeSpecs)
                GraphPortDots(state, nodeSpecs)
            }
        }
    }
}

/** Reimplements the library's backdrop grid (its public API doesn't expose it) for our gesture-owned canvas. */
@Composable
private fun GraphBackdrop(state: GraphynEditorState, modifier: Modifier = Modifier) {
    val minorSpacing = with(LocalDensity.current) { 28.dp.toPx() }
    val majorSpacing = minorSpacing * 4f
    val dotColor = GraphynDs.colors.border
    Canvas(modifier) {
        val topLeft = state.viewport.screenToWorld(Offset.Zero)
        val bottomRight = state.viewport.screenToWorld(Offset(size.width, size.height))
        val worldLeft = min(topLeft.x, bottomRight.x)
        val worldTop = min(topLeft.y, bottomRight.y)
        val worldRight = max(topLeft.x, bottomRight.x)
        val worldBottom = max(topLeft.y, bottomRight.y)

        drawRect(brush = Brush.verticalGradient(listOf(dotColor.copy(alpha = 0.05f), Color.Transparent)))

        var x = floor(worldLeft / minorSpacing) * minorSpacing
        while (x <= worldRight + minorSpacing) {
            val sx = state.viewport.worldToScreen(Offset(x, 0f)).x
            drawLine(dotColor.copy(alpha = 0.18f), Offset(sx, 0f), Offset(sx, size.height), strokeWidth = 1f)
            x += minorSpacing
        }
        var y = floor(worldTop / minorSpacing) * minorSpacing
        while (y <= worldBottom + minorSpacing) {
            val sy = state.viewport.worldToScreen(Offset(0f, y)).y
            drawLine(dotColor.copy(alpha = 0.18f), Offset(0f, sy), Offset(size.width, sy), strokeWidth = 1f)
            y += minorSpacing
        }
        var mx = floor(worldLeft / majorSpacing) * majorSpacing
        while (mx <= worldRight + majorSpacing) {
            val sx = state.viewport.worldToScreen(Offset(mx, 0f)).x
            drawLine(dotColor.copy(alpha = 0.45f), Offset(sx, 0f), Offset(sx, size.height), strokeWidth = 1f)
            mx += majorSpacing
        }
        var my = floor(worldTop / majorSpacing) * majorSpacing
        while (my <= worldBottom + majorSpacing) {
            val sy = state.viewport.worldToScreen(Offset(0f, my)).y
            drawLine(dotColor.copy(alpha = 0.45f), Offset(0f, sy), Offset(size.width, sy), strokeWidth = 1f)
            my += majorSpacing
        }
    }
}

/** Group frames behind their member cards — a re-implementation of the library's internal group layer. */
@Composable
private fun GraphGroupLayer(state: GraphynEditorState) {
    state.groups.forEachIndexed { index, group ->
        val positions = group.nodeIds.mapNotNull { nodeId ->
            val idx = state.workflow?.nodes?.indexOfFirst { it.id == nodeId }?.takeIf { it >= 0 } ?: return@mapNotNull null
            state.nodePosition(nodeId, idx)
        }
        if (positions.isEmpty()) return@forEachIndexed
        val nodeSize = IntSize(220, 180)
        val minX = positions.minOf { it.x } - GroupPadding
        val minY = positions.minOf { it.y } - GroupPadding
        val maxX = positions.maxOf { it.x } + nodeSize.width + GroupPadding
        val maxY = positions.maxOf { it.y } + nodeSize.height + GroupPadding
        val fillColor = groupColors[index % groupColors.size]
        val borderColor = groupBorderColors[index % groupBorderColors.size]
        Box(
            Modifier
                .offset { IntOffset(minX, minY) }
                .size((maxX - minX).dp, (maxY - minY).dp)
                .drawBehind { drawRect(fillColor) },
            contentAlignment = Alignment.TopStart,
        ) {
            Text(
                group.label,
                modifier = Modifier.offset(x = 8.dp, y = 4.dp),
                color = borderColor,
                fontSize = 10.sp,
            )
        }
    }
}

private val groupColors = listOf(
    Color(0x336C63F7), Color(0x33F9A825), Color(0x334ADE80),
    Color(0x33F87171), Color(0x3338BDF8),
)
private val groupBorderColors = listOf(
    Color(0xFF6C63F7), Color(0xFFF9A825), Color(0xFF4ADE80),
    Color(0xFFF87171), Color(0xFF38BDF8),
)
private const val GroupPadding = 20

/** Node cards rendered via the real [FieldCardFactory], positioned by the layout state. */
@Composable
private fun GraphCardLayer(state: GraphynEditorState, workflow: WorkflowDefinition, nodeSpecs: NodeSpecRegistry) {
    workflow.nodes.forEachIndexed { index, node ->
        val spec = nodeSpecs.resolve(node.type) ?: fallbackSpec(node)
        val position = state.nodePosition(node.id, index)
        val factory = FieldCardFactory(inputRows = spec.inputs.size, outputRows = spec.outputs.size)
        val ctx = NodeCanvasContext(
            node = node,
            spec = spec,
            selected = state.selectedNodeId == node.id,
            executionStatus = state.executionStatusByNodeId[node.id] ?: NodeExecutionStatus.Idle,
            onSelect = { state.dispatch(GraphynEditorIntent.SelectNode(node.id)) },
            onMove = { delta -> state.dispatch(GraphynEditorIntent.MoveNode(node.id, delta)) },
            contentColor = GraphynDs.colors.textPrimary,
            executionOutputs = state.outputsFor(node.id),
        )
        Box(Modifier.offset { position }) {
            with(factory) { NodeCanvas(ctx) }
        }
    }
}

/** Fallback for node types not in the registry (e.g. a node created by "Caja"/collapse). */
private fun fallbackSpec(node: NodeRef) = NodeSpec(
    type = node.type,
    label = node.type.substringAfter('.').ifBlank { node.type },
    inputs = listOf(PortSpec("input", WorkflowType.OpaqueType, required = false)),
    outputs = listOf(PortSpec("output", WorkflowType.OpaqueType, required = false)),
    defaultValues = emptyMap(),
    category = null,
    description = null,
)

/** Decorative port dots drawn where Graphyn would render them (world space, under the gesture overlay). */
@Composable
private fun GraphPortDots(state: GraphynEditorState, nodeSpecs: NodeSpecRegistry) {
    val density = LocalDensity.current
    Canvas(Modifier.fillMaxSize()) {
        val r = 6f * density.density
        for (wp in worldPorts(state, nodeSpecs, density)) {
            val color = if (wp.isInput) SnapGreen else SnapAmber
            drawCircle(color = color, radius = r, center = wp.world)
            drawCircle(color = Color(0xFF171717), radius = r * 0.5f, center = wp.world)
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
            Chip(text = "Box Select", active = selectMode, onClick = onToggleSelect)
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
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .swallowTouches(),
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
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)).swallowTouches(),
        contentAlignment = Alignment.Center,
    ) {
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
    Box(Modifier.fillMaxSize().swallowTouches(), contentAlignment = Alignment.BottomCenter) {
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

/** Eats every pointer event over an overlay so the gest layer behind never pans/zooms while a menu is open. */
private fun Modifier.swallowTouches(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false).consume()
        var alive = true
        while (alive) {
            val ev = awaitPointerEvent()
            ev.changes.forEach { if (it.pressed && !it.isConsumed) it.consume() }
            alive = ev.changes.any { it.pressed }
        }
    }
}

// ------------------------------------------------------------------------------------
// Gestures — the ONLY interaction layer on the graph. Godot-style, ported from the
// NodeXStudio engine the user already shipped:
//   • 1 finger on empty canvas  -> pan (content follows finger; same convention as @user's engine)
//   • 2 fingers                 -> pinch zoom on the midpoint
//   • 1 finger on a card body   -> grab: selects it and drags it (all selected move together)
//   • 1 finger on an output port-> drag a cable; compatible inputs glow green (red when rejected);
//                                 the nearest compatible input in the row hotzone is the magnet snap
//                                 target; dropping on a snap connects, on another input connects to the
//                                 nearest, on empty cancels and cuts that output's previous wire.
//   • selectMode = Pan          -> drag on empty is marquee multi-select instead of pan.
// Every event is consumed so Graphyn's card-internal tap/drag handlers never double-fire.
// ------------------------------------------------------------------------------------

private const val DragSlopPx = 10f
private val SnapGreen = Color(0xFF4ADE80)
private val SnapAmber = Color(0xFFFFC107)
private val SnapRed = Color(0xFFE2583C)

private sealed interface DragMode {
    data object Pan : DragMode
    data object Marquee : DragMode
    data object Node : DragMode
}

private data class PinchState(val dStart: Float, val scaleStart: Float)

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
private fun GraphGestures(
    state: GraphynEditorState,
    nodeSpecs: NodeSpecRegistry,
    selectMode: Boolean,
    modifier: Modifier = Modifier,
) {
    var rings by remember { mutableStateOf<List<RingPort>>(emptyList()) }
    var dragOut by remember { mutableStateOf<Offset?>(null) }
    var marqueeStart by remember { mutableStateOf<Offset?>(null) }
    var marqueeCurrent by remember { mutableStateOf<Offset?>(null) }
    val density = LocalDensity.current

    // When the draft vanishes (complete/cancel), clear the rings+drag ripple.
    LaunchedEffect(state.connectionDraft) {
        if (state.connectionDraft == null) {
            rings = emptyList()
            dragOut = null
        }
    }

    Box(
        modifier.pointerInput(state, nodeSpecs, selectMode) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (down.changedToUp()) return@awaitEachGesture
                down.consume()

                val downScreen = down.position
                val downWorld = screenToWorld(state, downScreen)

                // Touches on the top control chip row are the chips' own — never a canvas gesture here.
                val chipGuard = with(density) { 60.dp.toPx() }
                if (downScreen.y <= chipGuard) return@awaitEachGesture

                // ---- output port: start a connection drag ----
                val out = hitOutputPort(state, nodeSpecs, downWorld, density)
                if (out != null) {
                    state.dispatch(GraphynEditorIntent.BeginConnection(out.nodeId, out.portName))
                    var lastScreen = downScreen
                    var began = false
                    var snapped: WorldPort? = null
                    var exited = false
                    dragOut = worldToScreen(state, out.world)
                    while (true) {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.filter { it.pressed }
                        if (pressed.isEmpty() || pressed.none { it.id == down.id }) break
                        if (pressed.size >= 2) {
                            // Second finger = pinch: cancel the cable and let the pinch take over.
                            state.dispatch(GraphynEditorIntent.CancelConnection)
                            exited = true
                            break
                        }
                        val c = pressed.first { it.id == down.id }
                        c.consume()
                        val screen = c.position
                        if (!began && (screen - downScreen).getDistance() < DragSlopPx) continue
                        began = true
                        snapped = findSnapTarget(state, nodeSpecs, out, screenToWorld(state, screen), density)
                        val target = snapped?.world ?: screenToWorld(state, screen)
                        state.dispatch(GraphynEditorIntent.UpdateConnectionDraftPosition(target))
                        rings = inputRingPorts(state, nodeSpecs, out.spec, snapped, density)
                        dragOut = worldToScreen(state, out.world)
                        lastScreen = screen
                    }
                    rings = emptyList()
                    dragOut = null
                    if (exited || !began) return@awaitEachGesture
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
                    return@awaitEachGesture
                }

                // ---- card body grab / pan / marquee ----
                // Hit-tested against the card's real rendered rect in px (NOT Graphyn's internal
                // nodeBounds, whose dp-as-px mismatch made only the header strip draggable).
                val grabbed = hitNodeId(state, nodeSpecs, downWorld, density)
                val mode = when {
                    grabbed != null -> DragMode.Node
                    selectMode -> DragMode.Marquee
                    else -> DragMode.Pan
                }
                if (mode == DragMode.Node && grabbed != null) {
                    if (selectMode) {
                        // Box Select: tapping toggles membership, so several nodes build a selection.
                        state.dispatch(GraphynEditorIntent.ToggleNodeSelection(grabbed))
                    } else if (state.effectiveSelectedNodeIds.none { it == grabbed }) {
                        state.dispatch(GraphynEditorIntent.SelectNode(grabbed))
                    }
                }

                var lastScreen = downScreen
                var dragged = false
                var pinch: PinchState? = null

                while (true) {
                    val ev = awaitPointerEvent()
                    val pressed = ev.changes.filter { it.pressed }
                    if (pressed.isEmpty() || pressed.none { it.id == down.id }) break

                    // ---- pinch zoom on the midpoint ----
                    if (pressed.size >= 2) {
                        val a = pressed[0].position
                        val b = pressed[1].position
                        val d = (a - b).getDistance().coerceAtLeast(1f)
                        if (pinch == null) pinch = PinchState(dStart = d, scaleStart = state.viewport.scale)
                        val nextScale = (pinch!!.scaleStart * d / pinch!!.dStart).coerceIn(0.05f, 5f)
                        val mid = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
                        val factor = nextScale / state.viewport.scale
                        if (factor != 1f) {
                            state.dispatch(GraphynEditorIntent.UpdateViewportTransform(Offset.Zero, factor, mid))
                        }
                        marqueeStart = null
                        marqueeCurrent = null
                        dragged = true
                        pressed.forEach { it.consume() }
                        continue
                    }
                    if (pinch != null) {
                        // One finger left: resume normal 1-finger gesture from here.
                        pinch = null
                        lastScreen = pressed.first { it.id == down.id }.position
                    }

                    val change = pressed.first { it.id == down.id }
                    change.consume()
                    val screen = change.position
                    val delta = screen - lastScreen
                    lastScreen = screen

                    if (!dragged && (screen - downScreen).getDistance() > viewConfiguration.touchSlop) {
                        dragged = true
                        if (mode == DragMode.Marquee) marqueeStart = downScreen
                    }
                    if (!dragged) continue

                    when (mode) {
                        DragMode.Node -> {
                            val scale = state.viewport.scale
                            val worldDelta = IntOffset(
                                (delta.x / scale).roundToInt(),
                                (delta.y / scale).roundToInt(),
                            )
                            if (worldDelta != IntOffset.Zero) state.dispatch(GraphynEditorIntent.MoveSelectedNodes(worldDelta))
                        }
                        DragMode.Pan -> state.dispatch(GraphynEditorIntent.UpdateViewportTransform(delta, 1f, screen))
                        DragMode.Marquee -> marqueeCurrent = screen
                    }
                }

                when (mode) {
                    DragMode.Marquee -> {
                        if (dragged && marqueeStart != null) {
                            finalizeMarquee(state, nodeSpecs, density, marqueeStart!!, marqueeCurrent ?: marqueeStart!!)
                        } else {
                            state.selectedNodeIds = emptySet()
                            state.selectedNodeId = null
                        }
                        marqueeStart = null
                        marqueeCurrent = null
                    }
                    DragMode.Pan -> if (!dragged) {
                        state.selectedNodeId = null
                        state.selectedNodeIds = emptySet()
                    }
                    DragMode.Node -> {}
                }
            }
        },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val ms = marqueeStart
            val mc = marqueeCurrent
            if (ms != null && mc != null) {
                val rect = Rect(minOf(ms.x, mc.x), minOf(ms.y, mc.y), maxOf(ms.x, mc.x), maxOf(ms.y, mc.y))
                val topLeft = Offset(rect.left, rect.top)
                val size = Size(rect.width, rect.height)
                drawRect(Color(0x3333B5E5), topLeft = topLeft, size = size, style = Stroke(width = 2f))
                drawRect(Color(0x2233B5E5), topLeft = topLeft, size = size)
            }
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

/** Marquee selection against each card's real rendered rect (world px). */
private fun finalizeMarquee(
    state: GraphynEditorState,
    nodeSpecs: NodeSpecRegistry,
    density: Density,
    startScreen: Offset,
    endScreen: Offset,
) {
    val a = state.screenToWorld(startScreen)
    val b = state.screenToWorld(endScreen)
    val worldRect = Rect(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y))
    val wf = state.workflow ?: return
    val selected = buildSet {
        for (node in wf.nodes) {
            val r = nodeWorldRect(state, nodeSpecs, node, density) ?: continue
            if (worldRect.overlaps(r) || r.contains(worldRect.topLeft) || r.contains(worldRect.bottomRight)) add(node.id)
        }
    }
    state.selectedNodeIds = selected
    state.selectedNodeId = selected.firstOrNull()
}

/** World-space rect of a node card exactly as [FieldCardFactory] renders it (240dp wide, height from port rows). */
private fun nodeWorldRect(state: GraphynEditorState, nodeSpecs: NodeSpecRegistry, node: NodeRef, density: Density): Rect? {
    val spec = nodeSpecs.resolve(node.type) ?: return null
    val o = nodeOrigin(state, node)
    val d = density.density
    val heightPx = (28f + spec.inputs.size * 22f + 1f + spec.outputs.size * 22f) * d
    return Rect(o.x, o.y, o.x + 240f * d, o.y + heightPx)
}

/** Topmost node whose rendered card contains the world point (z-wise last drawn = last in workflow order). */
private fun hitNodeId(state: GraphynEditorState, nodeSpecs: NodeSpecRegistry, world: Offset, density: Density): String? {
    val wf = state.workflow ?: return null
    for (i in wf.nodes.indices.reversed()) {
        val node = wf.nodes[i]
        val r = nodeWorldRect(state, nodeSpecs, node, density) ?: continue
        if (r.contains(world)) return node.id
    }
    return null
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
    val wf = state.workflow
    val idx = wf?.nodes?.indexOfFirst { it.id == node.id }?.takeIf { it >= 0 } ?: return Offset.Zero
    val p = state.nodePosition(node.id, idx)
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