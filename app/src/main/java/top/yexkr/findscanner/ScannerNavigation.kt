package top.yexkr.findscanner

private fun ScannerState.isViewingHistory() = when (page) {
    Page.SampleHistory, Page.InferenceHistory -> true
    Page.Detail -> detailFromHistory
    Page.Inference -> inferenceFromHistory
    else -> false
}

internal fun ScannerState.navigateTo(destination: Page): ScannerState {
    val enteringHistory = destination == Page.SampleHistory || destination == Page.InferenceHistory
    return copy(
        page = if (destination == Page.Home && config == null) Page.Connection else destination,
        // Retain the entry page across history lists and their detail pages, even if an old
        // connection config exists while the user is currently on the connection screen.
        historyReturnPage = if (enteringHistory && !isViewingHistory()) page else historyReturnPage,
        detailFromHistory = if (destination == Page.Detail) false else detailFromHistory,
        inferenceFromHistory = if (destination == Page.Inference) false else inferenceFromHistory
    )
}

internal fun ScannerState.backPage(): Page = when (page) {
    Page.Permissions -> Page.Permissions
    Page.Connection -> Page.Permissions
    Page.Home -> Page.Connection
    Page.SampleHistory, Page.InferenceHistory -> historyReturnPage
    Page.Detail -> if (detailFromHistory) Page.SampleHistory else Page.Collection
    Page.Inference -> if (inferenceFromHistory) Page.InferenceHistory else Page.Home
    Page.Collection -> Page.Home
}
