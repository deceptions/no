
import com.google.common.truth.Truth.assertThat
import com.lagradost.shiro.utils.ShiroApi
import com.lagradost.shiro.utils.ShiroApi.Companion.getHomeOnly
import com.lagradost.shiro.utils.ShiroApi.Companion.getRandomAnimePage
import com.lagradost.shiro.utils.ShiroApi.Companion.getToken
import com.lagradost.shiro.utils.ShiroApi.Companion.getVideoLink
import com.lagradost.shiro.utils.ShiroApi.Companion.quickSearch
import com.lagradost.shiro.utils.ShiroApi.Companion.search
import org.junit.platform.runner.JUnitPlatform
import org.junit.runner.RunWith
import org.spekframework.spek2.Spek
import org.spekframework.spek2.style.specification.describe


@RunWith(JUnitPlatform::class)
object ApiTest : Spek({
    var token: ShiroApi.Token? = null
    var animePage: ShiroApi.AnimePage? = null

    val title = "Attack on titan"
    val slug = "shingeki-no-kyojin"

    group("Api test") {
        describe("Api Test") {
            it("Token test") {
                token = getToken()
                assertThat(token).isNotNull()
            }
            it("Home test") {
                val home = getHomeOnly(token)
                assertThat(home).isNotNull()
            }
            it("Seach test") {
                val search = search(title, token)
                assertThat(search).isNotNull()
            }
            it("Quick Search test") {
                val quickSearch = quickSearch(title, token)
                assertThat(quickSearch).isNotNull()
            }
            it("Animepage test") {
            }
            it("Video link test") {
                val episodeLink = animePage?.data?.episodes?.get(0)?.videos?.get(0)?.let { getVideoLink(it.video_id) }
                assertThat(episodeLink).isNotNull()
            }
            it("Random page test") {
                animePage = getRandomAnimePage(token)
                assertThat(animePage).isNotNull()
            }
        }
    }
})
