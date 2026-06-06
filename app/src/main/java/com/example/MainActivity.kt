package com.example

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.ui.theme.WealthTheme
import com.example.ui.theme.LocalIsDarkMode
import androidx.compose.runtime.CompositionLocalProvider
import com.example.R
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class WealthViewModel(application: Application, private val savedStateHandle: SavedStateHandle) : AndroidViewModel(application) {
    private val db by lazy {
        Room.databaseBuilder(
            application,
            AppDatabase::class.java, "wealth-db"
        )
        .fallbackToDestructiveMigration()
        .build()
    }
    
    private val repository by lazy { FinancialRepository(db) }

    private val prefs = application.getSharedPreferences("wealth_prefs", android.content.Context.MODE_PRIVATE)

    private val _isDarkMode = MutableStateFlow(prefs.getBoolean("dark_mode", false))
    val isDarkMode: StateFlow<Boolean> = _isDarkMode

    fun toggleTheme() {
        val newValue = !_isDarkMode.value
        prefs.edit().putBoolean("dark_mode", newValue).apply()
        _isDarkMode.value = newValue
    }

    private val _currencySymbol = MutableStateFlow(prefs.getString("currency", "$") ?: "$")
    val currencySymbol: StateFlow<String> = _currencySymbol

    fun setCurrency(symbol: String) {
        prefs.edit().putString("currency", symbol).apply()
        _currencySymbol.value = symbol
    }

    private val _isAuthenticated = savedStateHandle.getStateFlow("auth", false)
    val isAuthenticated: StateFlow<Boolean> = _isAuthenticated

    fun setAuthenticated(value: Boolean) {
        savedStateHandle["auth"] = value
    }

    private val _pinState = MutableStateFlow(prefs.getString("unlock_pin", null))
    val pinState: StateFlow<String?> = _pinState

    fun setPin(pin: String) {
        prefs.edit().putString("unlock_pin", pin).apply()
        _pinState.value = pin
    }

    fun hasPin(): Boolean = _pinState.value != null
    fun clearPin() {
        prefs.edit().remove("unlock_pin").apply()
        _pinState.value = null
    }

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _sortBy = MutableStateFlow("Value (High)")
    val sortBy: StateFlow<String> = _sortBy

    private val _selectedCategory = MutableStateFlow<String?>(null)
    val selectedCategory: StateFlow<String?> = _selectedCategory

    fun setSearchQuery(query: String) { _searchQuery.value = query }
    fun setSortBy(sort: String) { _sortBy.value = sort }
    fun setSelectedCategory(category: String?) {
        _selectedCategory.value = if (_selectedCategory.value == category) null else category
    }

    val items: StateFlow<List<FinancialItem>> = combine(
        repository.allItems,
        _searchQuery,
        _sortBy,
        _selectedCategory
    ) { allItems, query, sort, category ->
        allItems.filter { item ->
            (query.isEmpty() || item.name.contains(query, ignoreCase = true) || item.type.contains(query, ignoreCase = true)) &&
            (category == null || item.type == category)
        }.let { filtered ->
            when (sort) {
                "Value (High)" -> filtered.sortedByDescending { it.balance }
                "Value (Low)" -> filtered.sortedBy { it.balance }
                "Name (A-Z)" -> filtered.sortedBy { it.name }
                "Rate (High)" -> filtered.sortedByDescending { it.interestRate }
                else -> filtered
            }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )
        
    fun saveItem(item: FinancialItem) = viewModelScope.launch {
        if (item.id == 0) repository.insert(item) else repository.update(item)
    }
    
    fun deleteItem(id: Int) = viewModelScope.launch {
        repository.deleteById(id)
    }
}

class MainActivity : ComponentActivity() {
    private val viewModel: WealthViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        setContent {
            val isDarkMode by viewModel.isDarkMode.collectAsStateWithLifecycle()
            WealthTheme(darkTheme = isDarkMode) {
                MainScreen(viewModel)
            }
        }
    }
}

@Composable
fun MainScreen(viewModel: WealthViewModel) {
    val isAuthenticated by viewModel.isAuthenticated.collectAsStateWithLifecycle()
    
    if (!isAuthenticated) {
        PinVerificationScreen(viewModel)
    } else {
        WealthAppScreen(viewModel)
    }
}

