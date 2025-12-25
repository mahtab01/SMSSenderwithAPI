package com.example.myapplication

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.myapplication.ui.theme.MyApplicationTheme
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MyApplicationTheme {
                MainAppStructure()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppStructure() {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    
    // Load Settings
    val prefs = context.getSharedPreferences(SmsForegroundService.PREF_NAME, Context.MODE_PRIVATE)
    var userToken by remember { mutableStateOf(prefs.getString(SmsForegroundService.KEY_USER_TOKEN, "") ?: "") }
    var interval by remember { mutableStateOf(prefs.getInt(SmsForegroundService.KEY_INTERVAL, 1).toString()) }
    var apiEndpoint by remember { mutableStateOf(prefs.getString(SmsForegroundService.KEY_ENDPOINT, "SMS") ?: "SMS") }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Settings", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 16.dp))
                    
                    OutlinedTextField(
                        value = userToken,
                        onValueChange = { userToken = it },
                        label = { Text("User Key / Token") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    OutlinedTextField(
                        value = interval,
                        onValueChange = { interval = it.filter { char -> char.isDigit() } },
                        label = { Text("Interval (Minutes 1-60)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    OutlinedTextField(
                        value = apiEndpoint,
                        onValueChange = { apiEndpoint = it },
                        label = { Text("API Endpoint (e.g. SMS)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Button(
                        onClick = {
                            val intervalInt = interval.toIntOrNull() ?: 1
                            val finalInterval = if (intervalInt < 1) 1 else if (intervalInt > 60) 60 else intervalInt
                            
                            prefs.edit().apply {
                                putString(SmsForegroundService.KEY_USER_TOKEN, userToken)
                                putInt(SmsForegroundService.KEY_INTERVAL, finalInterval)
                                putString(SmsForegroundService.KEY_ENDPOINT, apiEndpoint)
                                apply()
                            }
                            
                            // Update UI state to reflect saved value
                            interval = finalInterval.toString()
                            
                            Toast.makeText(context, "Settings Saved!", Toast.LENGTH_SHORT).show()
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Save Settings")
                    }
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("SMS Sender") },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu")
                        }
                    }
                )
            }
        ) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding)) {
                MainScreen()
            }
        }
    }
}

@Composable
fun MainScreen() {
    val context = LocalContext.current
    var serviceRunning by remember { mutableStateOf(SmsForegroundService.isServiceRunning) }
    
    val permissionsToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.POST_NOTIFICATIONS)
    } else {
        arrayOf(Manifest.permission.SEND_SMS)
    }
    
    var hasPermissions by remember { 
        mutableStateOf(permissionsToRequest.all { 
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        })
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            hasPermissions = true
            Toast.makeText(context, "Permissions Granted!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Permissions Denied.", Toast.LENGTH_LONG).show()
        }
    }

    val database = remember { AppDatabase.getDatabase(context) }
    val logsFlow = remember { database.smsLogDao().getAllLogs() }
    val logs by logsFlow.collectAsState(initial = emptyList())

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // --- Service Control Card ---
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Service Status", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))

                if (!hasPermissions) {
                    Button(onClick = { permissionLauncher.launch(permissionsToRequest) }) {
                        Text("Grant Permissions")
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Button(
                            onClick = { 
                                Intent(context, SmsForegroundService::class.java).also { 
                                    context.startService(it)
                                    serviceRunning = true
                                }
                            },
                            enabled = !serviceRunning
                        ) {
                            Text("Start")
                        }
                        Button(
                            onClick = { 
                                Intent(context, SmsForegroundService::class.java).also { 
                                    context.stopService(it)
                                    serviceRunning = false
                                }
                             },
                            enabled = serviceRunning,
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                        ) {
                            Text("Stop")
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = if (serviceRunning) "Running" else "Stopped", fontWeight = FontWeight.Bold)
            }
        }

        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))
        
        // --- Logs List ---
        Text("History", style = MaterialTheme.typography.titleLarge, modifier = Modifier.align(Alignment.Start).padding(bottom = 8.dp))
        if (logs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No logs yet...", color = Color.Gray)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(logs) { log ->
                    LogItem(log)
                }
            }
        }
    }
}

@Composable
fun LogItem(log: SmsLog) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    val dateString = dateFormat.format(Date(log.timestamp))
    val statusColor = if (log.status == "SUCCESS") Color(0xFFE8F5E9) else Color(0xFFFFEBEE)
    val statusTextColor = if (log.status == "SUCCESS") Color(0xFF2E7D32) else Color(0xFFC62828)

    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Surface(color = statusColor) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(text = log.phoneNumber, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                    Text(text = log.status, color = statusTextColor, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = log.message, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = dateString, style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.align(Alignment.End))
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    MyApplicationTheme {
        MainAppStructure()
    }
}