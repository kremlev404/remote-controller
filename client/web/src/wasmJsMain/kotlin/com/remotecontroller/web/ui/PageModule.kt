package com.remotecontroller.web.ui

import com.remotecontroller.client.domain.usecase.ObserveClipboardUseCase
import com.remotecontroller.client.domain.usecase.ObserveScreenUseCase
import com.remotecontroller.client.domain.usecase.SendClipboardUseCase
import com.remotecontroller.client.domain.usecase.SendRemoteInputUseCase
import com.remotecontroller.web.data.PageClipboardRepository
import com.remotecontroller.web.data.PageLink
import com.remotecontroller.web.data.PageScreenRepository
import com.remotecontroller.web.data.PageTarget

/** The one place the page is put together. One screen needs no container for this. */
internal fun pageViewModel(target: PageTarget): PageViewModel {
    val link = PageLink(target)
    val clipboard = PageClipboardRepository(link)
    return PageViewModel(
        target = target,
        observeClipboard = ObserveClipboardUseCase(clipboard),
        sendClipboard = SendClipboardUseCase(clipboard),
        observeScreen = ObserveScreenUseCase(PageScreenRepository(link)),
        input = SendRemoteInputUseCase(clipboard),
        approvalCode = link.approvalCode,
    )
}
