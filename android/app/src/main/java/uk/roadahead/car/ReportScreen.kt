package uk.roadahead.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.*
import uk.roadahead.core.ReportType
import uk.roadahead.service.NavController

/** One-tap reporting grid, Waze-style. Reports are placed at your position and heading. */
class ReportScreen(ctx: CarContext) : Screen(ctx) {
    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
        for (t in listOf(ReportType.ACCIDENT, ReportType.STOPPED_VEHICLE, ReportType.LANE_BLOCKED, ReportType.OBJECT,
            ReportType.TRAFFIC, ReportType.POLICE, ReportType.MOBILE_CAMERA, ReportType.SPEED_CAMERA, ReportType.ROADWORKS, ReportType.WEATHER, ReportType.CLOSURE)) {
            list.addItem(GridItem.Builder().setTitle(t.label).setImage(CarIcons.glyph(t.icon), GridItem.IMAGE_TYPE_LARGE)
                .setOnClickListener {
                    val r = NavController.report(t)
                    CarToast.makeText(carContext, if (r != null) "${t.label} reported" else "No position yet", CarToast.LENGTH_SHORT).show()
                    screenManager.pop()
                }.build())
        }
        return GridTemplate.Builder().setTitle("Report").setHeaderAction(Action.BACK).setSingleList(list.build()).build()
    }
}