@Composable
fun PinVerificationScreen(viewModel: WealthViewModel) {
    val currentPin by viewModel.pinState.collectAsStateWithLifecycle()
    val hasPinExists = currentPin != null
    var screenState by remember(hasPinExists) { 
        mutableStateOf(if (hasPinExists) "ENTER" else "WELCOME") 
    }
    var enteredPin by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    
    val isDarkMode = LocalIsDarkMode.current
    val bgColor = if (isDarkMode) Color(0xFF0F172A) else Color(0xFFE9EEF9)
    val blob1Color = if (isDarkMode) Color(0x333B82F6) else Color(0x3393C5FD)
    val blob2Color = if (isDarkMode) Color(0x336366F1) else Color(0x44C7D2FE)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val r1 = (size.width * 0.8f).coerceAtLeast(1f)
            val r2 = (size.width * 0.8f).coerceAtLeast(1f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(blob1Color, Color.Transparent),
                    center = Offset(size.width * -0.1f, size.height * -0.1f),
                    radius = r1
                ),
                radius = r1,
                center = Offset(size.width * -0.1f, size.height * -0.1f)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(blob2Color, Color.Transparent),
                    center = Offset(size.width * 1.1f, size.height * 1.1f),
                    radius = r2
                ),
                radius = r2,
                center = Offset(size.width * 1.1f, size.height * 1.1f)
            )
        }
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.circular_wealth_logo_1780144080896),
                contentDescription = "App Logo",
                modifier = Modifier
                    .size(100.dp)
                    .clip(CircleShape)
                    .border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape),
                contentScale = ContentScale.Fit
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            when (screenState) {
                "WELCOME" -> {
                    Text(
                        text = "Secure Your Portfolio",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isDarkMode) Color.White else Color(0xFF191C1E)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Set a 4-digit security PIN to protect your wealth data, or skip setup to proceed directly.",
                        fontSize = 14.sp,
                        color = if (isDarkMode) Color(0xFF94A3B8) else Color(0xFF64748B),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    Spacer(modifier = Modifier.height(48.dp))
                    Button(
                        onClick = { screenState = "SET_PIN" },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0061A4))
                    ) {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Create Unlock PIN", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { viewModel.setAuthenticated(true) },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF0061A4))
                    ) {
                        Text("Skip Setup & Launch", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    }
                }
                "SET_PIN" -> {
                    Text(
                        text = "Create Unlock PIN",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isDarkMode) Color.White else Color(0xFF191C1E)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Enter a 4-digit PIN to secure My Wealth.",
                        fontSize = 14.sp,
                        color = if (isDarkMode) Color(0xFF94A3B8) else Color(0xFF64748B)
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    PinIndicatorDots(enteredPin.length)
                    
                    Spacer(modifier = Modifier.height(32.dp))
                    
                    PinKeypad(
                        onNumberClick = { num ->
                            if (enteredPin.length < 4) {
                                enteredPin += num
                                if (enteredPin.length == 4) {
                                    viewModel.setPin(enteredPin)
                                    viewModel.setAuthenticated(true)
                                }
                            }
                        },
                        onDeleteClick = {
                            if (enteredPin.isNotEmpty()) {
                                enteredPin = enteredPin.dropLast(1)
                            }
                        },
                        showSkip = true,
                        onSkipClick = {
                            viewModel.setAuthenticated(true)
                        }
                    )
                }
                "ENTER" -> {
                    Text(
                        text = "Welcome Back",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isDarkMode) Color.White else Color(0xFF191C1E)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = errorMsg ?: "Enter your 4-digit PIN to unlock.",
                        fontSize = 14.sp,
                        color = if (errorMsg != null) Color(0xFFEF4444) else (if (isDarkMode) Color(0xFF94A3B8) else Color(0xFF64748B)),
                        fontWeight = if (errorMsg != null) FontWeight.SemiBold else FontWeight.Normal
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    PinIndicatorDots(enteredPin.length)
                    
                    Spacer(modifier = Modifier.height(32.dp))
                    
                    PinKeypad(
                        onNumberClick = { num ->
                            if (enteredPin.length < 4) {
                                errorMsg = null
                                enteredPin += num
                                if (enteredPin.length == 4) {
                                    if (enteredPin == currentPin) {
                                        viewModel.setAuthenticated(true)
                                    } else {
                                        errorMsg = "Incorrect PIN. Try again."
                                        enteredPin = ""
                                    }
                                }
                            }
                        },
                        onDeleteClick = {
                            if (enteredPin.isNotEmpty()) {
                                enteredPin = enteredPin.dropLast(1)
                            }
                        },
                        showSkip = true,
                        onSkipClick = {
                            viewModel.clearPin()
                            viewModel.setAuthenticated(true)
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun PinIndicatorDots(length: Int) {
    val isDarkMode = LocalIsDarkMode.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(4) { idx ->
            val isActive = idx < length
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(if (isActive) (if (isDarkMode) Color(0xFF60A5FA) else Color(0xFF0061A4)) else (if (isDarkMode) Color.White.copy(alpha = 0.2f) else Color(0xFFCBD5E1)))
                    .border(1.dp, if (isActive) (if (isDarkMode) Color(0xFF60A5FA) else Color(0xFF0061A4)) else (if (isDarkMode) Color.White.copy(alpha = 0.35f) else Color(0xFF94A3B8).copy(alpha = 0.5f)), CircleShape)
            )
        }
    }
}

@Composable
fun PinKeypad(
    onNumberClick: (String) -> Unit,
    onDeleteClick: () -> Unit,
    showSkip: Boolean = false,
    onSkipClick: () -> Unit = {}
) {
    val isDarkMode = LocalIsDarkMode.current
    val keys = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf(if (showSkip) "Skip" else "", "0", "◀")
    )
    
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        keys.forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                row.forEach { valStr ->
                    if (valStr.isEmpty()) {
                        Spacer(modifier = Modifier.size(72.dp))
                    } else if (valStr == "Skip") {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .clickable { onSkipClick() },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Bypass", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (isDarkMode) Color(0xFF60A5FA) else Color(0xFF0061A4))
                        }
                    } else {
                        val isDelete = valStr == "◀"
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(if (isDelete) Color.Transparent else (if (isDarkMode) Color.White.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.35f)))
                                .border(1.dp, if (isDelete) Color.Transparent else (if (isDarkMode) Color.White.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.5f)), CircleShape)
                                .clickable {
                                    if (isDelete) onDeleteClick() else onNumberClick(valStr)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (isDelete) {
                                Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = "Delete", tint = if (isDarkMode) Color.White else Color(0xFF475569))
                            } else {
                                Text(valStr, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = if (isDarkMode) Color.White else Color(0xFF191C1E))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun WealthAppScreen(viewModel: WealthViewModel) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val currencySymbol by viewModel.currencySymbol.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val sortBy by viewModel.sortBy.collectAsStateWithLifecycle()
    val selectedCategory by viewModel.selectedCategory.collectAsStateWithLifecycle()
    
    val configuration = LocalConfiguration.current
    val columns = when {
        configuration.screenWidthDp < 600 -> 1
        configuration.screenWidthDp < 960 -> 2
        else -> 3
    }

    val isDarkMode by viewModel.isDarkMode.collectAsStateWithLifecycle()
    
    var currentTabIsDebt by remember { mutableStateOf(false) }
    var showDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showGrowthDialog by remember { mutableStateOf(false) }
    var editingItem by remember { mutableStateOf<FinancialItem?>(null) }
    var itemToDelete by remember { mutableStateOf<FinancialItem?>(null) }
    
    val assetsTotal by remember(items) { derivedStateOf { items.filter { !it.isDebt }.sumOf { it.balance } } }
    val debtsTotal by remember(items) { derivedStateOf { items.filter { it.isDebt }.sumOf { it.balance } } }
    val netWorth by remember(assetsTotal, debtsTotal) { derivedStateOf { assetsTotal - debtsTotal } }
    
    val validStockItems by remember(items) { derivedStateOf { items.filter { it.shares != null && it.purchasePrice != null && it.currentPrice != null } } }
    val unrealizedGains by remember(validStockItems) { derivedStateOf { validStockItems.sumOf { (it.shares!! * it.currentPrice!!) - (it.shares!! * it.purchasePrice!!) } } }
    val totalCostBasis by remember(validStockItems) { derivedStateOf { validStockItems.sumOf { it.shares!! * it.purchasePrice!! } } }
    
    val pctGain by remember(unrealizedGains, totalCostBasis) { 
        derivedStateOf { if (totalCostBasis > 0) (unrealizedGains / totalCostBasis) * 100 else 0.0 } 
    }
    
    val pctGainStr = if (pctGain >= 0) "+%.1f%%".format(pctGain) else "%.1f%%".format(pctGain)
    val pctGainColor = if (pctGain >= 0) Color(0xFF15803D) else Color(0xFFB91C1C)
    val pctGainBgColor = if (pctGain >= 0) Color(0xFFDCFCE7) else Color(0xFFFEE2E2)
    val showBadge = totalCostBasis > 0.0

    val bgColor = if (isDarkMode) Color(0xFF0F172A) else Color(0xFFE9EEF9)
    val blob1Color = if (isDarkMode) Color(0x443B82F6) else Color(0x5593C5FD)
    val blob2Color = if (isDarkMode) Color(0x446366F1) else Color(0x66C7D2FE)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val r1 = (size.width * 0.8f).coerceAtLeast(1f)
            val r2 = (size.width * 0.8f).coerceAtLeast(1f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(blob1Color, Color.Transparent),
                    center = Offset(size.width * -0.1f, size.height * -0.1f),
                    radius = r1
                ),
                radius = r1,
                center = Offset(size.width * -0.1f, size.height * -0.1f)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(blob2Color, Color.Transparent),
                    center = Offset(size.width * 1.1f, size.height * 1.1f),
                    radius = r2
                ),
                radius = r2,
                center = Offset(size.width * 1.1f, size.height * 1.1f)
            )
        }
        
        Scaffold(
            containerColor = Color.Transparent,
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets.systemBars,
            bottomBar = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (isDarkMode) Color.White.copy(alpha = 0.05f) else Color.White.copy(alpha = 0.35f))
                        .border(1.dp, if (isDarkMode) Color.White.copy(alpha = 0.1f) else Color.White.copy(alpha = 0.35f))
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (isDarkMode) Color(0xFF1D4ED8).copy(alpha = 0.2f) else Color(0xFFDBEAFE))
                                .padding(horizontal = 20.dp, vertical = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.AccountBalanceWallet, contentDescription = "Assets", tint = if (isDarkMode) Color(0xFF60A5FA) else Color(0xFF1D4ED8))
                        }
                        Text("Assets", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (isDarkMode) Color(0xFF60A5FA) else Color(0xFF1D4ED8))
                    }
                    Column(
                        modifier = Modifier.clickable { showGrowthDialog = true },
                        horizontalAlignment = Alignment.CenterHorizontally, 
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Default.Insights, contentDescription = "Growth", tint = if (isDarkMode) Color(0xFF94A3B8) else Color(0xFF64748B))
                        Text("Growth", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (isDarkMode) Color(0xFF94A3B8) else Color(0xFF64748B))
                    }
                    Column(
                        modifier = Modifier.clickable { showSettingsDialog = true },
                        horizontalAlignment = Alignment.CenterHorizontally, 
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = if (isDarkMode) Color(0xFF94A3B8) else Color(0xFF64748B))
                        Text("Settings", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (isDarkMode) Color(0xFF94A3B8) else Color(0xFF64748B))
                    }
                }
            },
            floatingActionButton = {
                Box(
                    modifier = Modifier
                        .padding(bottom = 16.dp, end = 8.dp)
                        .size(56.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (isDarkMode) Color(0xFF60A5FA) else Color(0xFF0061A4))
                        .clickable { showDialog = true; editingItem = null },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add", tint = Color.White)
                }
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Image(
                            painter = painterResource(id = R.drawable.circular_wealth_logo_1780144080896),
                            contentDescription = "App Logo",
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                        )
                        Column {
                            Text("PORTFOLIO ANALYSIS", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = if (isDarkMode) Color(0xFF94A3B8) else Color(0xFF64748B), letterSpacing = 1.sp)
                            Text("My Wealth", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = if (isDarkMode) Color.White else Color(0xFF191C1E))
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.25f))
                                .border(1.dp, Color.White.copy(alpha = 0.4f), CircleShape)
                                .clickable { viewModel.toggleTheme() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isDarkMode) Icons.Default.LightMode else Icons.Default.DarkMode, 
                                contentDescription = "Toggle Theme", 
                                tint = if (isDarkMode) Color(0xFFFACC15) else Color(0xFF191C1E)
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.25f))
                                .border(1.dp, Color.White.copy(alpha = 0.4f), CircleShape)
                                .clickable { viewModel.setAuthenticated(false) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Lock, contentDescription = "Lock App", tint = if (isDarkMode) Color.White else Color(0xFF001D35))
                        }
                    }
                }
                
                // Net Worth Summary Card
                NetWorthSummaryCard(
                    netWorth = netWorth,
                    assetsTotal = assetsTotal,
                    debtsTotal = debtsTotal,
                    currencySymbol = currencySymbol,
                    pctGainStr = pctGainStr,
                    pctGainColor = pctGainColor,
                    pctGainBgColor = pctGainBgColor,
                    showBadge = showBadge
                )
                
                // Allocation Chart
                AllocationSummaryCard(assetsTotal, debtsTotal)
                
                // Category Distribution Chart (New)
                val assetItems = items.filter { !it.isDebt }
                if (assetItems.isNotEmpty()) {
                    CategoryDistributionCard(
                        assetItems = assetItems, 
                        currencySymbol = currencySymbol,
                        selectedCategory = selectedCategory,
                        onCategorySelect = { viewModel.setSelectedCategory(it) }
                    )
                }
                
                // Advanced Controls
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextField(
                        value = searchQuery,
                        onValueChange = { viewModel.setSearchQuery(it) },
                        placeholder = { Text("Search assets...", fontSize = 14.sp) },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White.copy(alpha = 0.25f)),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color(0xFF64748B)) },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp)
                    )
                    
                    var showSortMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(
                            onClick = { showSortMenu = true },
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color.White.copy(alpha = 0.25f))
                        ) {
                            Icon(Icons.Default.Sort, contentDescription = "Sort", tint = Color(0xFF475569))
                        }
                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false },
                            modifier = Modifier.background(Color.White).clip(RoundedCornerShape(12.dp))
                        ) {
                            listOf("Value (High)", "Value (Low)", "Name (A-Z)", "Rate (High)").forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option) },
                                    onClick = { 
                                        viewModel.setSortBy(option)
                                        showSortMenu = false
                                    }
                                )
                            }
                        }
                    }
                }
                
                // Active Filters
                if (selectedCategory != null) {
                    Row(
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = getColorForType(selectedCategory!!).copy(alpha = 0.15f),
                            shape = RoundedCornerShape(12.dp),
                            onClick = { viewModel.setSelectedCategory(null) }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(getColorForType(selectedCategory!!)))
                                Text(selectedCategory!!, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = getColorForType(selectedCategory!!))
                                Icon(Icons.Default.Close, contentDescription = "Clear", modifier = Modifier.size(14.dp), tint = getColorForType(selectedCategory!!))
                            }
                        }
                    }
                }
                
                // Grid
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f).fillMaxWidth()
                ) {
                    item(span = { GridItemSpan(columns) }) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (isDarkMode) Color.White.copy(alpha = 0.05f) else Color.White.copy(alpha = 0.25f))
                                .padding(4.dp)
                        ) {
                            TabButton(
                                text = "Assets",
                                isSelected = !currentTabIsDebt,
                                onClick = { currentTabIsDebt = false },
                                modifier = Modifier.weight(1f)
                            )
                            TabButton(
                                text = "Debts",
                                isSelected = currentTabIsDebt,
                                onClick = { currentTabIsDebt = true },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    
                    val displayItems = items.filter { it.isDebt == currentTabIsDebt }
                    
                    if (displayItems.isEmpty()) {
                        item(span = { GridItemSpan(columns) }) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 48.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = if (searchQuery.isNotEmpty()) Icons.Default.SearchOff else Icons.Default.AddChart,
                                    contentDescription = null,
                                    modifier = Modifier.size(64.dp),
                                    tint = if (isDarkMode) Color(0xFF475569) else Color(0xFFCBD5E1)
                                )
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    text = if (searchQuery.isNotEmpty()) "No results matching \"$searchQuery\"" else "Start tracking your ${if (currentTabIsDebt) "liabilities" else "assets"}",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (isDarkMode) Color(0xFF94A3B8) else Color(0xFF94A3B8),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 32.dp)
                                )
                                if (searchQuery.isEmpty()) {
                                    TextButton(onClick = { showDialog = true; editingItem = null }) {
                                        Text("Add First Item", fontWeight = FontWeight.Bold, color = if (isDarkMode) Color(0xFF60A5FA) else Color(0xFF0061A4))
                                    }
                                }
                            }
                        }
                    } else {
                        items(displayItems, key = { it.id }) { item ->
                            ItemCard(
                                item = item, 
                                currencySymbol = currencySymbol, 
                                onClick = {
                                    editingItem = item
                                    showDialog = true
                                },
                                onDelete = { itemToDelete = item },
                                isSingleColumn = columns == 1,
                                modifier = Modifier.animateItem()
                            )
                        }
                    }
                }
            }
        }
    }
    
    if (itemToDelete != null) {
        AlertDialog(
            onDismissRequest = { itemToDelete = null },
            title = { Text("Delete Item?", color = if (isDarkMode) Color.White else Color(0xFF191C1E)) },
            text = { Text("Are you sure you want to delete '${itemToDelete?.name}'? This action cannot be undone.", color = if (isDarkMode) Color.White.copy(alpha = 0.7f) else Color(0xFF475569)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        itemToDelete?.let { viewModel.deleteItem(it.id) }
                        itemToDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFEF4444))
                ) {
                    Text("Delete", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { itemToDelete = null }) {
                    Text("Cancel", color = if (isDarkMode) Color.White.copy(alpha = 0.6f) else Color(0xFF64748B))
                }
            },
            containerColor = if (isDarkMode) Color(0xFF1E293B) else Color.White,
            shape = RoundedCornerShape(28.dp)
        )
    }
    
    if (showDialog) {
        ItemEditDialog(
            item = editingItem,
            isDebtDefault = currentTabIsDebt,
            onDismiss = { showDialog = false },
            onSave = { savedItem ->
                viewModel.saveItem(savedItem)
                showDialog = false
            },
            onDelete = { _ ->
                itemToDelete = editingItem
                showDialog = false
            }
        )
    }

    if (showSettingsDialog) {
        SettingsDialog(viewModel = viewModel, onDismiss = { showSettingsDialog = false })
    }

    if (showGrowthDialog) {
        GrowthDialog(
            unrealizedGains = unrealizedGains,
            totalCostBasis = totalCostBasis,
            currencySymbol = currencySymbol,
            onDismiss = { showGrowthDialog = false }
        )
    }
}

