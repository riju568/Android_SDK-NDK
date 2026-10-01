package com.example.whatsappchat

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

enum class MessageType { TEXT, VOICE, VIDEO, FILE }

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val senderId: String = "",
    val senderName: String = "",
    val text: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val isOutgoing: Boolean = true,
    val messageType: MessageType = MessageType.TEXT,
    val mediaUrl: String = "",
    val status: MessageStatus = MessageStatus.READ
)

enum class MessageStatus { SENT, DELIVERED, READ }

object WhatsAppColors {
    val DarkBackground = Color(0xFF0B141A)
    val TopBarBackground = Color(0xFF1F2C34)
    val IncomingBubble = Color(0xFF202C33)
    val OutgoingBubble = Color(0xFF005C4B)
    val TealAccent = Color(0xFF00A884)
    val TextPrimary = Color(0xFFE9EDEF)
    val TextSecondary = Color(0xFF8696A0)
    val ReadTickBlue = Color(0xFF53BDEB)
    val InputBackground = Color(0xFF2A3942)
}
class WhatsAppActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                WhatsAppMainScreen()
            }
        }
    }
}

@Composable
fun WhatsAppMainScreen() {
    val auth = FirebaseAuth.getInstance()
    var currentUser by remember { mutableStateOf(auth.currentUser) }
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            currentUser = firebaseAuth.currentUser
        }
        auth.addAuthStateListener(listener)
        onDispose { auth.removeAuthStateListener(listener) }
    }

    if (currentUser == null) {
        GoogleSignInScreen(onSignInSuccess = { currentUser = auth.currentUser })
    } else {
        WhatsAppChatScreen(
            currentUserId = currentUser?.uid ?: "",
            currentUserName = currentUser?.displayName ?: "User",
            currentUserPhoto = currentUser?.photoUrl?.toString() ?: "",
            onSignOut = {
                auth.signOut()
                GoogleSignIn.getClient(
                    context,
                    GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).build()
                ).signOut()
            }
        )
    }
}
@Composable
fun GoogleSignInScreen(onSignInSuccess: () -> Unit) {
    val context = LocalContext.current
    val auth = FirebaseAuth.getInstance()
    var isLoading by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(ApiException::class.java)
                account?.idToken?.let { idToken ->
                    val credential = GoogleAuthProvider.getCredential(idToken, null)
                    isLoading = true
                    auth.signInWithCredential(credential)
                        .addOnCompleteListener { authTask ->
                            isLoading = false
                            if (authTask.isSuccessful) {
                                onSignInSuccess()
                            } else {
                                Toast.makeText(context, "Authentication Failed", Toast.LENGTH_SHORT).show()
                            }
                        }
                }
            } catch (e: Exception) {
                isLoading = false
                Toast.makeText(context, "Sign In Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } else {
            isLoading = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WhatsAppColors.DarkBackground),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(24.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = WhatsAppColors.TealAccent,
                modifier = Modifier.size(96.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Chat,
                    contentDescription = "WhatsApp Logo",
                    tint = Color.White,
                    modifier = Modifier.padding(20.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Welcome to WhatsApp",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = WhatsAppColors.TextPrimary
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "End-to-End Encrypted Messenger",
                fontSize = 14.sp,
                color = WhatsAppColors.TextSecondary
            )

            Spacer(modifier = Modifier.height(48.dp))

            if (isLoading) {
                CircularProgressIndicator(color = WhatsAppColors.TealAccent)
            } else {
                Button(
                    onClick = {
                        isLoading = true
                        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                            .requestIdToken("YOUR_WEB_CLIENT_ID.apps.googleusercontent.com") // Replace with Web Client ID from Firebase Console
                            .requestEmail()
                            .build()
                        val googleSignInClient = GoogleSignIn.getClient(context, gso)
                        launcher.launch(googleSignInClient.signInIntent)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = WhatsAppColors.TealAccent),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = Color.White
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Sign in with Google",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsAppChatScreen(
    currentUserId: String,
    currentUserName: String,
    currentUserPhoto: String,
    onSignOut: () -> Unit
) {
    var messages by remember {
        mutableStateOf(
            listOf(
                ChatMessage(
                    senderId = "other",
                    senderName = "Alex",
                    text = "Hey! Is this WebRTC and Firebase solution 100% free?",
                    isOutgoing = false,
                    timestamp = System.currentTimeMillis() - 3600000
                ),
                ChatMessage(
                    senderId = currentUserId,
                    senderName = currentUserName,
                    text = "Yes! Firebase Auth and Storage Free Tiers combined with WebRTC direct peer-to-peer audio/video calling cost $0.",
                    isOutgoing = true,
                    timestamp = System.currentTimeMillis() - 1800000
                )
            )
        )
    }

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var showMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = WhatsAppColors.TopBarBackground
                ),
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = WhatsAppColors.TealAccent,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = "Profile",
                                tint = Color.White,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Alex (Engineering)",
                                color = WhatsAppColors.TextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "online",
                                color = WhatsAppColors.TealAccent,
                                fontSize = 12.sp
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { /* Launch WebRTC Video Call */ }) {
                        Icon(Icons.Default.Videocam, contentDescription = "Video Call", tint = WhatsAppColors.TextPrimary)
                    }
                    IconButton(onClick = { /* Launch WebRTC Voice Call */ }) {
                        Icon(Icons.Default.Call, contentDescription = "Voice Call", tint = WhatsAppColors.TextPrimary)
                    }
                    Box {
                        IconButton(onClick = { showMenu = !showMenu }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Menu", tint = WhatsAppColors.TextPrimary)
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            modifier = Modifier.background(WhatsAppColors.TopBarBackground)
                        ) {
                            DropdownMenuItem(
                                text = { Text("Logged in as: $currentUserName", color = WhatsAppColors.TextSecondary, fontSize = 12.sp) },
                                onClick = {}
                            )
                            HorizontalDivider(color = WhatsAppColors.InputBackground)
                            DropdownMenuItem(
                                text = { Text("Sign Out", color = Color.Red) },
                                onClick = {
                                    showMenu = false
                                    onSignOut()
                                }
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            WhatsAppInputBar(
                onSendMessage = { text ->
                    val newMessage = ChatMessage(
                        senderId = currentUserId,
                        senderName = currentUserName,
                        text = text,
                        isOutgoing = true,
                        timestamp = System.currentTimeMillis()
                    )
                    messages = messages + newMessage
                    coroutineScope.launch {
                        listState.animateScrollToItem(messages.size - 1)
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(WhatsAppColors.DarkBackground)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                items(messages, key = { it.id }) { message ->
                    MessageBubble(message = message)
                }
            }
        }
    }
}
@Composable
fun MessageBubble(message: ChatMessage) {
    val alignment = if (message.isOutgoing) Alignment.CenterEnd else Alignment.CenterStart
    val bubbleColor = if (message.isOutgoing) WhatsAppColors.OutgoingBubble else WhatsAppColors.IncomingBubble
    val bubbleShape = if (message.isOutgoing) {
        RoundedCornerShape(12.dp, 0.dp, 12.dp, 12.dp)
    } else {
        RoundedCornerShape(0.dp, 12.dp, 12.dp, 12.dp)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        contentAlignment = alignment
    ) {
        Surface(
            color = bubbleColor,
            shape = bubbleShape,
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = message.text,
                    color = WhatsAppColors.TextPrimary,
                    fontSize = 15.sp
                )

                Row(
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(message.timestamp)),
                        color = WhatsAppColors.TextSecondary,
                        fontSize = 10.sp
                    )
                    if (message.isOutgoing) {
                        Icon(
                            imageVector = Icons.Default.DoneAll,
                            contentDescription = "Read Status",
                            tint = if (message.status == MessageStatus.READ) WhatsAppColors.ReadTickBlue else WhatsAppColors.TextSecondary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
    }
}
@Composable
fun WhatsAppInputBar(onSendMessage: (String) -> Unit) {
    var textInput by remember { mutableStateOf("") }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(WhatsAppColors.DarkBackground)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            color = WhatsAppColors.InputBackground,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SentimentSatisfied,
                    contentDescription = "Emoji",
                    tint = WhatsAppColors.TextSecondary,
                    modifier = Modifier.size(24.dp)
                )

                TextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    placeholder = { Text("Message", color = WhatsAppColors.TextSecondary, fontSize = 16.sp) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedTextColor = WhatsAppColors.TextPrimary,
                        unfocusedTextColor = WhatsAppColors.TextPrimary,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    modifier = Modifier.weight(1f)
                )

                Icon(
                    imageVector = Icons.Default.AttachFile,
                    contentDescription = "Attach",
                    tint = WhatsAppColors.TextSecondary,
                    modifier = Modifier
                        .size(24.dp)
                        .clickable { /* Handle Attachment */ }
                )
                Spacer(modifier = Modifier.width(12.dp))
                Icon(
                    imageVector = Icons.Default.CameraAlt,
                    contentDescription = "Camera",
                    tint = WhatsAppColors.TextSecondary,
                    modifier = Modifier
                        .size(24.dp)
                        .clickable { /* Handle Camera */ }
                )
            }
        }

        Spacer(modifier = Modifier.width(6.dp))
        FloatingActionButton(
            onClick = {
                if (textInput.isNotBlank()) {
                    onSendMessage(textInput)
                    textInput = ""
                } else {
                    /* Handle Mic Record */
                }
            },
            containerColor = WhatsAppColors.TealAccent,
            contentColor = Color.White,
            shape = CircleShape,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                imageVector = if (textInput.isNotBlank()) Icons.Default.Send else Icons.Default.Mic,
                contentDescription = "Send or Record",
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
