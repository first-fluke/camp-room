package camp.room.app.observability

import android.content.Context
import camp.room.app.BuildConfig
import com.posthog.PersonProfiles
import com.posthog.PostHog
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.android.core.SentryAndroid
import io.sentry.protocol.Message
import io.sentry.protocol.SentryException
import io.sentry.protocol.SentryStackFrame
import io.sentry.protocol.SentryStackTrace
import java.io.File
import java.net.URI
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CampTelemetry @Inject constructor(@ApplicationContext private val context: Context):Telemetry {
    private val store=DiagnosticStore(File(context.filesDir,"diagnostics"))
    private val executor=ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,ArrayBlockingQueue(128),
        { task -> Thread(task,"camp-diagnostics").apply { isDaemon=true } },ThreadPoolExecutor.DiscardOldestPolicy())
    @Volatile private var initialized=false
    @Volatile private var sentry=false
    @Volatile private var analytics=false
    private val anonymousSession=UUID.randomUUID().toString()

    @Synchronized override fun initialize() {
        if(initialized)return
        initialized=true
        if(BuildConfig.TELEMETRY_CLOUD_ENABLED) {
            if(BuildConfig.SENTRY_DSN.isNotBlank())runCatching {
                SentryAndroid.init(context) { options ->
                    options.dsn=BuildConfig.SENTRY_DSN
                    options.isSendDefaultPii=false
                    options.isEnableAutoSessionTracking=false
                    options.isEnableActivityLifecycleBreadcrumbs=false
                    options.isEnableAppLifecycleBreadcrumbs=false
                    options.isEnableSystemEventBreadcrumbs=false
                    options.isEnableUserInteractionBreadcrumbs=false
                    options.isEnableUserInteractionTracing=false
                    options.isAttachScreenshot=false
                    options.isAttachViewHierarchy=false
                    options.isAnrEnabled=true
                    options.tracesSampleRate=0.0
                    options.maxBreadcrumbs=0
                    options.environment=if(BuildConfig.IS_DEMO)"demo" else "pleos"
                    options.release="camp-room@"+BuildConfig.VERSION_NAME
                    options.setBeforeSend { event,_ -> sanitizedSentry(event) }
                }
                sentry=Sentry.isEnabled()
            }
            val host=runCatching { URI(BuildConfig.POSTHOG_HOST) }.getOrNull()
            if(BuildConfig.POSTHOG_API_KEY.isNotBlank() && host?.scheme=="https" && host.host!=null && host.userInfo==null)runCatching {
                val config=PostHogAndroidConfig(projectToken=BuildConfig.POSTHOG_API_KEY,host=BuildConfig.POSTHOG_HOST).apply {
                    captureApplicationLifecycleEvents=false;captureScreenViews=false;captureDeepLinks=false
                    capturePushNotificationSubscriptions=false;capturePushNotificationOpened=false
                    personProfiles=PersonProfiles.NEVER;setDefaultPersonProperties=false
                    sessionReplay=false;preloadFeatureFlags=false;sendFeatureFlagEvent=false;remoteConfig=false
                    errorTrackingConfig.autoCapture=false
                    maxQueueSize=100;maxBatchSize=20;flushAt=10;flushIntervalSeconds=30
                    addBeforeSend { event ->
                        if(!TelemetryPolicy.isKnownEvent(event.event))null else {
                            val properties=TelemetryPolicy.filter(event.properties.orEmpty()).mapValues { it.value as Any }.toMutableMap()
                            properties["\$geoip_disable"]=true
                            properties["\$process_person_profile"]=false
                            event.copy(distinctId=anonymousSession,properties=properties,uuid=UUID.randomUUID(),set=null)
                        }
                    }
                }
                PostHogAndroid.setup(context,config)
                analytics=true
            }
        }
        Thread.getDefaultUncaughtExceptionHandler()?.let { previous ->
            Thread.setDefaultUncaughtExceptionHandler(RecordingExceptionHandler(store,previous))
        }
        record(Event.APP_STARTED)
        record(Event.CLOUD_STATE,Dimensions(outcome=if(sentry||analytics)Outcome.STARTED else Outcome.UNAVAILABLE))
    }

    override fun record(event:Event,dimensions:Dimensions) {
        val record=TelemetryPolicy.record(event,dimensions.copy(
            runtime=if(BuildConfig.IS_DEMO)RuntimeMode.DEMO else RuntimeMode.PLEOS,
            orderMode=if(dimensions.action==Action.CHECKOUT || dimensions.api==ApiOperation.CREATE_ORDER)OrderMode.DEMO else null,
        ))
        executor.execute {
            runCatching { store.append(record) }
            if(analytics)runCatching { PostHog.capture(event=record.event,properties=record.attributes) }
        }
    }
    override fun failure(error:Throwable,dimensions:Dimensions) {
        record(Event.FAILURE,dimensions.copy(outcome=Outcome.FAILED,errorType=TelemetryPolicy.errorType(error)))
        if(sentry)runCatching {
            val event=SentryEvent().apply {
                level=SentryLevel.ERROR
                message=Message().apply { formatted="camp_failure" }
                exceptions=listOf(SentryException().apply {
                    type=TelemetryPolicy.errorType(error)
                    stacktrace=SentryStackTrace(error.stackTrace.take(32).map { source -> SentryStackFrame().apply {
                        module=source.className;function=source.methodName;lineno=source.lineNumber
                    } })
                })
            }
            Sentry.captureEvent(event)
        }
    }
}

/** Rebuild instead of trying to redact an expanding vendor schema. Raw messages never survive. */
internal fun sanitizedSentry(original:SentryEvent):SentryEvent = SentryEvent().apply {
    level=original.level
    release=original.release
    environment=original.environment
    platform="java"
    exceptions=original.exceptions?.take(4)?.map { source -> SentryException().apply {
        type=source.type?.take(120);module=source.module?.take(120)
        mechanism=io.sentry.protocol.Mechanism().apply { type=source.mechanism?.type;isHandled=source.mechanism?.isHandled }
        stacktrace=SentryStackTrace(source.stacktrace?.frames.orEmpty().takeLast(32).map { frame -> SentryStackFrame().apply {
            module=frame.module?.take(120);function=frame.function?.take(100);lineno=frame.lineno
        } })
    } }
    if(original.message?.formatted?.let(TelemetryPolicy::isKnownEvent)==true)
        message=Message().apply { formatted=original.message?.formatted }
    tags=TelemetryPolicy.filter(original.tags.orEmpty())
}
