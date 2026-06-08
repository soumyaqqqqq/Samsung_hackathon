package com.friday.node.onboarding.ui.screens

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import com.friday.node.onboarding.model.SensorModule
import com.friday.node.onboarding.model.OnboardingStep
import com.friday.node.onboarding.viewmodel.OnboardingViewModel

// Theme colors from design.md (Collaborative Canvas style)
val FigmaBg = Color(0xFFF9F9F9)
val FigmaTextPrimary = Color(0xFF1B1B1B)
val FigmaTextSecondary = Color(0xFF4C4546)
val FigmaOutline = Color(0xFF7E7576)
val FigmaOutlineVariant = Color(0xFFCFC4C5)
val BlockLime = Color(0xFFD6FF3D)
val BlockLilac = Color(0xFFC6BFFF)
val BlockCream = Color(0xFFFFF9E3)
val BlockMint = Color(0xFFB2FFD6)
val BlockPink = Color(0xFFFFB8EB)
val BlockCoral = Color(0xFFFF8C70)
val BlockNavy = Color(0xFF10162F)

@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    deviceId: String,
    onOnboardingFinished: () -> Unit
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    
    // Periodically refresh the permission status when on the permissions screen
    LaunchedEffect(uiState.currentStep) {
        if (uiState.currentStep == 2 && context is Activity) {
            viewModel.checkPermissionsStatus(context)
        }
    }
    
    // Handle checking on app foregrounding
    DisposableEffect(Unit) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                if (uiState.currentStep == 2 && context is Activity) {
                    viewModel.checkPermissionsStatus(context)
                }
            }
        }
        val lifecycle = (context as? androidx.lifecycle.LifecycleOwner)?.lifecycle
        lifecycle?.addObserver(observer)
        onDispose {
            lifecycle?.removeObserver(observer)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(FigmaBg)
    ) {
        // Figma marketing editor dot grid canvas background
        Canvas(modifier = Modifier.fillMaxSize()) {
            val dotRadius = 1.dp.toPx()
            val spacing = 40.dp.toPx()
            val cols = (size.width / spacing).toInt() + 1
            val rows = (size.height / spacing).toInt() + 1
            for (i in 0 until cols) {
                for (j in 0 until rows) {
                    drawCircle(
                        color = Color(0x0F000000),
                        radius = dotRadius,
                        center = Offset(i * spacing, j * spacing)
                    )
                }
            }
        }

        val step = when (uiState.currentStep) {
            0 -> OnboardingStep.HERO
            1 -> OnboardingStep.IDENTITY
            2 -> OnboardingStep.PERMISSIONS
            3 -> OnboardingStep.INITIALIZATION
            else -> OnboardingStep.COMPLETE
        }

        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            when (step) {
                OnboardingStep.HERO -> {
                    HeroStepContent(
                        onNext = { viewModel.nextStep() }
                    )
                }
                OnboardingStep.IDENTITY -> {
                    IdentityStepContent(
                        name = uiState.userName,
                        dob = uiState.userDob,
                        onNameChanged = { viewModel.setUserName(it) },
                        onDobChanged = { viewModel.setUserDob(it) },
                        onBack = { viewModel.previousStep() },
                        onNext = { viewModel.nextStep() }
                    )
                }
                OnboardingStep.PERMISSIONS -> {
                    PermissionsStepContent(
                        modules = uiState.modules,
                        onModuleToggle = { moduleId ->
                            val module = uiState.modules.find { it.id == moduleId }
                            if (module != null) {
                                val turningOn = !module.isEnabled
                                viewModel.toggleModule(moduleId)
                                if (turningOn && context is Activity) {
                                    viewModel.requestPermissionForModule(context, moduleId)
                                }
                            }
                        },
                        onRequestPermissions = {
                            if (context is Activity) {
                                viewModel.requestPermissions(context)
                            }
                        },
                        onBack = { viewModel.previousStep() },
                        onNext = {
                            viewModel.nextStep()
                            viewModel.initializeSensingNode(deviceId) {
                                // Will auto transition when complete matches step
                            }
                        }
                    )
                }
                OnboardingStep.INITIALIZATION -> {
                    InitializationStepContent(
                        isInitializing = uiState.isInitializing,
                        modules = uiState.modules,
                        error = uiState.error,
                        onNextStep = { viewModel.nextStep() }
                    )
                }
                OnboardingStep.COMPLETE -> {
                    CompleteStepContent(
                        onFinish = onOnboardingFinished
                    )
                }
            }
        }
    }
}

