@file:OptIn(ExperimentalMaterial3Api::class)
package com.rb.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import kotlinx.coroutines.launch
import com.rb.app.data.*
import com.rb.app.scanner.ScannerScreen

private data class Destination(val route: String, val label: String, val icon: ImageVector)
private val destinations = listOf(
    Destination("groups", "المجموعات", Icons.Outlined.Folder),
    Destination("scan", "المسح", Icons.Outlined.QrCodeScanner),
    Destination("archive", "الأرشيف", Icons.Outlined.Inventory2),
    Destination("search", "البحث", Icons.Outlined.Search),
)

@Composable
fun RbApp(repository: InventoryRepository) = RbTheme {
    val vm: InventoryViewModel = viewModel(factory = viewModelFactory { initializer { InventoryViewModel(repository) } })
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext as android.app.Application
    val exportVm: com.rb.app.export.ExportViewModel = viewModel(factory = viewModelFactory {
        initializer { com.rb.app.export.ExportViewModel(repository, appContext) }
    })
    val state by vm.state.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: "groups"
    val mainDestination = destinations.any { it.route == route }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val finishScan: (String?, ScanResult) -> Unit = { id, result ->
        nav.navigate(if (id == null) "archive" else "group/$id") {
            popUpTo("groups")
            launchSingleTop = true
        }
        scope.launch { snackbar.showSnackbar("تم حفظ ${result.added + result.moved} شريحة · متجاهلة: ${result.ignored}") }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (mainDestination) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                destinations.forEach { item ->
                    NavigationBarItem(selected = route == item.route,
                        onClick = { nav.navigate(item.route) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true; restoreState = true
                        } },
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = { Text(item.label) })
                }
            }
        },
    ) { padding ->
        NavHost(navController = nav, startDestination = "groups", modifier = Modifier.padding(padding)) {
            composable("groups") { GroupsScreen(state, create = { vm.clearError(); nav.navigate("create") }, open = { nav.navigate("group/$it") }, export = { nav.navigate("export") }, history = { nav.navigate("transfers") }) }
            composable("transfers") { TransferHistoryScreen(repository, back = { nav.popBackStack() }, open = { nav.navigate("transfer/$it") }) }
            composable("transfer/{id}") { historyEntry ->
                TransferHistoryScreen(repository, id = requireNotNull(historyEntry.arguments?.getString("id")), back = { nav.popBackStack() })
            }
            composable("export") { ExportScreen(exportVm) { nav.popBackStack() } }
            composable("create") { CreateScreen(vm, back = { nav.popBackStack() }) }
            composable("group/{id}") { detailEntry ->
                val id = requireNotNull(detailEntry.arguments?.getString("id"))
                LaunchedEffect(id) { vm.clearError() }
                DetailsScreen(repository, state, id, vm, back = { nav.popBackStack() }, scan = { nav.navigate("scanGroup/$id") })
            }
            composable("scan") { ScannerScreen(repository, state.groups, done = finishScan) }
            composable("scanGroup/{id}") { scanEntry ->
                ScannerScreen(repository, state.groups, initial = scanEntry.arguments?.getString("id"), done = finishScan)
            }
            composable("archive") { StockScreen(repository, state.groups) }
            composable("search") { StockScreen(repository, state.groups, global = true) }
        }
    }
}

