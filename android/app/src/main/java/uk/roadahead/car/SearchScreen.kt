package uk.roadahead.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uk.roadahead.core.Place
import uk.roadahead.service.NavController

/** Destination search (recent places first). Picking one plans the route and starts navigating. */
class SearchScreen(ctx: CarContext) : Screen(ctx) {
    private var results: List<Place> = NavController.settings.recents().map { Place(it.first, it.second) }
    private var loading = false
    private var error: String? = null
    private var job: Job? = null

    private fun runSearch(q: String, debounce: Boolean) {
        job?.cancel()
        if (q.length < 3) return
        job = lifecycleScope.launch {
            if (debounce) delay(700)
            loading = true; invalidate()
            runCatching { NavController.search(q) }.onSuccess { results = it; error = null }.onFailure { error = it.message }
            loading = false; invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        val b = SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(searchText: String) = runSearch(searchText, true)
            override fun onSearchSubmitted(searchText: String) = runSearch(searchText, false)
        }).setHeaderAction(Action.BACK).setSearchHint("Town, postcode, place").setShowKeyboardByDefault(false)
        if (loading) return b.setLoading(true).build()
        val list = ItemList.Builder()
        if (results.isEmpty()) list.setNoItemsMessage(error ?: "Type a destination")
        results.take(6).forEach { p ->
            val parts = p.name.split(",").map { it.trim() }
            list.addItem(Row.Builder().setTitle(parts.first()).addText(parts.drop(1).take(3).joinToString(", ").ifEmpty { " " })
                .setOnClickListener {
                    NavController.plan(null, p, startNavigating = true)
                    screenManager.pop()
                }.build())
        }
        return b.setItemList(list.build()).build()
    }
}
