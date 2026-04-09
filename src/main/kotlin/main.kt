import config.DbClient
import io.vertx.core.Vertx
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import server.AppServer

fun main() = runBlocking {
    val vertx = Vertx.vertx()
    val db = DbClient.fromEnv(vertx)
    val stopSignal = CompletableDeferred<Unit>()
    Runtime.getRuntime().addShutdownHook(Thread { stopSignal.complete(Unit) })
    try {
        val server = AppServer(vertx, db).start(8080)
        println("Server started on http://localhost:${server.actualPort()}")
        stopSignal.await()
    } finally {
        db.close()
        vertx.close()
    }
}
