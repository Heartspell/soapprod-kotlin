package server

import config.DbClient
import io.vertx.core.Vertx
import io.vertx.core.http.HttpServer
import io.vertx.ext.web.Router
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.kotlin.coroutines.coAwait
import java.nio.file.Paths
import models.AuthSession
import repositories.*
import services.AuthService
import services.CreditService
import services.DocumentService
import services.ProductionRequestService
import services.PurchaseService
import services.SaleService
import services.TransactionService
import templates.TemplateRenderer

class AppServer(
    internal val vertx: Vertx,
    internal val db: DbClient
) {
    internal val employees = EmployeeRepository(db.pool)
    internal val positions = PositionRepository(db.pool)
    internal val units = MeasurementUnitRepository(db.pool)
    internal val products = FinishedProductRepository(db.pool)
    internal val rawMaterials = RawMaterialRepository(db.pool)
    internal val ingredients = IngredientRepository(db.pool)
    internal val production = ProductProductionRepository(db.pool)
    internal val purchases = PurchaseRawMaterialRepository(db.pool)
    internal val productSales = ProductSalesRepository(db.pool)
    internal val salesExtended = SalesExtendedViewRepository(db.pool)
    internal val productInventory = ProductInventoryViewRepository(db.pool)
    internal val rawInventory = RawMaterialInventoryViewRepository(db.pool)
    internal val budgets = BudgetRepository(db.pool)
    internal val salaries = SalaryPaymentRepository(db.pool)
    internal val credits = CreditRepository(db.pool)
    internal val transactions = TransactionRepository(db.pool)
    internal val documents = DocumentRepository(db.pool)

    internal val productionRequests = ProductionRequestRepository(db.pool)

    internal val purchaseService = PurchaseService(purchases)
    internal val saleService = SaleService(productSales)
    internal val transactionService = TransactionService(transactions)
    internal val documentService = DocumentService(documents)
    internal val creditService = CreditService(credits, transactions)
    internal val productionRequestService = ProductionRequestService(
        productionRequests, ingredients, rawMaterials, budgets,
        purchases, production, productSales, employees, products
    )

    internal val authRepo = AuthRepository(db.pool)
    internal val authService = AuthService(authRepo)
    internal val sessions = mutableMapOf<String, AuthSession>()
    internal val templates = TemplateRenderer(Paths.get("frontend"))

    suspend fun start(port: Int = 8080): HttpServer {
        authService.ensureSeed()
        creditService.ensureSchema()
        productionRequestService.ensureSchema()

        val router = Router.router(vertx)
        router.route().handler(BodyHandler.create())

        router.get("/style.css").handler { ctx ->
            serveStaticFile(ctx, Paths.get("frontend", "style.css"))
        }

        registerApiRoutes(router)
        registerWebRoutes(router)

        router.route().last().handler { ctx ->
            ctx.response().setStatusCode(404)
                .putHeader("Content-Type", "text/html; charset=utf-8")
                .end(simplePage("Not found", "<h1>Page not found</h1><p><a href=\"/\">Back</a></p>"))
        }

        return vertx.createHttpServer()
            .requestHandler(router)
            .listen(port)
            .coAwait()
    }
}