@Composable
fun AllocationSummaryCard(assetsTotal: Double, debtsTotal: Double) {
    val isDarkMode = LocalIsDarkMode.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.linearGradient(
                    colors = if (isDarkMode) 
                        listOf(Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.02f))
                    else 
                        listOf(Color.White.copy(alpha = 0.4f), Color.White.copy(alpha = 0.1f)),
                    start = Offset(0f, 0f),
                    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = if (isDarkMode)
                        listOf(Color.White.copy(alpha = 0.15f), Color.Transparent)
                    else
                        listOf(Color.White.copy(alpha = 0.6f), Color.White.copy(alpha = 0.1f))
                ),
                shape = RoundedCornerShape(24.dp)
            )
            .padding(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(modifier = Modifier.size(80.dp), contentAlignment = Alignment.Center) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val total = (assetsTotal + debtsTotal).toFloat()
                    if (total > 0) {
                        val assetSweep = (assetsTotal.toFloat() / total) * 360f
                        val debtSweep = (debtsTotal.toFloat() / total) * 360f

                        drawArc(
                            color = Color(0xFF3B82F6),
                            startAngle = -90f,
                            sweepAngle = assetSweep,
                            useCenter = false,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 24f)
                        )
                        drawArc(
                            color = Color(0xFFEF4444), // Consistent Red 500
                            startAngle = -90f + assetSweep,
                            sweepAngle = debtSweep,
                            useCenter = false,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 24f)
                        )
                    } else {
                        drawCircle(
                            color = Color(0xFFCBD5E1),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 24f)
                        )
                    }
                }
                Text("Split", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (isDarkMode) Color(0xFF94A3B8) else Color(0xFF64748B))
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF3B82F6)))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Assets: ${if (assetsTotal + debtsTotal > 0) "%.1f%%".format((assetsTotal / (assetsTotal + debtsTotal)) * 100) else "0%"}",
                        fontSize = 12.sp,
                        color = if (isDarkMode) Color.White else Color(0xFF191C1E)
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFEF4444)))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Debts: ${if (assetsTotal + debtsTotal > 0) "%.1f%%".format((debtsTotal / (assetsTotal + debtsTotal)) * 100) else "0%"}",
                        fontSize = 12.sp,
                        color = if (isDarkMode) Color.White else Color(0xFF191C1E)
                    )
                }
            }
        }
    }
}