@Composable
fun SwipeToStartButton(
    onSwipeComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val dragAmount = remember { Animatable(0f) }
    var isTriggered by remember { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(32.dp))
            .background(Color(0xFFF0F0F0))
            .border(2.dp, FigmaTextPrimary, RoundedCornerShape(32.dp))
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val knobSize = 56.dp
        val density = androidx.compose.ui.platform.LocalDensity.current
        
        val knobSizePx = with(density) { knobSize.toPx() }
        val paddingPx = with(density) { 4.dp.toPx() }
        val maxDragSafe = (widthPx - knobSizePx - paddingPx * 2).coerceAtLeast(0f)

        // Progress background
        val offsetDp = with(density) { dragAmount.value.toDp() }
        if (dragAmount.value > 0f) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .width(offsetDp + knobSize / 2 + 4.dp)
                    .background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(BlockMint, BlockLime)
                        )
                    )
            )
        }

        // Background text
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "SWIPE TO START",
                color = FigmaTextPrimary.copy(alpha = 0.7f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp
            )
        }

        // Draggable knob
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(4.dp)
                .offset(x = offsetDp)
                .size(knobSize)
                .clip(CircleShape)
                .background(FigmaTextPrimary)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            coroutineScope.launch {
                                if (dragAmount.value >= maxDragSafe * 0.85f) {
                                    if (!isTriggered) {
                                        isTriggered = true
                                        dragAmount.animateTo(maxDragSafe, animationSpec = spring())
                                        onSwipeComplete()
                                    }
                                } else {
                                    dragAmount.animateTo(0f, animationSpec = spring())
                                }
                            }
                        },
                        onDragCancel = {
                            coroutineScope.launch {
                                dragAmount.animateTo(0f, animationSpec = spring())
                            }
                        },
                        onHorizontalDrag = { change, dragDelta ->
                            change.consume()
                            coroutineScope.launch {
                                val target = (dragAmount.value + dragDelta).coerceIn(0f, maxDragSafe)
                                dragAmount.snapTo(target)
                            }
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            // Right arrow icon
            Canvas(modifier = Modifier.size(16.dp)) {
                val path = Path().apply {
                    moveTo(0f, size.height / 2)
                    lineTo(size.width, size.height / 2)
                    moveTo(size.width - 6.dp.toPx(), size.height / 2 - 6.dp.toPx())
                    lineTo(size.width, size.height / 2)
                    lineTo(size.width - 6.dp.toPx(), size.height / 2 + 6.dp.toPx())
                }
                drawPath(path, Color.White, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
            }
        }
    }
}

@Composable
fun HeroStepContent(onNext: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Top empty space / alignment
        Spacer(modifier = Modifier.height(32.dp))

        // Center Content
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Giant display text
            Text(
                text = "Hello, I'm FRIDAY.",
                color = FigmaTextPrimary,
                fontSize = 48.sp,
                lineHeight = 52.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = (-1.5).sp,
                textAlign = TextAlign.Center
            )
            
            Text(
                text = "Let's align your ecosystem.",
                color = FigmaTextSecondary,
                fontSize = 40.sp,
                lineHeight = 46.sp,
                fontWeight = FontWeight.Light,
                fontStyle = FontStyle.Italic,
                letterSpacing = (-1).sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        // Floating Action Area (Bottom)
        SwipeToStartButton(
            onSwipeComplete = onNext,
            modifier = Modifier
                .padding(bottom = 24.dp)
        )
    }
}

