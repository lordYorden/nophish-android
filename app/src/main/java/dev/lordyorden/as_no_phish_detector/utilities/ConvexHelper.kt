package dev.lordyorden.as_no_phish_detector.utilities

import android.content.Context
import com.clerk.convex.createClerkConvexClient
import dev.convex.android.ConvexClientWithAuth
import dev.convex.android.initConvexLogging
import java.lang.ref.WeakReference

class ConvexHelper private constructor(context: Context){
    companion object {

        @Volatile
        private var instance: ConvexHelper? = null

        fun getInstance(): ConvexHelper {
            return instance
                ?: throw IllegalStateException("ConvexHelper must be initialized by calling init(context) before use")
        }

        fun init(context: Context): ConvexHelper {
            return instance ?: synchronized(this) {
                instance ?: ConvexHelper(context).also { instance = it }
            }
        }
    }

    private val contextRef = WeakReference(context)

    lateinit var convexClient: ConvexClientWithAuth<String>

    init {
        contextRef.get()?.let { ctx ->
            convexClient = createClerkConvexClient("https://enchanted-mallard-804.convex.cloud", ctx)
            //initConvexLogging()
        }
    }

}