@Composable
fun CategoryDistributionCard(
    assetItems: List<FinancialItem>, 
    currencySymbol: String,
    selectedCategory: String? = null,
    onCategorySelect: (String) -> Unit = {}
) {
    val isDarkMode = LocalIsDarkMode.current
    val categoryDistribution = remember(assetItems) {
        assetItems.groupBy { it.type }
            .mapValues { entry -> entry.value.sumOf { it.balance } }
            .toList()
            .sortedByDescending { it.second }
    }
    
    val totalAssets = remember(categoryDistribution) { categoryDistribution.sumOf { it.second } }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.linearGradient(
                    colors = if (isDarkMode) 
                        listOf(Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.02f))
                    else 
                        listOf(Color.White.copy(alpha = 0.45f), Color.White.copy(alpha = 0.15f)),
                    start = Offset(0f, 0f),
                    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = if (isDarkMode)
                        listOf(Color.White.copy(alpha = 0.15f), Color.Transparent)
                    else
                        listOf(Color.White.copy(alpha = 0.7f), Color.White.copy(alpha = 0.15f))
                ),
                shape = RoundedCornerShape(24.dp)
            )
            .padding(16.dp)
    ) {
        Column {
            Text("Asset Allocation", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (isDarkMode) Color.White.copy(alpha = 0.7f) else Color(0xFF475569), modifier = Modifier.padding(bottom = 12.dp))
            
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Box(modifier = Modifier.size(100.dp), contentAlignment = Alignment.Center) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        var currentStartAngle = -90f
                        categoryDistribution.forEach { (category, amount) ->
                            val sweepAngle = (amount.toFloat() / totalAssets.toFloat()) * 360f
                            val isSelected = selectedCategory == category
                            
                            drawArc(
                                color = getColorForType(category),
                                startAngle = currentStartAngle,
                                sweepAngle = sweepAngle,
                                useCenter = false,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(
                                    width = if (isSelected) 40f else 30f,
                                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                                )
                            )
                            currentStartAngle += sweepAngle
                        }
                        
                        if (categoryDistribution.isEmpty()) {
                            drawCircle(
                                color = Color(0xFFCBD5E1),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 30f)
                            )
                        }
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Assets", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF64748B))
                        Text("Dist.", fontSize = 10.sp, color = Color(0xFF94A3B8))
                    }
                }
                
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    categoryDistribution.take(5).forEach { (category, amount) ->
                        val pct = (amount / totalAssets) * 100
                        val isSelected = selectedCategory == category
                        
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier
                                 .fillMaxWidth()
                                 .clip(RoundedCornerShape(12.dp))
                                 .background(if (isSelected) getColorForType(category).copy(alpha = 0.1f) else Color.Transparent)
                                 .clickable { onCategorySelect(category) }
                                 .padding(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(getColorForType(category).copy(alpha = 0.2f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = getIconForType(category),
                                        contentDescription = null,
                                        tint = getColorForType(category),
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    category,
                                    fontSize = 13.sp,
                                    color = if (isSelected) getColorForType(category) else (if (isDarkMode) Color.White else Color(0xFF191C1E)),
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                            Text("%.1f%%".format(pct), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (isDarkMode) Color(0xFF94A3B8) else Color(0xFF475569))
                        }
                    }
                    if (categoryDistribution.size > 5) {
                        Text("+ ${categoryDistribution.size - 5} more categories", fontSize = 10.sp, color = Color(0xFF94A3B8), fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun TabButton(text: String, isSelected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val isDarkMode = LocalIsDarkMode.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) (if (isDarkMode) Color.White.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.8f)) else Color.Transparent)
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            fontSize = 14.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = if (isSelected) (if (isDarkMode) Color.White else Color(0xFF191C1E)) else (if (isDarkMode) Color(0xFF94A3B8) else Color(0xFF64748B))
        )
    }
}

