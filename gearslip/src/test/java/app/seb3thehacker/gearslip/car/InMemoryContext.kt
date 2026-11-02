package app.seb3thehacker.gearslip.car

import android.content.ContextWrapper
import android.content.SharedPreferences
import java.lang.reflect.Proxy

/** Just enough Context for [CarSettings]: strings kept in a map, so a second init reads them back. */
class InMemoryContext : ContextWrapper(null) {
    val stored = mutableMapOf<String, String>()

    private val editor: SharedPreferences.Editor = proxy { self, name, args ->
        if (name == "putString") stored[args[0] as String] = args[1] as String
        self.takeIf { name == "putString" }
    }

    private val preferences: SharedPreferences = proxy { _, name, args ->
        when (name) {
            "edit" -> editor
            "getString" -> stored[args[0] as String] ?: args[1]
            else -> null
        }
    }

    override fun getApplicationContext() = this

    override fun getSharedPreferences(name: String?, mode: Int) = preferences

    private inline fun <reified T> proxy(crossinline call: (Any, String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { self, method, args ->
            call(self, method.name, args.orEmpty())
        } as T
}
