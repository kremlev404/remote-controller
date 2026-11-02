package com.remotecontroller.client.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AppScope : CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Default)