@Composable
fun ItemCard(item: FinancialItem, currencySymbol: String, onClick: () -> Unit, onDelete: () -> Unit, isSingleColumn: Boolean = false, modifier: Modifier = Modifier) {
    val isDarkMode = LocalIsDarkMode.current
    val typeColor = getColorForType(item.type)
    
    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(if (isSingleColumn) Modifier.heightIn(min = 120.dp) else Modifier.aspectRatio(1.2f))
            .clip(RoundedCornerShape(28.dp))
            .background(
                Brush.linearGradient(
                    colors = if (isDarkMode) 
                        listOf(Color.White.copy(alpha = 0.1f), Color.White.copy(alpha = 0.02f))
                    else 
                        listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0.15f)),
                    start = Offset(0f, 0f),
                    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = if (isDarkMode)
                        listOf(Color.White.copy(alpha = 0.15f), Color.Transparent)
                    else
                        listOf(Color.White.copy(alpha = 0.7f), Color.White.copy(alpha = 0.15f))
                ),
                shape = RoundedCornerShape(28.dp)
            )
            .clickable { onClick() }
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(typeColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = getIconForType(item.type), 
                        contentDescription = null,
                        tint = typeColor,
                        modifier = Modifier.size(26.dp)
                    )
                }
                
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(
                        modifier = Modifier
                            .background(typeColor.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            "${item.interestRate}%",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            color = typeColor
                        )
                    }
                    
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.DeleteOutline,
                            contentDescription = "Delete",
                            tint = if (isDarkMode) Color.White.copy(alpha = 0.3f) else Color(0xFF94A3B8),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    item.name, 
                    fontSize = 13.sp, 
                    color = if (isDarkMode) Color(0xFF94A3B8) else Color(0xFF64748B), 
                    fontWeight = FontWeight.SemiBold, 
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Text(
                    formatCurrency(item.balance, currencySymbol), 
                    fontSize = 20.sp, 
                    fontWeight = FontWeight.ExtraBold, 
                    color = if (isDarkMode) Color.White else Color(0xFF0F172A)
                )
                
                if (item.shares != null && item.purchasePrice != null && item.currentPrice != null) {
                    val totalCost = item.shares * item.purchasePrice
                    val totalValue = item.shares * item.currentPrice
                    val unrealizedGain = totalValue - totalCost
                    val gainColor = if (unrealizedGain >= 0) Color(0xFF10B981) else Color(0xFFEF4444)
                    val sign = if (unrealizedGain >= 0) "+" else ""
                    
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(
                            if (unrealizedGain >= 0) Icons.Default.TrendingUp else Icons.Default.TrendingDown,
                            contentDescription = null,
                            tint = gainColor,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            "${sign}${formatCurrency(unrealizedGain, currencySymbol)}", 
                            fontSize = 12.sp, 
                            fontWeight = FontWeight.Bold, 
                            color = gainColor
                        )
                    }
                } else if (item.isDebt && item.minimumPayment > 0.0) {
                    Text(
                        "Min: ${formatCurrency(item.minimumPayment, currencySymbol)}", 
                        fontSize = 12.sp, 
                        color = if (isDarkMode) Color(0xFF60A5FA).copy(alpha = 0.8f) else Color(0xFF1D4ED8),
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemEditDialog(
    item: FinancialItem?,
    isDebtDefault: Boolean,
    onDismiss: () -> Unit,
    onSave: (FinancialItem) -> Unit,
    onDelete: (Int) -> Unit
) {
    val isDebt = item?.isDebt ?: isDebtDefault
    var name by remember { mutableStateOf(item?.name ?: "") }
    var type by remember { mutableStateOf(item?.type ?: if (isDebt) "Mortgage" else "Savings") }
    var balance by remember { mutableStateOf(item?.balance?.let { if (it > 0.0) it.toString() else "" } ?: "") }
    var interestRate by remember { mutableStateOf(item?.interestRate?.let { if (it > 0.0) it.toString() else "" } ?: "") }
    var minimumPayment by remember { mutableStateOf(item?.minimumPayment?.let { if (it > 0.0) it.toString() else "" } ?: "") }
    var shares by remember { mutableStateOf(item?.shares?.let { if (it > 0.0) it.toString() else "" } ?: "") }
    var purchasePrice by remember { mutableStateOf(item?.purchasePrice?.let { if (it > 0.0) it.toString() else "" } ?: "") }
    var currentPrice by remember { mutableStateOf(item?.currentPrice?.let { if (it > 0.0) it.toString() else "" } ?: "") }
    
    var expanded by remember { mutableStateOf(false) }
    
    val assetTypes = listOf("Cash", "Savings", "Mutual Funds", "Real Estate", "Fixed Deposit", "Recurring Deposit", "PPF", "Bonds", "Stock", "ETF", "Other Asset")
    val debtTypes = listOf("Mortgage", "Car Loan", "Student Loan", "Credit Card", "Other Debt")
    val options = if (isDebt) debtTypes else assetTypes

    val isDarkMode = LocalIsDarkMode.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = if (isDarkMode) Color(0xFF1E293B) else Color.White
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                if (item == null) (if (isDebt) "Add New Debt" else "Add New Asset") else "Edit Item",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = if (isDarkMode) Color.White else Color(0xFF191C1E)
            )
            
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name / Description") },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedLabelColor = if (isDarkMode) Color(0xFF60A5FA) else Color(0xFF0061A4),
                    cursorColor = if (isDarkMode) Color(0xFF60A5FA) else Color(0xFF0061A4)
                )
            )
            
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded }
            ) {
                OutlinedTextField(
                    readOnly = true,
                    value = type,
                    onValueChange = { },
                    label = { Text("Category") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.background(if (isDarkMode) Color(0xFF1E293B) else Color.White)
                ) {
                    options.forEach { selectionOption ->
                        DropdownMenuItem(
                            text = { 
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(getColorForType(selectionOption).copy(alpha = 0.15f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = getIconForType(selectionOption),
                                            contentDescription = null,
                                            tint = getColorForType(selectionOption),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    Text(selectionOption)
                                }
                            },
                            onClick = {
                                type = selectionOption
                                expanded = false
                            }
                        )
                    }
                }
            }
            
            val isStockOrETF = type.lowercase() in listOf("stock", "etf")
            
            if (isStockOrETF) {
                OutlinedTextField(
                    value = shares,
                    onValueChange = { shares = it },
                    label = { Text("Number of Shares") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = purchasePrice,
                    onValueChange = { purchasePrice = it },
                    label = { Text("Purchase Price (per share)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = currentPrice,
                    onValueChange = { currentPrice = it },
                    label = { Text("Current Market Price") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                OutlinedTextField(
                    value = balance,
                    onValueChange = { balance = it },
                    label = { Text(if (isDebt) "Outstanding Balance" else "Current Value") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                
                OutlinedTextField(
                    value = interestRate,
                    onValueChange = { interestRate = it },
                    label = { Text(if (isDebt) "Interest Rate (%)" else "Expected Return (%)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            
            if (isDebt) {
                OutlinedTextField(
                    value = minimumPayment,
                    onValueChange = { minimumPayment = it },
                    label = { Text("Minimum Payment (Optional)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (item != null) {
                    TextButton(onClick = { onDelete(item.id) }) {
                        Text("Delete", color = if (isDarkMode) Color(0xFFF87171) else Color.Red)
                    }
                } else {
                    Spacer(Modifier.width(8.dp))
                }
                
                Row {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = if (isDarkMode) Color.White.copy(alpha = 0.6f) else Color(0xFF64748B))
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val parsedShares = shares.toDoubleOrNull() ?: 0.0
                            val parsedPurchasePrice = purchasePrice.toDoubleOrNull() ?: 0.0
                            val parsedCurrentPrice = currentPrice.toDoubleOrNull() ?: 0.0
                            val parsedBalance = if (isStockOrETF) parsedShares * parsedCurrentPrice else (balance.toDoubleOrNull() ?: 0.0)
                            val parsedInterest = interestRate.toDoubleOrNull() ?: 0.0
                            val parsedMinPayment = minimumPayment.toDoubleOrNull() ?: 0.0
                            onSave(
                                FinancialItem(
                                    id = item?.id ?: 0,
                                    isDebt = isDebt,
                                    type = type,
                                    name = name.ifBlank { "Unnamed" },
                                    balance = parsedBalance,
                                    interestRate = parsedInterest,
                                    minimumPayment = parsedMinPayment,
                                    shares = if (isStockOrETF) parsedShares else null,
                                    purchasePrice = if (isStockOrETF) parsedPurchasePrice else null,
                                    currentPrice = if (isStockOrETF) parsedCurrentPrice else null
                                )
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = if (isDarkMode) Color(0xFF3B82F6) else Color(0xFF0061A4))
                    ) {
                        Text("Save")
                    }
                }
            }
        }
    }
}

fun getIconForType(type: String): ImageVector {
    return when(type.lowercase()) {
        "cash" -> Icons.Default.Payments
        "savings" -> Icons.Default.AccountBalance
        "mutual funds" -> Icons.Default.PieChart
        "real estate" -> Icons.Default.Apartment
        "fixed deposit" -> Icons.Default.LockClock
        "recurring deposit" -> Icons.Default.Update
        "ppf" -> Icons.Default.VerifiedUser
        "bonds" -> Icons.Default.Description
        "stock" -> Icons.Default.ShowChart
        "etf" -> Icons.Default.Timeline
        "other asset" -> Icons.Default.BubbleChart
        "mortgage" -> Icons.Default.HomeWork
        "car loan" -> Icons.Default.DirectionsCar
        "student loan" -> Icons.Default.School
        "credit card" -> Icons.Default.CreditCard
        "other debt" -> Icons.Default.ReceiptLong
        else -> Icons.Default.Category
    }
}

fun getColorForType(type: String): Color {
    return when(type.lowercase()) {
        "cash" -> Color(0xFF10B981) // Emerald 500
        "savings" -> Color(0xFF3B82F6) // Blue 500
        "mutual funds" -> Color(0xFF8B5CF6) // Violet 500
        "real estate" -> Color(0xFFF59E0B) // Amber 500
        "fixed deposit" -> Color(0xFF6366F1) // Indigo 500
        "recurring deposit" -> Color(0xFF06B6D4) // Cyan 500
        "ppf" -> Color(0xFF0D9488) // Teal 600
        "bonds" -> Color(0xFF4F46E5) // Indigo 600
        "stock" -> Color(0xFFEC4899) // Pink 500
        "etf" -> Color(0xFFF43F5E) // Rose 500
        "other asset" -> Color(0xFF64748B) // Slate 500
        "mortgage" -> Color(0xFFEF4444) // Red 500
        "car loan" -> Color(0xFFF97316) // Orange 500
        "student loan" -> Color(0xFF8B5CF6) // Violet 500
        "credit card" -> Color(0xFFE11D48) // Rose 600
        "other debt" -> Color(0xFF475569) // Slate 600
        else -> Color(0xFF94A3B8) // Slate 400
    }
}

fun formatCurrency(amount: Double, symbol: String): String {
    val absoluteAmount = kotlin.math.abs(amount)
    val sign = if (amount < 0) "-" else ""
    val (formatted, suffix) = when {
        absoluteAmount >= 1_000_000_000 -> (absoluteAmount / 1_000_000_000.0) to "B"
        absoluteAmount >= 1_000_000 -> (absoluteAmount / 1_000_000.0) to "M"
        absoluteAmount >= 10_000 -> (absoluteAmount / 1_000.0) to "k"
        else -> absoluteAmount to ""
    }
    
    return if (suffix.isNotEmpty()) {
        val df = java.text.DecimalFormat("#,##0.1")
        "$sign$symbol${df.format(formatted)}$suffix"
    } else {
        val df = java.text.DecimalFormat("#,##0.00")
        "$sign$symbol${df.format(formatted)}"
    }
}

@Composable
fun GrowthDialog(unrealizedGains: Double, totalCostBasis: Double, currencySymbol: String, onDismiss: () -> Unit) {
    val isDarkMode = LocalIsDarkMode.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Portfolio Growth", color = if (isDarkMode) Color.White else Color(0xFF191C1E)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (totalCostBasis > 0) {
                    val pct = (unrealizedGains / totalCostBasis) * 100
                    Text("Total Invested: ${formatCurrency(totalCostBasis, currencySymbol)}", color = if (isDarkMode) Color.White.copy(alpha = 0.8f) else Color(0xFF475569))
                    Text("Current Value: ${formatCurrency(totalCostBasis + unrealizedGains, currencySymbol)}", color = if (isDarkMode) Color.White.copy(alpha = 0.8f) else Color(0xFF475569))
                    Text("Unrealized Gains: ${formatCurrency(unrealizedGains, currencySymbol)}", color = if (isDarkMode) Color.White.copy(alpha = 0.8f) else Color(0xFF475569))
                    Text("Return on Investment: %.2f%%".format(pct), color = if (isDarkMode) Color.White else Color(0xFF191C1E), fontWeight = FontWeight.Bold)
                } else {
                    Text("You don't have any stocks or ETFs tracked yet. Add assets with 'Stock' or 'ETF' category and provide share quantities and prices to see your portfolio growth tracked over time!", color = if (isDarkMode) Color.White.copy(alpha = 0.6f) else Color(0xFF64748B))
                }
            }
        },
        containerColor = if (isDarkMode) Color(0xFF1E293B) else Color.White,
        shape = RoundedCornerShape(28.dp),
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
fun SettingsDialog(viewModel: WealthViewModel, onDismiss: () -> Unit) {
    val isDarkMode = LocalIsDarkMode.current
    val currentCurrency by viewModel.currencySymbol.collectAsStateWithLifecycle()
    val currencies = listOf("$" to "USD", "€" to "EUR", "£" to "GBP", "₹" to "INR", "¥" to "JPY", "A$" to "AUD", "C$" to "CAD")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings", color = if (isDarkMode) Color.White else Color(0xFF191C1E)) },
        text = {
            Column {
                Text("Select Currency", fontWeight = FontWeight.Bold, color = if (isDarkMode) Color.White else Color(0xFF191C1E), modifier = Modifier.padding(bottom = 8.dp))
                currencies.forEach { (symbol, name) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.setCurrency(symbol)
                                onDismiss()
                            }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = symbol == currentCurrency,
                            onClick = {
                                viewModel.setCurrency(symbol)
                                onDismiss()
                            },
                            colors = RadioButtonDefaults.colors(selectedColor = if (isDarkMode) Color(0xFF60A5FA) else Color(0xFF0061A4))
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("$symbol - $name", color = if (isDarkMode) Color.White.copy(alpha = 0.8f) else Color(0xFF475569))
                    }
                }
            }
        },
        containerColor = if (isDarkMode) Color(0xFF1E293B) else Color.White,
        shape = RoundedCornerShape(28.dp),
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
fun NetWorthSummaryCard(
    netWorth: Double,
    assetsTotal: Double,
    debtsTotal: Double,
    currencySymbol: String,
    pctGainStr: String,
    pctGainColor: Color,
    pctGainBgColor: Color,
    showBadge: Boolean
) {
    val isDarkMode = LocalIsDarkMode.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(32.dp))
            .background(
                Brush.linearGradient(
                    colors = if (isDarkMode) 
                        listOf(Color.White.copy(alpha = 0.12f), Color.White.copy(alpha = 0.03f))
                    else 
                        listOf(Color.White.copy(alpha = 0.5f), Color.White.copy(alpha = 0.15f)),
                    start = Offset(0f, 0f),
                    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                )
            )
            .border(
                width = 1.2.dp,
                brush = Brush.linearGradient(
                    colors = if (isDarkMode)
                        listOf(Color.White.copy(alpha = 0.2f), Color.Transparent)
                    else
                        listOf(Color.White.copy(alpha = 0.8f), Color.White.copy(alpha = 0.1f))
                ),
                shape = RoundedCornerShape(32.dp)
            )
            .padding(24.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    "ESTIMATED NET WORTH", 
                    fontSize = 11.sp, 
                    fontWeight = FontWeight.Bold, 
                    color = if (isDarkMode) Color(0xFF94A3B8) else Color(0xFF64748B),
                    letterSpacing = 1.sp
                )
                if (showBadge) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isDarkMode) pctGainBgColor.copy(alpha = 0.2f) else pctGainBgColor)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            pctGainStr,
                            color = if (isDarkMode) pctGainColor.copy(alpha = 0.9f) else pctGainColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }
            
            Text(
                text = formatCurrency(netWorth, currencySymbol),
                fontSize = 42.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (-1.5).sp,
                color = if (isDarkMode) Color.White else Color(0xFF0F172A),
                modifier = Modifier.padding(top = 4.dp)
            )
            
            Row(
                verticalAlignment = Alignment.CenterVertically, 
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Icon(
                    imageVector = if (netWorth >= 0) Icons.Default.TrendingUp else Icons.Default.TrendingDown,
                    contentDescription = null,
                    tint = if (netWorth >= 0) Color(0xFF15803D) else Color(0xFFB91C1C),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Overall Portfolio Standing",
                    fontSize = 13.sp,
                    color = if (isDarkMode) Color(0xFFCBD5E1) else Color(0xFF475569),
                    fontWeight = FontWeight.Medium
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                SummaryStat(
                    label = "TOTAL ASSETS",
                    value = formatCurrency(assetsTotal, currencySymbol),
                    color = Color(0xFF10B981) // Emerald 500
                )
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(36.dp)
                        .background(if (isDarkMode) Color.White.copy(alpha = 0.1f) else Color(0xFFCBD5E1).copy(alpha = 0.5f))
                        .align(Alignment.CenterVertically)
                )
                SummaryStat(
                    label = "LIABILITIES",
                    value = formatCurrency(debtsTotal, currencySymbol),
                    color = Color(0xFFEF4444) // Red 500
                )
            }
        }
    }
}

@Composable
fun SummaryStat(label: String, value: String, color: Color) {
    val isDarkMode = LocalIsDarkMode.current
    Column {
        Text(
            label, 
            fontSize = 10.sp, 
            fontWeight = FontWeight.ExtraBold, 
            letterSpacing = 1.2.sp, 
            color = if (isDarkMode) Color(0xFF94A3B8) else Color(0xFF64748B)
        )
        Text(
            value, 
            fontSize = 16.sp, 
            fontWeight = FontWeight.Bold, 
            color = if (isDarkMode) color.copy(alpha = 0.85f) else color.copy(alpha = 0.9f)
        )
    }
}
