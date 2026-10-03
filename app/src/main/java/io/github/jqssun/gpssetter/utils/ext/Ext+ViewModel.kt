package io.github.jqssun.gpssetter.utils.ext

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

fun ViewModel.onIO(body: suspend () -> Unit): Job {
    return viewModelScope.launch(Dispatchers.IO) {
        body()
    }
}

fun ViewModel.onDefault(body: suspend () -> Unit): Job {
    return viewModelScope.launch(Dispatchers.Default) {
        body()
    }
}

fun ViewModel.onMain(body: suspend () -> Unit): Job {
    return viewModelScope.launch(Dispatchers.Main) {
        body()
    }
}