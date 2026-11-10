package com.yll682.houche.ui.screens.search

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.yll682.houche.ui.components.LocalBulletinCenter
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yll682.houche.core.*

@Composable fun SearchScreen(
    vm: SearchViewModel, back: () -> Unit, line: (LineSummary) -> Unit, station: (Station) -> Unit,
    metro: (String) -> Unit, hideKeyboard: () -> Unit, modifier: Modifier = Modifier
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val bulletin = LocalBulletinCenter.current
    DisposableEffect(Unit) { onDispose { bulletin.dismiss() } }
    SearchContent(
        state, back,
        { vm.rememberQuery(); hideKeyboard(); line(it) },
        { vm.rememberQuery(); hideKeyboard(); station(it) },
        { vm.query(it) }, { vm.query(it, true) }, { vm.rememberQuery(); hideKeyboard() }, { vm.clear { undo -> bulletin.show("已清空搜索记录", undo) } },
        { keyword -> vm.remove(keyword) { undo -> bulletin.show("已删除搜索记录", undo) } }, inputModifier = modifier,
        metro = { vm.rememberQuery(); hideKeyboard(); metro(it) }
    )
}
