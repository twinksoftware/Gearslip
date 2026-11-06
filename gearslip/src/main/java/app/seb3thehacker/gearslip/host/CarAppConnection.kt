package app.seb3thehacker.gearslip.host

import app.seb3thehacker.gearslip.car.CarEnvironment
import app.seb3thehacker.gearslip.car.CarToasts
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.graphics.Rect
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Surface
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.HandshakeInfo
import androidx.car.app.SessionInfo
import androidx.car.app.SessionInfoIntentEncoder
import androidx.car.app.IAppHost
import androidx.car.app.IAppManager
import androidx.car.app.ICarApp
import androidx.car.app.ICarHost
import androidx.car.app.IOnDoneCallback
import androidx.car.app.model.Template
import androidx.car.app.model.TemplateWrapper
import androidx.car.app.ISurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.AppInfo
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.constraints.IConstraintHost
import androidx.car.app.suggestion.ISuggestionHost
import androidx.car.app.hardware.ICarHardwareHost
import androidx.car.app.hardware.ICarHardwareResult
import androidx.car.app.navigation.INavigationHost
import androidx.car.app.navigation.INavigationManager
import androidx.car.app.serialization.Bundleable
import androidx.car.app.versioning.CarAppApiLevels
import app.seb3thehacker.gearslip.GearslipLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Speaks the Car App Library's host half, so a templated app can be rendered by Gearslip.
 *
 * This is the opposite end of the interface an app implements: the app exports a
 * CarAppService and describes a screen as a template, and whichever host it is bound to
 * decides how that template looks. Android Auto is one such host. So is this.
 *
 * Whether an app will talk to us at all is decided by its HostValidator, which accepts a host
 * that either holds android.car.permission.TEMPLATE_RENDERER or is signed by a key on its
 * allow-list. Gearslip declares that permission in its manifest, which is the whole reason
 * this can work from an ordinary app - see AndroidManifest.xml.
 *
 * Every binder callback lands on a binder thread and is hopped to the main thread, because
 * the state here feeds Compose.
 *
 * With a cluster [session], this is the same app's second session, for the car's instrument
 * cluster: a bind of its own, which the app answers with a session of its own. It leans on the
 * main session for location, so it holds none itself.
 */
class CarAppConnection(private val context: Context, private val session: SessionInfo? = null) {

    enum class Phase { IDLE, BINDING, HANDSHAKE, RUNNING, REJECTED, FAILED }

    data class Status(
        val phase: Phase = Phase.IDLE,
        val app: String? = null,
        val detail: String? = null,
        val apiLevel: Int = 0,
        val template: String? = null,
        val surfaceRequested: Boolean = false,
        val surfaceAttached: Boolean = false,
    )

    /** The Surface lent to the app, plus the size the app should draw at. */
    private data class Lent(val surface: Surface, val width: Int, val height: Int, val dpi: Int)

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    /** The app's current screen, as it described it. Null until the first one arrives. */
    private val _template = MutableStateFlow<Template?>(null)
    val template: StateFlow<Template?> = _template.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    /** Captured by each host Binder so callbacks queued by a replaced app cannot show a toast. */
    private var toastOwner = Any()

    private var carApp: ICarApp? = null
    private var appManager: IAppManager? = null
    /** Only present for an app that asked for it - most apps aren't navigation apps. */
    private var navigationManager: INavigationManager? = null
    /** Main thread only, both of these - the handoff is a race between them either way round. */
    private var surfaceCallback: ISurfaceCallback? = null

    /** Last visible and stable areas the UI reported, kept for the next surface. */
    private var areas: Pair<Rect, Rect>? = null
    private var lent: Lent? = null

    private var binding: ServiceConnection? = null
    private var component: ComponentName? = null

    /** Whose icon the header shows for the standard app-icon action. */
    val appPackage: String? get() = component?.packageName

    /** Which app is bound right now, if any - lets a caller tell "still this one" from "a new one". */
    val connectedComponent: ComponentName? get() = component

    private var frameWidth = 800
    private var frameHeight = 480
    private var frameDensity = 160

    /** A destination to navigate to as soon as the app is created; see [navigateTo]. */
    private var pendingDestination: String? = null

    // --- connection ---------------------------------------------------------------------