@Composable
private fun PageHeader(title: String, subtitle: String? = null, back: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Column {
        TopAppBar(title = { Text(title, fontWeight = FontWeight.Bold) }, windowInsets = WindowInsets(0, 0, 0, 0),
            navigationIcon = { if (back != null) IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "رجوع") } },
            actions = actions,
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        if (subtitle != null) Text(subtitle, Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun NumberText(value: String, modifier: Modifier = Modifier, large: Boolean = false) {
    Text(value, modifier, fontFamily = NumberFont, fontWeight = FontWeight.SemiBold,
        style = TextStyle(textDirection = TextDirection.Ltr, fontSize = if (large) 28.sp else 18.sp, lineHeight = if (large) 34.sp else 25.sp))
}

@Composable
private fun EmptyMessage(icon: ImageVector, title: String, description: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant) {
            Icon(icon, null, Modifier.padding(18.dp).size(30.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun ErrorMessage(message: String?) {
    if (message != null) Text(message, Modifier.fillMaxWidth().padding(vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun GroupsScreen(state: InventoryState, create: () -> Unit, open: (String) -> Unit, export: () -> Unit, history: () -> Unit) {
    var filter by rememberSaveable { mutableStateOf("") }
    val visibleGroups = remember(state.groups, filter) {
        val text = InputRules.code(filter)
        state.groups.filter { it.group.code.contains(text) || it.group.ownerName.contains(filter.trim(), ignoreCase = true) }
    }
    Column(Modifier.fillMaxSize()) {
        PageHeader("المجموعات", "Rb  /  إدارة الشرائح محليًا", actions = {
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "قائمة التطبيق") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("سجل النقل") }, leadingIcon = { Icon(Icons.Outlined.History, null) }, onClick = { menu = false; history() })
                    DropdownMenuItem(text = { Text("تصدير إلى Excel") }, leadingIcon = { Icon(Icons.Outlined.TableView, null) }, onClick = { menu = false; export() })
                }
            }
        })
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.large) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text("إجمالي الشرائح", style = MaterialTheme.typography.titleMedium)
                            Text("في المجموعات والأرشيف", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        NumberText(state.total.toString(), large = true)
                    }
                }
            }
            item {
                FlowRow(Modifier.fillMaxWidth(), itemVerticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("مجموعاتك · ${state.groups.size}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    FilledTonalButton(onClick = create) { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("إنشاء مجموعة") }
                }
            }
            item {
                OutlinedTextField(filter, { filter = it }, label = { Text("بحث المجموعات") }, placeholder = { Text("رقم المجموعة أو المسؤول") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true,
                    trailingIcon = { if (filter.isNotEmpty()) IconButton(onClick = { filter = "" }) { Icon(Icons.Outlined.Close, "مسح فلتر المجموعات") } },
                    modifier = Modifier.fillMaxWidth())
            }
            if (!state.loading && state.groups.isNotEmpty() && visibleGroups.isEmpty()) item {
                EmptyMessage(Icons.Outlined.SearchOff, "لا توجد مجموعات مطابقة", "غيّر رقم المجموعة أو اسم المسؤول أو امسح البحث.")
            }
            when {
                state.loading -> item { Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                state.error != null -> item { ErrorMessage(state.error) }
                state.groups.isEmpty() -> item { EmptyMessage(Icons.Outlined.FolderOpen, "ابدأ بمجموعتك الأولى", "أنشئ مجموعة برقمها واسم المسؤول عنها. ستظهر أعداد الشرائح هنا تلقائيًا.") }
            }
            items(visibleGroups, key = { it.group.id }) { summary ->
                Surface(onClick = { open(summary.group.id) }, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Folder, null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            NumberText(summary.group.code.takeLast(4))
                            if (summary.group.code.length > 4) Text(summary.group.code, fontFamily = NumberFont, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr))
                            Text(summary.group.ownerName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) { NumberText(summary.simCount.toString()); Text("شريحة", style = MaterialTheme.typography.labelSmall) }
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateScreen(vm: InventoryViewModel, back: () -> Unit) {
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    var scanning by rememberSaveable { mutableStateOf(false) }
    var code by rememberSaveable { mutableStateOf("") }
    var owner by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf(false) }
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val badCode = submitted && !InputRules.validCode(InputRules.code(code))
    val badOwner = submitted && !InputRules.validOwner(owner)
    Column(Modifier.fillMaxSize().imePadding()) {
        PageHeader("إنشاء مجموعة", back = back)
        LazyColumn(contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                OutlinedTextField(value = code, onValueChange = { code = it; vm.clearError() }, label = { Text("رقم المجموعة") },
                    trailingIcon = { IconButton(onClick = { scanning = true }, enabled = !busy) { Icon(Icons.Outlined.QrCodeScanner, "مسح رقم المجموعة") } }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = TextStyle(fontFamily = NumberFont, textDirection = TextDirection.Ltr),
                    singleLine = true, isError = badCode, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                    supportingText = if (badCode) { { Text("أدخل من 1 إلى 64 خانة رقمية") } } else null)
            }
            item { OwnerField(owner, { owner = it; vm.clearError() }, badOwner, !busy) }
            item {
                ErrorMessage(error)
                Button(onClick = { submitted = true; if (InputRules.validCode(InputRules.code(code)) && InputRules.validOwner(owner)) { focus.clearFocus(); vm.create(code, owner, back) } },
                    enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp) else Icon(Icons.Outlined.Check, null)
                    Spacer(Modifier.width(8.dp)); Text(if (busy) "جارٍ الحفظ…" else "حفظ المجموعة")
                }
            }
        }
    }
    if (scanning) com.rb.app.scanner.CaptureDialog(group = true, dismiss = { scanning = false },
        confirm = { code = it; scanning = false; vm.clearError() })
}

@Composable
private fun OwnerField(owner: String, change: (String) -> Unit, invalid: Boolean, enabled: Boolean) {
    OutlinedTextField(value = owner, onValueChange = change, label = { Text("اسم المسؤول") }, singleLine = true,
        enabled = enabled, isError = invalid, modifier = Modifier.fillMaxWidth(),
        supportingText = if (invalid) { { Text("أدخل اسمًا من 1 إلى 120 حرفًا") } } else null)
}

@Composable
private fun DetailsScreen(repository: InventoryRepository, state: InventoryState, id: String, vm: InventoryViewModel, back: () -> Unit, scan: () -> Unit) {
    val summary = state.groups.find { it.group.id == id }
    var editing by rememberSaveable(id) { mutableStateOf(false) }
    var confirming by rememberSaveable(id) { mutableStateOf(false) }
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        PageHeader("تفاصيل المجموعة", back = back)
        if (summary == null) {
            if (state.loading) CircularProgressIndicator(Modifier.padding(24.dp))
            else EmptyMessage(Icons.Outlined.FolderOff, "المجموعة غير متاحة", state.error ?: "ارجع إلى المجموعات لعرض القائمة الحالية.")
        } else StockScreen(repository, state.groups, groupId = id, header = {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) { NumberText(summary.group.code); Text(summary.group.ownerName, fontWeight = FontWeight.Bold) }
                    TextButton(onClick = { vm.clearError(); editing = true }, enabled = !busy) { Text("تعديل المسؤول") }
                }
                Button(onClick = scan, modifier = Modifier.fillMaxWidth()) { Text("مسح وإضافة شرائح") }
            }
        }, footer = {
            Column(Modifier.padding(16.dp)) {
                ErrorMessage(error)
                OutlinedButton(onClick = { vm.clearError(); confirming = true }, enabled = summary.simCount == 0 && !busy,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error), modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.DeleteOutline, null); Spacer(Modifier.width(8.dp)); Text("حذف المجموعة")
                }
                Text(if (summary.simCount > 0) "لا يمكن حذف مجموعة تحتوي على شرائح." else "يمكن حذف المجموعة ما دامت فارغة.", style = MaterialTheme.typography.bodySmall)
            }
        })
    }
    if (editing && summary != null) {
        var owner by rememberSaveable { mutableStateOf(summary.group.ownerName) }
        var submitted by rememberSaveable { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { if (!busy) { editing = false; vm.clearError() } }, title = { Text("تعديل اسم المسؤول") },
            text = { Column { OwnerField(owner, { owner = it; vm.clearError() }, submitted && !InputRules.validOwner(owner), !busy); ErrorMessage(error) } },
            confirmButton = { TextButton(enabled = !busy, onClick = { submitted = true; if (InputRules.validOwner(owner)) vm.rename(id, owner) { editing = false } }) { Text("حفظ") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { editing = false; vm.clearError() }) { Text("إلغاء") } })
    }
    if (confirming && summary != null) AlertDialog(onDismissRequest = { if (!busy) confirming = false },
        title = { Text("حذف المجموعة نهائيًا؟") },
        text = { Column { Text("سيُحذف سجل هذه المجموعة الفارغة. لا يمكن التراجع عن الحذف."); NumberText(summary.group.code); ErrorMessage(error) } },
        confirmButton = { TextButton(enabled = !busy, onClick = { vm.delete(id) { confirming = false; back() } }) { Text("حذف نهائي", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(enabled = !busy, onClick = { confirming = false; vm.clearError() }) { Text("إلغاء") } })
}
