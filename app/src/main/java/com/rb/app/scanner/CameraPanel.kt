package com.rb.app.scanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Size
import android.view.MotionEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors

@Composable
fun CameraPanel(modifier: Modifier, reading: (SerialRecognition.Reading) -> Unit, error: (String) -> Unit, group: Boolean = false) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; asked = true }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    if (!granted) {
        Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("اسمح بالكاميرا لقراءة الشرائح محليًا. لا تُحفظ الصور.")
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("السماح بالكاميرا") }
            if (asked) {
                Text("إن كان الإذن محظورًا، فعّله من إعدادات التطبيق. يمكنك أيضًا مراجعة الأرقام وإدخالها يدويًا.")
                TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))) }) { Text("فتح الإعدادات") }
            }
        }
        return
    }
    val previewView = remember { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE; scaleType = PreviewView.ScaleType.FIT_CENTER } }
    val latestReading by rememberUpdatedState(reading)
    val latestError by rememberUpdatedState(error)
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torch by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    Box(modifier) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        Box(Modifier.align(Alignment.Center).fillMaxWidth(.88f).height(140.dp).border(1.dp, Color.White, MaterialTheme.shapes.medium))
        Text(if (group) "رقم المجموعة داخل الإطار · المس للتركيز" else "شريحة واحدة · قرّب الرقم داخل الإطار · المس للتركيز", Modifier.align(Alignment.TopCenter).padding(8.dp), color = Color.White,
            style = MaterialTheme.typography.labelSmall)
        if (camera?.cameraInfo?.hasFlashUnit() == true) FilledTonalButton(onClick = { torch = !torch }, modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)) {
            Text(if (torch) "إطفاء الإضاءة" else "إضاءة")
        }
        failure?.let { message -> Surface(Modifier.align(Alignment.Center).padding(16.dp)) {
            Column(Modifier.padding(12.dp)) { Text(message); TextButton(onClick = { failure = null; retry++ }) { Text("إعادة تشغيل الكاميرا") } }
        } }
    }
    LaunchedEffect(camera, torch) {
        camera?.cameraControl?.enableTorch(torch)?.addListener({}, ContextCompat.getMainExecutor(context))
    }
    DisposableEffect(lifecycle, previewView, retry, group) {
        val executor = Executors.newSingleThreadExecutor()
        val main = ContextCompat.getMainExecutor(context)
        var disposed = false
        var provider: ProcessCameraProvider? = null
        var boundCamera: Camera? = null
        val cameraStateObserver = Observer<CameraState> { status ->
            if (!disposed) {
                if (status.error != null) failure = "الكاميرا غير متاحة حاليًا. أغلق التطبيقات التي تستخدمها ثم أعد المحاولة."
                else if (status.type == CameraState.Type.OPEN) failure = null
            }
        }
        val preview = Preview.Builder().build().apply { surfaceProvider = previewView.surfaceProvider }
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
                ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build())
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        val analyzer = SimAnalyzer(main, {
            if (!disposed && lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) latestReading(it)
        }, { if (!disposed) latestError("تعذرت قراءة إطار الكاميرا. ثبّت الشريحة أو أعد فتح الكاميرا؛ يتوفر التصحيح اليدوي.") }, group)
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (!disposed) try {
                provider = future.get()
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    granted = false
                } else if (provider?.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) != true) {
                    failure = "لا توجد كاميرا خلفية متاحة. يمكنك الإدخال اليدوي من المراجعة."
                } else {
                    analysis.setAnalyzer(executor, analyzer)
                    boundCamera = provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    camera = boundCamera
                    boundCamera.cameraInfo.cameraState.observe(lifecycle, cameraStateObserver)
                    previewView.setOnTouchListener { view, event ->
                        if (event.action == MotionEvent.ACTION_UP) {
                            val point = previewView.meteringPointFactory.createPoint(event.x, event.y)
                            camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
                            view.performClick()
                        }
                        true
                    }
                }
            } catch (_: Exception) { failure = "تعذر تشغيل الكاميرا. أغلق التطبيقات التي تستخدمها ثم أعد المحاولة." }
        }, main)
        onDispose {
            disposed = true
            boundCamera?.cameraInfo?.cameraState?.removeObserver(cameraStateObserver)
            boundCamera?.cameraControl?.enableTorch(false)
            analysis.clearAnalyzer()
            provider?.unbind(preview, analysis)
            previewView.setOnTouchListener(null)
            analyzer.close()
            executor.shutdown()
            camera = null
        }
    }
}