    fun connect(app: TemplateApp, width: Int, height: Int, densityDpi: Int, destination: String? = null) {
        // Keeps the location hold through a reconnect: stopping and restarting the service can be
        // refused once the phone is locked, leaving the new app without location.
        disconnect(keepLocation = true)
        frameWidth = width
        frameHeight = height
        frameDensity = densityDpi
        component = app.component
        pendingDestination = destination
        update { Status(phase = Phase.BINDING, app = app.label) }

        val intent = Intent(CarAppService.SERVICE_INTERFACE).setComponent(app.component)
        session?.let { SessionInfoIntentEncoder.encode(it, intent) }
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                main.post { onBound(service) }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                main.post { fail(Phase.FAILED, "the app's service disconnected") }
            }

            override fun onNullBinding(name: ComponentName) {
                // A templated app that refuses a host returns no binder at all.
                main.post { fail(Phase.REJECTED, "the app refused to bind - host not accepted") }
            }
        }
        binding = connection

        val bind = bind@{
            // Replaced by another connect (or a disconnect) while waiting on the location service.
            if (binding !== connection) return@bind
            var bindError: Throwable? = null
            // BIND_ALLOW_ACTIVITY_STARTS lets the app start its own location service while
            // Gearslip's car window is showing. Since Android 14 a bound app only inherits that
            // from a visible host that opts in, and MapQuest crashes as navigation starts without it.
            val flags = Context.BIND_AUTO_CREATE or LocationKeepAlive.BIND_INCLUDE_CAPABILITIES or
                (if (Build.VERSION.SDK_INT >= 34) Context.BIND_ALLOW_ACTIVITY_STARTS else 0)
            val bound = runCatching {
                // Without BIND_FOREGROUND_SERVICE, an app bound by a foreground service (which is
                // all Gearslip is once the phone screen leaves it) only reaches "important
                // foreground", and that state loses network under Doze and background network
                // restrictions: the map and GPS keep working while search and routing go dead.
                // In the preview Gearslip is the top app, which hides this. Retried without the
                // flag in case a build of Android refuses it from an unprivileged caller.
                runCatching { context.bindService(intent, connection, flags or BIND_FOREGROUND_SERVICE) }
                    .getOrElse {
                        GearslipLog.w("host: foreground binding refused (${it.message}); binding without it")
                        context.bindService(intent, connection, flags)
                    }
            }.getOrElse {
                GearslipLog.e("bindService threw for ${app.component}", it)
                bindError = it
                false
            }
            if (!bound) {
                binding = null
                fail(Phase.REJECTED, explainBindFailure(app.component, bindError))
            } else {
                GearslipLog.i("host: binding to ${app.component.flattenToShortString()}")
            }
        }

        // BIND_INCLUDE_CAPABILITIES only lends the location capability Gearslip holds at bind
        // time, so the keep-alive has to be running first - a nav app that starts its own
        // location service before then is refused, and MapQuest crashes on that.
        if (holdsLocation || session != null) {
            bind()
        } else {
            holdsLocation = true
            LocationKeepAlive.acquire(context) { bind() }
        }
    }

    /** Whether this connection has a hold on [LocationKeepAlive]. */
    private var holdsLocation = false

    /**
     * A service that names a permission can only be bound by an app holding it. Some navigation apps
     * name one that only Google's own Android Auto host holds, and no other app can be granted.
     */
    private fun explainBindFailure(component: ComponentName, error: Throwable?): String {
        if (error !is SecurityException) return "the app would not let Gearslip connect"
        val needed = runCatching { context.packageManager.getServiceInfo(component, 0).permission }.getOrNull()
        return if (needed != null) {
            "It only accepts Google's own Android Auto: its service requires the permission $needed, which Gearslip can't hold."
        } else {
            "It only accepts Google's own Android Auto host."
        }
    }

    /** The app's own account of why it said no, in words rather than an object id. */
    private fun describeFailure(step: String, value: Any?): String {
        val failure = value as? androidx.car.app.FailureResponse
            ?: return "$step failed: ${value ?: "no reason given"}"
        val firstLine = failure.stackTrace.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        GearslipLog.w("host: $step failed: type=${failure.errorType}\n${failure.stackTrace.lineSequence().take(6).joinToString("\n")}")
        // The Car App Library's own host check: the app keeps a list of hosts it trusts by signature.
        if (firstLine.contains("Unrecognized host")) {
            return "It only trusts hosts on its own allow-list (Google's Android Auto and a few others), and Gearslip isn't on it."
        }
        val kind = when (failure.errorType) {
            androidx.car.app.FailureResponse.SECURITY_EXCEPTION -> "It rejected Gearslip as a host"
            androidx.car.app.FailureResponse.ILLEGAL_STATE_EXCEPTION -> "It reported a state error"
            androidx.car.app.FailureResponse.INVALID_PARAMETER_EXCEPTION -> "It reported an invalid parameter"
            else -> "It failed"
        }
        return "$kind at $step: ${firstLine.substringAfterLast("Exception: ", firstLine)}"
    }

    fun disconnect() = disconnect(keepLocation = false)

    private fun disconnect(keepLocation: Boolean) {
        main.removeCallbacks(handshakeTimeout)
        hardware.stop()
        clearToasts()
        lent?.let { sendSurfaceDestroyed(it) }
        lent = null
        val carApp = this.carApp
        if (carApp != null) {
            runCatching { carApp.onAppPause(noop("onAppPause")) }
            runCatching { carApp.onAppStop(noop("onAppStop")) }
        }
        if (holdsLocation && !keepLocation) {
            holdsLocation = false
            LocationKeepAlive.release(context)
        }
        binding?.let { runCatching { context.unbindService(it) } }
        binding = null
        this.carApp = null
        appManager = null
        navigationManager = null
        navigating = false
        main.removeCallbacks(sendReroute)
        reroute = null
        autoRoute.disarm()
        _panMode.value = false
        surfaceCallback = null
        component = null
        pendingDestination = null
        _template.value = null
        update { Status() }
    }

    // --- handshake ---------------------------------------------------------------------

    /**
     * An app that won't have Gearslip as its host never answers the first question. Car App
     * Library 1.9 and later throw inside the app when a host that isn't Google's asks, and since
     * the call is one-way the error never comes back, so without this the car would show
     * "Starting..." for ever.
     */
    private val handshakeTimeout = Runnable {
        if (_status.value.phase == Phase.HANDSHAKE) {
            fail(Phase.REJECTED, "It didn't answer Gearslip. Apps built for newer versions of Android Auto only run on Google's.")
        }
    }

    private fun onBound(service: IBinder) {
        val app = ICarApp.Stub.asInterface(service)
        carApp = app
        update { it.copy(phase = Phase.HANDSHAKE, detail = "bound, asking for app info") }
        GearslipLog.i("host: bound; requesting app info")
        main.postDelayed(handshakeTimeout, HANDSHAKE_TIMEOUT_MS)

        call("getAppInfo", onValue = { value ->
            val info = value as? AppInfo ?: return@call fail(Phase.FAILED, "app info was ${value?.javaClass?.simpleName}")
            onAppInfo(info)
        }) { app.getAppInfo(it) }
    }

    /**
     * Picks the newest API level both sides understand. An app that needs more than this host
     * offers cannot be rendered correctly, so it is refused here rather than half-drawn.
     */
    private fun onAppInfo(info: AppInfo) {
        main.removeCallbacks(handshakeTimeout)
        val negotiated = minOf(HOST_API_LEVEL, info.latestCarAppApiLevel)
        GearslipLog.i(
            "host: app api ${info.minCarAppApiLevel}..${info.latestCarAppApiLevel} " +
                "(library ${info.libraryDisplayVersion}), host $HOST_API_LEVEL -> $negotiated"
        )
        if (negotiated < info.minCarAppApiLevel) {
            fail(Phase.FAILED, "app needs car api ${info.minCarAppApiLevel}, host offers $HOST_API_LEVEL")
            return
        }
        val app = carApp ?: return
        update { it.copy(apiLevel = negotiated, detail = "handshake") }

        val handshake = runCatching { Bundleable.create(HandshakeInfo(context.packageName, negotiated)) }
            .getOrElse { return fail(Phase.FAILED, "could not bundle the handshake: ${it.message}") }

        call("onHandshakeCompleted", onValue = { onHandshakeAccepted() }) {
            app.onHandshakeCompleted(handshake, it)
        }
    }

    private fun onHandshakeAccepted() {
        val app = carApp ?: return
        GearslipLog.i("host: handshake accepted; creating the app")
        val intent = pendingDestination?.let { autoRoute.arm(); navigateIntent(it) }
            ?: Intent(Intent.ACTION_MAIN).setComponent(component)
        pendingDestination = null
        call("onAppCreate", onValue = { onCreated() }) {
            app.onAppCreate(createCarHost(toastOwner), intent, carConfiguration(), it)
        }
    }

    /**
     * "navigate to X" deep link, the same one Android Auto's own Assistant integration sends -
     * [ACTION_NAVIGATE] with the destination as a `geo:0,0` search query, rather than anything
     * app-specific. The app searches near its own location; [AutoRoute] then picks the nearest
     * result. Coordinates in the link would mean "go exactly here": Organic Maps routed to the
     * phone's own position when it was given them.
     */
    private fun navigateIntent(destination: String): Intent =
        Intent(CarContext.ACTION_NAVIGATE, Uri.parse("geo:0,0?q=" + Uri.encode(destination)))
            .setComponent(component)

    /**
     * Sends a fresh destination to an app that's already running, instead of tearing it down
     * and rebuilding the whole session - see [app.seb3thehacker.gearslip.car.CarServices.connectNav].
     *
     * An app already on a route ends it first: Organic Maps ignored a new destination mid-route
     * and kept the old directions. The new one goes once the app says the route ended, or after
     * [REROUTE_WAIT_MS] if it never does.
     */
    fun navigateTo(destination: String) {
        if (carApp == null) return
        main.removeCallbacks(sendReroute)
        if (!navigating) {
            reroute = null
            sendDestination(destination)
            return
        }
        reroute = destination
        stopNavigating()
        main.postDelayed(sendReroute, REROUTE_WAIT_MS)
    }

    private fun sendDestination(destination: String) {
        val app = carApp ?: return
        autoRoute.arm()
        call("onNewIntent", onValue = {}) {
            app.onNewIntent(navigateIntent(destination), it)
        }
    }

    private val autoRoute = AutoRoute()

    /** A destination waiting for the current route to end; see [navigateTo]. */
    private var reroute: String? = null
    private val sendReroute = Runnable {
        reroute?.let { reroute = null; sendDestination(it) }
    }

    /** Whether the app has said it's on a route, between navigationStarted and navigationEnded. */
    @Volatile var navigating = false
        private set

    private fun onCreated() {
        val app = carApp ?: return
        GearslipLog.i("host: created; starting")
        call("onAppStart", onValue = {
            call("onAppResume", onValue = { onRunning() }) { app.onAppResume(it) }
        }) { app.onAppStart(it) }
    }

    private fun onRunning() {
        val app = carApp ?: return
        update { it.copy(phase = Phase.RUNNING, detail = "connected") }
        GearslipLog.verdict(
            "${component?.packageName} accepted Gearslip as a template host",
            "Handshake completed at car API level ${_status.value.apiLevel}.",
        )

        call("getManager(app)", onValue = { value ->
            appManager = when (value) {
                is IAppManager -> value
                is IBinder -> IAppManager.Stub.asInterface(value)
                else -> null
            }
            if (appManager == null) GearslipLog.e("host: app manager was ${value?.javaClass?.name}")
            requestTemplate()
        }) { app.getManager(CarContext.APP_SERVICE, it) }

        // Best-effort and silent: an app that never asked for NavigationManager (not every
        // templated app navigates) simply has no binder to hand back, which is normal, not a
        // failure worth surfacing.
        runCatching {
            app.getManager(CarContext.NAVIGATION_SERVICE, object : IOnDoneCallback.Stub() {
                // Car App Library 1.8 versions its host interfaces; each answers with the version it was built from.
                override fun getInterfaceVersion(): Int = IOnDoneCallback.VERSION
                override fun onSuccess(response: Bundleable?) {
                    val value = response?.let { runCatching { it.get() }.getOrNull() }
                    navigationManager = when (value) {
                        is INavigationManager -> value
                        is IBinder -> INavigationManager.Stub.asInterface(value)
                        else -> null
                    }
                }
                override fun onFailure(response: Bundleable?) = Unit
            })
        }
    }

    /**
     * Tells the app itself to stop navigating - the proper end of a route, not just leaving its
     * screen. Ending only the on-screen guidance (a back press) leaves the app's own
     * "am I navigating" flag set, which is what stopped a fresh route from starting afterwards;
     * this is the call [NavigationManagerCallback.onStopNavigation] on the app's side answers,
     * and it is expected to clear that flag and present its own post-navigation screen.
     */
    fun stopNavigating() {
        val manager = navigationManager ?: return
        GearslipLog.i("host: asking the app to stop navigating")
        runCatching { manager.onStopNavigation(noop("stop navigation")) }
            .onFailure { GearslipLog.w("host: could not stop navigation: ${it.message}") }
    }

    /** The app describes its current screen; this is what the host draws. */
    private fun requestTemplate() {
        val manager = appManager ?: return
        call("getTemplate", onValue = { value ->
            // TemplateWrapper carries the template plus the id the host echoes back on refresh.
            val template = (value as? TemplateWrapper)?.template ?: value as? Template
            if (template == null) {
                GearslipLog.w("host: getTemplate returned ${value?.javaClass?.name}")
                return@call
            }
            GearslipLog.i("host: template = ${template.javaClass.simpleName}")
            // The app's ScreenManager lists every screen on its stack; one or none is its first.
            _atRoot.value = ((value as? TemplateWrapper)?.templateInfosForScreenStack?.size ?: 1) <= 1
            _template.value = template
            update { it.copy(template = template.javaClass.simpleName) }
            autoRoute.onTemplate(template)
        }) { manager.getTemplate(it) }
    }

    private val _atRoot = MutableStateFlow(true)

    /** True while the app shows its first screen, where its own back would only close it. */
    val atRoot: StateFlow<Boolean> = _atRoot

    private val _panMode = MutableStateFlow(false)

    /**
     * Pan mode is the host's to run: the Pan action has no click handler, tapping it just switches
     * the screen to the map and its own controls. The app is told only if its template asks.
     */
    val panMode: StateFlow<Boolean> = _panMode

    fun setPanMode(on: Boolean) {
        if (_panMode.value == on) return
        _panMode.value = on
        GearslipLog.i("host: pan mode ${if (on) "on" else "off"}")
        (_template.value as? androidx.car.app.navigation.model.NavigationTemplate)
            ?.panModeDelegate.panModeChanged(on)
    }

    /**
     * The Back action carries no click handler of its own: the app keeps its own screen stack, so
     * the host reports the press and the app answers with the template it went back to.
     */
    fun backPressed() {
        val manager = appManager ?: return
        GearslipLog.i("host: back pressed")
        call("onBackPressed", onValue = { requestTemplate() }) { manager.onBackPressed(it) }
    }

    // --- the surface the app draws its map into -------------------------------------------

    /**
     * Lends the app a Surface to draw into.
     *
     * Navigation apps render their own map rather than describing it as a template, so the host
     * has to give them somewhere to draw. Gearslip's car UI already lives in a Presentation on a
     * VirtualDisplay backed by the encoder, so the Surface handed over here belongs to a
     * SurfaceView in that Presentation: the app draws straight into the car's picture and
     * SurfaceFlinger composites it under our chrome, with no readback or GL on our side.
     *
     * Safe to call before the app has asked for a surface - whichever of the two arrives second
     * completes the handoff.
     */
    fun attachSurface(surface: Surface, width: Int, height: Int, dpi: Int) {
        main.post {
            val current = lent
            if (current != null &&
                current.surface == surface &&
                current.width == width &&
                current.height == height
            ) {
                return@post
            }
            current?.let { sendSurfaceDestroyed(it) }
            lent = Lent(surface, width, height, dpi)
            sendSurfaceAvailable()
        }
    }

    /**
     * Takes the Surface back, e.g. when the SurfaceView goes away. With [surface], only if that is
     * still the one lent - a replaced view's teardown must not take back its successor's.
     */
    fun detachSurface(surface: Surface? = null) {
        main.post {
            val current = lent ?: return@post
            if (surface != null && current.surface !== surface) return@post
            lent = null
            sendSurfaceDestroyed(current)
            update { it.copy(surfaceAttached = false) }
        }
    }

    private val _surfaceEpoch = MutableStateFlow(0)

    /** Bumped to throw the lent surface away, so the stage builds a fresh one. */
    val surfaceEpoch: StateFlow<Int> = _surfaceEpoch.asStateFlow()

    /**
     * Called as the map comes back into view: replaces the surface only if it died while the map
     * was covered. A live one is left alone, because every hand-over costs the app its map - some
     * (MapQuest) rebuild theirs from a world view and fly back to the car's position.
     *
     * Always a new surface, never the dead one lent again: an app that draws through its own
     * VirtualDisplay (Flitsmeister does) builds its new display on a re-lent surface before
     * releasing the old, and the old one's disconnect then takes the shared BufferQueue's
     * connection with it - the map freezes on its last frame for good. The stage's key on
     * [surfaceEpoch] swaps in a new SurfaceView, which lends itself as it comes up.
     */
    fun replaceDeadSurface() {
        main.post {
            val current = lent ?: return@post
            if (current.surface.isValid) return@post
            GearslipLog.w("host: the lent surface died; building a new one")
            lent = null
            sendSurfaceDestroyed(current)
            _surfaceEpoch.value = _surfaceEpoch.value + 1
        }
    }

    private fun sendSurfaceAvailable() {
        val callback = surfaceCallback ?: return
        val surface = lent ?: return
        if (!surface.surface.isValid) {
            GearslipLog.w("host: not lending an invalid surface")
            return
        }

        val container = runCatching {
            Bundleable.create(
                SurfaceContainer(surface.surface, surface.width, surface.height, surface.dpi)
            )
        }.getOrElse {
            GearslipLog.e("host: could not bundle the surface", it)
            return
        }

        val sent = runCatching { callback.onSurfaceAvailable(container, noop("onSurfaceAvailable")) }
        if (sent.isFailure) {
            GearslipLog.e("host: onSurfaceAvailable threw", sent.exceptionOrNull())
            return
        }

        // The app draws for the whole surface; the visible area is what isn't covered by host
        // chrome, and the stable area is the part that never gets covered. Both depend on the
        // template (see visibleAreaFor) and are reported by the UI via [reportAreas] once it
        // has measured. A fresh surface the same size keeps the last ones, since the UI only
        // reports again when they change; until then the whole surface is fair game.
        val whole = Rect(0, 0, surface.width, surface.height)
        val last = areas?.takeIf { (visible, stable) -> whole.contains(visible) && whole.contains(stable) }
        reportAreas(last?.first ?: whole, last?.second ?: whole)

        GearslipLog.i(
            "host: lent the app a ${surface.width}x${surface.height} @${surface.dpi}dpi surface"
        )
        update { it.copy(surfaceAttached = true) }
    }

    private fun sendSurfaceDestroyed(surface: Lent) {
        val callback = surfaceCallback ?: return
        val container = runCatching {
            Bundleable.create(
                SurfaceContainer(surface.surface, surface.width, surface.height, surface.dpi)
            )
        }.getOrNull() ?: return
        runCatching { callback.onSurfaceDestroyed(container, noop("onSurfaceDestroyed")) }
        GearslipLog.i("host: took the surface back")
    }

    /** Tells the app which part of its surface the driver can actually see. */
    fun reportAreas(visible: Rect, stable: Rect) {
        areas = visible to stable
        val callback = surfaceCallback ?: return
        GearslipLog.i("host: areas for $appPackage: visible=${visible.toShortString()} stable=${stable.toShortString()}")
        runCatching { callback.onStableAreaChanged(stable, noop("onStableAreaChanged")) }
        runCatching { callback.onVisibleAreaChanged(visible, noop("onVisibleAreaChanged")) }
    }

    // --- gestures on the app's surface -----------------------------------------------------
    //
    // These are the map interactions a host forwards: the app never sees raw touches, only
    // these four. Coordinates are in surface pixels. Scroll distances follow GestureDetector's
    // convention (previous minus current), which is the opposite sign to a Compose drag.

    fun surfaceClick(x: Float, y: Float) = onSurface("onClick") { it.onClick(x, y) }

    fun surfaceScroll(distanceX: Float, distanceY: Float) =
        onSurface("onScroll") { it.onScroll(distanceX, distanceY) }

    fun surfaceFling(velocityX: Float, velocityY: Float) =
        onSurface("onFling") { it.onFling(velocityX, velocityY) }

    fun surfaceScale(focusX: Float, focusY: Float, scaleFactor: Float) =
        onSurface("onScale") { it.onScale(focusX, focusY, scaleFactor) }

    private inline fun onSurface(name: String, send: (ISurfaceCallback) -> Unit) {
        val callback = surfaceCallback ?: return
        runCatching { send(callback) }.onFailure { GearslipLog.w("host: $name failed: ${it.message}") }
    }

    // --- host interfaces the app calls back into -----------------------------------------

    private fun createAppHost(owner: Any) = object : IAppHost.Stub() {
        override fun getInterfaceVersion(): Int = IAppHost.VERSION
        override fun invalidate() {
            GearslipLog.i("host: app invalidated its template")
            main.post { requestTemplate() }
        }

        override fun showToast(text: CharSequence?, duration: Int) {
            val message = text?.toString() ?: return
            main.post {
                if (toastOwner !== owner) return@post
                GearslipLog.i("host: toast \"$message\"")
                CarToasts.show(owner, message, duration)
            }
        }

        /**
         * Navigation apps draw their own map, so the host lends them a Surface. Receiving this
         * is the signal that the app is ready to render into the car's picture.
         */
        override fun setSurfaceCallback(callback: ISurfaceCallback?) {
            GearslipLog.i("host: app asked for a surface (callback=${callback != null})")
            main.post {
                surfaceCallback = callback
                update { it.copy(surfaceRequested = callback != null) }
                if (callback != null) sendSurfaceAvailable()
            }
        }

        override fun sendLocation(location: Location?) = Unit
        override fun showAlert(alert: Bundleable?) {
            val shown = runCatching { alert?.get() as? androidx.car.app.model.Alert }
                .onFailure { GearslipLog.w("host: couldn't read an alert: ${it.message}") }
                .getOrNull() ?: return
            main.post { if (toastOwner === owner) app.seb3thehacker.gearslip.car.CarAlerts.show(owner, shown) }
        }

        override fun dismissAlert(alertId: Int) {
            main.post { app.seb3thehacker.gearslip.car.CarAlerts.dismiss(owner, alertId) }
        }
        override fun openMicrophone(request: Bundleable?): Bundleable? = null
    }

    private val _trip = MutableStateFlow<androidx.car.app.navigation.model.Trip?>(null)
    /** The route as the app tells clusters about it: steps and how far to each. Null when not navigating. */
    val trip: StateFlow<androidx.car.app.navigation.model.Trip?> = _trip.asStateFlow()

    private val navigationHost = object : INavigationHost.Stub() {
        override fun getInterfaceVersion(): Int = INavigationHost.VERSION
        override fun navigationStarted() {
            GearslipLog.i("host: navigation started")
            navigating = true
        }
        override fun navigationEnded() {
            GearslipLog.i("host: navigation ended")
            navigating = false
            _trip.value = null
            main.post { if (reroute != null) { main.removeCallbacks(sendReroute); sendReroute.run() } }
        }
        override fun updateTrip(trip: Bundleable?) {
            _trip.value = runCatching { trip?.get() as? androidx.car.app.navigation.model.Trip }.getOrNull()
        }
    }

    /**
     * Answers car-hardware questions from what the car itself sends: see [CarHardwareFeed].
     *
     * Every question has to get an actual answer, even "unsupported". Returning no binder for
     * this service at all is what made Organic Maps crash in onAppStart: its CarSensorsManager
     * assumes a host that offers the service, and the library's own "unsupported" path only runs
     * once a reply arrives. The reply's IBinder must be a real IOnDoneCallback: the app
     * dispatches the reply through it.
     */
    private val hardware = CarHardwareFeed { name -> noop(name).asBinder() }

    private val hardwareHost = object : ICarHardwareHost.Stub() {
        override fun getInterfaceVersion(): Int = ICarHardwareHost.VERSION
        override fun getCarHardwareResult(type: Int, params: Bundleable?, result: ICarHardwareResult?) =
            hardware.get(type, result)

        override fun subscribeCarHardwareResult(type: Int, params: Bundleable?, result: ICarHardwareResult?) =
            hardware.subscribe(type, result)

        override fun unsubscribeCarHardwareResult(type: Int, params: Bundleable?) = hardware.unsubscribe(type)
    }

    /**
     * Tells apps how much content this screen can take.
     *
     * These are safety limits in a real car - how many rows a driver may be shown at once -
     * and the app trims its own lists to them. Gearslip's numbers match what the Car App
     * Library documents for a projected head unit; they are about the screen, not about us.
     */
    private val constraintHost = object : IConstraintHost.Stub() {
        override fun getInterfaceVersion(): Int = IConstraintHost.VERSION
        override fun getContentLimit(type: Int): Int = when (type) {
            ConstraintManager.CONTENT_LIMIT_TYPE_GRID -> 12
            ConstraintManager.CONTENT_LIMIT_TYPE_PANE -> 4
            ConstraintManager.CONTENT_LIMIT_TYPE_ROUTE_LIST -> 3
            else -> 12
        }

        /** The app may refresh its own template without the driver touching anything. */
        override fun isAppDrivenRefreshEnabled(): Boolean = true
    }

    /**
     * Accepts the shortcuts an app offers for a car launcher.
     *
     * Gearslip has nowhere to show these yet, but accepting and dropping them is right: an app
     * that pushes suggestions shouldn't fail because its host doesn't surface them.
     */
    private val suggestionHost = object : ISuggestionHost.Stub() {
        override fun getInterfaceVersion(): Int = ISuggestionHost.VERSION
        override fun updateSuggestions(suggestions: Bundleable?) = Unit
    }

    private fun createCarHost(owner: Any) = object : ICarHost.Stub() {
        override fun getInterfaceVersion(): Int = ICarHost.VERSION
        private val appHost = createAppHost(owner)

        override fun getHost(type: String?): IBinder? = when (type) {
            CarContext.APP_SERVICE -> appHost.asBinder()
            CarContext.NAVIGATION_SERVICE -> navigationHost.asBinder()
            CarContext.HARDWARE_SERVICE -> hardwareHost.asBinder()
            CarContext.CONSTRAINT_SERVICE -> constraintHost.asBinder()
            CarContext.SUGGESTION_SERVICE -> suggestionHost.asBinder()
            else -> {
                GearslipLog.i("host: app asked for an unsupported service \"$type\"")
                null
            }
        }

        override fun startCarApp(intent: Intent?) {
            GearslipLog.i("host: app wants to start $intent")
        }

        override fun finish() {
            GearslipLog.i("host: app finished")
            main.post { disconnect() }
        }
    }

    // --- plumbing ------------------------------------------------------------------------

    /**
     * Every call in this protocol is one-way plus an IOnDoneCallback, so this pairs them back
     * up and funnels a remote failure into the visible status rather than losing it.
     */
    private fun call(name: String, onValue: (Any?) -> Unit, send: (IOnDoneCallback) -> Unit) {
        val callback = object : IOnDoneCallback.Stub() {
            override fun getInterfaceVersion(): Int = IOnDoneCallback.VERSION
            override fun onSuccess(response: Bundleable?) {
                val value = response?.let { runCatching { it.get() }.getOrNull() }
                main.post { onValue(value) }
            }

            override fun onFailure(response: Bundleable?) {
                val value = response?.let { runCatching { it.get() }.getOrNull() }
                main.post { fail(Phase.REJECTED, describeFailure(name, value)) }
            }
        }
        runCatching { send(callback) }
            .onFailure { fail(Phase.REJECTED, "$name threw: ${it.javaClass.simpleName} ${it.message}") }
    }

    private fun noop(name: String) = object : IOnDoneCallback.Stub() {
        override fun getInterfaceVersion(): Int = IOnDoneCallback.VERSION
        override fun onSuccess(response: Bundleable?) = Unit
        override fun onFailure(response: Bundleable?) = GearslipLog.w("host: $name failed")
    }

    /**
     * The app lays itself out for this, so it describes the car screen rather than the phone,
     * including UI_MODE_TYPE_CAR - templated apps branch on that.
     */
    /** The day/night value last handed to the app, so a change is only pushed when it is one. */
    private var sentNight: Boolean? = null

    /**
     * Tells a running app that the car's light changed. Templated apps take their map style from
     * the configuration the host reports, so this is what turns the map to day or night.
     */
    fun pushConfiguration() {
        val app = carApp ?: return
        if (_status.value.phase != Phase.RUNNING) return
        if (CarEnvironment.darkOutside.value == sentNight) return
        val configuration = carConfiguration()
        call("onConfigurationChanged", onValue = { }) { app.onConfigurationChanged(configuration, it) }
    }

    private fun carConfiguration(): Configuration {
        val configuration = Configuration(context.resources.configuration)
        val night = CarEnvironment.darkOutside.value
        sentNight = night
        configuration.uiMode = (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        configuration.densityDpi = frameDensity
        configuration.screenWidthDp = frameWidth * 160 / frameDensity
        configuration.screenHeightDp = frameHeight * 160 / frameDensity
        configuration.smallestScreenWidthDp =
            minOf(configuration.screenWidthDp, configuration.screenHeightDp)
        configuration.orientation =
            if (frameWidth >= frameHeight) Configuration.ORIENTATION_LANDSCAPE
            else Configuration.ORIENTATION_PORTRAIT
        configuration.uiMode = (configuration.uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or
            Configuration.UI_MODE_TYPE_CAR
        return configuration
    }

    private fun fail(phase: Phase, detail: String) {
        clearToasts()
        GearslipLog.e("host: $detail")
        update { it.copy(phase = phase, detail = detail) }
    }

    private fun clearToasts() {
        CarToasts.clear(toastOwner)
        app.seb3thehacker.gearslip.car.CarAlerts.clear(toastOwner)
        toastOwner = Any()
    }

    private fun update(block: (Status) -> Status) {
        _status.value = block(_status.value)
    }

    private companion object {
        /** The newest template API this host claims to understand. */
        const val HOST_API_LEVEL = CarAppApiLevels.LEVEL_8

        /** How long a new destination waits for the old route to end; see [navigateTo]. */
        const val REROUTE_WAIT_MS = 1_500L

        /** How long an app gets to answer the first question before it counts as refusing. */
        const val HANDSHAKE_TIMEOUT_MS = 10_000L

        /** Context.BIND_FOREGROUND_SERVICE; the constant itself is hidden from the SDK. */
        const val BIND_FOREGROUND_SERVICE = 0x04000000
    }
}
