@file:OptIn(com.ronjunevaldoz.graphyn.core.GraphynExperimentalApi::class)

package dev.ide.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ronjunevaldoz.graphyn.core.model.WorkflowValue
import com.ronjunevaldoz.graphyn.core.model.ConnectionRef
import com.ronjunevaldoz.graphyn.core.model.WorkflowDefinition
import com.ronjunevaldoz.graphyn.core.model.WorkflowNodePosition
import com.ronjunevaldoz.graphyn.core.model.NodeRef
import com.ronjunevaldoz.graphyn.core.model.NodeSpec
import com.ronjunevaldoz.graphyn.core.model.PortSpec
import com.ronjunevaldoz.graphyn.core.model.WorkflowType
import com.ronjunevaldoz.graphyn.core.registry.DefaultNodeSpecRegistry
import com.ronjunevaldoz.graphyn.core.serialization.toJson
import com.ronjunevaldoz.graphyn.editor.interaction.GraphynEditorIntent
import com.ronjunevaldoz.graphyn.editor.shell.GraphynEditorShell
import com.ronjunevaldoz.graphyn.editor.shell.GraphynEditorShellDependencies
import com.ronjunevaldoz.graphyn.editor.state.rememberGraphynEditorState
import com.ronjunevaldoz.graphyn.editor.theme.GraphynBranding

/**
 * Graphyn spike (v1): render a hardcoded demo workflow on the Graphyn canvas to prove the library
 * compiles + runs on Android (pan/zoom), that subgraph collapse ("caja") works, and that the graph
 * serializes to JSON via graphyn-core-serialization. No Kotlin-file wiring yet — that comes once the
 * canvas proves usable on-device (see docs/block-editing.md for the future translator).
 */
@Composable
fun CodeGraphView(modifier: Modifier = Modifier) {
    val specs = remember { demoNodeSpecRegistry() }
    val state = rememberGraphynEditorState(initialWorkflow = demoWorkflow(), nodeSpecs = specs)
    var json by remember { mutableStateOf<String?>(null) }

    Column(modifier) {
        Controls(
            onCaja = {
                val wf = state.workflow
                if (wf != null) {
                    state.selectedNodeIds = wf.nodes.map { it.id }.toSet()
                    state.dispatch(GraphynEditorIntent.CollapseSelectionToSubgraph)
                }
            },
            onExpand = {
                val wf = state.workflow
                val sub = wf?.nodes?.firstOrNull { it.type == "graphyn.subgraph" }
                if (sub != null) state.dispatch(GraphynEditorIntent.ExpandSubgraph(sub.id))
            },
            onLayout = { state.dispatch(GraphynEditorIntent.AutoLayout) },
            onJson = { json = state.workflow?.toJson() },
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            GraphynEditorShell(
                dependencies = GraphynEditorShellDependencies(nodeSpecs = specs),
                branding = GraphynBranding(appName = "Grafo"),
                state = state,
            )
        }
        if (json != null) {
            Text(
                text = json!!,
                style = MaterialTheme.typography.bodySmall,
                fontSize = 10.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
                    .padding(6.dp),
            )
        }
    }
}

@Composable
private fun Controls(
    onCaja: () -> Unit,
    onExpand: () -> Unit,
    onLayout: () -> Unit,
    onJson: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(6.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(onClick = onCaja) { Text("Caja", fontSize = 12.sp) }
        Button(onClick = onExpand) { Text("Expandir", fontSize = 12.sp) }
        Button(onClick = onLayout) { Text("AutoLayout", fontSize = 12.sp) }
        Button(onClick = onJson) { Text("Serializar", fontSize = 12.sp) }
        Spacer(Modifier.weight(1f))
    }
}

/** Single node spec for the demo: an opaque exec node with optional typed in/out ports. */
private fun demoNodeSpecRegistry() = DefaultNodeSpecRegistry().apply {
    register(
        NodeSpec(
            type = "demo.stmt",
            label = "Fila",
            inputs = listOf(
                PortSpec(name = "cond", type = WorkflowType.OpaqueType, required = false),
            ),
            outputs = listOf(
                PortSpec(name = "next", type = WorkflowType.OpaqueType, required = false),
            ),
            defaultValues = emptyMap<String, WorkflowValue>(),
            category = "demo",
            description = "Nodo de demo para el spike Graphyn",
        )
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
        "n1" to WorkflowNodePosition(x = 40, y = 40),
        "n2" to WorkflowNodePosition(x = 260, y = 120),
        "n3" to WorkflowNodePosition(x = 480, y = 200),
        "n4" to WorkflowNodePosition(x = 700, y = 280),
        "n5" to WorkflowNodePosition(x = 920, y = 360),
    ),
)