@Composable
fun IdentityStepContent(
    name: String,
    dob: String,
    onNameChanged: (String) -> Unit,
    onDobChanged: (String) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        item {
            Column {
                Spacer(modifier = Modifier.height(56.dp))
                
                Text(
                    text = "SYSTEM IDENTIFICATION",
                    color = FigmaTextSecondary,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                
                Text(
                    text = "Personal Identity",
                    color = FigmaTextPrimary,
                    fontSize = 44.sp,
                    lineHeight = 48.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-1.5).sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                // Name Input
                Column {
                    Text(
                        text = "LEGAL NAME / MONIKER",
                        color = FigmaTextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    OutlinedTextField(
                        value = name,
                        onValueChange = onNameChanged,
                        placeholder = { Text("Your name", color = FigmaOutlineVariant, fontSize = 20.sp) },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = FigmaTextPrimary,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color(0xFFF3F3F3),
                            unfocusedContainerColor = Color(0xFFF3F3F3),
                            focusedBorderColor = BlockLime,
                            unfocusedBorderColor = FigmaOutlineVariant,
                            cursorColor = FigmaTextPrimary
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // DOB Input
                Column {
                    Text(
                        text = "TEMPORAL ORIGIN (DOB)",
                        color = FigmaTextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    val context = LocalContext.current
                    fun showDatePicker() {
                        val calendar = java.util.Calendar.getInstance()
                        if (dob.isNotEmpty()) {
                            val parts = dob.split("/")
                            if (parts.size == 3) {
                                try {
                                    val day = parts[0].trim().toInt()
                                    val month = parts[1].trim().toInt() - 1
                                    val year = parts[2].trim().toInt()
                                    calendar.set(year, month, day)
                                } catch (e: java.lang.Exception) {
                                    // ignore
                                }
                            }
                        }
                        android.app.DatePickerDialog(
                            context,
                            { _, year, month, dayOfMonth ->
                                val formattedDate = String.format("%02d/%02d/%04d", dayOfMonth, month + 1, year)
                                onDobChanged(formattedDate)
                            },
                            calendar.get(java.util.Calendar.YEAR),
                            calendar.get(java.util.Calendar.MONTH),
                            calendar.get(java.util.Calendar.DAY_OF_MONTH)
                        ).show()
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showDatePicker() }
                    ) {
                        OutlinedTextField(
                            value = dob,
                            onValueChange = {},
                            placeholder = { Text("DD / MM / YYYY", color = FigmaOutlineVariant, fontSize = 20.sp) },
                            singleLine = true,
                            readOnly = true,
                            enabled = false,
                            trailingIcon = {
                                // Calendar icon
                                Canvas(modifier = Modifier.size(20.dp)) {
                                    drawRect(
                                        color = FigmaTextSecondary,
                                        topLeft = Offset(0f, 4.dp.toPx()),
                                        size = androidx.compose.ui.geometry.Size(size.width, size.height - 4.dp.toPx()),
                                        style = Stroke(width = 2.dp.toPx())
                                    )
                                    drawLine(FigmaTextSecondary, Offset(4.dp.toPx(), 0f), Offset(4.dp.toPx(), 6.dp.toPx()), strokeWidth = 2.dp.toPx())
                                    drawLine(FigmaTextSecondary, Offset(size.width - 4.dp.toPx(), 0f), Offset(size.width - 4.dp.toPx(), 6.dp.toPx()), strokeWidth = 2.dp.toPx())
                                    drawLine(FigmaTextSecondary, Offset(0f, 10.dp.toPx()), Offset(size.width, 10.dp.toPx()), strokeWidth = 1.5f.dp.toPx())
                                }
                            },
                            textStyle = androidx.compose.ui.text.TextStyle(
                                color = FigmaTextPrimary,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                disabledTextColor = FigmaTextPrimary,
                                disabledBorderColor = FigmaOutlineVariant,
                                disabledContainerColor = Color(0xFFF3F3F3),
                                disabledPlaceholderColor = FigmaOutlineVariant,
                                disabledTrailingIconColor = FigmaTextSecondary
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        // System Guide Section (Horizontal Bento list)
        item {
            Column(modifier = Modifier.padding(top = 16.dp)) {
                Text(
                    text = "SYSTEM GUIDE",
                    color = FigmaTextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    BentoCard(
                        title = "Passive Observation",
                        description = "Your assistant learns through nuance, not just commands. We observe patterns to anticipate needs before they arise.",
                        backgroundColor = BlockMint,
                        icon = { EyeIcon() }
                    )
                    BentoCard(
                        title = "Proactive Optimization",
                        description = "Dynamic scheduling and automated task batching to preserve your cognitive load for what truly matters.",
                        backgroundColor = BlockPink,
                        icon = { BoltIcon() }
                    )
                    BentoCard(
                        title = "Cross-Device Continuity",
                        description = "Flow seamlessly between your mobile, desktop, and wearable interfaces without losing context or momentum.",
                        backgroundColor = BlockLilac,
                        icon = { DevicesIcon() }
                    )
                }
            }
        }

        // Biometric Sync section
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(BlockCream)
                    .padding(24.dp)
            ) {
                Text(
                    text = "Biometric Confirmation",
                    color = FigmaTextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    text = "Securely anchor your identity using on-device biometrics. This optional sync enables seamless authentication across your ambient ecosystem while keeping your sensitive data encrypted and private.",
                    color = FigmaTextSecondary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .border(2.dp, FigmaTextPrimary, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        FingerprintIcon()
                    }
                    Text(
                        text = "Optional: Scan to sync biometric security profile",
                        color = FigmaTextSecondary,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                HorizontalDivider(color = FigmaOutlineVariant, thickness = 1.dp, modifier = Modifier.padding(vertical = 12.dp))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BulletPoint("Local Encryption: Your biometric hash never leaves your device.")
                    BulletPoint("Seamless Sync: Instant recognition across all connected nodes.")
                }
            }
        }

        // Action controls
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Button(
                    onClick = onBack,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                    modifier = Modifier
                        .weight(0.3f)
                        .height(56.dp)
                        .border(1.dp, FigmaOutline, RoundedCornerShape(12.dp))
                ) {
                    Text(text = "BACK", color = FigmaTextPrimary, fontFamily = FontFamily.Monospace)
                }

                Button(
                    onClick = {
                        if (name.isNotEmpty()) {
                            onNext()
                        }
                    },
                    enabled = name.isNotEmpty(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = FigmaTextPrimary),
                    modifier = Modifier
                        .weight(0.7f)
                        .height(56.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Begin Initialization",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Canvas(modifier = Modifier.size(16.dp)) {
                            val path = Path().apply {
                                moveTo(0f, size.height / 2)
                                lineTo(size.width, size.height / 2)
                                moveTo(size.width - 6.dp.toPx(), size.height / 2 - 6.dp.toPx())
                                lineTo(size.width, size.height / 2)
                                lineTo(size.width - 6.dp.toPx(), size.height / 2 + 6.dp.toPx())
                            }
                            drawPath(path, Color.White, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PermissionsStepContent(
    modules: List<SensorModule>,
    onModuleToggle: (Int) -> Unit,
    onRequestPermissions: () -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    val allGranted = modules.none { it.isEnabled && it.statusMessage != "GRANTED & ACTIVE" }
    
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        item {
            Column {
                Spacer(modifier = Modifier.height(56.dp))
                
                // Eyebrow tag
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(99.dp))
                        .background(BlockLime)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "SYSTEM IGNITION",
                        color = FigmaTextPrimary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                Text(
                    text = "Wake Up Your\nDigital Senses.",
                    color = FigmaTextPrimary,
                    fontSize = 44.sp,
                    lineHeight = 48.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-1.5).sp,
                    modifier = Modifier.padding(top = 16.dp)
                )
                
                Text(
                    text = "Help FRIDAY understand your world. Grant the permissions below so your assistant can learn your rhythms and adapt to your needs.",
                    color = FigmaTextSecondary,
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        // Modules Grid
        item {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                modules.forEach { module ->
                    FigmaPermissionCard(
                        module = module,
                        onToggle = { onModuleToggle(module.id) }
                    )
                }
            }
        }

        // Action Block
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Text(
                    text = "Ready to start?",
                    color = FigmaTextPrimary,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-1).sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Button(
                        onClick = onBack,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                        modifier = Modifier
                            .weight(0.3f)
                            .height(56.dp)
                            .border(1.dp, FigmaOutline, RoundedCornerShape(12.dp))
                    ) {
                        Text(text = "BACK", color = FigmaTextPrimary, fontFamily = FontFamily.Monospace)
                    }

                    if (!allGranted) {
                        Button(
                            onClick = onRequestPermissions,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = FigmaTextPrimary),
                            modifier = Modifier
                                .weight(0.7f)
                                .height(56.dp)
                        ) {
                            Text(
                                text = "GRANT SENSES",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    } else {
                        Button(
                            onClick = onNext,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = FigmaTextPrimary),
                            modifier = Modifier
                                .weight(0.7f)
                                .height(56.dp)
                        ) {
                            Text(
                                text = "INITIALIZE FRIDAY",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FigmaPermissionCard(
    module: SensorModule,
    onToggle: () -> Unit
) {
    val backgroundColor = when (module.title) {
        "Location & Environment" -> BlockCream
        "Accessibility & Touch" -> BlockPink
        "Notification & Activity" -> BlockLilac
        else -> BlockMint
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(backgroundColor)
            .padding(24.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = module.moduleNumber,
                    color = FigmaTextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = module.title,
                    color = FigmaTextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            
            // Customized Vector Draw scope
            Canvas(modifier = Modifier.size(36.dp)) {
                when (module.title) {
                    "Location & Environment" -> {
                        // Ring nodes sensors
                        drawCircle(Color.Black, radius = 4.dp.toPx(), center = Offset(size.width/2, size.height/2))
                        drawCircle(Color.Black, radius = 10.dp.toPx(), center = Offset(size.width/2, size.height/2), style = Stroke(width = 2.dp.toPx()))
                        drawCircle(Color.Black, radius = 16.dp.toPx(), center = Offset(size.width/2, size.height/2), style = Stroke(width = 1.5f.dp.toPx()))
                    }
                    "Accessibility & Touch" -> {
                        // Hand gestures
                        drawCircle(Color.Black, radius = 6.dp.toPx(), center = Offset(size.width/2, size.height/2), style = Stroke(width = 2.dp.toPx()))
                        drawCircle(Color.Black, radius = 12.dp.toPx(), center = Offset(size.width/2, size.height/2), style = Stroke(width = 1.dp.toPx()))
                        drawLine(Color.Black, Offset(size.width/2, size.height * 0.1f), Offset(size.width/2, size.height * 0.3f), strokeWidth = 2.dp.toPx())
                        drawLine(Color.Black, Offset(size.width * 0.1f, size.height / 2), Offset(size.width * 0.3f, size.height / 2), strokeWidth = 2.dp.toPx())
                    }
                    "Notification & Activity" -> {
                        // EKG / Heart graph
                        val path = Path().apply {
                            moveTo(0f, size.height / 2)
                            lineTo(size.width * 0.3f, size.height / 2)
                            lineTo(size.width * 0.4f, size.height * 0.2f)
                            lineTo(size.width * 0.5f, size.height * 0.8f)
                            lineTo(size.width * 0.6f, size.height / 2)
                            lineTo(size.width, size.height / 2)
                        }
                        drawPath(path, Color.Black, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = module.description,
            color = FigmaTextSecondary,
            fontSize = 14.sp,
            lineHeight = 20.sp
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Toggle shell
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val isOperational = module.isEnabled && module.statusMessage == "GRANTED & ACTIVE"
            val statusLabel = if (isOperational) "SENSOR OPERATIONAL" else "SENSOR INACTIVE"
            val labelColor = if (isOperational) FigmaTextPrimary else FigmaTextSecondary

            Text(
                text = statusLabel,
                color = labelColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )

            CustomSwitch(
                checked = module.isEnabled,
                onCheckedChange = { onToggle() }
            )
        }
    }
}

@Composable
fun CustomSwitch(
    checked: Boolean,
    onCheckedChange: () -> Unit
) {
    val trackColor by animateColorAsState(
        targetValue = if (checked) BlockMint else Color(0xFFE2E2E2),
        animationSpec = tween(250),
        label = "trackColor"
    )
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 20.dp else 0.dp,
        animationSpec = tween(250),
        label = "thumbOffset"
    )

    Box(
        modifier = Modifier
            .width(44.dp)
            .height(24.dp)
            .clip(CircleShape)
            .background(trackColor)
            .clickable { onCheckedChange() }
            .padding(2.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .padding(start = thumbOffset)
                .size(20.dp)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}

@Composable
fun BentoCard(
    title: String,
    description: String,
    backgroundColor: Color,
    icon: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .width(280.dp)
            .height(300.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(backgroundColor)
            .padding(24.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Box(modifier = Modifier.padding(bottom = 16.dp)) {
                icon()
            }
            Text(
                text = title,
                color = Color.Black,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                text = description,
                color = FigmaTextSecondary,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
        }
    }
}

@Composable
fun EyeIcon() {
    Canvas(modifier = Modifier.size(36.dp)) {
        val path = Path().apply {
            moveTo(size.width * 0.1f, size.height / 2)
            quadraticTo(size.width / 2, size.height * 0.15f, size.width * 0.9f, size.height / 2)
            quadraticTo(size.width / 2, size.height * 0.85f, size.width * 0.1f, size.height / 2)
        }
        drawPath(path, Color.Black, style = Stroke(width = 2.dp.toPx()))
        drawCircle(Color.Black, radius = 5.dp.toPx(), center = Offset(size.width / 2, size.height / 2))
    }
}

@Composable
fun BoltIcon() {
    Canvas(modifier = Modifier.size(36.dp)) {
        val path = Path().apply {
            moveTo(size.width * 0.6f, size.height * 0.1f)
            lineTo(size.width * 0.2f, size.height * 0.55f)
            lineTo(size.width * 0.5f, size.height * 0.55f)
            lineTo(size.width * 0.4f, size.height * 0.9f)
            lineTo(size.width * 0.8f, size.height * 0.45f)
            lineTo(size.width * 0.5f, size.height * 0.45f)
            close()
        }
        drawPath(path, Color.Black, style = Stroke(width = 2.dp.toPx()))
    }
}

@Composable
fun DevicesIcon() {
    Canvas(modifier = Modifier.size(36.dp)) {
        // Laptop screen outline
        drawRoundRect(
            color = Color.Black,
            topLeft = Offset(size.width * 0.1f, size.height * 0.25f),
            size = androidx.compose.ui.geometry.Size(size.width * 0.55f, size.height * 0.45f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx(), 4.dp.toPx()),
            style = Stroke(width = 2.dp.toPx())
        )
        // Laptop keyboard base line
        drawLine(
            color = Color.Black,
            start = Offset(size.width * 0.05f, size.height * 0.7f),
            end = Offset(size.width * 0.7f, size.height * 0.7f),
            strokeWidth = 3.dp.toPx()
        )
        // Mobile phone
        drawRoundRect(
            color = Color.Black,
            topLeft = Offset(size.width * 0.65f, size.height * 0.45f),
            size = androidx.compose.ui.geometry.Size(size.width * 0.25f, size.height * 0.35f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx(), 3.dp.toPx()),
            style = Stroke(width = 2.dp.toPx())
        )
    }
}

@Composable
fun FingerprintIcon() {
    Canvas(modifier = Modifier.size(24.dp)) {
        for (i in 1..4) {
            val radius = i * 4.dp.toPx()
            drawArc(
                color = Color.Black,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(size.width / 2 - radius, size.height / 2 - radius * 0.7f),
                size = androidx.compose.ui.geometry.Size(radius * 2, radius * 1.4f),
                style = Stroke(width = 1.5f.dp.toPx(), cap = StrokeCap.Round)
            )
        }
        drawLine(
            color = Color.Black,
            start = Offset(size.width / 2, size.height / 2),
            end = Offset(size.width / 2, size.height * 0.85f),
            strokeWidth = 1.5f.dp.toPx(),
            cap = StrokeCap.Round
        )
    }
}

@Composable
fun BulletPoint(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(text = "•", color = FigmaTextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(text = text, color = FigmaTextSecondary, fontSize = 12.sp, lineHeight = 16.sp)
    }
}

@Composable
fun InitializationStepContent(
    isInitializing: Boolean,
    modules: List<SensorModule>,
    error: String?,
    onNextStep: () -> Unit
) {
    // Automatically skip forward once finished
    LaunchedEffect(isInitializing, error) {
        if (!isInitializing && error == null) {
            onNextStep()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (isInitializing) {
            CircularProgressIndicator(
                color = FigmaTextPrimary,
                strokeWidth = 4.dp,
                modifier = Modifier.size(64.dp)
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            Text(
                text = "INITIALIZATION PROTOCOL IN PROGRESS",
                color = FigmaTextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "Aligning offline telemetry models to Friday Hub...",
                color = FigmaTextSecondary,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Show status per enabled module
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White, RoundedCornerShape(12.dp))
                    .border(1.dp, FigmaOutlineVariant, RoundedCornerShape(12.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                modules.filter { it.isEnabled }.forEach { module ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = module.title,
                            color = FigmaTextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = module.statusMessage,
                            color = if (module.statusMessage.contains("COMPLETE") || module.statusMessage.contains("ACTIVE")) FigmaTextPrimary else FigmaTextSecondary,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        } else if (error != null) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(BlockCoral.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Text("⚠️", fontSize = 28.sp)
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Text(
                text = "INITIALIZATION CORRUPTED",
                color = Color.Black,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = error,
                color = FigmaTextSecondary,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
    }
}

@Composable
fun CompleteStepContent(onFinish: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Success checkmark animation ring
        Box(
            modifier = Modifier.size(100.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    color = Color.Black,
                    style = Stroke(width = 3.dp.toPx())
                )
                
                // Draw checkmark path
                val path = Path().apply {
                    moveTo(size.width * 0.3f, size.height * 0.5f)
                    lineTo(size.width * 0.45f, size.height * 0.65f)
                    lineTo(size.width * 0.7f, size.height * 0.35f)
                }
                drawPath(
                    path = path,
                    color = Color.Black,
                    style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }

        Spacer(modifier = Modifier.height(36.dp))

        Text(
            text = "COGNITIVE SYNAPSE ACTIVE",
            color = Color.Black,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.sp
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "The local sensing node is now fully bound to Friday. All selected telemetry modules will feed telemetry streams to the hub.",
            color = FigmaTextSecondary,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(56.dp))

        Button(
            onClick = onFinish,
            shape = RoundedCornerShape(99.dp),
            colors = ButtonDefaults.buttonColors(containerColor = FigmaTextPrimary),
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
        ) {
            Text(
                text = "ENTER PORTAL",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp
            )
        }
    }
}
