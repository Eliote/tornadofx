@file:Suppress("unused")

package tornadofx

import javafx.application.Application
import javafx.application.Platform
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleObjectProperty
import javafx.collections.FXCollections
import javafx.collections.ListChangeListener
import javafx.event.EventTarget
import javafx.scene.Group
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.image.Image
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.layout.BorderPane
import javafx.scene.layout.Pane
import javafx.stage.Stage
import tornadofx.osgi.impl.getBundleId
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.logging.Logger
import kotlin.reflect.KClass

class FX {
    companion object {
        val log = Logger.getLogger("FX")
        val initialized = SimpleBooleanProperty(false)
        lateinit var primaryStage: Stage
        lateinit var application: Application
        val stylesheets = FXCollections.observableArrayList<String>()
        val components = HashMap<KClass<out Injectable>, Injectable>()
        val lock = Any()
        @JvmStatic
        var dicontainer: DIContainer? = null
        var reloadStylesheetsOnFocus = false
        var reloadViewsOnFocus = false
        var dumpStylesheets = false
        var layoutDebuggerShortcut: KeyCodeCombination? = KeyCodeCombination(KeyCode.J, KeyCodeCombination.META_DOWN, KeyCodeCombination.ALT_DOWN)
        var osgiDebuggerShortcut: KeyCodeCombination? = KeyCodeCombination(KeyCode.O, KeyCodeCombination.META_DOWN, KeyCodeCombination.ALT_DOWN)

        val osgiAvailable: Boolean by lazy {
            try {
                Class.forName("org.osgi.framework.FrameworkUtil")
                true
            } catch (ex: Throwable) {
                false
            }
        }

        private val _locale: SimpleObjectProperty<Locale> = object : SimpleObjectProperty<Locale>() {
            override fun invalidated() = loadMessages()
        }
        var locale: Locale get() = _locale.get(); set(value) = _locale.set(value)
        fun localeProperty() = _locale

        private val _messages: SimpleObjectProperty<ResourceBundle> = SimpleObjectProperty()
        var messages: ResourceBundle get() = _messages.get(); set(value) = _messages.set(value)
        fun messagesProperty() = _messages

        /**
         * Load global resource bundle for the current locale. Triggered when the locale changes.
         */
        private fun loadMessages() {
            try {
                messages = ResourceBundle.getBundle("Messages", locale, FXResourceBundleControl.INSTANCE)
            } catch (ex: Exception) {
                log.fine({ "No global Messages found in locale $locale, using empty bundle" })
                messages = EmptyResourceBundle.INSTANCE
            }
        }

        fun installErrorHandler() {
            if (Thread.getDefaultUncaughtExceptionHandler() == null)
                Thread.setDefaultUncaughtExceptionHandler(DefaultErrorHandler())
        }

        init {
            locale = Locale.getDefault()
        }

        fun runAndWait(action: () -> Unit) {
            // run synchronously on JavaFX thread
            if (Platform.isFxApplicationThread()) {
                action()
                return
            }

            // queue on JavaFX thread and wait for completion
            val doneLatch = CountDownLatch(1)
            Platform.runLater {
                try {
                    action()
                } finally {
                    doneLatch.countDown()
                }
            }

            try {
                doneLatch.await()
            } catch (e: InterruptedException) {
                // ignore exception
            }
        }

        @JvmStatic
        fun registerApplication(application: Application, primaryStage: Stage) {
            FX.installErrorHandler()
            FX.primaryStage = primaryStage
            FX.application = application

            if (application.parameters?.unnamed != null) {
                with(application.parameters.unnamed) {
                    if (contains("--dev-mode")) {
                        reloadStylesheetsOnFocus = true
                        dumpStylesheets = true
                        reloadViewsOnFocus = true
                    }
                    if (contains("--live-stylesheets")) reloadStylesheetsOnFocus = true
                    if (contains("--dump-stylesheets")) dumpStylesheets = true
                    if (contains("--live-views")) reloadViewsOnFocus = true
                }
            }

            if (reloadStylesheetsOnFocus) primaryStage.reloadStylesheetsOnFocus()
            if (reloadViewsOnFocus) primaryStage.reloadViewsOnFocus()
        }

        @JvmStatic
        fun <T : Component> find(componentType: Class<T>): T = find(componentType.kotlin)

        fun replaceComponent(obsolete: UIComponent) {
            val replacement: UIComponent

            if (obsolete is View) {
                components.remove(obsolete.javaClass.kotlin)
                replacement = find(obsolete.javaClass.kotlin)
            } else {
                val noArgsConstructor = obsolete.javaClass.constructors.filter { it.parameterCount == 0 }.isNotEmpty()
                if (noArgsConstructor) {
                    replacement = obsolete.javaClass.newInstance()
                } else {
                    log.warning("Unable to reload $obsolete because it's missing a no args constructor")
                    return
                }
            }

            replacement.reloadInit = true

            if (obsolete.root.parent is Pane) {
                (obsolete.root.parent as Pane).children.apply {
                    val index = indexOf(obsolete.root)
                    remove(obsolete.root)
                    add(index, replacement.root)
                }
                log.info("Reloaded [Parent] $obsolete")
            } else {
                if (obsolete.properties.containsKey("tornadofx.scene")) {
                    val scene = obsolete.properties["tornadofx.scene"] as Scene
                    replacement.properties["tornadofx.scene"] = scene
                    scene.root = replacement.root
                    log.info("Reloaded [Scene] $obsolete")
                } else {
                    log.warning("Unable to reload $obsolete because it has no parent and no scene attached")
                }
            }
        }

        fun applyStylesheetsTo(scene: Scene) {
            scene.stylesheets.addAll(stylesheets)
            stylesheets.addListener(ListChangeListener {
                while (it.next()) {
                    if (it.wasAdded()) it.addedSubList.forEach { scene.stylesheets.add(it) }
                    if (it.wasRemoved()) it.removed.forEach { scene.stylesheets.remove(it) }
                }
            })
        }
    }
}

