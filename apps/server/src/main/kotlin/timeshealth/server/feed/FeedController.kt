package timeshealth.server.feed

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import timeshealth.app.core.model.HomeFeedResponse
import timeshealth.app.core.model.LiveClassJoinResponse
import timeshealth.app.core.model.LiveClassListResponse
import timeshealth.server.db.entity.UserEntity
import timeshealth.server.security.CurrentUser
import timeshealth.server.yoga.LiveClassService

/** Home (built from the admin CMS) and live classes. */
@RestController
@RequestMapping("/v1")
class FeedController(private val home: HomeFeedService, private val liveClasses: LiveClassService) {

    /** The entire Home layout, decided server-side from the admin's sections. */
    @GetMapping("/home")
    fun home(@CurrentUser user: UserEntity): HomeFeedResponse = home.build(user)

    /** Today's and the coming week's live classes (premieres), soonest first. */
    @GetMapping("/yoga/live")
    fun live(@CurrentUser user: UserEntity): LiveClassListResponse = liveClasses.list(user)

    /** The stream for a live class, and attendance for members. */
    @PostMapping("/yoga/live/{id}/join")
    fun join(@PathVariable("id") id: String, @CurrentUser user: UserEntity): LiveClassJoinResponse =
        liveClasses.join(user, id)
}
