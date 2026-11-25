package fr.paul.telecommande.core

import fr.paul.telecommande.ui.BoxActions
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProgramReportTest {
    @Test fun reportSaysWhereRatingsComeFrom() {
        val res = JSONObject().put("perms", JSONObject().put("media", false).put("guide", false).put("guideAvailable", true))
            .put("apps", JSONArray().put(JSONObject().put("label", "Netflix").put("samples", 12).put("title", 12).put("ratingScreen", 3)
                .put("lastRating", "16+ · violence").put("lastRatingTitle", "Les Ombres de minuit")))
        val l = BoxActions.reportLines(res)
        assertTrue(l[0].contains("Titre en cours"))
        assertTrue(l[1].contains("Guide des programmes"))
        assertTrue(l[2].startsWith("Netflix — titre ✓ · classification : annonce du lecteur — · écran ✓ · infos média — · guide —"))
        assertTrue(l[2].contains("16+ · violence"))
    }

    @Test fun emptyReportExplainsLearning() {
        val l = BoxActions.reportLines(JSONObject().put("perms", JSONObject().put("media", true)).put("apps", JSONArray()))
        assertTrue(l.single().contains("apprend toute seule"))
    }
}