fun addStageIcon(icon: Image) {
    val adder = { FX.primaryStage.icons += icon }
    if (FX.initialized.value) adder() else FX.initialized.addListener { obs, o, n -> adder() }
}

fun reloadStylesheetsOnFocus() {
    FX.reloadStylesheetsOnFocus = true
}

fun dumpStylesheets() {
    FX.dumpStylesheets = true
}

fun reloadViewsOnFocus() {
    FX.reloadViewsOnFocus = true
}

fun importStylesheet(stylesheet: String) {
    val css = FX::class.java.getResource(stylesheet)
    FX.stylesheets.add(css.toExternalForm())
}

fun <T : Stylesheet> importStylesheet(stylesheetType: KClass<T>) {
    val url = StringBuilder("css://${stylesheetType.java.name}")
    if (FX.osgiAvailable) {
        val bundleId = getBundleId(stylesheetType)
        if (bundleId != null) url.append("?$bundleId")
    }
    FX.stylesheets.add(url.toString())
}

fun <T : Stylesheet> removeStylesheet(stylesheetType: KClass<T>) {
    val url = StringBuilder("css://${stylesheetType.java.name}")
    if (FX.osgiAvailable) {
        val bundleId = getBundleId(stylesheetType)
        if (bundleId != null) url.append("?$bundleId")
    }
    FX.stylesheets.remove(url.toString())
}

@Deprecated("No need for a separate findFragment function anymore", ReplaceWith("find"), DeprecationLevel.WARNING)
inline fun <reified T : Fragment> findFragment(): T = find(T::class)
@Deprecated("No need for a separate findFragment function anymore", ReplaceWith("find"), DeprecationLevel.WARNING)
fun <T : Fragment> findFragment(type: KClass<T>): T = find(type)

inline fun <reified T : Component> find(): T = find(T::class)

@Suppress("UNCHECKED_CAST")
fun <T : Component> find(type: KClass<T>): T {
    if (Injectable::class.java.isAssignableFrom(type.java)) {
        if (!FX.components.containsKey(type as KClass<out Injectable>)) {
            synchronized(FX.lock) {
                if (!FX.components.containsKey(type)) {
                    val cmp = type.java.newInstance()
                    if (cmp is UIComponent) cmp.init()
                    FX.components[type] = cmp
                }
            }
        }
        return FX.components[type] as T
    }

    val cmp = type.java.newInstance()
    if (cmp is Fragment) cmp.init()
    return cmp
}

interface DIContainer {
    fun <T : Any> getInstance(type: KClass<T>): T
}

/**
 * Add the given node to the pane, invoke the node operation and return the node
 */
fun <T : Node> opcr(parent: EventTarget, node: T, op: (T.() -> Unit)? = null): T {
    parent.addChildIfPossible(node)
    op?.invoke(node)
    return node
}

@Suppress("UNNECESSARY_SAFE_CALL")
fun EventTarget.addChildIfPossible(node: Node) {
    when (this) {
        // Only add if root is already created, else this will become the root
        is UIComponent -> root?.addChildIfPossible(node)
        is BorderPane -> {
            val target = builderTarget
            if (target != null) target.invoke(this).value = node
        }
        is ScrollPane -> content = node
        is Tab -> content = node
        is TabPane -> {
            val uicmp = if (node is Parent) node.uiComponent<UIComponent>() else null
            val tab = Tab(uicmp?.title ?: node.toString(), node)
            tabs.add(tab)
        }
        is TitledPane -> content = node
        is Accordion -> if (node is TitledPane) {
            panes += node
            if (node.isExpanded) expandedPane = node
        } else {
            panes.add(TitledPane((node as? Parent)?.uiComponent<UIComponent>()?.title ?: node.toString(), node))
        }
        is DataGrid<*> -> { }
        else -> getChildList()?.add(node)
    }
}

/**
 * Find the list of children from a Parent node. Gleaned code from ControlsFX for this.
 */
fun EventTarget.getChildList(): MutableList<Node>? = when (this) {
    is SplitPane -> items
    is ToolBar -> items
    is Pane -> children
    is Group -> children
    is Control -> if (skin is SkinBase<*>) (skin as SkinBase<*>).children else getChildrenReflectively()
    is Parent -> getChildrenReflectively()
    else -> null
}

@Suppress("UNCHECKED_CAST", "PLATFORM_CLASS_MAPPED_TO_KOTLIN")
private fun Parent.getChildrenReflectively(): MutableList<Node>? {
    val getter = this.javaClass.findMethodByName("getChildren")
    if (getter != null && java.util.List::class.java.isAssignableFrom(getter.returnType)) {
        getter.isAccessible = true
        return getter.invoke(this) as MutableList<Node>
    }
    return null
}