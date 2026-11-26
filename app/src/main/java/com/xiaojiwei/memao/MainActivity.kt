package com.xiaojiwei.memao

import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.AbstractSavedStateViewModelFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.xiaojiwei.memao.ui.MemaoApp
import com.xiaojiwei.memao.ui.MemaoViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress

class MainActivity : ComponentActivity() {
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val factory=object:AbstractSavedStateViewModelFactory(this,savedInstanceState) {
            override fun <T:ViewModel> create(key:String,modelClass:Class<T>,handle:SavedStateHandle):T {
                val repository=(application as MemaoApplication).repository
                @Suppress("UNCHECKED_CAST")
                return MemaoViewModel(repository,applicationContext,handle,ownsRepository=false) as T
            }
        }
        val vm=ViewModelProvider(this,factory)[MemaoViewModel::class.java]
        setContent {
            var pending by remember {mutableStateOf<Pair<String,String>?>(null)}
            var checking by remember {mutableStateOf(false)}
            val scope=rememberCoroutineScope()
            val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {granted ->
                val credentials=pending
                pending=null
                if(granted&&credentials!=null)vm.connect(credentials.first,credentials.second)
                else if(!granted)vm.loginError("未获得局域网访问权限，无法连接局域网服务器。请允许权限后重试；已有缓存和草稿保留。")
            }
            MemaoApp(vm) {server,token ->
                if(!checking)scope.launch {
                    checking=true
                    try {
                        val needsLocalPermission=Build.VERSION.SDK_INT>=37&&
                            checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK")!=PackageManager.PERMISSION_GRANTED&&
                            withContext(Dispatchers.IO){isLocalServer(server)}
                        if(needsLocalPermission) {
                            pending=server to token
                            permission.launch("android.permission.ACCESS_LOCAL_NETWORK")
                        } else vm.connect(server,token)
                    } finally {checking=false}
                }
            }
        }
    }
}

/** DNS is inspected only for the server supplied by the user, never for background discovery. */
private fun isLocalServer(server:String):Boolean {
    val host=runCatching {Uri.parse(server).host}.getOrNull()?:return false
    if(host.equals("localhost",ignoreCase=true)||host.endsWith(".local",ignoreCase=true)||(!host.contains('.')&&!host.contains(':')))return true
    return runCatching {InetAddress.getAllByName(host).any {it.isSiteLocalAddress||it.isLinkLocalAddress||it.isLoopbackAddress}}.getOrDefault(false)
}
