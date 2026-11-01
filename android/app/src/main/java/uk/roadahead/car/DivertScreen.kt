package uk.roadahead.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import uk.roadahead.service.NavController

/** Shows ways round a hazard compared with staying on route. */
class DivertScreen(ctx: CarContext) : Screen(ctx) {
    init {
        lifecycleScope.launch { NavController.state.collect { invalidate() } }
    }

    override fun onGetTemplate(): Template {
        val s = NavController.state.value
        val d = s.diversion
        val b = ListTemplate.Builder().setHeaderAction(Action.BACK)
        if (d == null) return b.setTitle(s.busy ?: "Finding a way round…").setLoading(s.busy != null || s.error == null)
            .apply { if (s.busy == null && s.error != null) setSingleList(ItemList.Builder().setNoItemsMessage(s.error).build()) }.build()
        b.setTitle("Avoid: ${d.hazard.headline}".take(60))
        val list = ItemList.Builder()
        list.addItem(Row.Builder().setTitle("Stay on route · ${d.stayMinutes} min").addText("Includes ~${d.stayDelay} min delays")
            .setOnClickListener { NavController.cancelDiversion(); screenManager.pop() }.build())
        d.options.forEachIndexed { i, r ->
            val delta = Math.round(r.score - d.stayMinutes)
            list.addItem(Row.Builder().setTitle("${Math.round(r.score)} min · ${if (delta <= 0) "${-delta} min faster" else "+$delta min"}")
                .addText("via ${r.summary.ifEmpty { "local roads" }}")
                .setOnClickListener { NavController.chooseDiversion(i); screenManager.pop() }.build())
        }
        if (d.options.isEmpty()) list.addItem(Row.Builder().setTitle("No sensible diversion").addText("Every way round is slower or hits the same problem").build())
        return b.setSingleList(list.build()).build()
    }
}
